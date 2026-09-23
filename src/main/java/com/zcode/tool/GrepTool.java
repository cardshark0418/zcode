package com.zcode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class GrepTool implements Tool {

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "Regex search file contents under the workspace. Returns path:line:content matches.";
    }

    @Override
    public JsonNode inputSchema(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("pattern").put("type", "string").put("description", "Java regex pattern");
        props.putObject("path").put("type", "string").put("description", "Optional file or directory to search");
        props.putObject("glob").put("type", "string").put("description", "Optional file name glob filter, e.g. *.java");
        props.putObject("case_insensitive").put("type", "boolean");
        root.putArray("required").add("pattern");
        return root;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext ctx) throws Exception {
        String patternText = text(input, "pattern");
        if (patternText == null || patternText.isBlank()) {
            return ToolResult.error("pattern is required");
        }
        boolean ignoreCase = input != null && input.path("case_insensitive").asBoolean(false);
        int flags = ignoreCase ? Pattern.CASE_INSENSITIVE : 0;
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternText, flags);
        } catch (Exception e) {
            return ToolResult.error("invalid regex: " + e.getMessage());
        }

        Path start = ctx.workspace();
        String sub = text(input, "path");
        if (sub != null && !sub.isBlank()) {
            start = WorkspacePaths.resolveInside(ctx.workspace(), sub);
        }
        String fileGlob = text(input, "glob");
        Pattern fileNameFilter = null;
        if (fileGlob != null && !fileGlob.isBlank()) {
            String g = fileGlob.replace(".", "\\.").replace("*", ".*").replace("?", ".");
            fileNameFilter = Pattern.compile("^" + g + "$", Pattern.CASE_INSENSITIVE);
        }

        Path root = ctx.workspace().toAbsolutePath().normalize();
        List<String> hits = new ArrayList<>();
        if (Files.isRegularFile(start)) {
            searchFile(start, root, pattern, hits, ctx.maxOutputChars());
        } else if (Files.isDirectory(start)) {
            Pattern finalFilter = fileNameFilter;
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
                    if (finalFilter != null && !finalFilter.matcher(file.getFileName().toString()).matches()) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (attrs.size() > 1_000_000) {
                        return FileVisitResult.CONTINUE;
                    }
                    searchFile(file, root, pattern, hits, ctx.maxOutputChars());
                    return hits.size() >= 200 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            return ToolResult.error("path not found: " + start);
        }

        if (hits.isEmpty()) {
            return ToolResult.ok("(no matches)");
        }
        return ToolResult.ok(String.join("\n", hits));
    }

    private static void searchFile(Path file, Path root, Pattern pattern, List<String> hits, int maxChars) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String rel = root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = pattern.matcher(lines.get(i));
                if (m.find()) {
                    hits.add(rel + ":" + (i + 1) + ":" + lines.get(i));
                    if (hits.size() >= 200) {
                        return;
                    }
                    int total = hits.stream().mapToInt(String::length).sum();
                    if (total > maxChars) {
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
            // skip binary / unreadable
        }
    }

    private static String text(JsonNode input, String field) {
        JsonNode n = input == null ? null : input.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}
