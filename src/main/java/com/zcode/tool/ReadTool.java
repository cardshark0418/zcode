package com.zcode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ReadTool implements Tool {

    @Override
    public String name() {
        return "read";
    }

    @Override
    public String description() {
        return "Read a text file under the workspace, or list a directory. Optional offset/limit are 1-based line numbers.";
    }

    @Override
    public JsonNode inputSchema(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "File or directory path (relative to workspace or absolute inside it)");
        props.putObject("offset").put("type", "integer").put("description", "Optional 1-based start line for files");
        props.putObject("limit").put("type", "integer").put("description", "Optional max lines to return");
        root.putArray("required").add("path");
        return root;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext ctx) throws Exception {
        Path path = WorkspacePaths.resolveInside(ctx.workspace(), text(input, "path"));
        if (!Files.exists(path)) {
            return ToolResult.error("not found: " + path);
        }
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                String listing = stream
                        .map(p -> (Files.isDirectory(p) ? "dir " : "file") + "  " + p.getFileName())
                        .sorted()
                        .collect(Collectors.joining("\n"));
                return ToolResult.ok(listing.isEmpty() ? "(empty directory)" : listing);
            }
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int offset = Math.max(1, intOr(input, "offset", 1));
        int limit = intOr(input, "limit", lines.size());
        if (limit <= 0) {
            limit = lines.size();
        }
        int from = Math.min(lines.size(), offset - 1);
        int to = Math.min(lines.size(), from + limit);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(i + 1).append("|").append(lines.get(i)).append('\n');
            if (sb.length() > ctx.maxOutputChars()) {
                sb.append("...[truncated]");
                break;
            }
        }
        return ToolResult.ok(sb.toString());
    }

    private static String text(JsonNode input, String field) {
        JsonNode n = input == null ? null : input.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static int intOr(JsonNode input, String field, int fallback) {
        JsonNode n = input == null ? null : input.get(field);
        return n == null || !n.isNumber() ? fallback : n.asInt();
    }
}
