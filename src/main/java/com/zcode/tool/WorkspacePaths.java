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
}
