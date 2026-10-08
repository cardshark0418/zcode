package com.zcode.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Minimal Qdrant REST client (collections + points upsert/search/delete). */
@Component
public class QdrantClient {

    private final CodeIndexProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public QdrantClient(CodeIndexProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public void ping() throws Exception {
        JsonNode root = getJson("/");
        if (root == null) {
            throw new IllegalStateException("empty Qdrant response");
        }
    }

    public void ensureCollection(String name, int vectorSize) throws Exception {
        HttpResponse<String> exists = raw("GET", "/collections/" + enc(name), null, 10_000);
        if (exists.statusCode() == 200) {
            return;
        }
        ObjectNode body = mapper.createObjectNode();
        ObjectNode vectors = body.putObject("vectors");
        vectors.put("size", vectorSize);
        vectors.put("distance", "Cosine");
        HttpResponse<String> created = raw("PUT", "/collections/" + enc(name), body, 30_000);
        if (created.statusCode() < 200 || created.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Qdrant create collection HTTP " + created.statusCode() + ": " + truncate(created.body(), 300));
        }
    }

    public void upsert(String collection, List<Point> points) throws Exception {
        if (points == null || points.isEmpty()) {
            return;
        }
        ObjectNode body = mapper.createObjectNode();
        ArrayNode arr = body.putArray("points");
        for (Point p : points) {
            ObjectNode row = arr.addObject();
            row.put("id", p.id().toString());
            ArrayNode vec = row.putArray("vector");
            for (float v : p.vector()) {
                vec.add(v);
            }
            ObjectNode payload = row.putObject("payload");
            payload.put("path", p.path());
            payload.put("start_line", p.startLine());
            payload.put("end_line", p.endLine());
            payload.put("text", p.text());
            payload.put("file_hash", p.fileHash());
        }
        HttpResponse<String> resp =
                raw("PUT", "/collections/" + enc(collection) + "/points?wait=true", body, 120_000);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Qdrant upsert HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 300));
        }
    }

    public void deletePoints(String collection, Collection<UUID> ids) throws Exception {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        ObjectNode body = mapper.createObjectNode();
        ArrayNode arr = body.putArray("points");
        for (UUID id : ids) {
            arr.add(id.toString());
        }
        HttpResponse<String> resp =
                raw("POST", "/collections/" + enc(collection) + "/points/delete?wait=true", body, 60_000);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Qdrant delete HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 300));
        }
    }

    public List<SearchHit> search(String collection, float[] vector, int limit) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode vec = body.putArray("vector");
        for (float v : vector) {
            vec.add(v);
        }
        body.put("limit", Math.max(1, limit));
        body.put("with_payload", true);
        HttpResponse<String> resp =
                raw("POST", "/collections/" + enc(collection) + "/points/search", body, 60_000);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Qdrant search HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 300));
        }
        JsonNode root = mapper.readTree(resp.body());
        JsonNode result = root.path("result");
        List<SearchHit> hits = new ArrayList<>();
        if (!result.isArray()) {
            return hits;
        }
        for (JsonNode row : result) {
            JsonNode payload = row.path("payload");
            hits.add(new SearchHit(
                    payload.path("path").asText(""),
                    payload.path("start_line").asInt(1),
                    payload.path("end_line").asInt(1),
                    payload.path("text").asText(""),
                    row.path("score").asDouble(0)));
        }
        return hits;
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> resp = raw("GET", path, null, 10_000);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Qdrant HTTP " + resp.statusCode() + " at " + path + ": " + truncate(resp.body(), 200)
                            + " — is Qdrant running at " + props.safeQdrantUrl() + "?");
        }
        return mapper.readTree(resp.body());
    }

    private HttpResponse<String> raw(String method, String path, JsonNode body, long timeoutMs) throws Exception {
        String base = props.safeQdrantUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URI uri = URI.create(base + path);
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMs));
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Qdrant unreachable at " + props.safeQdrantUrl() + " (" + e.getMessage()
                            + "). Start local Qdrant or set zcode.code-index.qdrant-url. Meanwhile use grep/glob.",
                    e);
        }
    }

    private static String enc(String name) {
        return name.replace(" ", "%20");
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    public record Point(UUID id, float[] vector, String path, int startLine, int endLine, String text, String fileHash) {}

    public record SearchHit(String path, int startLine, int endLine, String text, double score) {}
}
