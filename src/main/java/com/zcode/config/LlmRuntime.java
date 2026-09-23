package com.zcode.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Multi-provider LLM settings under {@code <workspace>/.zcode/llm.json}.
 * Effective values: live env → active provider → boot defaults for api/baseUrl/model.
 * API keys never fall back to boot — each provider must own its key (or env lock).
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
    private final AtomicReference<StoredConfig> stored = new AtomicReference<>(StoredConfig.empty());

    public LlmRuntime(LlmProperties boot, ObjectMapper objectMapper, ZcodeHome zcodeHome) {
        this.boot = boot;
        this.objectMapper = objectMapper;
        this.settingsFile = zcodeHome.llmJson();
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
            stored.set(migrateBootOnly());
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(settingsFile, StandardCharsets.UTF_8));
            stored.set(materializeBootKey(parseStored(root)));
        } catch (Exception e) {
            stored.set(migrateBootOnly());
        }
    }

    /**
     * Legacy repair: if no provider has a stored key but boot props do, copy once onto the
     * first/default provider. Never copy onto an empty provider just because it is active —
     * that would leak credentials across multi-provider switches.
     */
    private StoredConfig materializeBootKey(StoredConfig cfg) {
        if (cfg == null || cfg.providers().isEmpty()) {
            return cfg;
        }
        if (apiKeyLocked() || !StringUtils.hasText(boot.apiKey())) {
            return cfg;
        }
        boolean anyKey = false;
        for (Provider p : cfg.providers()) {
            if (StringUtils.hasText(p.apiKey())) {
                anyKey = true;
                break;
            }
        }
        if (anyKey) {
            return cfg;
        }
        String targetId = StringUtils.hasText(cfg.activeId()) && find(cfg, cfg.activeId()) != null
                ? cfg.activeId()
                : cfg.providers().get(0).id();
        Provider p = find(cfg, targetId);
        if (p == null) {
            return cfg;
        }
        Provider filled = new Provider(p.id(), p.name(), p.api(), p.baseUrl(), boot.apiKey().trim(), p.model());
        List<Provider> list = new ArrayList<>();
        for (Provider x : cfg.providers()) {
            list.add(x.id().equals(filled.id()) ? filled : x);
        }
        StoredConfig next = new StoredConfig(targetId, list);
        try {
            persist(next);
            return next;
        } catch (IOException e) {
            return next;
        }
    }

    public String api() {
        return first(env(ENV_API), active().api(), boot.api(), "anthropic");
    }

    public String baseUrl() {
        return first(env(ENV_BASE_URL), active().baseUrl(), boot.baseUrl(), "");
    }

    public String apiKey() {
        return first(env(ENV_API_KEY), active().apiKey(), "");
    }

    public String model() {
        return first(env(ENV_MODEL), active().model(), boot.model(), "");
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
        return mask(apiKey());
    }

    public String activeId() {
        StoredConfig cfg = stored.get();
        if (StringUtils.hasText(cfg.activeId()) && find(cfg, cfg.activeId()) != null) {
            return cfg.activeId();
        }
        return cfg.providers().isEmpty() ? "" : cfg.providers().get(0).id();
    }

    public Map<String, Object> describe() {
        StoredConfig cfg = stored.get();
        String active = activeId();
        List<Map<String, Object>> providers = new ArrayList<>();
        for (Provider p : cfg.providers()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.id());
            row.put("name", displayName(p));
            row.put("api", blank(p.api(), "anthropic"));
            row.put("baseUrl", p.baseUrl() == null ? "" : p.baseUrl());
            row.put("model", p.model() == null ? "" : p.model());
            // Per-provider key status must NOT inherit boot/env fallback of whoever is currently active.
            boolean ownKey = StringUtils.hasText(p.apiKey());
            boolean envOnActive = p.id().equals(active) && apiKeyLocked();
            row.put("apiKeyConfigured", ownKey || envOnActive);
            row.put(
                    "apiKeyMasked",
                    mask(ownKey ? p.apiKey() : (envOnActive ? apiKey() : "")));
            row.put("active", p.id().equals(active));
            providers.add(row);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activeId", active);
        m.put("providers", providers);
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

    /** Update fields of a provider (default: active). Blank apiKey keeps previous. */
    public synchronized Map<String, Object> update(UpdateRequest req) throws IOException {
        if (req == null) {
            throw new IllegalArgumentException("body required");
        }
        StoredConfig cfg = copy(stored.get());
        String id = StringUtils.hasText(req.id()) ? req.id().trim() : activeId();
        Provider cur = find(cfg, id);
        if (cur == null) {
            throw new IllegalArgumentException("unknown provider: " + id);
        }

        String name = cur.name();
        if (req.name() != null) {
            name = req.name().isBlank() ? null : req.name().trim();
        }

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

        persistReplace(cfg, new Provider(cur.id(), name, api, baseUrl, apiKey, model));
        return describe();
    }

    public synchronized Map<String, Object> select(String providerId) throws IOException {
        if (!StringUtils.hasText(providerId)) {
            throw new IllegalArgumentException("id required");
        }
        StoredConfig cfg = copy(stored.get());
        if (find(cfg, providerId.trim()) == null) {
            throw new IllegalArgumentException("unknown provider: " + providerId);
        }
        cfg = new StoredConfig(providerId.trim(), cfg.providers());
        persist(cfg);
        return describe();
    }

    public synchronized Map<String, Object> add(AddRequest req) throws IOException {
        StoredConfig cfg = copy(stored.get());
        String id = newId();
        String requested = req != null && StringUtils.hasText(req.name()) ? req.name().trim() : "新提供方";
        String name = uniqueProviderName(cfg, requested);
        String api = "anthropic";
        if (req != null && StringUtils.hasText(req.api())) {
            api = req.api().trim().toLowerCase();
            if (!"anthropic".equals(api) && !"openai".equals(api)) {
                throw new IllegalArgumentException("api must be anthropic or openai");
            }
        }
        String baseUrl = req != null && StringUtils.hasText(req.baseUrl()) ? req.baseUrl().trim() : null;
        String model = req != null && StringUtils.hasText(req.model()) ? req.model().trim() : null;
        String apiKey = req != null && StringUtils.hasText(req.apiKey()) ? req.apiKey().trim() : null;
        List<Provider> next = new ArrayList<>(cfg.providers());
        next.add(new Provider(id, name, api, baseUrl, apiKey, model));
        // Keep current active — adding should not steal the working provider.
        String keepActive = activeId();
        if (!StringUtils.hasText(keepActive) || find(new StoredConfig(keepActive, next), keepActive) == null) {
            keepActive = cfg.providers().isEmpty() ? id : cfg.providers().get(0).id();
        }
        persist(new StoredConfig(keepActive, next));
        Map<String, Object> m = describe();
        m.put("createdId", id);
        return m;
    }

    public synchronized Map<String, Object> delete(String providerId) throws IOException {
        if (!StringUtils.hasText(providerId)) {
            throw new IllegalArgumentException("id required");
        }
        StoredConfig cfg = copy(stored.get());
        if (cfg.providers().size() <= 1) {
            throw new IllegalStateException("至少保留一个提供方");
        }
        String id = providerId.trim();
        if (find(cfg, id) == null) {
            throw new IllegalArgumentException("unknown provider: " + id);
        }
        List<Provider> next = cfg.providers().stream().filter(p -> !id.equals(p.id())).toList();
        String active = id.equals(cfg.activeId()) ? next.get(0).id() : cfg.activeId();
        if (find(new StoredConfig(active, next), active) == null) {
            active = next.get(0).id();
        }
        persist(new StoredConfig(active, next));
        return describe();
    }

    public record UpdateRequest(String id, String name, String api, String baseUrl, String apiKey, String model) {}

    public record AddRequest(String name, String api, String baseUrl, String apiKey, String model) {}

    public record SelectRequest(String id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Provider(String id, String name, String api, String baseUrl, String apiKey, String model) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StoredConfig(String activeId, List<Provider> providers) {
        public StoredConfig {
            providers = providers == null ? List.of() : List.copyOf(providers);
        }

        static StoredConfig empty() {
            return new StoredConfig(null, List.of());
        }
    }

    private Provider active() {
        StoredConfig cfg = stored.get();
        Provider p = find(cfg, activeId());
        return p == null ? new Provider(null, null, null, null, null, null) : p;
    }

    private StoredConfig migrateBootOnly() {
        Provider p = new Provider(
                "default",
                "默认",
                blankToNull(boot.api()),
                blankToNull(boot.baseUrl()),
                blankToNull(boot.apiKey()),
                blankToNull(boot.model()));
        return new StoredConfig("default", List.of(p));
    }

    private StoredConfig parseStored(JsonNode root) {
        if (root == null || root.isNull()) {
            return migrateBootOnly();
        }
        if (root.has("providers") && root.get("providers").isArray()) {
            List<Provider> list = new ArrayList<>();
            for (JsonNode n : root.get("providers")) {
                String id = text(n, "id");
                if (!StringUtils.hasText(id)) {
                    id = newId();
                }
                list.add(new Provider(
                        id,
                        blankToNull(text(n, "name")),
                        blankToNull(text(n, "api")),
                        blankToNull(text(n, "baseUrl")),
                        blankToNull(text(n, "apiKey")),
                        blankToNull(text(n, "model"))));
            }
            if (list.isEmpty()) {
                return migrateBootOnly();
            }
            String activeRaw = text(root, "activeId");
            String active = activeRaw;
            final String activeCheck = active;
            boolean known = StringUtils.hasText(activeCheck)
                    && list.stream().anyMatch(p -> activeCheck.equals(p.id()));
            if (!known) {
                active = list.get(0).id();
            }
            return new StoredConfig(active, list);
        }
        // Legacy flat shape
        Provider p = new Provider(
                "default",
                "默认",
                blankToNull(text(root, "api")),
                blankToNull(text(root, "baseUrl")),
                blankToNull(text(root, "apiKey")),
                blankToNull(text(root, "model")));
        return new StoredConfig("default", List.of(p));
    }

    private void persist(StoredConfig cfg) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("activeId", cfg.activeId());
        ArrayNode arr = root.putArray("providers");
        for (Provider p : cfg.providers()) {
            ObjectNode n = arr.addObject();
            n.put("id", p.id());
            if (p.name() != null) {
                n.put("name", p.name());
            }
            if (p.api() != null) {
                n.put("api", p.api());
            }
            if (p.baseUrl() != null) {
                n.put("baseUrl", p.baseUrl());
            }
            if (p.apiKey() != null) {
                n.put("apiKey", p.apiKey());
            }
            if (p.model() != null) {
                n.put("model", p.model());
            }
        }
        Files.createDirectories(settingsFile.getParent());
        Files.writeString(
                settingsFile,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        stored.set(cfg);
    }

    private static StoredConfig copy(StoredConfig cfg) {
        return new StoredConfig(cfg.activeId(), new ArrayList<>(cfg.providers()));
    }

    private void persistReplace(StoredConfig cfg, Provider next) throws IOException {
        List<Provider> list = new ArrayList<>();
        for (Provider p : cfg.providers()) {
            list.add(p.id().equals(next.id()) ? next : p);
        }
        persist(new StoredConfig(cfg.activeId(), list));
    }

    private static String uniqueProviderName(StoredConfig cfg, String desired) {
        String base = StringUtils.hasText(desired) ? desired.trim() : "新提供方";
        Set<String> used = new HashSet<>();
        for (Provider p : cfg.providers()) {
            String n = displayName(p);
            if (StringUtils.hasText(n)) {
                used.add(n);
            }
        }
        if (!used.contains(base)) {
            return base;
        }
        for (int i = 1; i < 10000; i++) {
            String candidate = base + "（" + i + "）";
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        return base + "（" + newId() + "）";
    }

    private static Provider find(StoredConfig cfg, String id) {
        if (cfg == null || !StringUtils.hasText(id)) {
            return null;
        }
        for (Provider p : cfg.providers()) {
            if (id.equals(p.id())) {
                return p;
            }
        }
        return null;
    }

    private static String displayName(Provider p) {
        if (p == null) {
            return "提供方";
        }
        if (StringUtils.hasText(p.name())) {
            return p.name();
        }
        if (StringUtils.hasText(p.model())) {
            return p.model();
        }
        return p.id();
    }

    private static String mask(String key) {
        if (!StringUtils.hasText(key)) {
            return "";
        }
        String k = key.trim();
        if (k.length() <= 8) {
            return "••••";
        }
        return k.substring(0, 4) + "…" + k.substring(k.length() - 4);
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return null;
        }
        return n.get(field).asText();
    }

    private static String blank(String v, String fallback) {
        return StringUtils.hasText(v) ? v.trim() : fallback;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
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

    private static String newId() {
        return "p-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
