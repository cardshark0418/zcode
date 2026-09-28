package com.zcode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zcode.mcp.McpConfigLoader;
import com.zcode.mcp.McpManager;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Let the agent inspect / edit {@code .zcode/mcp.json} and hot-reload MCP servers
 * without restarting zcode (stdio + remote HTTP).
 */
@Component
public class McpManageTool implements Tool {

    private final McpManager mcpManager;

    public McpManageTool(McpManager mcpManager) {
        this.mcpManager = mcpManager;
    }

    @Override
    public String name() {
        return "mcp_manage";
    }

    @Override
    public String description() {
        return "Manage MCP servers for this zcode process. "
                + "action=status|reload|upsert|remove. "
                + "Stdio upsert: name + command + args[] + env{} + cwd. "
                + "HTTP upsert: name + url + optional headers{} / bearerToken (Bearer auth; ${ENV} expansion). "
                + "Do not set both command and url. OAuth browser login is NOT supported — use a token. "
                + "For ANY platform: skill mcp-setup first (playbook + catalog.md); cloud SaaS prefer remote url over npx; "
                + "on ok=false read message and switch transport — do not retry the same failing command. "
                + "Never invent API tokens — ask_user if needed. "
                + "Users can also configure MCP in Web Settings → MCP without editing files.";
    }

    @Override
    public JsonNode inputSchema(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("action")
                .put("type", "string")
                .put("description", "status | reload | upsert | remove");
        props.putObject("name").put("type", "string").put("description", "Server id (upsert/remove)");
        props.putObject("command").put("type", "string").put("description", "Stdio executable");
        props.putObject("args")
                .put("type", "array")
                .set("items", mapper.createObjectNode().put("type", "string"));
        props.putObject("env").put("type", "object").put("description", "Env map (stdio) or auth helpers");
        props.putObject("cwd").put("type", "string");
        props.putObject("url").put("type", "string").put("description", "Remote MCP Streamable HTTP endpoint");
        props.putObject("headers").put("type", "object").put("description", "HTTP headers (e.g. Authorization)");
        props.putObject("bearerToken").put("type", "string").put("description", "Sets Authorization: Bearer …");
        props.putObject("disabled").put("type", "boolean");
        root.putArray("required").add("action");
        return root;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext ctx) throws Exception {
        String action = text(input, "action");
        if (action == null || action.isBlank()) {
            return ToolResult.error("action is required (status|reload|upsert|remove)");
        }
        String a = action.trim().toLowerCase(Locale.ROOT);
        return switch (a) {
            case "status", "list" -> status();
            case "reload", "refresh" -> reload();
            case "upsert", "add", "set" -> upsert(input);
            case "remove", "delete" -> remove(input);
            default -> ToolResult.error("unknown action: " + action);
        };
    }

    private ToolResult status() {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> d = mcpManager.describe();
        sb.append("config: ").append(d.get("config")).append('\n');
        sb.append("transports: stdio + http (Bearer/headers; no OAuth browser flow)\n\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) d.get("servers");
        if (rows == null || rows.isEmpty()) {
            sb.append("(no servers — use Settings → MCP or mcp_manage upsert)\n");
        } else {
            sb.append("servers:\n");
            for (Map<String, Object> row : rows) {
                sb.append("- ")
                        .append(row.get("name"))
                        .append("  [")
                        .append(row.getOrDefault("transport", "?"))
                        .append("]  ok=")
                        .append(row.get("ok"))
                        .append("  tools=")
                        .append(row.get("toolCount"))
                        .append("  ")
                        .append(row.get("message"))
                        .append('\n');
            }
        }
        sb.append("\nexposed tools (").append(mcpManager.toolCount()).append("):\n");
        for (var t : mcpManager.tools()) {
            sb.append("- ").append(t.name()).append('\n');
        }
        return ToolResult.ok(sb.toString().trim());
    }

    private ToolResult reload() {
        mcpManager.refresh();
        return status();
    }

    private ToolResult upsert(JsonNode input) throws Exception {
        String name = text(input, "name");
        List<String> args = new ArrayList<>();
        JsonNode argsIn = input == null ? null : input.get("args");
        if (argsIn != null && argsIn.isArray()) {
            for (JsonNode a : argsIn) {
                if (a != null && !a.isNull()) {
                    args.add(a.asText());
                }
            }
        }
        mcpManager.upsertAndRefresh(
                new McpConfigLoader.UpsertRequest(
                        name,
                        text(input, "command"),
                        args,
                        readStringMap(input == null ? null : input.get("env")),
                        text(input, "cwd"),
                        text(input, "url"),
                        readStringMap(input == null ? null : input.get("headers")),
                        text(input, "bearerToken"),
                        input != null && input.path("disabled").asBoolean(false)));
        return ToolResult.ok("upserted '" + (name == null ? "" : name.trim()) + "' and reloaded\n\n" + status().output());
    }

    private ToolResult remove(JsonNode input) throws Exception {
        String name = text(input, "name");
        mcpManager.removeAndRefresh(name);
        return ToolResult.ok("removed '" + (name == null ? "" : name.trim()) + "' and reloaded\n\n" + status().output());
    }

    private static Map<String, String> readStringMap(JsonNode src) {
        Map<String, String> map = new LinkedHashMap<>();
        if (src == null || !src.isObject()) {
            return map;
        }
        Iterator<Map.Entry<String, JsonNode>> it = src.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getValue() != null && !e.getValue().isNull()) {
                map.put(e.getKey(), e.getValue().asText());
            }
        }
        return map;
    }

    private static String text(JsonNode input, String field) {
        JsonNode n = input == null ? null : input.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}
