package com.zcode.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Live smoke against GitHub remote MCP. Skips unless {@code GITHUB_PERSONAL_ACCESS_TOKEN}
 * or {@code GH_TOKEN} is set (e.g. from {@code gh auth token}).
 */
class GitHubRemoteMcpIT {

    @Test
    void initializeListAndGetMe() throws Exception {
        String tok = firstEnv("GITHUB_PERSONAL_ACCESS_TOKEN", "GH_TOKEN");
        assumeTrue(tok != null && !tok.isBlank(), "no GitHub token in env — skip live IT");

        ObjectMapper mapper = new ObjectMapper();
        McpServerConfig cfg = new McpServerConfig(
                "github",
                null,
                List.of(),
                Map.of(),
                null,
                "https://api.githubcopilot.com/mcp/",
                Map.of(),
                tok,
                false);
        try (McpHttpClient client = McpHttpClient.start(cfg, mapper)) {
            client.initialize();
            var listed = client.listTools();
            int n = listed.path("tools").size();
            assertTrue(n > 0, "expected GitHub MCP tools");
            String tool = "get_me";
            boolean hasGetMe = false;
            for (var t : listed.path("tools")) {
                if ("get_me".equals(t.path("name").asText())) {
                    hasGetMe = true;
                    break;
                }
            }
            assumeTrue(hasGetMe, "get_me not in tool list");
            ObjectNode args = mapper.createObjectNode();
            var result = client.callTool(tool, args, 60_000L);
            assertTrue(result != null && !result.isNull(), "empty get_me result");
            String s = result.toString();
            assertTrue(s.contains("login") || s.contains("content") || s.length() > 10, s);
        }
    }

    private static String firstEnv(String... keys) {
        for (String k : keys) {
            String v = System.getenv(k);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        // try gh auth token for local dogfood
        try {
            Process p = new ProcessBuilder("gh", "auth", "token").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 && !out.isBlank()) {
                return out;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
