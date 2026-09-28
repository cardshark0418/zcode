package com.zcode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zcode.config.ZcodeHome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Loads / writes {@code <workspace>/.zcode/mcp.json} (Claude Desktop / Cursor style).
 */
@Component
public class McpConfigLoader {

    private final ZcodeHome zcodeHome;
    private final ObjectMapper objectMapper;

    public McpConfigLoader(ZcodeHome zcodeHome, ObjectMapper objectMapper) {
        this.zcodeHome = zcodeHome;
        this.objectMapper = objectMapper;
    }

    public Path configPath() {
        return zcodeHome.home().resolve("mcp.json");
    }

    public List<McpServerConfig> load() throws IOException {
        Path path = configPath();
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        JsonNode root = objectMapper.readTree(Files.readString(path));
        JsonNode servers = root.path("mcpServers");
        if (!servers.isObject() || servers.isEmpty()) {
            return List.of();
        }
        List<McpServerConfig> out = new ArrayList<>();
        var fields = servers.fields();
        while (fields.hasNext()) {
            var e = fields.next();
            out.add(parseServer(e.getKey(), e.getValue()));
        }
        return List.copyOf(out);
    }

    /** Config rows for UI / API (secrets kept as stored strings, often {@code ${ENV}}). */
    public List<Map<String, Object>> listEntries() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (McpServerConfig cfg : load()) {
            out.add(toEntryMap(cfg));
        }
        return out;
    }

    public void upsert(UpsertRequest req) throws IOException {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        String name = req.name().trim();
        boolean hasCmd = req.command() != null && !req.command().isBlank();
        boolean hasUrl = req.url() != null && !req.url().isBlank();
        if (!hasCmd && !hasUrl) {
            throw new IllegalArgumentException("needs command (stdio) or url (http)");
        }
        if (hasCmd && hasUrl) {
            throw new IllegalArgumentException("set either command or url, not both");
        }

        Path path = configPath();
        Files.createDirectories(path.getParent());
        ObjectNode root = readOrCreate(path);
        ObjectNode servers = root.with("mcpServers");
        ObjectNode entry = servers.putObject(name);

        if (hasUrl) {
            entry.put("url", req.url().trim());
            ObjectNode headers = entry.putObject("headers");
            putStringMap(headers, req.headers());
            if (req.bearerToken() != null && !req.bearerToken().isBlank()) {
                entry.put("bearerToken", req.bearerToken().trim());
            }
            ObjectNode env = entry.putObject("env");
            putStringMap(env, req.env());
        } else {
            entry.put("command", req.command().trim());
            ArrayNode args = entry.putArray("args");
            if (req.args() != null) {
                for (String a : req.args()) {
                    if (a != null && !a.isBlank()) {
                        args.add(a);
                    }
                }
            }
            ObjectNode env = entry.putObject("env");
            putStringMap(env, req.env());
            if (req.cwd() != null && !req.cwd().isBlank()) {
                entry.put("cwd", req.cwd().trim());
            }
        }
        entry.put("disabled", req.disabled());
        writePretty(path, root);
    }

    public void remove(String name) throws IOException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        Path path = configPath();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("no mcp.json at " + path);
        }
        ObjectNode root = readOrCreate(path);
        ObjectNode servers = root.with("mcpServers");
        String key = name.trim();
        if (!servers.has(key)) {
            throw new IllegalArgumentException("server not found: " + name);
        }
        servers.remove(key);
        writePretty(path, root);
    }

    public record UpsertRequest(
            String name,
            String command,
            List<String> args,
            Map<String, String> env,
            String cwd,
            String url,
            Map<String, String> headers,
            String bearerToken,
            boolean disabled) {}

    private static Map<String, Object> toEntryMap(McpServerConfig cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", cfg.name());
        m.put("transport", cfg.isHttp() ? "http" : "stdio");
        m.put("command", cfg.command() == null ? "" : cfg.command());
        m.put("args", cfg.args());
        m.put("env", cfg.env());
        m.put("cwd", cfg.cwd() == null ? "" : cfg.cwd());
        m.put("url", cfg.url() == null ? "" : cfg.url());
        m.put("headers", cfg.headers());
        m.put("bearerToken", cfg.bearerToken() == null ? "" : cfg.bearerToken());
        m.put("disabled", cfg.disabled());
        return m;
    }

    private ObjectNode readOrCreate(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            JsonNode n = objectMapper.readTree(Files.readString(path));
            if (n != null && n.isObject()) {
                return (ObjectNode) n;
            }
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("mcpServers");
        return root;
    }

    private void writePretty(Path path, ObjectNode root) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), root);
    }

    private static void putStringMap(ObjectNode dest, Map<String, String> src) {
        if (src == null) {
            return;
        }
        for (var e : src.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                dest.put(e.getKey(), e.getValue());
            }
        }
    }

    private static McpServerConfig parseServer(String name, JsonNode n) {
        if (n == null || !n.isObject()) {
            throw new IllegalArgumentException("mcp server '" + name + "' must be an object");
        }
        boolean disabled = n.path("disabled").asBoolean(false);
        String command = text(n, "command");
        String url = text(n, "url");
        if (url == null || url.isBlank()) {
            url = text(n, "serverUrl");
        }
        List<String> args = new ArrayList<>();
        JsonNode argsNode = n.get("args");
        if (argsNode != null && argsNode.isArray()) {
            for (JsonNode a : argsNode) {
                if (a != null && !a.isNull()) {
                    args.add(a.asText());
                }
            }
        }
        Map<String, String> env = readStringMap(n.get("env"));
        Map<String, String> headers = readStringMap(n.get("headers"));
        String cwd = text(n, "cwd");
        String bearer = text(n, "bearerToken");
        if (bearer == null || bearer.isBlank()) {
            bearer = text(n, "bearer_token");
        }
        return new McpServerConfig(
                name,
                command == null ? null : command.trim(),
                args,
                env,
                cwd,
                url == null ? null : url.trim(),
                headers,
                bearer,
                disabled);
    }

    private static Map<String, String> readStringMap(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var ef = fields.next();
                if (ef.getValue() != null && !ef.getValue().isNull()) {
                    map.put(ef.getKey(), ef.getValue().asText());
                }
            }
        }
        return map;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
