package com.zcode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zcode.memory.SessionStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Bind this session's tool root to a directory (same as Web UI workspace picker).
 */
@Component
public class SetWorkspaceTool implements Tool {

    private final SessionStore sessionStore;

    public SetWorkspaceTool(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @Override
    public String name() {
        return "set_workspace";
    }

    @Override
    public String description() {
        return "Change this chat session's tool workspace to an absolute directory path "
                + "(e.g. the user's Desktop). After success, read/edit/write/bash use the new root. "
                + "Does not restart zcode. Prefer this when the user asks to switch projects or folders.";
    }

    @Override
    public JsonNode inputSchema(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("path")
                .put("type", "string")
                .put("description", "Absolute path to an existing directory");
        root.putArray("required").add("path");
        return root;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext ctx) throws Exception {
        String sessionId = ctx == null ? null : ctx.sessionId();
        if (!StringUtils.hasText(sessionId)) {
            return ToolResult.error("set_workspace requires an active session");
        }
        String raw = text(input, "path");
        if (!StringUtils.hasText(raw)) {
            return ToolResult.error("path is required");
        }
        Path abs = Path.of(raw.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(abs)) {
            return ToolResult.error("not a directory: " + abs);
        }
        String previous = sessionStore.readWorkspace(sessionId);
        sessionStore.setWorkspace(sessionId, abs.toString());
        String now = sessionStore.readWorkspace(sessionId);
        return ToolResult.ok(
                "workspace updated for this session\n"
                        + "previous: " + previous + "\n"
                        + "current:  " + now + "\n"
                        + "Later tools in this turn already use the new root.");
    }

    private static String text(JsonNode input, String field) {
        JsonNode n = input == null ? null : input.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}
