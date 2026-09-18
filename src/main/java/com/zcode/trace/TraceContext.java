package com.zcode.trace;

/**
 * Correlates events within one user turn / session.
 */
public record TraceContext(String sessionId, String turnId) {

    public static TraceContext of(String sessionId, String turnId) {
        return new TraceContext(sessionId, turnId);
    }
}
