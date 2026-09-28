package com.zcode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcode.config.ZcodeHome;
import com.zcode.tool.Tool;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Starts configured MCP servers (stdio + HTTP), discovers tools, exposes them as {@link Tool}s.
 */
@Component
public class McpManager {

    private static final Logger log = LoggerFactory.getLogger(McpManager.class);

    private final McpConfigLoader configLoader;
    private final ObjectMapper objectMapper;
    private final ZcodeHome zcodeHome;

    private final Object lock = new Object();
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Tool> tools = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> statusRows = new CopyOnWriteArrayList<>();

    public McpManager(McpConfigLoader configLoader, ObjectMapper objectMapper, ZcodeHome zcodeHome) {
        this.configLoader = configLoader;
        this.objectMapper = objectMapper;
        this.zcodeHome = zcodeHome;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        refresh();
    }

    /** Reload {@code mcp.json}, restart servers, refresh tool list. */
    public void refresh() {
        synchronized (lock) {
            closeAllUnlocked();
            statusRows.clear();
            tools.clear();
            List<McpServerConfig> configs;
            try {
                configs = configLoader.load();
            } catch (Exception e) {
                log.warn("Failed to load MCP config {}: {}", configLoader.configPath(), e.getMessage());
                statusRows.add(status("config", "—", false, e.getMessage(), 0));
                return;
            }
            if (configs.isEmpty()) {
                log.info("MCP: no servers in {}", configLoader.configPath());
                return;
            }
            Path cwd = zcodeHome.workspace();
            for (McpServerConfig cfg : configs) {
                String transport = cfg.isHttp() ? "http" : "stdio";
                if (cfg.disabled()) {
                    statusRows.add(status(cfg.name(), transport, false, "disabled", 0));
                    continue;
                }
                try {
                    Session session = connect(cfg, cwd);
                    sessions.put(cfg.name(), session);
                    tools.addAll(session.tools());
                    statusRows.add(status(cfg.name(), transport, true, "ok", session.tools().size()));
                    log.info(
                            "MCP server '{}' ({}) ready ({} tools)",
                            cfg.name(),
                            transport,
                            session.tools().size());
                } catch (Exception e) {
                    log.warn("MCP server '{}' failed: {}", cfg.name(), e.getMessage());
                    statusRows.add(status(cfg.name(), transport, false, e.getMessage(), 0));
                }
            }
        }
    }

    public List<Tool> tools() {
        return List.copyOf(tools);
    }

    public List<Map<String, Object>> status() {
        return List.copyOf(statusRows);
    }

    public Path configPath() {
        return configLoader.configPath();
    }

    public int toolCount() {
        return tools.size();
    }

    /** Config entries + live status for settings UI / API. */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("config", configPath().toString());
        out.put("toolCount", toolCount());
        out.put(
                "tools",
                tools().stream().map(Tool::name).filter(n -> n.startsWith("mcp__")).toList());
        Map<String, Map<String, Object>> statusByName = new LinkedHashMap<>();
        for (Map<String, Object> row : status()) {
            Object n = row.get("name");
            if (n != null) {
                statusByName.put(n.toString(), row);
            }
        }
        List<Map<String, Object>> servers = new ArrayList<>();
        try {
            for (Map<String, Object> entry : configLoader.listEntries()) {
                Map<String, Object> merged = new LinkedHashMap<>(entry);
                Map<String, Object> st = statusByName.get(String.valueOf(entry.get("name")));
                if (st != null) {
                    merged.put("ok", st.get("ok"));
                    merged.put("message", st.get("message"));
                    merged.put("toolCount", st.get("toolCount"));
                    merged.put("liveTransport", st.get("transport"));
                } else {
                    merged.put("ok", false);
                    merged.put("message", "not loaded");
                    merged.put("toolCount", 0);
                }
                servers.add(merged);
            }
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        out.put("servers", servers);
        out.put("status", status());
        return out;
    }

    public Map<String, Object> upsertAndRefresh(McpConfigLoader.UpsertRequest req) throws Exception {
        configLoader.upsert(req);
        refresh();
        return describe();
    }

    public Map<String, Object> removeAndRefresh(String name) throws Exception {
        configLoader.remove(name);
        refresh();
        return describe();
    }

    JsonNode callTool(String serverName, String remoteName, JsonNode arguments) throws Exception {
        Session session;
        synchronized (lock) {
            session = sessions.get(serverName);
        }
        if (session == null || session.client() == null || !session.client().alive()) {
            throw new IllegalStateException("MCP server '" + serverName + "' is not connected");
        }
        return session.client().callTool(remoteName, arguments, 120_000L);
    }

    private Session connect(McpServerConfig cfg, Path cwd) throws Exception {
        McpClient client;
        if (cfg.isHttp()) {
            client = McpHttpClient.start(cfg, objectMapper);
        } else {
            client = McpStdioClient.start(cfg, objectMapper, cwd);
        }
        try {
            client.initialize();
            JsonNode listed = client.listTools();
            JsonNode arr = listed.path("tools");
            List<Tool> discovered = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode t : arr) {
                    String remote = t.path("name").asText(null);
                    if (remote == null || remote.isBlank()) {
                        continue;
                    }
                    String exposed = McpProxyTool.exposedName(cfg.name(), remote);
                    String desc = t.path("description").asText("");
                    JsonNode schema = t.get("inputSchema");
                    if (schema == null) {
                        schema = t.get("input_schema");
                    }
                    discovered.add(new McpProxyTool(exposed, cfg.name(), remote, desc, schema, this));
                }
            }
            return new Session(cfg.name(), client, List.copyOf(discovered));
        } catch (Exception e) {
            client.close();
            throw e;
        }
    }

    private void closeAllUnlocked() {
        for (Session s : sessions.values()) {
            try {
                s.client().close();
            } catch (Exception ignored) {
            }
        }
        sessions.clear();
    }

    @PreDestroy
    public void shutdown() {
        synchronized (lock) {
            closeAllUnlocked();
            tools.clear();
        }
    }

    private static Map<String, Object> status(
            String name, String transport, boolean ok, String message, int toolCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("transport", transport);
        m.put("ok", ok);
        m.put("message", message == null ? "" : message);
        m.put("toolCount", toolCount);
        return m;
    }

    private record Session(String name, McpClient client, List<Tool> tools) {}
}
