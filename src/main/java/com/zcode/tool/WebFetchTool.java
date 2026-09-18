package com.zcode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class WebFetchTool implements Tool {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String name() {
        return "webfetch";
    }

    @Override
    public String description() {
        return "Fetch a URL and return readable text (HTML tags stripped). "
                + "Use after websearch, or directly for known URLs. "
                + "For weather you can fetch https://wttr.in/<city>?format=j1 or ?format=3. "
                + "Never claim you cannot access the internet.";
    }

    @Override
    public JsonNode inputSchema(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("url").put("type", "string").put("description", "http(s) URL");
        props.putObject("max_chars").put("type", "integer").put("description", "Optional truncate length");
        root.putArray("required").add("url");
        return root;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext ctx) throws Exception {
        String url = text(input, "url");
        if (url == null || url.isBlank()) {
            return ToolResult.error("url is required");
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return ToolResult.error("only http/https URLs are allowed");
        }
        int maxChars = input != null && input.path("max_chars").isNumber()
                ? Math.min(ctx.maxOutputChars(), Math.max(500, input.path("max_chars").asInt()))
                : Math.min(ctx.maxOutputChars(), 20_000);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", "zcode-webfetch/0.1")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.7")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 400) {
            return ToolResult.error("HTTP " + response.statusCode());
        }
        String body = response.body() == null ? "" : response.body();
        String ctype = response.headers().firstValue("Content-Type").orElse("");
        String text = ctype.contains("html") || body.contains("<html") || body.contains("<HTML")
                ? htmlToText(body)
                : body;
        text = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars) + "\n...[truncated]";
        }
        return ToolResult.ok(text.isEmpty() ? "(empty body)" : text);
    }

    private static String htmlToText(String html) {
        String s = html;
        s = s.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        s = s.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        s = s.replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</p>", "\n\n");
        s = s.replaceAll("(?i)</div>", "\n");
        s = s.replaceAll("(?i)</li>", "\n");
        s = s.replaceAll("(?i)<[^>]+>", " ");
        s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return s;
    }

    private static String text(JsonNode input, String field) {
        JsonNode n = input == null ? null : input.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}
