package com.zcode.index;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Local code semantic index (Qdrant + cloud embeddings).
 *
 * <p>Prefix {@code zcode.code-index}.
 */
@ConfigurationProperties(prefix = "zcode.code-index")
public record CodeIndexProperties(
        boolean enabled,
        String qdrantUrl,
        Embed embed,
        int topK,
        int smallFileMaxLines,
        int windowLines,
        int overlapLines,
        int maxFilesPerSync,
        int maxSyncMs,
        int maxFileBytes,
        List<String> extensions
) {
    public record Embed(String baseUrl, String apiKey, String model, int timeoutSeconds) {
        public int safeTimeoutSeconds() {
            return timeoutSeconds <= 0 ? 60 : timeoutSeconds;
        }
    }

    public boolean safeEnabled() {
        return enabled;
    }

    public String safeQdrantUrl() {
        return (qdrantUrl == null || qdrantUrl.isBlank()) ? "http://127.0.0.1:6333" : qdrantUrl.trim();
    }

    public int safeTopK() {
        return topK <= 0 ? 8 : topK;
    }

    public int safeSmallFileMaxLines() {
        return smallFileMaxLines <= 0 ? 400 : smallFileMaxLines;
    }

    public int safeWindowLines() {
        return windowLines <= 0 ? 200 : windowLines;
    }

    public int safeOverlapLines() {
        int o = overlapLines < 0 ? 40 : overlapLines;
        int w = safeWindowLines();
        return Math.min(o, Math.max(0, w - 1));
    }

    public int safeMaxFilesPerSync() {
        return maxFilesPerSync <= 0 ? 32 : maxFilesPerSync;
    }

    public int safeMaxSyncMs() {
        return maxSyncMs <= 0 ? 15_000 : maxSyncMs;
    }

    public int safeMaxFileBytes() {
        return maxFileBytes <= 0 ? 1_000_000 : maxFileBytes;
    }

    public List<String> safeExtensions() {
        if (extensions == null || extensions.isEmpty()) {
            return List.of(
                    "java",
                    "md",
                    "xml",
                    "yml",
                    "yaml",
                    "properties",
                    "json",
                    "js",
                    "ts",
                    "tsx",
                    "jsx",
                    "py",
                    "go",
                    "rs",
                    "c",
                    "h",
                    "cpp",
                    "hpp",
                    "cs",
                    "kt",
                    "kts",
                    "gradle",
                    "sql",
                    "sh",
                    "ps1",
                    "cmd",
                    "txt",
                    "html",
                    "css",
                    "vue");
        }
        return List.copyOf(extensions);
    }
}
