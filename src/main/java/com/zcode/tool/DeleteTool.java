package com.zcode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zcode.checkpoint.CheckpointService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class DeleteTool implements Tool {

    private final CheckpointService checkpointService;

    public DeleteTool(CheckpointService checkpointService) {
        this.checkpointService = checkpointService;
    }

    @Override
    public String name() {
        return "delete";
    }

    @Override
    public String description() {
        return "Delete a file inside the workspace. Prefer this over bash rm so changes can be checkpoint-restored.";
    }

    @Override
    public JsonNode inputSchema(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "File path relative to workspace");
        root.putArray("required").add("path");
        return root;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext ctx) throws Exception {
        Path path = WorkspacePaths.resolveInside(ctx.workspace(), text(input, "path"));
        if (!Files.exists(path)) {
            return ToolResult.error("not found: " + path);
        }
        if (!Files.isRegularFile(path)) {
            return ToolResult.error("not a file (directories not supported): " + path);
        }
        byte[] beforeBytes = Files.readAllBytes(path);
        Files.delete(path);
        checkpointService.recordMutate(path, beforeBytes, null);
        return ToolResult.ok("deleted " + path);
    }

    private static String text(JsonNode input, String field) {
        JsonNode n = input == null ? null : input.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}
