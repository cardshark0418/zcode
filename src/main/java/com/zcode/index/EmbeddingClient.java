package com.zcode.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zcode.config.LlmProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** OpenAI-compatible {@code /v1/embeddings} client. */
@Component
public class EmbeddingClient {

    private final CodeIndexProperties props;
    private final LlmProperties llm;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public EmbeddingClient(CodeIndexProperties props, LlmProperties llm, ObjectMapper mapper) {
        this.props = props;
        this.llm = llm;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    public String resolveModel() {
        if (props.embed() != null && StringUtils.hasText(props.embed().model())) {
            return props.embed().model().trim();
        }
        return "text-embedding-3-small";
    }

    public void requireConfigured() {
        if (!StringUtils.hasText(resolveBaseUrl())) {
            throw new IllegalStateException(
                    "embedding API base URL not configured (zcode.code-index.embed.base-url or ZCODE_EMBED_BASE_URL)");
        }
        if (!StringUtils.hasText(resolveApiKey())) {
            throw new IllegalStateException(
                    "embedding API key not configured (zcode.code-index.embed.api-key or ZCODE_EMBED_API_KEY / ZCODE_LLM_API_KEY)");
        }
    }

    public List<float[]> embed(List<String> texts) throws Exception {
        requireConfigured();
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", resolveModel());
        ArrayNode input = body.putArray("input");
        for (String t : texts) {
            input.add(t == null ? "" : t);
        }

        String url = embeddingsEndpoint(resolveBaseUrl());
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(props.embed() == null ? 60 : props.embed().safeTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + resolveApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));

        HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException(
                    "embedding API HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 400));
        }
        JsonNode root = mapper.readTree(resp.body());
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("embedding API returned no data");
        }
        // OpenAI may return unsorted; sort by index
        List<JsonNode> rows = new ArrayList<>();
        data.forEach(rows::add);
        rows.sort((a, b) -> Integer.compare(a.path("index").asInt(0), b.path("index").asInt(0)));
        List<float[]> out = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            JsonNode emb = row.path("embedding");
            if (!emb.isArray()) {
                throw new IllegalStateException("embedding API missing embedding array");
            }
            float[] vec = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) {
                vec[i] = (float) emb.get(i).asDouble();
            }
            out.add(vec);
        }
        if (out.size() != texts.size()) {
            throw new IllegalStateException(
                    "embedding count mismatch: got " + out.size() + " for " + texts.size() + " inputs");
        }
        return out;
    }

    private String resolveBaseUrl() {
        if (props.embed() != null && StringUtils.hasText(props.embed().baseUrl())) {
            return props.embed().baseUrl().trim();
        }
        return llm.baseUrl() == null ? "" : llm.baseUrl().trim();
    }

    private String resolveApiKey() {
        if (props.embed() != null && StringUtils.hasText(props.embed().apiKey())) {
            return props.embed().apiKey().trim();
        }
        return llm.apiKey() == null ? "" : llm.apiKey().trim();
    }

    static String embeddingsEndpoint(String baseUrl) {
        String b = baseUrl.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (b.endsWith("/embeddings")) {
            return b;
        }
        if (b.endsWith("/v1")) {
            return b + "/embeddings";
        }
        return b + "/v1/embeddings";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
