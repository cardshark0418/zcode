package com.zcode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zcode.checkpoint.CheckpointService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class EditTool implements Tool {

    private final CheckpointService checkpointService;

    public EditTool(CheckpointService checkpointService) {
        this.checkpointService = checkpointService;
    }

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String description() {
        return "Exact string replacement in a file. old_string must appear exactly once unless replace_all=true.";
    }

    @Override
    public JsonNode inputSchema(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("path").put("type", "string");
        props.putObject("old_string").put("type", "string");
        props.putObject("new_string").put("type", "string");
        props.putObject("replace_all").put("type", "boolean").put("description", "Replace every occurrence (default false)");
        root.putArray("required").add("path").add("old_string").add("new_string");
        return root;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext ctx) throws Exception {
        Path path = WorkspacePaths.resolveInside(ctx.workspace(), text(input, "path"));
        if (!Files.isRegularFile(path)) {
            return ToolResult.error("not a file: " + path);
        }
        String oldString = text(input, "old_string");
        String newString = text(input, "new_string");
        if (oldString == null || newString == null) {
            return ToolResult.error("old_string and new_string are required");
        }
        if (oldString.isEmpty()) {
            return ToolResult.error("old_string must not be empty");
        }
        boolean replaceAll = input != null && input.path("replace_all").asBoolean(false);
        String original = Files.readString(path, StandardCharsets.UTF_8);
        int count = countOccurrences(original, oldString);
        if (count == 0) {
            return ToolResult.error("old_string not found");
        }
        if (!replaceAll && count > 1) {
            return ToolResult.error("old_string found " + count + " times; set replace_all=true or provide a more unique string");
        }
        String updated = replaceAll
                ? original.replace(oldString, newString)
                : original.replaceFirst(
                        java.util.regex.Pattern.quote(oldString),
                        java.util.regex.Matcher.quoteReplacement(newString));
        byte[] beforeBytes = original.getBytes(StandardCharsets.UTF_8);
        byte[] afterBytes = updated.getBytes(StandardCharsets.UTF_8);
        Files.writeString(path, updated, StandardCharsets.UTF_8);
        checkpointService.recordMutate(path, beforeBytes, afterBytes);
        StringBuilder out = new StringBuilder();
        out.append("edited ").append(path).append(" (").append(replaceAll ? count : 1).append(" replacement(s))\n\n");
        out.append("```diff\n");
        out.append("--- a/").append(path.getFileName()).append('\n');
        out.append("+++ b/").append(path.getFileName()).append('\n');
        for (String line : oldString.split("\n", -1)) {
            out.append('-').append(line).append('\n');
        }
        for (String line : newString.split("\n", -1)) {
            out.append('+').append(line).append('\n');
        }
        out.append("```");
        return ToolResult.ok(out.toString());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += Math.max(1, needle.length());
        }
        return count;
    }

    private static String text(JsonNode input, String field) {
        JsonNode n = input == null ? null : input.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}
