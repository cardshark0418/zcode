package com.zcode.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zcode.config.LlmRuntime;
import com.zcode.memory.ChatMessage;
import com.zcode.memory.ToolCall;
import com.zcode.trace.EventStore;
import com.zcode.trace.TraceContext;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Streaming chat for FoxCode:
 * - Anthropic Messages ({@code /claude/.../v1/messages}) — Claude
 * - OpenAI Chat Completions ({@code /codex/v1/chat/completions}) — GPT
 */
@Service
public class ChatService {

    private final LlmRuntime llmProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AnthropicMessageCodec anthropicMessageCodec;
    private final OpenAiMessageCodec openAiMessageCodec;
    private final EventStore eventStore;

    public ChatService(
            LlmRuntime llmProperties,
            ObjectMapper objectMapper,
            AnthropicMessageCodec anthropicMessageCodec,
            OpenAiMessageCodec openAiMessageCodec,
            EventStore eventStore) {
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
        this.anthropicMessageCodec = anthropicMessageCodec;
        this.openAiMessageCodec = openAiMessageCodec;
        this.eventStore = eventStore;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public String chat(String message) {
        StringBuilder full = new StringBuilder();
        stream(null, List.of(ChatMessage.user(message)), full::append);
        return full.toString();
    }

    /** Single-turn convenience (no prior history). */
    public void stream(String message, Consumer<String> onPartial) {
        stream(null, List.of(ChatMessage.user(message)), onPartial);
    }

    /**
     * Stream a reply for a full message list (already truncated by caller if needed).
     * Roles must be {@code user} / {@code assistant}; last message should be the new user turn.
     */
    public void stream(List<ChatMessage> messages, Consumer<String> onPartial) {
        stream(null, messages, onPartial);
    }

    /**
     * @param systemSummary full system instructions (environment / tools). Compaction summary is
     *                      carried in {@code messages} as a checkpoint user message, not here.
     */
    public void stream(String systemSummary, List<ChatMessage> messages, Consumer<String> onPartial) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        requireApiKey();
        withModelRetries(
                "stream",
                null,
                onPartial,
                null,
                tracked -> {
                    if (llmProperties.anthropic()) {
                        streamAnthropic(systemSummary, messages, tracked);
                    } else {
                        streamOpenAi(systemSummary, messages, tracked);
                    }
                    return Boolean.TRUE;
                });
    }

