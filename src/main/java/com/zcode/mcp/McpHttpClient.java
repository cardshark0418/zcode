package com.zcode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP Streamable HTTP client (JSON-RPC over POST; JSON or SSE response body).
 *
 * <p>Auth: optional {@code Authorization: Bearer …} from config. No OAuth browser flow yet.
 */
final class McpHttpClient implements McpClient {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String HEADER_SESSION = "Mcp-Session-Id";
    private static final String HEADER_PROTOCOL = "Mcp-Protocol-Version";

    private final String serverName;
    private final URI endpoint;
    private final ObjectMapper mapper;
    private final Map<String, String> headers;
    private final HttpClient http;
    private final AtomicLong nextId = new AtomicLong(1);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile String sessionId;
    private volatile String negotiatedVersion = PROTOCOL_VERSION;

    McpHttpClient(String serverName, URI endpoint, Map<String, String> headers, ObjectMapper mapper) {
        this.serverName = serverName;
        this.endpoint = endpoint;
        this.mapper = mapper;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    static McpHttpClient start(McpServerConfig config, ObjectMapper mapper) {
        if (!config.isHttp()) {
            throw new IllegalArgumentException("not an HTTP mcp server: " + config.name());
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (config.headers() != null) {
            for (var e : config.headers().entrySet()) {
                headers.put(e.getKey(), expandEnv(e.getValue()));
            }
        }
        if (config.bearerToken() != null && !config.bearerToken().isBlank()) {
            headers.putIfAbsent("Authorization", "Bearer " + expandEnv(config.bearerToken().trim()));
        }
        // Common Cursor-style: token only in env map
        if (config.env() != null) {
            String t = config.env().get("Authorization");
            if (t == null) {
                t = config.env().get("AUTHORIZATION");
            }
            if (t != null && !t.isBlank()) {
                headers.putIfAbsent("Authorization", expandEnv(t.trim()));
            }
            String bearer = config.env().get("BEARER_TOKEN");
            if (bearer == null) {
                bearer = config.env().get("MCP_BEARER_TOKEN");
            }
            if (bearer != null && !bearer.isBlank()) {
                headers.putIfAbsent("Authorization", "Bearer " + expandEnv(bearer.trim()));
            }
        }
        return new McpHttpClient(config.name(), URI.create(config.url().trim()), headers, mapper);
    }

    @Override
    public void initialize() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "zcode");
        clientInfo.put("version", "0.1.0");

        PostResult init = post("initialize", params, false, 45_000L);
        if (init.sessionId() != null && !init.sessionId().isBlank()) {
            sessionId = init.sessionId();
        }
        JsonNode result = init.result();
        if (result != null && result.hasNonNull("protocolVersion")) {
            negotiatedVersion = result.get("protocolVersion").asText(PROTOCOL_VERSION);
        }

        // notifications/initialized — often 202/204 with empty body
        postNotification("notifications/initialized", mapper.createObjectNode());
    }

    @Override
    public JsonNode listTools() throws Exception {
        PostResult r = post("tools/list", mapper.createObjectNode(), true, 60_000L);
        return r.result() == null ? mapper.createObjectNode() : r.result();
    }

