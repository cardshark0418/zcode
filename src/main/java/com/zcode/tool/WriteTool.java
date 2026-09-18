package com.zcode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class WriteTool implements Tool {

    @Override
    public String name() {
        return "write";
    }

    @Override
    public String description() {
        return "Create or overwrite a text file in the workspace. Prefer edit for small changes to existing files.";
    }

    @Override
    public JsonNode inputSchema(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("path").put("type", "string");
        props.putObject("content").put("type", "string").put("description", "Full file contents");
        root.putArray("required").add("path").add("content");
        return root;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext ctx) throws Exception {
        Path path = WorkspacePaths.resolveInside(ctx.workspace(), text(input, "path"));
        String content = text(input, "content");
        if (content == null) {
            return ToolResult.error("content is required");
        }
        WorkspacePaths.ensureParent(path);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return ToolResult.ok("wrote " + path + " (" + content.length() + " chars)");
    }

    private static String text(JsonNode input, String field) {
        JsonNode n = input == null ? null : input.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}
