package com.zcode.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One append-only session log line (dsh-style). Chat + trajectory both project from this.
 *
 * <p>Legacy sessions remain ChatMessage-per-line JSONL without {@code type}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionEvent(
        int v,
        String id,
        long ts,
        String sessionId,
        String turnId,
        String type,
        Map<String, Object> payload
) {
    public static final int VERSION = 2;

    public static final String USER_MESSAGE = "user.message";
    public static final String ASSISTANT_MESSAGE = "assistant.message";
    public static final String TOOL_RESULT = "tool.result";
    public static final String SUMMARY = "summary";
    public static final String REQUEST_HEADER = "request.header";
    public static final String TURN_START = "turn.start";
    public static final String TURN_END = "turn.end";
    public static final String TOOL_START = "tool.start";
    public static final String TOOL_END = "tool.end";
    public static final String ERROR = "error";
    public static final String COMPACT = "compact";
    public static final String PERMISSION_MODE = "permission.mode";
    public static final String FILE_MUTATE = "file.mutate";

    public static SessionEvent of(String sessionId, String turnId, String type, Map<String, Object> payload) {
        return new SessionEvent(
                VERSION,
                newId(),
                System.currentTimeMillis(),
                sessionId,
                turnId,
                type,
                payload == null || payload.isEmpty() ? Map.of() : Map.copyOf(payload));
    }

    public static SessionEvent fromMessage(String sessionId, String turnId, ChatMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("message required");
        }
        String type = switch (message.role() == null ? "" : message.role()) {
            case "user" -> USER_MESSAGE;
            case "assistant" -> ASSISTANT_MESSAGE;
            case "tool" -> TOOL_RESULT;
            case "summary" -> SUMMARY;
            default -> "message." + message.role();
        };
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", message.id());
        payload.put("content", message.content() == null ? "" : message.content());
        if (message.coversUntil() != null) {
            payload.put("coversUntil", message.coversUntil());
        }
        if (message.toolUseId() != null) {
            payload.put("toolUseId", message.toolUseId());
        }
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            payload.put("toolCalls", message.toolCalls());
        }
        return new SessionEvent(
                VERSION,
                message.id() != null ? message.id() : newId(),
                message.ts() > 0 ? message.ts() : System.currentTimeMillis(),
                sessionId,
                turnId,
                type,
                Map.copyOf(payload));
    }

    public boolean isSurface() {
        return USER_MESSAGE.equals(type)
                || ASSISTANT_MESSAGE.equals(type)
                || TOOL_RESULT.equals(type)
                || SUMMARY.equals(type);
    }

    public boolean isDialogueSurface() {
        return USER_MESSAGE.equals(type) || ASSISTANT_MESSAGE.equals(type) || TOOL_RESULT.equals(type);
    }

    private static String newId() {
        return Long.toString(System.currentTimeMillis(), 36)
                + "-"
                + Integer.toHexString((int) (Math.random() * 0xffff))
                + "-"
                + UUID.randomUUID().toString().substring(0, 4);
    }
}
