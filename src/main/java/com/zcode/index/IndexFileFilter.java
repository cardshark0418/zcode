package com.zcode.index;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Which workspace files are eligible for the code index. */
public final class IndexFileFilter {

    private static final Set<String> SECRET_NAMES = Set.of(
            ".env",
            ".env.local",
            ".env.development",
            ".env.production",
            "id_rsa",
            "id_dsa",
            "id_ecdsa",
            "id_ed25519",
            "credentials.json",
            "service-account.json");

    private static final Set<String> SECRET_SUFFIXES = Set.of(".pem", ".key", ".p12", ".pfx", ".jks");

    private IndexFileFilter() {}

    public static boolean skipDirectory(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return name.equals(".git")
                || name.equals(".zcode")
                || name.equals("node_modules")
                || name.equals("target")
                || name.equals("dist")
                || name.equals("build")
                || name.equals("__pycache__")
                || name.equals(".idea")
                || name.equals(".vscode")
                || name.equals(".mvn")
                || name.equals("sessions")
                || name.equals("checkpoints")
                || name.equals("blobs");
    }

    public static boolean includeFile(Path file, Set<String> extensionsLower) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (name.isBlank() || name.startsWith(".")) {
            // allow .gitignore? no — skip dotfiles; secrets often dot-prefixed
            if (SECRET_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                return false;
            }
            // still skip most dotfiles from index
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (SECRET_NAMES.contains(lower)) {
            return false;
        }
        for (String suffix : SECRET_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return false;
            }
        }
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) {
            return false;
        }
        String ext = lower.substring(dot + 1);
        return extensionsLower.contains(ext);
    }
}
