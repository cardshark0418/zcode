package com.zcode.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Derive LLM / chat {@link ChatMessage}s from a unified {@link SessionEvent} log.
 */
public final class SessionProjection {

    private SessionProjection() {}

    public static List<ChatMessage> toMessages(List<SessionEvent> events, ObjectMapper mapper) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> out = new ArrayList<>();
        for (SessionEvent e : events) {
            ChatMessage m = toMessage(e, mapper);
            if (m != null) {
                out.add(m);
            }
        }
        return List.copyOf(out);
    }

    public static ChatMessage toMessage(SessionEvent e, ObjectMapper mapper) {
        if (e == null || e.type() == null) {
            return null;
        }
        Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
        String id = str(p.get("id"));
        if (id == null || id.isBlank()) {
            id = e.id();
        }
        long ts = e.ts() > 0 ? e.ts() : System.currentTimeMillis();
        String content = str(p.get("content"));
        return switch (e.type()) {
            case SessionEvent.USER_MESSAGE ->
                    new ChatMessage(id, "user", content == null ? "" : content, ts, null, null, null);
            case SessionEvent.ASSISTANT_MESSAGE -> {
                List<ToolCall> calls = null;
                Object raw = p.get("toolCalls");
                if (raw != null && mapper != null) {
                    calls = mapper.convertValue(
                            raw, mapper.getTypeFactory().constructCollectionType(List.class, ToolCall.class));
                    if (calls != null && calls.isEmpty()) {
                        calls = null;
                    }
                }
                yield new ChatMessage(id, "assistant", content, ts, null, calls, null);
            }
            case SessionEvent.TOOL_RESULT ->
                    new ChatMessage(id, "tool", content == null ? "" : content, ts, null, null, str(p.get("toolUseId")));
            case SessionEvent.SUMMARY ->
                    new ChatMessage(id, "summary", content == null ? "" : content, ts, str(p.get("coversUntil")), null, null);
            default -> null;
        };
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
