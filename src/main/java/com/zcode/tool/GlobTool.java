package com.zcode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class GlobTool implements Tool {

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "Find files by glob pattern under the workspace (e.g. **/*.java). Returns relative paths.";
    }

    @Override
    public JsonNode inputSchema(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("pattern").put("type", "string").put("description", "Glob pattern");
        props.putObject("path").put("type", "string").put("description", "Optional subdirectory to search in");
        root.putArray("required").add("pattern");
        return root;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext ctx) throws Exception {
        String pattern = text(input, "pattern");
        if (pattern == null || pattern.isBlank()) {
            return ToolResult.error("pattern is required");
        }
        Path start = ctx.workspace();
        String sub = text(input, "path");
        if (sub != null && !sub.isBlank()) {
            start = WorkspacePaths.resolveInside(ctx.workspace(), sub);
        }
        if (!Files.isDirectory(start)) {
            return ToolResult.error("not a directory: " + start);
        }

        String normalized = pattern.replace('\\', '/');
        if (!normalized.startsWith("**/") && !normalized.startsWith("/")) {
            // allow "*.java" to mean recursive
            if (!normalized.contains("/")) {
                normalized = "**/" + normalized;
            }
        }
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + normalized);
        Path root = ctx.workspace().toAbsolutePath().normalize();
        List<String> hits = new ArrayList<>();
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (WorkspacePaths.skipWalkDir(dir, name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path rel = root.relativize(file.toAbsolutePath().normalize());
                if (matcher.matches(rel) || matcher.matches(file.getFileName())) {
                    hits.add(rel.toString().replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        hits.sort(String::compareTo);
        if (hits.isEmpty()) {
            return ToolResult.ok("(no matches)");
        }
        StringBuilder sb = new StringBuilder();
        int max = Math.min(hits.size(), 200);
        for (int i = 0; i < max; i++) {
            sb.append(hits.get(i)).append('\n');
            if (sb.length() > ctx.maxOutputChars()) {
                sb.append("...[truncated]");
                break;
            }
        }
        if (hits.size() > max) {
            sb.append("...[").append(hits.size() - max).append(" more]");
        }
        return ToolResult.ok(sb.toString());
    }

    private static String text(JsonNode input, String field) {
        JsonNode n = input == null ? null : input.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}
