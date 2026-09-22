package com.zcode.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcode.config.TraceProperties;
import com.zcode.memory.SessionEvent;
import com.zcode.memory.SessionStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Runtime / session event writer. For event-sourced sessions, events append to the
 * same {@code <id>.jsonl} as chat history (dsh-style). Legacy ChatMessage sessions
 * still use a sidecar {@code <id>.events.jsonl}.
 */
@Component
public class EventStore {

    private final ObjectMapper objectMapper;
    private final SessionStore sessionStore;
    private final TraceProperties traceProperties;

    public EventStore(ObjectMapper objectMapper, SessionStore sessionStore, TraceProperties traceProperties) {
        this.objectMapper = objectMapper;
        this.sessionStore = sessionStore;
        this.traceProperties = traceProperties;
    }

    public boolean enabled() {
        return traceProperties.isEnabled();
    }

    public int previewChars() {
        return traceProperties.safePreviewChars();
    }

    public Path eventsFile(String sessionId) {
        return sessionStore.sessionsDir().resolve(sessionId + ".events.jsonl");
    }

    public void emit(String type, TraceContext ctx, Map<String, Object> payload) {
        if (!enabled() || ctx == null || ctx.sessionId() == null || ctx.sessionId().isBlank()) {
            return;
        }
        try {
            boolean eventLog = sessionStore.isEventLog(ctx.sessionId());
            if (eventLog) {
                sessionStore.appendEvent(SessionEvent.of(ctx.sessionId(), ctx.turnId(), type, payload));
                return;
            }
            Files.createDirectories(sessionStore.sessionsDir());
            TraceEvent event = TraceEvent.of(ctx.sessionId(), ctx.turnId(), type, payload);
            String line = objectMapper.writeValueAsString(event) + "\n";
            Files.writeString(
                    eventsFile(ctx.sessionId()),
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // tracing must never break the agent
        }
    }

    public void emit(String type, String sessionId, String turnId, Map<String, Object> payload) {
        emit(type, TraceContext.of(sessionId, turnId), payload);
    }

    public String preview(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replace('\r', ' ').replace('\n', ' ').trim();
        int max = previewChars();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i] != null && kv[i + 1] != null) {
                m.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
        return m;
    }

    /**
     * Load trajectory events: unified session log when event-sourced, else sidecar file.
     * Returned as {@link TraceEvent} for API compatibility (same shape as {@link SessionEvent}).
     */
    public List<TraceEvent> load(String sessionId, int limit) throws IOException {
        List<TraceEvent> all = new ArrayList<>();
        if (sessionStore.isEventLog(sessionId)) {
            for (SessionEvent e : sessionStore.loadEvents(sessionId)) {
                all.add(new TraceEvent(e.id(), e.ts(), e.sessionId(), e.turnId(), e.type(), e.payload()));
            }
        } else {
            Path file = eventsFile(sessionId);
            if (Files.isRegularFile(file)) {
                try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) {
                            continue;
                        }
                        all.add(objectMapper.readValue(line, TraceEvent.class));
                    }
                }
            }
        }
        if (limit <= 0 || all.size() <= limit) {
            return List.copyOf(all);
        }
        return List.copyOf(all.subList(all.size() - limit, all.size()));
    }

    /** Events for the most recent turnId that appears in the file. */
    public List<TraceEvent> loadLatestTurn(String sessionId) throws IOException {
        List<TraceEvent> all = load(sessionId, 0);
        String turnId = null;
        for (int i = all.size() - 1; i >= 0; i--) {
            String t = all.get(i).turnId();
            if (t != null && !t.isBlank()) {
                turnId = t;
                break;
            }
        }
        if (turnId == null) {
            return List.of();
        }
        String finalTurnId = turnId;
        return all.stream().filter(e -> finalTurnId.equals(e.turnId())).toList();
    }
}
