package com.zcode.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcode.checkpoint.CheckpointService;
import com.zcode.memory.ChatMessage;
import com.zcode.memory.CompactionService;
import com.zcode.memory.SessionStore;
import com.zcode.trace.EventStore;
import com.zcode.trace.TraceEvent;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionStore sessionStore;
    private final CompactionService compactionService;
    private final AgentTurnService agentTurnService;
    private final InteractionRegistry interactions;
    private final EventStore eventStore;
    private final CheckpointService checkpointService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public SessionController(
            SessionStore sessionStore,
            CompactionService compactionService,
            AgentTurnService agentTurnService,
            InteractionRegistry interactions,
            EventStore eventStore,
            CheckpointService checkpointService,
            ObjectMapper objectMapper) {
        this.sessionStore = sessionStore;
        this.compactionService = compactionService;
        this.agentTurnService = agentTurnService;
        this.interactions = interactions;
        this.eventStore = eventStore;
        this.checkpointService = checkpointService;
        this.objectMapper = objectMapper;
    }

    public record CreateSessionRequest(Boolean resume) {}

    public record RenameSessionRequest(String title) {}

    public record TurnRequest(String message) {}

    public record InteractionReply(String answer, Boolean approved) {}

    public record RestoreRequest(String eventId) {}

    @GetMapping
    public List<Map<String, Object>> list() throws IOException {
        return sessionStore.listSessions().stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.id());
                    m.put("title", s.title());
                    m.put("displayName", s.displayName());
                    m.put("renamed", sessionStore.isTitleRenamed(s.id()));
                    m.put("mtimeMs", s.mtimeMs());
                    m.put("messageCount", s.messageCount());
                    return m;
                })
                .toList();
    }

    @PutMapping("/{sessionId}")
    public Map<String, Object> rename(@PathVariable String sessionId, @RequestBody RenameSessionRequest body)
            throws IOException {
        requireSession(sessionId);
        sessionStore.setTitle(sessionId, body == null ? null : body.title());
        String title = sessionStore.readTitle(sessionId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", sessionId);
        m.put("title", title);
        m.put("displayName", title == null || title.isBlank() ? SessionStore.DEFAULT_TITLE : title);
        m.put("renamed", sessionStore.isTitleRenamed(sessionId));
        return m;
    }

    @DeleteMapping("/{sessionId}")
    public Map<String, Object> delete(@PathVariable String sessionId) throws IOException {
        requireSession(sessionId);
        sessionStore.deleteSession(sessionId);
        return Map.of("ok", true);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody(required = false) CreateSessionRequest req) throws IOException {
        String id;
        if (req != null && Boolean.TRUE.equals(req.resume())) {
            id = sessionStore.resumeOrCreate();
        } else {
            id = sessionStore.createSession();
        }
        return Map.of("sessionId", id);
    }

    @GetMapping("/{sessionId}/messages")
    public List<ChatMessage> messages(@PathVariable String sessionId) throws IOException {
        requireSession(sessionId);
        return sessionStore.load(sessionId).stream().filter(ChatMessage::isDialogue).toList();
    }

    @GetMapping("/{sessionId}/events")
    public List<TraceEvent> events(
            @PathVariable String sessionId, @RequestParam(defaultValue = "0") int limit) throws IOException {
        requireSession(sessionId);
        return eventStore.load(sessionId, Math.max(0, limit));
    }

    @DeleteMapping("/{sessionId}/messages")
    public Map<String, Object> clear(@PathVariable String sessionId) throws IOException {
        requireSession(sessionId);
        sessionStore.clear(sessionId);
        checkpointService.clearUndo(sessionId);
        return Map.of("ok", true);
    }

    @PostMapping("/{sessionId}/compact")
    public Map<String, Object> compact(@PathVariable String sessionId) throws Exception {
        requireSession(sessionId);
        boolean did = compactionService.forceCompact(sessionId);
        return Map.of("ok", true, "compacted", did);
    }

    @PostMapping("/{sessionId}/restore")
    public Map<String, Object> restore(@PathVariable String sessionId, @RequestBody RestoreRequest body)
            throws IOException {
        requireSession(sessionId);
        if (body == null || body.eventId() == null || body.eventId().isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (agentTurnService.isBusy(sessionId)) {
            throw new IllegalStateException("session busy");
        }
        CheckpointService.RestoreResult result = checkpointService.restoreTo(sessionId, body.eventId().trim());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("filesRestored", result.filesRestored());
        m.put("undoAvailable", result.undoAvailable());
        return m;
    }

    @PostMapping("/{sessionId}/restore/undo")
    public Map<String, Object> undoRestore(@PathVariable String sessionId) throws IOException {
        requireSession(sessionId);
        if (agentTurnService.isBusy(sessionId)) {
            throw new IllegalStateException("session busy");
        }
        checkpointService.undoRestore(sessionId);
        return Map.of("ok", true);
    }

    @GetMapping("/{sessionId}/restore/undo")
    public Map<String, Object> undoStatus(@PathVariable String sessionId) {
        requireSession(sessionId);
        return Map.of("available", checkpointService.hasUndo(sessionId));
    }

    @PostMapping(value = "/{sessionId}/turns", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter turn(@PathVariable String sessionId, @RequestBody TurnRequest body) throws IOException {
        requireSession(sessionId);
        if (body == null || body.message() == null || body.message().isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        if (agentTurnService.isBusy(sessionId)) {
            throw new IllegalStateException("session busy");
        }
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        executor.execute(() -> {
            try {
                agentTurnService.runWebTurn(sessionId, body.message().trim(), (type, payload) -> {
                    try {
                        emitter.send(SseEmitter.event().name(type).data(objectMapper.writeValueAsString(payload)));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                try {
                    String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(objectMapper.writeValueAsString(Map.of("message", msg))));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @PostMapping("/{sessionId}/interactions/{interactionId}")
    public Map<String, Object> reply(
            @PathVariable String sessionId,
            @PathVariable String interactionId,
            @RequestBody InteractionReply body) {
        InteractionRegistry.Pending pending = interactions.get(interactionId);
        if (pending == null || !sessionId.equals(pending.sessionId())) {
            throw new IllegalArgumentException("unknown interaction");
        }
        Object value;
        if (InteractionRegistry.KIND_APPROVAL.equals(pending.kind())) {
            value = body != null && Boolean.TRUE.equals(body.approved());
        } else {
            value = body == null || body.answer() == null ? "" : body.answer();
        }
        boolean ok = interactions.complete(interactionId, value);
        return Map.of("ok", ok);
    }

    private void requireSession(String sessionId) {
        if (!sessionStore.exists(sessionId)) {
            throw new IllegalArgumentException("unknown session: " + sessionId);
        }
    }
}