    /**
     * Non-streaming completion (used by memory compaction).
     */
    public String complete(String system, String userMessage, int maxTokens) {
        if (!StringUtils.hasText(userMessage)) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }
        requireApiKey();
        int tokens = Math.max(256, maxTokens);
        return withModelRetries(
                "complete",
                null,
                null,
                null,
                ignored -> {
                    if (llmProperties.anthropic()) {
                        return completeAnthropic(system, userMessage.trim(), tokens);
                    }
                    return completeOpenAi(system, userMessage.trim(), tokens);
                });
    }

    /**
     * Streaming Anthropic Messages call with optional tools.
     * History may include tool_use / tool_result rows.
     * Text deltas are pushed to {@code onPartialText} as they arrive; tool_use is assembled
     * when each content block completes (executed by the agent loop after the stream ends).
     */
    public AnthropicMessageCodec.ModelTurn completeAgentTurn(
            String systemSummary, List<ChatMessage> history, ArrayNode tools) {
        return completeAgentTurn(systemSummary, history, tools, null, null);
    }

    public AnthropicMessageCodec.ModelTurn completeAgentTurn(
            String systemSummary,
            List<ChatMessage> history,
            ArrayNode tools,
            TraceContext trace) {
        return completeAgentTurn(systemSummary, history, tools, trace, null);
    }

    public AnthropicMessageCodec.ModelTurn completeAgentTurn(
            String systemSummary,
            List<ChatMessage> history,
            ArrayNode tools,
            TraceContext trace,
            Consumer<String> onPartialText) {
        return completeAgentTurn(systemSummary, history, tools, trace, onPartialText, null);
    }

    /**
     * @param onRetry optional status sink when a failed attempt will be retried (e.g. UI toast)
     */
    public AnthropicMessageCodec.ModelTurn completeAgentTurn(
            String systemSummary,
            List<ChatMessage> history,
            ArrayNode tools,
            TraceContext trace,
            Consumer<String> onPartialText,
            Consumer<String> onRetry) {
        requireApiKey();
        return withModelRetries(
                "completeAgentTurn",
                trace,
                onPartialText,
                onRetry,
                tracked -> {
                    if (!llmProperties.anthropic()) {
                        return completeAgentTurnOpenAi(systemSummary, history, tools, trace, tracked);
                    }
                    return completeAgentTurnAnthropic(systemSummary, history, tools, trace, tracked);
                });
    }

    private AnthropicMessageCodec.ModelTurn completeAgentTurnAnthropic(
            String systemSummary,
            List<ChatMessage> history,
            ArrayNode tools,
            TraceContext trace,
            Consumer<String> onPartialText) throws Exception {
        List<String> toolNames = new ArrayList<>();
        if (tools != null) {
            for (JsonNode t : tools) {
                if (t != null && t.hasNonNull("name")) {
                    toolNames.add(t.get("name").asText());
                }
            }
        }
        eventStore.emit(
                "model.request",
                trace,
                eventStore.mapOf(
                        "model", llmProperties.model(),
                        "toolNames", toolNames,
                        "messageCount", history == null ? 0 : history.size(),
                        "hasSystem", StringUtils.hasText(systemSummary),
                        "stream", true));
        long started = System.currentTimeMillis();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", llmProperties.model());
        root.put("max_tokens", Math.max(256, llmProperties.maxTokens()));
        root.put("stream", true);
        if (StringUtils.hasText(systemSummary)) {
            root.put("system", systemSummary.trim());
        }
        root.set("messages", anthropicMessageCodec.toMessagesArray(history));
        if (tools != null && !tools.isEmpty()) {
            root.set("tools", tools);
        }
        String body = objectMapper.writeValueAsString(root);
        RequestDump.write(objectMapper, "last-chat-request.json", body);

        String url = joinUrl(llmProperties.baseUrl(), "/v1/messages");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(30, llmProperties.timeoutSeconds())))
                .header("x-api-key", llmProperties.apiKey())
                .header("Authorization", "Bearer " + llmProperties.apiKey())
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "HTTP " + response.statusCode() + ": " + readQuietly(response.body()));
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        AnthropicMessageCodec.ModelTurn turn;
        if (contentType.contains("text/event-stream")
                || contentType.contains("stream")
                || contentType.isBlank()) {
            turn = readAnthropicAgentSse(response.body(), onPartialText);
        } else {
            String json = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            turn = anthropicMessageCodec.parseResponse(json);
            if (onPartialText != null && StringUtils.hasText(turn.text())) {
                onPartialText.accept(turn.text());
            }
        }

        List<String> callNames = new ArrayList<>();
        if (turn.toolCalls() != null) {
            turn.toolCalls().forEach(c -> callNames.add(c.name()));
        }
        eventStore.emit(
                "model.response",
                trace,
                eventStore.mapOf(
                        "stopReason", turn.stopReason(),
                        "latencyMs", System.currentTimeMillis() - started,
                        "textPreview", eventStore.preview(turn.text()),
                        "toolCallNames", callNames,
                        "stream", true));
        return turn;
    }

    private AnthropicMessageCodec.ModelTurn completeAgentTurnOpenAi(
            String systemSummary,
            List<ChatMessage> history,
            ArrayNode tools,
            TraceContext trace,
            Consumer<String> onPartialText) throws Exception {
        List<String> toolNames = new ArrayList<>();
        if (tools != null) {
            for (JsonNode t : tools) {
                JsonNode fn = t == null ? null : t.get("function");
                if (fn != null && fn.hasNonNull("name")) {
                    toolNames.add(fn.get("name").asText());
                } else if (t != null && t.hasNonNull("name")) {
                    toolNames.add(t.get("name").asText());
                }
            }
        }
        eventStore.emit(
                "model.request",
                trace,
                eventStore.mapOf(
                        "model", llmProperties.model(),
                        "toolNames", toolNames,
                        "messageCount", history == null ? 0 : history.size(),
                        "hasSystem", StringUtils.hasText(systemSummary),
                        "stream", true,
                        "api", "openai"));
        long started = System.currentTimeMillis();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", llmProperties.model());
        root.put("stream", true);
        root.put("max_tokens", Math.max(256, llmProperties.maxTokens()));
        root.set("messages", openAiMessageCodec.toMessagesArray(systemSummary, history));
        if (tools != null && !tools.isEmpty()) {
            root.set("tools", tools);
            root.put("tool_choice", "auto");
        }
        String body = objectMapper.writeValueAsString(root);
        RequestDump.write(objectMapper, "last-chat-request.json", body);

        String url = joinUrl(llmProperties.baseUrl(), "/chat/completions");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(30, llmProperties.timeoutSeconds())))
                .header("Authorization", "Bearer " + llmProperties.apiKey())
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "HTTP " + response.statusCode() + ": " + readQuietly(response.body()));
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        AnthropicMessageCodec.ModelTurn turn;
        if (contentType.contains("text/event-stream")
                || contentType.contains("stream")
                || contentType.isBlank()) {
            turn = readOpenAiAgentSse(response.body(), onPartialText);
        } else {
            String json = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            turn = openAiMessageCodec.parseResponse(json);
            if (onPartialText != null && StringUtils.hasText(turn.text())) {
                onPartialText.accept(turn.text());
            }
        }

        List<String> callNames = new ArrayList<>();
        if (turn.toolCalls() != null) {
            turn.toolCalls().forEach(c -> callNames.add(c.name()));
        }
        eventStore.emit(
                "model.response",
                trace,
                eventStore.mapOf(
                        "stopReason", turn.stopReason(),
                        "latencyMs", System.currentTimeMillis() - started,
                        "textPreview", eventStore.preview(turn.text()),
                        "toolCallNames", callNames,
                        "stream", true,
                        "api", "openai"));
        return turn;
    }

    /** 1 initial try + 2 retries. */
    private static final int MODEL_MAX_ATTEMPTS = 3;

    @FunctionalInterface
    private interface ModelAttempt<T> {
        T run(Consumer<String> onPartialText) throws Exception;
    }

    private <T> T withModelRetries(
            String where,
            TraceContext trace,
            Consumer<String> onPartialText,
            Consumer<String> onRetry,
            ModelAttempt<T> attempt) {
        RuntimeException last = null;
        for (int attemptNo = 1; attemptNo <= MODEL_MAX_ATTEMPTS; attemptNo++) {
            java.util.concurrent.atomic.AtomicBoolean streamed =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            Consumer<String> tracked =
                    t -> {
                        if (StringUtils.hasText(t)) {
                            streamed.set(true);
                        }
                        if (onPartialText != null) {
                            onPartialText.accept(t);
                        }
                    };
            try {
                return attempt.run(tracked);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("chat interrupted", e);
            } catch (Exception e) {
                RuntimeException wrapped =
                        e instanceof RuntimeException re
                                ? re
                                : new IllegalStateException("agent turn failed: " + e.getMessage(), e);
                last = wrapped;
                boolean canRetry =
                        attemptNo < MODEL_MAX_ATTEMPTS
                                && !streamed.get()
                                && isRetryableModelFailure(wrapped);
                if (!canRetry) {
                    eventStore.emit(
                            "error",
                            trace,
                            eventStore.mapOf(
                                    "where", "ChatService." + where,
                                    "message", wrapped.getMessage(),
                                    "attempt", attemptNo));
                    throw wrapped;
                }
                int retryIndex = attemptNo; // 1st retry after attempt 1 fails
                String msg =
                        "模型请求失败，正在重试 ("
                                + retryIndex
                                + "/"
                                + (MODEL_MAX_ATTEMPTS - 1)
                                + ") · "
                                + shortErr(wrapped.getMessage());
                eventStore.emit(
                        "model.retry",
                        trace,
                        eventStore.mapOf(
                                "attempt", attemptNo,
                                "maxAttempts", MODEL_MAX_ATTEMPTS,
                                "message", wrapped.getMessage()));
                if (onRetry != null) {
                    onRetry.accept(msg);
                }
                try {
                    Thread.sleep(400L * attemptNo);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw wrapped;
                }
            }
        }
        throw last != null ? last : new IllegalStateException("agent turn failed");
    }

    /** Auth / bad-request / interrupt: do not retry. Network / 429 / 5xx / empty stream: retry. */
    static boolean isRetryableModelFailure(Throwable e) {
        if (e instanceof IllegalArgumentException) {
            return false;
        }
        String msg = e.getMessage() == null ? "" : e.getMessage();
        String lower = msg.toLowerCase();
        if (lower.contains("interrupted")) {
            return false;
        }
        if (msg.contains("HTTP 400")
                || msg.contains("HTTP 401")
                || msg.contains("HTTP 403")
                || msg.contains("HTTP 404")) {
            return false;
        }
        if (msg.contains("未配置 API Key") || msg.contains("未配置 api key")) {
            return false;
        }
        return true;
    }

    private static String shortErr(String msg) {
        if (msg == null || msg.isBlank()) {
            return "error";
        }
        String one = msg.replace('\n', ' ').trim();
        return one.length() <= 120 ? one : one.substring(0, 117) + "...";
    }

    private void requireApiKey() {
        if (!StringUtils.hasText(llmProperties.apiKey())) {
            throw new IllegalStateException(
                    "当前提供方未配置 API Key。请在设置 → 模型 中填写，或切换到已配置的提供方。");
        }
    }

    private void streamAnthropic(String systemSummary, List<ChatMessage> messages, Consumer<String> onPartial) {
        String url = joinUrl(llmProperties.baseUrl(), "/v1/messages");
        String body;
        try {
            body = buildAnthropicBody(systemSummary, messages, true, llmProperties.maxTokens());
            RequestDump.write(objectMapper, "last-chat-request.json", body);
        } catch (Exception e) {
            throw new IllegalStateException("failed to build request: " + e.getMessage(), e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(30, llmProperties.timeoutSeconds())))
                .header("x-api-key", llmProperties.apiKey())
                .header("Authorization", "Bearer " + llmProperties.apiKey())
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        consumeSse(request, onPartial, true);
    }

    private void streamOpenAi(String systemSummary, List<ChatMessage> messages, Consumer<String> onPartial) {
        String url = joinUrl(llmProperties.baseUrl(), "/chat/completions");
        String body;
        try {
            body = buildOpenAiBody(systemSummary, messages, true, null);
            RequestDump.write(objectMapper, "last-chat-request.json", body);
        } catch (Exception e) {
            throw new IllegalStateException("failed to build request: " + e.getMessage(), e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(30, llmProperties.timeoutSeconds())))
                .header("Authorization", "Bearer " + llmProperties.apiKey())
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        consumeSse(request, onPartial, false);
    }

    private String completeAnthropic(String system, String userMessage, int maxTokens) throws Exception {
        String url = joinUrl(llmProperties.baseUrl(), "/v1/messages");
        String body = buildAnthropicBody(system, List.of(ChatMessage.user(userMessage)), false, maxTokens);
        RequestDump.write(objectMapper, "last-compact-request.json", body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(30, llmProperties.timeoutSeconds())))
                .header("x-api-key", llmProperties.apiKey())
                .header("Authorization", "Bearer " + llmProperties.apiKey())
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }
        String text = extractAnthropicNonStream(response.body());
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException("empty completion response");
        }
        return text;
    }

    private String completeOpenAi(String system, String userMessage, int maxTokens) throws Exception {
        String url = joinUrl(llmProperties.baseUrl(), "/chat/completions");
        String body = buildOpenAiBody(system, List.of(ChatMessage.user(userMessage)), false, maxTokens);
        RequestDump.write(objectMapper, "last-compact-request.json", body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(30, llmProperties.timeoutSeconds())))
                .header("Authorization", "Bearer " + llmProperties.apiKey())
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }
        String text = extractOpenAiNonStream(response.body());
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException("empty completion response");
        }
        return text;
    }

    private void consumeSse(HttpRequest request, Consumer<String> onPartial, boolean anthropic) {
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("chat interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("HTTP request failed: " + e.getMessage(), e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String errBody = readQuietly(response.body());
            throw new IllegalStateException("HTTP " + status + (errBody.isBlank() ? "" : (": " + errBody)));
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        try {
            if (contentType.contains("text/event-stream") || contentType.contains("stream") || anthropic) {
                if (anthropic) {
                    readAnthropicSse(response.body(), onPartial);
                } else {
                    readOpenAiSse(response.body(), onPartial);
                }
            } else {
                String json = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                String text = anthropic ? extractAnthropicNonStream(json) : extractOpenAiNonStream(json);
                if (StringUtils.hasText(text)) {
                    onPartial.accept(text);
                } else {
                    throw new IllegalStateException("empty response");
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to read response: " + e.getMessage(), e);
        }
    }

    private String buildAnthropicBody(
            String systemSummary, List<ChatMessage> history, boolean stream, int maxTokens) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", llmProperties.model());
        root.put("max_tokens", Math.max(256, maxTokens));
        root.put("stream", stream);
        if (StringUtils.hasText(systemSummary)) {
            root.put("system", systemSummary.trim());
        }
        ArrayNode messages = root.putArray("messages");
        for (ChatMessage m : history) {
            if (m == null || !m.isDialogue()) {
                continue;
            }
            ObjectNode row = messages.addObject();
            row.put("role", normalizeDialogueRole(m.role()));
            row.put("content", m.content() == null ? "" : m.content());
        }
        return objectMapper.writeValueAsString(root);
    }

    private String buildOpenAiBody(
            String systemSummary, List<ChatMessage> history, boolean stream, Integer maxTokens) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", llmProperties.model());
        root.put("stream", stream);
        if (maxTokens != null) {
            root.put("max_tokens", Math.max(256, maxTokens));
        }
        ArrayNode messages = root.putArray("messages");
        if (StringUtils.hasText(systemSummary)) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", systemSummary.trim());
        }
        for (ChatMessage m : history) {
            if (m == null || !m.isDialogue()) {
                continue;
            }
            ObjectNode row = messages.addObject();
            row.put("role", normalizeDialogueRole(m.role()));
            row.put("content", m.content() == null ? "" : m.content());
        }
        return objectMapper.writeValueAsString(root);
    }

    private static String normalizeDialogueRole(String role) {
        if (role != null && "assistant".equalsIgnoreCase(role.trim())) {
            return "assistant";
        }
        return "user";
    }

    private void readAnthropicSse(InputStream body, Consumer<String> onPartial) throws Exception {
        StringBuilder collected = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith(":") || line.startsWith("event:")) {
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    continue;
                }

                JsonNode root = objectMapper.readTree(data);
                String type = textOrNull(root.get("type"));
                if ("content_block_delta".equals(type)) {
                    JsonNode delta = root.get("delta");
                    if (delta != null) {
                        String token = textOrNull(delta.get("text"));
                        if (token != null && !token.isEmpty()) {
                            collected.append(token);
                            onPartial.accept(token);
                        }
                    }
                } else if ("message_stop".equals(type)) {
                    break;
                } else if ("error".equals(type)) {
                    throw new IllegalStateException(root.toString());
                }
            }
        }
        if (collected.toString().isBlank()) {
            throw new IllegalStateException("empty stream response (no content deltas)");
        }
    }

    /**
     * Anthropic Messages SSE with text + tool_use blocks.
     * Empty text is allowed when the model only emits tool calls.
     */
    private AnthropicMessageCodec.ModelTurn readAnthropicAgentSse(
            InputStream body, Consumer<String> onPartialText) throws Exception {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        String stopReason = null;

        String blockType = null;
        String toolId = null;
        String toolName = null;
        StringBuilder toolJson = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith(":") || line.startsWith("event:")) {
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }

                JsonNode root = objectMapper.readTree(data);
                String type = textOrNull(root.get("type"));
                if ("content_block_start".equals(type)) {
                    JsonNode block = root.get("content_block");
                    blockType = block == null ? null : textOrNull(block.get("type"));
                    if ("tool_use".equals(blockType)) {
                        toolId = textOrNull(block.get("id"));
                        toolName = textOrNull(block.get("name"));
                        toolJson = new StringBuilder();
                    } else {
                        toolId = null;
                        toolName = null;
                        toolJson = null;
                    }
                } else if ("content_block_delta".equals(type)) {
                    JsonNode delta = root.get("delta");
                    if (delta == null) {
                        continue;
                    }
                    String deltaType = textOrNull(delta.get("type"));
                    if ("text_delta".equals(deltaType) || delta.hasNonNull("text")) {
                        String token = textOrNull(delta.get("text"));
                        if (token != null && !token.isEmpty()) {
                            text.append(token);
                            if (onPartialText != null) {
                                onPartialText.accept(token);
                            }
                        }
                    } else if ("input_json_delta".equals(deltaType) || delta.has("partial_json")) {
                        String partial = textOrNull(delta.get("partial_json"));
                        if (partial != null && toolJson != null) {
                            toolJson.append(partial);
                        }
                    }
                } else if ("content_block_stop".equals(type)) {
                    if ("tool_use".equals(blockType) && toolName != null) {
                        String inputJson = toolJson == null || toolJson.isEmpty() ? "{}" : toolJson.toString();
                        calls.add(new ToolCall(toolId, toolName, inputJson));
                    }
                    blockType = null;
                    toolId = null;
                    toolName = null;
                    toolJson = null;
                } else if ("message_delta".equals(type)) {
                    JsonNode delta = root.get("delta");
                    if (delta != null && delta.hasNonNull("stop_reason")) {
                        stopReason = delta.get("stop_reason").asText();
                    }
                } else if ("message_stop".equals(type)) {
                    break;
                } else if ("error".equals(type)) {
                    throw new IllegalStateException(root.toString());
                }
            }
        }

        if (text.isEmpty() && calls.isEmpty()) {
            throw new IllegalStateException("empty stream response (no text or tool_use)");
        }
        return new AnthropicMessageCodec.ModelTurn(stopReason, text.toString(), List.copyOf(calls));
    }

    private void readOpenAiSse(InputStream body, Consumer<String> onPartial) throws Exception {
        StringBuilder collected = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith(":") || line.startsWith("event:")) {
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }

                JsonNode root = objectMapper.readTree(data);
                JsonNode choices = root.get("choices");
                if (choices != null && choices.isArray() && !choices.isEmpty()) {
                    JsonNode choice = choices.get(0);
                    JsonNode delta = choice.get("delta");
                    if (delta != null) {
                        String token = textOrNull(delta.get("content"));
                        if (token != null && !token.isEmpty()) {
                            collected.append(token);
                            onPartial.accept(token);
                        }
                    }
                    JsonNode finish = choice.get("finish_reason");
                    if (finish != null && !finish.isNull() && !finish.asText().isBlank()) {
                        break;
                    }
                }
            }
        }
        if (collected.toString().isBlank()) {
            throw new IllegalStateException("empty stream response (no content deltas)");
        }
    }

    /**
     * OpenAI Chat Completions SSE with text + incremental {@code tool_calls}.
     * Empty text is allowed when the model only emits tool calls.
     */
    private AnthropicMessageCodec.ModelTurn readOpenAiAgentSse(
            InputStream body, Consumer<String> onPartialText) throws Exception {
        StringBuilder text = new StringBuilder();
        Map<Integer, OpenAiToolDraft> drafts = new TreeMap<>();
        String stopReason = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith(":") || line.startsWith("event:")) {
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }

                JsonNode root = objectMapper.readTree(data);
                JsonNode choices = root.get("choices");
                if (choices == null || !choices.isArray() || choices.isEmpty()) {
                    continue;
                }
                JsonNode choice = choices.get(0);
                JsonNode delta = choice.get("delta");
                if (delta != null) {
                    String token = textOrNull(delta.get("content"));
                    if (token != null && !token.isEmpty()) {
                        text.append(token);
                        if (onPartialText != null) {
                            onPartialText.accept(token);
                        }
                    }
                    JsonNode toolCalls = delta.get("tool_calls");
                    if (toolCalls != null && toolCalls.isArray()) {
                        for (JsonNode tc : toolCalls) {
                            int index = tc.has("index") ? tc.get("index").asInt(0) : 0;
                            OpenAiToolDraft draft = drafts.computeIfAbsent(index, k -> new OpenAiToolDraft());
                            if (tc.hasNonNull("id")) {
                                draft.id = tc.get("id").asText();
                            }
                            JsonNode fn = tc.get("function");
                            if (fn != null) {
                                if (fn.hasNonNull("name")) {
                                    draft.name = fn.get("name").asText();
                                }
                                if (fn.has("arguments") && !fn.get("arguments").isNull()) {
                                    draft.arguments.append(fn.get("arguments").asText(""));
                                }
                            }
                        }
                    }
                }
                JsonNode finish = choice.get("finish_reason");
                if (finish != null && !finish.isNull() && !finish.asText().isBlank()) {
                    stopReason = finish.asText();
                    // Keep reading until [DONE] in case late chunks arrive; most providers stop here.
                    if ("stop".equals(stopReason) || "tool_calls".equals(stopReason) || "end_turn".equals(stopReason)) {
                        break;
                    }
                }
            }
        }

        List<ToolCall> calls = new ArrayList<>();
        for (OpenAiToolDraft draft : drafts.values()) {
            if (!StringUtils.hasText(draft.name)) {
                continue;
            }
            String args = draft.arguments.isEmpty() ? "{}" : draft.arguments.toString();
            calls.add(new ToolCall(draft.id, draft.name, args));
        }
        if (text.isEmpty() && calls.isEmpty()) {
            throw new IllegalStateException("empty stream response (no text or tool_calls)");
        }
        return new AnthropicMessageCodec.ModelTurn(stopReason, text.toString(), List.copyOf(calls));
    }

    private static final class OpenAiToolDraft {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    private String extractAnthropicNonStream(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode content = root.get("content");
        if (content == null || !content.isArray()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(textOrNull(block.get("type")))) {
                String t = textOrNull(block.get("text"));
                if (t != null) {
                    sb.append(t);
                }
            }
        }
        return sb.toString();
    }

    private String extractOpenAiNonStream(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode message = choices.get(0).get("message");
        return message == null ? null : textOrNull(message.get("content"));
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String joinUrl(String base, String path) {
        if (base.endsWith("/") && path.startsWith("/")) {
            return base.substring(0, base.length() - 1) + path;
        }
        if (!base.endsWith("/") && !path.startsWith("/")) {
            return base + "/" + path;
        }
        return base + path;
    }

    private static String readQuietly(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
