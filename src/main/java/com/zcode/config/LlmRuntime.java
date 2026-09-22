package com.zcode.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Effective LLM settings: live env → {@code ~/.zcode/llm.json} → boot {@link LlmProperties}.
 * UI can save non-secret and secret fields into llm.json (env-locked fields stay read-only).
 */
@Service
public class LlmRuntime {

    public static final String ENV_API = "ZCODE_LLM_API";
    public static final String ENV_BASE_URL = "ZCODE_LLM_BASE_URL";
    public static final String ENV_API_KEY = "ZCODE_LLM_API_KEY";
    public static final String ENV_MODEL = "ZCODE_LLM_MODEL";

    private final LlmProperties boot;
    private final ObjectMapper objectMapper;
    private final Path settingsFile;
    private final AtomicReference<FileSettings> file = new AtomicReference<>(FileSettings.empty());

    public LlmRuntime(LlmProperties boot, ObjectMapper objectMapper) {
        this.boot = boot;
        this.objectMapper = objectMapper;
        this.settingsFile = Path.of(System.getProperty("user.home"), ".zcode", "llm.json");
    }

    @PostConstruct
    void load() {
        reloadFromDisk();
    }

    public Path settingsFile() {
        return settingsFile;
    }

    public void reloadFromDisk() {
        if (!Files.isRegularFile(settingsFile)) {
            file.set(FileSettings.empty());
            return;
        }
        try {
            FileSettings s = objectMapper.readValue(Files.readString(settingsFile, StandardCharsets.UTF_8), FileSettings.class);
            file.set(s == null ? FileSettings.empty() : s);
        } catch (Exception e) {
            file.set(FileSettings.empty());
        }
    }

    public String api() {
        return first(env(ENV_API), file.get().api(), boot.api(), "anthropic");
    }

    public String baseUrl() {
        return first(env(ENV_BASE_URL), file.get().baseUrl(), boot.baseUrl(), "");
    }

    public String apiKey() {
        return first(env(ENV_API_KEY), file.get().apiKey(), boot.apiKey(), "");
    }

    public String model() {
        return first(env(ENV_MODEL), file.get().model(), boot.model(), "");
    }

    public int maxTokens() {
        return boot.maxTokens() <= 0 ? 8192 : boot.maxTokens();
    }

    public int timeoutSeconds() {
        return boot.timeoutSeconds() <= 0 ? 180 : boot.timeoutSeconds();
    }

    public boolean anthropic() {
        String a = api();
        return a == null || a.isBlank() || "anthropic".equalsIgnoreCase(a);
    }

    public boolean apiKeyConfigured() {
        return StringUtils.hasText(apiKey());
    }

    public boolean apiLocked() {
        return StringUtils.hasText(env(ENV_API));
    }

    public boolean baseUrlLocked() {
        return StringUtils.hasText(env(ENV_BASE_URL));
    }

    public boolean apiKeyLocked() {
        return StringUtils.hasText(env(ENV_API_KEY));
    }

    public boolean modelLocked() {
        return StringUtils.hasText(env(ENV_MODEL));
    }

    public String apiKeyMasked() {
        String key = apiKey();
        if (!StringUtils.hasText(key)) {
            return "";
        }
        String k = key.trim();
        if (k.length() <= 8) {
            return "••••";
        }
        return k.substring(0, 4) + "…" + k.substring(k.length() - 4);
    }

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("api", api());
        m.put("baseUrl", baseUrl() == null ? "" : baseUrl());
        m.put("model", model() == null ? "" : model());
        m.put("apiKeyConfigured", apiKeyConfigured());
        m.put("apiKeyMasked", apiKeyMasked());
        m.put("apiLocked", apiLocked());
        m.put("baseUrlLocked", baseUrlLocked());
        m.put("apiKeyLocked", apiKeyLocked());
        m.put("modelLocked", modelLocked());
        m.put("settingsFile", settingsFile.toString());
        return m;
    }

    /**
     * Persist UI edits. Blank {@code apiKey} keeps the previous key. Env-locked fields are ignored.
     */
    public synchronized Map<String, Object> update(UpdateRequest req) throws IOException {
        if (req == null) {
            throw new IllegalArgumentException("body required");
        }
        FileSettings cur = file.get();

        String api = cur.api();
        if (!apiLocked() && req.api() != null && !req.api().isBlank()) {
            String norm = req.api().trim().toLowerCase();
            if (!"anthropic".equals(norm) && !"openai".equals(norm)) {
                throw new IllegalArgumentException("api must be anthropic or openai");
            }
            api = norm;
        }

        String baseUrl = cur.baseUrl();
        if (!baseUrlLocked() && req.baseUrl() != null) {
            baseUrl = req.baseUrl().isBlank() ? null : req.baseUrl().trim();
        }

        String model = cur.model();
        if (!modelLocked() && req.model() != null) {
            model = req.model().isBlank() ? null : req.model().trim();
        }

        String apiKey = cur.apiKey();
        if (!apiKeyLocked() && req.apiKey() != null && !req.apiKey().isBlank()) {
            apiKey = req.apiKey().trim();
        }

        FileSettings next = new FileSettings(api, baseUrl, apiKey, model);
        Files.createDirectories(settingsFile.getParent());
        Files.writeString(
                settingsFile,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(next) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        file.set(next);
        return describe();
    }

    public record UpdateRequest(String api, String baseUrl, String apiKey, String model) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileSettings(String api, String baseUrl, String apiKey, String model) {
        static FileSettings empty() {
            return new FileSettings(null, null, null, null);
        }
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return StringUtils.hasText(v) ? v.trim() : null;
    }

    private static String first(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (StringUtils.hasText(v)) {
                return v.trim();
            }
        }
        return "";
    }
}
