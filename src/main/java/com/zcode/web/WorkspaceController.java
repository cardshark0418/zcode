package com.zcode.web;

import com.zcode.tool.ToolRegistry;
import com.zcode.config.ZcodeHome;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace file listing for Web UI {@code @} mentions.
 */
@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git",
            "node_modules",
            "target",
            ".idea",
            ".vscode",
            "dist",
            "build",
            "__pycache__",
            ".mvn",
            // under .zcode: keep skills, skip runtime noise
            "sessions");

    private static final Set<String> ALLOW_DOT_DIRS = Set.of(".github", ".zcode");

    private static final int HARD_CAP = 5_000;

    private final ToolRegistry toolRegistry;
    private final ZcodeHome zcodeHome;

    public WorkspaceController(ToolRegistry toolRegistry, ZcodeHome zcodeHome) {
        this.toolRegistry = toolRegistry;
        this.zcodeHome = zcodeHome;
    }

    public record PickDirRequest(String initial) {}

    /** Opens the OS folder picker; blocks until the user selects or cancels. */
    @PostMapping("/pick-dir")
    public Map<String, Object> pickDir(@RequestBody(required = false) PickDirRequest body) {
        String initial =
                body != null && body.initial() != null && !body.initial().isBlank()
                        ? body.initial().trim()
                        : zcodeHome.workspace().toString();
        Path picked = FolderPicker.pickDirectory(initial);
        Map<String, Object> out = new LinkedHashMap<>();
        if (picked == null) {
            out.put("cancelled", true);
            return out;
        }
        out.put("cancelled", false);
        out.put("path", picked.toString());
        return out;
    }

    @GetMapping("/files")
    public Map<String, Object> files(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "limit", defaultValue = "80") int limit,
            @RequestParam(value = "sessionId", required = false) String sessionId)
            throws IOException {
        int lim = Math.max(1, Math.min(200, limit));
        Path root =
                (sessionId != null && !sessionId.isBlank())
                        ? toolRegistry.workspaceForSession(sessionId)
                        : toolRegistry.workspace().toAbsolutePath().normalize();
        root = root.toAbsolutePath().normalize();
        List<String> all = listRelativeFiles(root);
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
        List<String> matched = new ArrayList<>();
        if (q.isEmpty()) {
            matched.addAll(all.subList(0, Math.min(lim, all.size())));
        } else {
            List<String> primary = new ArrayList<>();
            List<String> secondary = new ArrayList<>();
            for (String path : all) {
                String lower = path.toLowerCase(Locale.ROOT);
                String name = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;
                if (name.startsWith(q) || lower.startsWith(q)) {
                    primary.add(path);
                } else if (name.contains(q) || lower.contains(q)) {
                    secondary.add(path);
                }
                if (primary.size() + secondary.size() >= lim * 3) {
                    // enough candidates
                    break;
                }
            }
            matched.addAll(primary);
            for (String path : secondary) {
                if (matched.size() >= lim) {
                    break;
                }
                matched.add(path);
            }
            if (matched.size() > lim) {
                matched = matched.subList(0, lim);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workspace", root.toString());
        out.put("total", all.size());
        out.put("files", matched);
        return out;
    }

    private static List<String> listRelativeFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<String> files = new ArrayList<>();
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (dir.equals(root)) {
                            return FileVisitResult.CONTINUE;
                        }
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        if (SKIP_DIRS.contains(name)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        // Skip other dot-directories except allow-list (.zcode / .github)
                        if (name.startsWith(".") && !ALLOW_DOT_DIRS.contains(name)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (files.size() >= HARD_CAP) {
                            return FileVisitResult.TERMINATE;
                        }
                        Path abs = file.toAbsolutePath().normalize();
                        if (!abs.startsWith(root)) {
                            return FileVisitResult.CONTINUE;
                        }
                        String rel = root.relativize(abs).toString().replace('\\', '/');
                        if (rel.isBlank()) {
                            return FileVisitResult.CONTINUE;
                        }
                        // skip fat jar / launcher noise inside .zcode
                        String base = abs.getFileName() == null ? "" : abs.getFileName().toString();
                        if (base.equals("zcode.jar") || base.equals("zcode.cmd") || base.equals("llm.json")) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (base.startsWith("last-") && base.endsWith(".json")) {
                            return FileVisitResult.CONTINUE;
                        }
                        files.add(rel);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
        files.sort(Comparator.comparing(String::toLowerCase));
        return files;
    }
}
