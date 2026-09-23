package com.zcode.tool;

import java.nio.file.Files;
import java.nio.file.Path;

final class WorkspacePaths {

    private WorkspacePaths() {}

    static Path resolveInside(Path workspace, String raw) throws Exception {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        Path root = workspace.toAbsolutePath().normalize();
        Path resolved = Path.of(raw);
        if (!resolved.isAbsolute()) {
            resolved = root.resolve(resolved);
        }
        resolved = resolved.toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path escapes workspace: " + raw);
        }
        return resolved;
    }

    static void ensureParent(Path file) throws Exception {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /**
     * Directories to skip while walking the workspace for glob/grep.
     * Allows {@code .zcode/skills} so the agent can dogfood its own skills, but skips session/runtime noise.
     */
    static boolean skipWalkDir(Path dir, String name) {
        if (name.equals(".git")
                || name.equals("node_modules")
                || name.equals("target")
                || name.equals("dist")
                || name.equals("build")
                || name.equals("__pycache__")
                || name.equals(".idea")
                || name.equals(".vscode")
                || name.equals(".mvn")) {
            return true;
        }
        // Under .zcode keep skills/; skip sessions and other runtime dirs.
        if (name.equals("sessions") || name.equals("checkpoints") || name.equals("blobs")) {
            return true;
        }
        return false;
    }
}
