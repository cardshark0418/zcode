package com.zcode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Web search with short timeouts and multiple backends (Bing → DuckDuckGo).
 * Weather-like queries also try wttr.in for a fast factual snippet.
 */
@Component
public class WebSearchTool implements Tool {

    private static final Pattern BING_ALGO = Pattern.compile(
            "(?is)<li class=\"b_algo\".*?<h2>\\s*<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>");
    private static final Pattern DDG_RESULT = Pattern.compile(
            "(?is)<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>");
    private static final Pattern UDDG = Pattern.compile("uddg=([^&]+)");
    private static final Pattern WEATHER_HINT = Pattern.compile(
            "(?i)(天气|氣溫|气温|weather|forecast|temperature|降雨|湿度)");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public WebSearchTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "websearch";
    }

    @Override
    public String description() {
        return "Search the live public web. Use for ANY realtime/factual question you cannot know from "
                + "local files alone — weather, news, docs, versions, prices, people, places, etc. "
                + "Returns titles/URLs/snippets (and a direct weather line when the query looks like weather). "
                + "Then webfetch promising URLs if you need more detail. Never refuse by saying you lack internet.";
    }

    @Override
    public JsonNode inputSchema(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("query").put("type", "string");
        props.putObject("max_results").put("type", "integer").put("description", "1-8, default 5");
        root.putArray("required").add("query");
        return root;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext ctx) {
        String query = text(input, "query");
        if (query == null || query.isBlank()) {
            return ToolResult.error("query is required");
        }
        int max = 5;
        if (input != null && input.path("max_results").isNumber()) {
            max = Math.max(1, Math.min(8, input.path("max_results").asInt()));
        }

        StringBuilder out = new StringBuilder();
        List<String> errors = new ArrayList<>();

        if (WEATHER_HINT.matcher(query).find()) {
            String weather = tryWeather(query);
            if (weather != null && !weather.isBlank()) {
                out.append("## Direct weather\n").append(weather.trim()).append("\n\n");
            }
        }

        List<Hit> hits = List.of();
        try {
            hits = searchBing(query, max);
        } catch (Exception e) {
            errors.add("bing: " + shortErr(e));
        }
        if (hits.isEmpty()) {
            try {
                hits = searchDdgHtml(query, max);
            } catch (Exception e) {
                errors.add("duckduckgo: " + shortErr(e));
            }
        }

        if (!hits.isEmpty()) {
            out.append("## Web results\n");
            int i = 1;
            for (Hit h : hits) {
                out.append(i++).append(". ").append(h.title()).append('\n');
                out.append("   ").append(h.url()).append('\n');
                if (h.snippet() != null && !h.snippet().isBlank()) {
                    out.append("   ").append(h.snippet()).append('\n');
                }
                out.append('\n');
            }
            return ToolResult.ok(out.toString().trim());
        }

        if (out.length() > 0) {
            return ToolResult.ok(out.toString().trim());
        }
        String err = errors.isEmpty() ? "no results" : String.join("; ", errors);
        return ToolResult.error("websearch failed (" + err + "). Try webfetch on a known URL, "
                + "e.g. https://wttr.in/<city>?format=j1 for weather.");
    }

    private List<Hit> searchBing(String query, int max) throws Exception {
        String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String html = httpGet(
                "https://www.bing.com/search?q=" + q + "&setlang=zh-CN",
                Duration.ofSeconds(12));
        Matcher m = BING_ALGO.matcher(html);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Hit> hits = new ArrayList<>();
        while (m.find() && hits.size() < max) {
            String url = m.group(1).trim();
            String title = stripTags(m.group(2)).trim();
            if (url.isBlank() || title.isBlank() || !seen.add(url)) {
                continue;
            }
            if (url.startsWith("/")) {
                continue;
            }
            hits.add(new Hit(title, url, ""));
        }
        return hits;
    }

    private List<Hit> searchDdgHtml(String query, int max) throws Exception {
        String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String html = httpGet("https://html.duckduckgo.com/html/?q=" + q, Duration.ofSeconds(10));
        Matcher m = DDG_RESULT.matcher(html);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Hit> hits = new ArrayList<>();
        while (m.find() && hits.size() < max) {
            String url = decodeUrl(m.group(1));
            String title = stripTags(m.group(2)).trim();
            if (url == null || title.isBlank() || !seen.add(url)) {
                continue;
            }
            hits.add(new Hit(title, url, ""));
        }
        return hits;
    }

    private String tryWeather(String query) {
        String city = query.replaceAll("(?i)(今天|今日|明天|实时|怎么样|如何|查询|看看|帮我)", "")
                .replaceAll(WEATHER_HINT.pattern(), "")
                .replaceAll("[\\s?？!！,，.。]+", "")
                .trim();
        if (city.isBlank()) {
            city = "Taizhou";
        }
        // Prefer Chinese path; also try Latin fallback for 台州.
        String[] candidates = city.equals("台州")
                ? new String[] {"台州", "Taizhou", "Taizhou,Zhejiang"}
                : new String[] {city, city + ",China"};
        for (String c : candidates) {
            try {
                String json = httpGet(
                        "https://wttr.in/" + URLEncoder.encode(c, StandardCharsets.UTF_8) + "?format=j1",
                        Duration.ofSeconds(10));
                String summary = summarizeWttr(json, c);
                if (summary != null) {
                    return summary;
                }
            } catch (Exception ignored) {
                // try next
            }
            try {
                String line = httpGet(
                        "https://wttr.in/" + URLEncoder.encode(c, StandardCharsets.UTF_8) + "?format=3",
                        Duration.ofSeconds(8));
                if (line != null && !line.isBlank() && !line.contains("<!DOCTYPE")) {
                    return line.trim();
                }
            } catch (Exception ignored) {
                // try next
            }
        }
        return null;
    }

    private String summarizeWttr(String json, String label) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode cur = root.path("current_condition");
            if (!cur.isArray() || cur.isEmpty()) {
                return null;
            }
            JsonNode c = cur.get(0);
            String temp = textNode(c, "temp_C");
            String feels = textNode(c, "FeelsLikeC");
            String humidity = textNode(c, "humidity");
            String wind = textNode(c, "windspeedKmph");
            String desc = "";
            JsonNode weatherDesc = c.path("lang_zh");
            if (weatherDesc.isArray() && !weatherDesc.isEmpty()) {
                desc = textNode(weatherDesc.get(0), "value");
            }
            if (desc.isBlank()) {
                JsonNode en = c.path("weatherDesc");
                if (en.isArray() && !en.isEmpty()) {
                    desc = textNode(en.get(0), "value");
                }
            }
            return label + " 当前: " + desc
                    + ", " + temp + "°C (体感 " + feels + "°C), 湿度 " + humidity
                    + "%, 风速 " + wind + " km/h";
        } catch (Exception e) {
            return null;
        }
    }

    private String httpGet(String url, Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET()
                .build();
        CompletableFuture<HttpResponse<String>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        HttpResponse<String> response = future.get(timeout.toMillis() + 500, TimeUnit.MILLISECONDS);
        if (response.statusCode() < 200 || response.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body() == null ? "" : response.body();
    }

    private static String decodeUrl(String href) {
        if (href == null) {
            return null;
        }
        Matcher u = UDDG.matcher(href);
        if (u.find()) {
            return java.net.URLDecoder.decode(u.group(1), StandardCharsets.UTF_8);
        }
        if (href.startsWith("http")) {
            return href;
        }
        return null;
    }

    private static String stripTags(String s) {
        return s == null ? "" : s.replaceAll("(?is)<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String text(JsonNode input, String field) {
        JsonNode n = input == null ? null : input.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static String textNode(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    private static String shortErr(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lower = msg.toLowerCase(Locale.ROOT);
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "timeout";
        }
        return msg.length() > 80 ? msg.substring(0, 77) + "..." : msg;
    }

    private record Hit(String title, String url, String snippet) {}
}
