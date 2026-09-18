package com.zcode.trace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * One line in {@code <sessionId>.events.jsonl}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraceEvent(
        String id,
        long ts,
        String sessionId,
        String turnId,
        String type,
        Map<String, Object> payload
) {
    public static TraceEvent of(String sessionId, String turnId, String type, Map<String, Object> payload) {
        return new TraceEvent(
                newId(),
                System.currentTimeMillis(),
                sessionId,
                turnId,
                type,
                payload == null || payload.isEmpty() ? Map.of() : Map.copyOf(payload));
    }

    private static String newId() {
        return Long.toString(System.currentTimeMillis(), 36) + "-" + Integer.toHexString((int) (Math.random() * 0xffff));
    }
}
