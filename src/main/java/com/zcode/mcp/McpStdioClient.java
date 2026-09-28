package com.zcode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal MCP client over stdio (JSON-RPC 2.0 + Content-Length framing).
 */
final class McpStdioClient implements McpClient {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final long DEFAULT_TIMEOUT_MS = 60_000L;
    /** Handshake / tools/list — fail fast so agents can switch transport (npx often hangs). */
    private static final long CONNECT_TIMEOUT_MS = 25_000L;

    private final String serverName;
    private final ObjectMapper mapper;
    private final Process process;
    private final OutputStream stdin;
    private final Thread readerThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    private McpStdioClient(String serverName, ObjectMapper mapper, Process process) {
        this.serverName = serverName;
        this.mapper = mapper;
        this.process = process;
        this.stdin = process.getOutputStream();
        this.readerThread = Thread.ofVirtual().name("mcp-stdio-" + serverName).start(this::readLoop);
    }

    static McpStdioClient start(McpServerConfig config, ObjectMapper mapper, Path defaultCwd) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(config.command());
        cmd.addAll(config.args());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Path cwd = defaultCwd;
        if (config.cwd() != null && !config.cwd().isBlank()) {
            cwd = Path.of(config.cwd()).toAbsolutePath().normalize();
        }
        if (cwd != null && Files.isDirectory(cwd)) {
            pb.directory(cwd.toFile());
        }
        Map<String, String> env = pb.environment();
        if (config.env() != null) {
            env.putAll(config.env());
        }
        // Keep stderr visible in parent logs when debugging; merge to avoid pipe fill.
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        Process process = pb.start();
        // Drain stderr so the child never blocks on a full pipe.
        Thread.ofVirtual()
                .name("mcp-stderr-" + config.name())
                .start(() -> drainQuietly(process.getErrorStream()));
        return new McpStdioClient(config.name(), mapper, process);
    }

    @Override
    public void initialize() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "zcode");
        clientInfo.put("version", "0.1.0");
        JsonNode result = request("initialize", params, CONNECT_TIMEOUT_MS);
        if (result == null || result.isNull()) {
            throw new IllegalStateException("mcp '" + serverName + "' initialize returned empty");
        }
        ObjectNode note = mapper.createObjectNode();
        note.put("jsonrpc", "2.0");
        note.put("method", "notifications/initialized");
        writeRaw(note);
    }

    @Override
    public JsonNode listTools() throws Exception {
        JsonNode result = request("tools/list", mapper.createObjectNode(), CONNECT_TIMEOUT_MS);
        return result == null ? mapper.createObjectNode() : result;
    }

    @Override
    public JsonNode callTool(String toolName, JsonNode arguments, long timeoutMs) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments == null || arguments.isNull() ? mapper.createObjectNode() : arguments);
        return request("tools/call", params, timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs);
    }

    private JsonNode request(String method, JsonNode params, long timeoutMs) throws Exception {
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
        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        pending.put(id, fut);
        try {
            writeRaw(msg);
            return fut.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            pending.remove(id);
            throw new IllegalStateException(
                    "mcp '"
                            + serverName
                            + "' "
                            + method
                            + " timed out after "
                            + timeoutMs
                            + "ms — if this is npx/stdio download, switch to remote HTTP url or a preinstalled binary",
                    e);
        } catch (Exception e) {
            pending.remove(id);
            throw e;
        }
    }

    private void writeRaw(JsonNode msg) throws IOException {
        byte[] body = mapper.writeValueAsBytes(msg);
        synchronized (writeLock) {
            String header = "Content-Length: " + body.length + "\r\n\r\n";
            stdin.write(header.getBytes(StandardCharsets.US_ASCII));
            stdin.write(body);
            stdin.flush();
        }
    }

    private void readLoop() {
        try (InputStream in = process.getInputStream()) {
            while (!closed.get()) {
                byte[] payload = readFramedMessage(in);
                if (payload == null) {
                    break;
                }
                JsonNode msg = mapper.readTree(payload);
                handleIncoming(msg);
            }
        } catch (Exception ignored) {
            // process exit / close
        } finally {
            failAllPending("mcp '" + serverName + "' connection closed");
        }
    }

    private void handleIncoming(JsonNode msg) throws IOException {
        if (msg == null || !msg.isObject()) {
            return;
        }
        JsonNode idNode = msg.get("id");
        String method = text(msg, "method");
        if (method != null && (idNode == null || idNode.isNull())) {
            // notification — ignore
            return;
        }
        if (method != null && idNode != null && !idNode.isNull()) {
            // server→client request
            respondToServerRequest(msg, idNode, method);
            return;
        }
        if (idNode != null && !idNode.isNull()) {
            long id = idNode.asLong();
            CompletableFuture<JsonNode> fut = pending.remove(id);
            if (fut == null) {
                return;
            }
            if (msg.has("error")) {
                fut.completeExceptionally(new IllegalStateException(
                        "mcp '" + serverName + "' error: " + msg.get("error").toString()));
            } else {
                fut.complete(msg.get("result"));
            }
        }
    }

    private void respondToServerRequest(JsonNode msg, JsonNode idNode, String method) throws IOException {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", idNode);
        if ("ping".equals(method)) {
            resp.putObject("result");
        } else {
            ObjectNode err = resp.putObject("error");
            err.put("code", -32601);
            err.put("message", "Method not found: " + method);
        }
        writeRaw(resp);
    }

    private void failAllPending(String reason) {
        for (var e : pending.entrySet()) {
            e.getValue().completeExceptionally(new IllegalStateException(reason));
        }
        pending.clear();
    }

    /**
     * Reads one LSP/MCP framed message. Returns null on EOF.
     */
    static byte[] readFramedMessage(InputStream in) throws IOException {
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
        int state = 0; // count trailing newlines in \r?\n\r?\n
        int prev = -1;
        while (true) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            headerBuf.write(b);
            if (b == '\n') {
                if (prev == '\n' || (prev == '\r' && headerBuf.size() >= 2)) {
                    // Detect \n\n or \r\n\r\n by scanning end of buffer
                    byte[] hb = headerBuf.toByteArray();
                    if (endsWithBlankLine(hb)) {
                        break;
                    }
                }
                state++;
            } else if (b != '\r') {
                state = 0;
            }
            prev = b;
            if (headerBuf.size() > 64_000) {
                throw new IOException("MCP header too large");
            }
        }
        String header = headerBuf.toString(StandardCharsets.US_ASCII);
        int contentLength = -1;
        for (String line : header.split("\r?\n")) {
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = line.substring(0, idx).trim();
            String val = line.substring(idx + 1).trim();
            if (key.equalsIgnoreCase("Content-Length")) {
                contentLength = Integer.parseInt(val);
            }
        }
        if (contentLength < 0) {
            throw new IOException("MCP message missing Content-Length");
        }
        if (contentLength > 16 * 1024 * 1024) {
            throw new IOException("MCP message too large: " + contentLength);
        }
        byte[] body = in.readNBytes(contentLength);
        if (body.length < contentLength) {
            return null;
        }
        return body;
    }

    private static boolean endsWithBlankLine(byte[] hb) {
        int n = hb.length;
        if (n >= 4 && hb[n - 4] == '\r' && hb[n - 3] == '\n' && hb[n - 2] == '\r' && hb[n - 1] == '\n') {
            return true;
        }
        return n >= 2 && hb[n - 2] == '\n' && hb[n - 1] == '\n';
    }

    private static void drainQuietly(InputStream in) {
        try (in) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignored) {
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    @Override
    public boolean alive() {
        return !closed.get() && process.isAlive();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        failAllPending("mcp '" + serverName + "' closed");
        try {
            stdin.close();
        } catch (IOException ignored) {
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
