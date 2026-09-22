package com.zcode.trace;

/**
 * Turn correlation for the current agent thread (used when appending session events).
 */
public final class TraceContextHolder {

    private static final ThreadLocal<TraceContext> CTX = new ThreadLocal<>();

    private TraceContextHolder() {}

    public static void set(TraceContext ctx) {
        CTX.set(ctx);
    }

    public static TraceContext get() {
        return CTX.get();
    }

    public static String turnId() {
        TraceContext ctx = CTX.get();
        return ctx == null ? null : ctx.turnId();
    }

    public static String sessionId() {
        TraceContext ctx = CTX.get();
        return ctx == null ? null : ctx.sessionId();
    }

    public static void clear() {
        CTX.remove();
    }
}
