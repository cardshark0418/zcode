package com.zcode.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

class McpClientTest {

    @Test
    void readsContentLengthFrame() throws Exception {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String raw = "Content-Length: " + body.length + "\r\n\r\n" + json;
        byte[] got = McpStdioClient.readFramedMessage(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
        assertEquals(json, new String(got, StandardCharsets.UTF_8));
    }

    @Test
    void exposedNameSanitizes() {
        assertEquals("mcp__my_server__read_file", McpProxyTool.exposedName("my server", "read/file"));
        assertTrue(McpProxyTool.isMcpToolName("mcp__echo__echo"));
    }

    @Test
    @EnabledIf("nodeAvailable")
    void stdioEchoRoundTrip() throws Exception {
        Path script = Path.of("scripts/mcp-echo.mjs").toAbsolutePath().normalize();
        McpServerConfig cfg = new McpServerConfig(
                "echo",
                "node",
                List.of(script.toString()),
                Map.of(),
                Path.of(".").toAbsolutePath().normalize().toString(),
                null,
                Map.of(),
                null,
                false);
        ObjectMapper mapper = new ObjectMapper();
        try (McpStdioClient client = McpStdioClient.start(cfg, mapper, Path.of(".").toAbsolutePath())) {
            client.initialize();
            var listed = client.listTools();
            assertEquals(1, listed.path("tools").size());
            ObjectNode args = mapper.createObjectNode();
            args.put("text", "hello-mcp");
            var result = client.callTool("echo", args, 15_000L);
            assertEquals("hello-mcp", result.path("content").get(0).path("text").asText());
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void httpEchoRoundTrip() throws Exception {
        Path script = Path.of("scripts/mcp-http-echo.cjs").toAbsolutePath().normalize();
        int port = 18765;
        Process proc = new ProcessBuilder("node", script.toString(), String.valueOf(port))
                .redirectErrorStream(true)
                .start();
        try {
            boolean up = false;
            for (int i = 0; i < 50; i++) {
                if (!proc.isAlive()) {
                    break;
                }
                try {
                    var c = java.net.http.HttpClient.newHttpClient();
                    var r = c.send(
                            java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                                    .timeout(java.time.Duration.ofSeconds(1))
                                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                            "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"ping\"}"))
                                    .header("Content-Type", "application/json")
                                    .build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() > 0) {
                        up = true;
                        break;
                    }
                } catch (Exception ignored) {
                    Thread.sleep(100);
                }
            }
            assertTrue(up, "http echo server should start");

            McpServerConfig cfg = new McpServerConfig(
                    "httpecho",
                    null,
                    List.of(),
                    Map.of(),
                    null,
                    "http://127.0.0.1:" + port + "/",
                    Map.of(),
                    null,
                    false);
            ObjectMapper mapper = new ObjectMapper();
            try (McpHttpClient client = McpHttpClient.start(cfg, mapper)) {
                client.initialize();
                var listed = client.listTools();
                assertEquals(1, listed.path("tools").size());
                ObjectNode args = mapper.createObjectNode();
                args.put("text", "hello-http-mcp");
                var result = client.callTool("echo", args, 15_000L);
                assertEquals("hello-http-mcp", result.path("content").get(0).path("text").asText());
            }
        } finally {
            proc.destroyForcibly();
            proc.waitFor(3, TimeUnit.SECONDS);
        }
    }

    static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "-v").redirectErrorStream(true).start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            p.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }
}