    @Override
    public JsonNode callTool(String toolName, JsonNode arguments, long timeoutMs) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments == null || arguments.isNull() ? mapper.createObjectNode() : arguments);
        PostResult r = post("tools/call", params, true, timeoutMs <= 0 ? 120_000L : timeoutMs);
        return r.result();
    }

    @Override
    public boolean alive() {
        return !closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json, text/event-stream")
                    .DELETE();
            applyHeaders(b, true);
            http.send(b.build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // best-effort session teardown
        }
    }

    private void postNotification(String method, JsonNode params) throws Exception {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        if (params != null) {
            msg.set("params", params);
        }
        byte[] body = mapper.writeValueAsBytes(msg);
        HttpRequest.Builder b = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        applyHeaders(b, true);
        HttpResponse<Void> resp = http.send(b.build(), HttpResponse.BodyHandlers.discarding());
        int code = resp.statusCode();
        if (code >= 400) {
            throw new IllegalStateException(
                    "mcp '" + serverName + "' " + method + " HTTP " + code);
        }
    }

    private PostResult post(String method, JsonNode params, boolean requireSession, long timeoutMs)
            throws Exception {
        if (closed.get()) {
            throw new IllegalStateException("mcp '" + serverName + "' closed");
        }
        long id = nextId.getAndIncrement();
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        if (params != null) {
            msg.set("params", params);
        }
        byte[] body = mapper.writeValueAsBytes(msg);
        HttpRequest.Builder b = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(Math.max(5_000L, timeoutMs)))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        applyHeaders(b, requireSession || sessionId != null);
        HttpResponse<InputStream> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        int code = resp.statusCode();
        String newSession = resp.headers().firstValue(HEADER_SESSION).orElse(null);
        if (newSession != null && !newSession.isBlank()) {
            sessionId = newSession;
        }
        if (code == 401 || code == 403) {
            throw new IllegalStateException(
                    "mcp '" + serverName + "' HTTP " + code + " (auth required / forbidden). "
                            + "Set bearerToken or headers.Authorization in mcp.json. OAuth browser login not supported yet.");
        }
        if (code >= 400) {
            String errBody = readQuietly(resp.body());
            throw new IllegalStateException(
                    "mcp '" + serverName + "' " + method + " HTTP " + code
                            + (errBody.isBlank() ? "" : ": " + truncate(errBody, 300)));
        }
        String ctype = resp.headers().firstValue("Content-Type").orElse("");
        JsonNode result;
        if (ctype.contains("text/event-stream")) {
            result = readSseResult(resp.body(), id);
        } else {
            // 202/204 with no body — treat as empty result for notifications only; requests need body
            if (code == 202 || code == 204) {
                try (InputStream in = resp.body()) {
                    // drain
                }
                throw new IllegalStateException(
                        "mcp '" + serverName + "' " + method + " returned HTTP " + code + " without JSON body");
            }
            String json = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            if (json.isBlank()) {
                throw new IllegalStateException("mcp '" + serverName + "' empty HTTP body for " + method);
            }
            result = extractJsonRpcResult(mapper.readTree(json), id);
        }
        return new PostResult(result, sessionId);
    }

    private void applyHeaders(HttpRequest.Builder b, boolean withSession) {
        for (var e : headers.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                b.header(e.getKey(), e.getValue());
            }
        }
        b.header(HEADER_PROTOCOL, negotiatedVersion == null ? PROTOCOL_VERSION : negotiatedVersion);
        if (withSession && sessionId != null && !sessionId.isBlank()) {
            b.header(HEADER_SESSION, sessionId);
        }
    }

    private JsonNode readSseResult(InputStream body, long expectId) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        String payload = data.toString().trim();
                        data.setLength(0);
                        if (payload.isEmpty() || "[DONE]".equals(payload)) {
                            continue;
                        }
                        JsonNode msg = mapper.readTree(payload);
                        if (msg.has("error") && idMatches(msg, expectId)) {
                            throw new IllegalStateException(
                                    "mcp '" + serverName + "' error: " + msg.get("error"));
                        }
                        if (msg.has("result") && idMatches(msg, expectId)) {
                            return msg.get("result");
                        }
                        // skip notifications / other ids
                    }
                    continue;
                }
                if (line.startsWith(":") || line.startsWith("event:") || line.startsWith("id:")) {
                    continue;
                }
                if (line.startsWith("data:")) {
                    String part = line.substring(5);
                    if (part.startsWith(" ")) {
                        part = part.substring(1);
                    }
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(part);
                }
            }
            if (data.length() > 0) {
                JsonNode msg = mapper.readTree(data.toString().trim());
                if (msg.has("result") && idMatches(msg, expectId)) {
                    return msg.get("result");
                }
                if (msg.has("error") && idMatches(msg, expectId)) {
                    throw new IllegalStateException("mcp '" + serverName + "' error: " + msg.get("error"));
                }
            }
        }
        throw new IllegalStateException(
                "mcp '" + serverName + "' SSE stream ended without result for id=" + expectId);
    }

    private JsonNode extractJsonRpcResult(JsonNode msg, long expectId) {
        if (msg == null || msg.isNull()) {
            throw new IllegalStateException("mcp '" + serverName + "' null JSON body");
        }
        // Some gateways wrap { "jsonrpc": ... } or return the result bare — prefer standard.
        if (msg.has("error") && idMatches(msg, expectId)) {
            throw new IllegalStateException("mcp '" + serverName + "' error: " + msg.get("error"));
        }
        if (msg.has("result")) {
            return msg.get("result");
        }
        throw new IllegalStateException(
                "mcp '" + serverName + "' unexpected JSON-RPC shape: " + truncate(msg.toString(), 200));
    }

    private static boolean idMatches(JsonNode msg, long expectId) {
        JsonNode id = msg.get("id");
        if (id == null || id.isNull()) {
            return true; // tolerate missing id on some gateways
        }
        return id.asLong() == expectId || String.valueOf(expectId).equals(id.asText());
    }

    private static String expandEnv(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        // ${VAR} or $VAR
        String out = raw;
        int i = 0;
        while (i < out.length()) {
            int start = out.indexOf("${", i);
            if (start < 0) {
                break;
            }
            int end = out.indexOf('}', start + 2);
            if (end < 0) {
                break;
            }
            String var = out.substring(start + 2, end);
            String val = System.getenv(var);
            if (val == null) {
                val = System.getProperty(var, "");
            }
            out = out.substring(0, start) + val + out.substring(end + 1);
            i = start + val.length();
        }
        return out;
    }

    private static String readQuietly(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private record PostResult(JsonNode result, String sessionId) {}
}
