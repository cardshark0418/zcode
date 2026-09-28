package com.zcode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zcode.tool.Tool;
import com.zcode.tool.ToolContext;
import com.zcode.tool.ToolResult;

/**
 * Local {@link Tool} facade for a remote MCP tool ({@code mcp__server__tool}).
 */
public final class McpProxyTool implements Tool {

    private final String exposedName;
    private final String serverName;
    private final String remoteName;
    private final String description;
    private final JsonNode inputSchema;
    private final McpManager manager;

    public McpProxyTool(
            String exposedName,
            String serverName,
            String remoteName,
            String description,
            JsonNode inputSchema,
            McpManager manager) {
        this.exposedName = exposedName;
        this.serverName = serverName;
        this.remoteName = remoteName;
        this.description = description == null || description.isBlank()
                ? ("MCP tool " + remoteName + " via " + serverName)
                : description;
        this.inputSchema = inputSchema;
        this.manager = manager;
    }

    public String serverName() {
        return serverName;
    }

    public String remoteName() {
        return remoteName;
    }

    @Override
    public String name() {
        return exposedName;
    }

    @Override
    public String description() {
        return "[mcp:" + serverName + "] " + description;
    }

    @Override
    public JsonNode inputSchema(ObjectMapper mapper) {
        if (inputSchema != null && inputSchema.isObject()) {
            return inputSchema;
        }
        ObjectNode empty = mapper.createObjectNode();
        empty.put("type", "object");
        empty.putObject("properties");
        return empty;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext ctx) throws Exception {
        JsonNode result = manager.callTool(serverName, remoteName, input);
        return ToolResult.ok(formatResult(result, ctx == null ? 30_000 : ctx.maxOutputChars()));
    }

    static String formatResult(JsonNode result, int maxChars) {
        if (result == null || result.isNull()) {
            return "(empty MCP result)";
        }
        StringBuilder sb = new StringBuilder();
        if (result.path("isError").asBoolean(false)) {
            sb.append("[mcp error]\n");
        }
        JsonNode content = result.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode item : content) {
                String type = item.path("type").asText("");
                if ("text".equals(type)) {
                    sb.append(item.path("text").asText("")).append('\n');
                } else if ("resource".equals(type) || "resource_link".equals(type)) {
                    sb.append(item.toString()).append('\n');
                } else if ("image".equals(type)) {
                    sb.append("[image ").append(item.path("mimeType").asText("")).append("]\n");
                } else {
                    sb.append(item.toString()).append('\n');
                }
            }
        } else if (result.isTextual()) {
            sb.append(result.asText());
        } else {
            sb.append(result.toString());
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) {
            text = result.toString();
        }
        if (maxChars > 0 && text.length() > maxChars) {
            return text.substring(0, maxChars) + "\n...[truncated " + (text.length() - maxChars) + " chars]";
        }
        return text;
    }

    /** Build exposed name {@code mcp__server__tool}. */
    public static String exposedName(String server, String remoteTool) {
        return "mcp__" + sanitize(server) + "__" + sanitize(remoteTool);
    }

    public static boolean isMcpToolName(String name) {
        return name != null && name.startsWith("mcp__");
    }

    static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unnamed";
        }
        String s = raw.trim().replaceAll("[^a-zA-Z0-9_.-]+", "_");
        if (s.isEmpty()) {
            return "unnamed";
        }
        return s;
    }
}
