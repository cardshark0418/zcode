package com.zcode.web;

import com.zcode.agent.AgentService;
import com.zcode.checkpoint.CheckpointService;
import com.zcode.memory.ChatMessage;
import com.zcode.memory.CompactionService;
import com.zcode.memory.ContextView;
import com.zcode.memory.SessionStore;
import com.zcode.tool.AskUserHandler;
import com.zcode.tool.ToolApprover;
import com.zcode.trace.EventStore;
import com.zcode.trace.TraceContext;
import com.zcode.trace.TraceContextHolder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Service;

/**
 * Shared agent turn pipeline for CLI and Web.
 */
@Service
public class AgentTurnService {

    private final AgentService agentService;
    private final SessionStore sessionStore;
    private final CompactionService compactionService;
    private final EventStore eventStore;
    private final InteractionRegistry interactions;
    private final CheckpointService checkpointService;
    private final ConcurrentHashMap<String, Boolean> busy = new ConcurrentHashMap<>();

    public AgentTurnService(
            AgentService agentService,
            SessionStore sessionStore,
            CompactionService compactionService,
            EventStore eventStore,
            InteractionRegistry interactions,
            CheckpointService checkpointService) {
        this.agentService = agentService;
        this.sessionStore = sessionStore;
        this.compactionService = compactionService;
        this.eventStore = eventStore;
        this.interactions = interactions;
        this.checkpointService = checkpointService;
    }

    public boolean isBusy(String sessionId) {
        return busy.containsKey(sessionId);
    }

    /**
     * @param emit (eventType, payload) — payload must be JSON-serializable
     */
    public void runWebTurn(String sessionId, String message, BiConsumer<String, Map<String, Object>> emit)
            throws Exception {
        if (!sessionStore.exists(sessionId)) {
            throw new IllegalArgumentException("unknown session: " + sessionId);
        }
        if (busy.putIfAbsent(sessionId, Boolean.TRUE) != null) {
            throw new IllegalStateException("session busy");
        }
        String turnId = newTurnId();
        TraceContext ctx = TraceContext.of(sessionId, turnId);
        TraceContextHolder.set(ctx);
        try {
            checkpointService.clearUndo(sessionId);

            // Supersede unanswered user lines left by restore-to-checkpoint (open intents).
            List<String> pruned = sessionStore.dropTrailingUnansweredUserMessages(sessionId);
            if (!pruned.isEmpty()) {
                emit.accept("history.prune", Map.of("droppedUserIds", pruned));
            }

            eventStore.emit(
                    "turn.start",
                    ctx,
                    eventStore.mapOf("userPreview", eventStore.preview(message)));
            emit.accept("turn.start", Map.of("turnId", turnId, "userPreview", eventStore.preview(message)));

            String title = sessionStore.maybeAutoTitleFromUserMessage(sessionId, message);
            if (title != null && !title.isBlank()) {
                emit.accept("session.title", Map.of("sessionId", sessionId, "title", title));
            }

            if (compactionService.maybeCompact(sessionId, message, turnId)) {
                emit.accept("status", Map.of("message", "memory compacted"));
            }

            ContextView view = sessionStore.buildContextView(sessionId);
            String system = agentService.buildSystemPrompt();
            eventStore.emit(
                    "request.header",
                    ctx,
                    eventStore.mapOf(
                            "system", system,
                            "messageCount", view.messages().size(),
                            "hasSummary", view.hasSummary()));
            emit.accept(
                    "request.header",
                    Map.of(
                            "turnId", turnId,
                            "messageCount", view.messages().size(),
                            "systemPreview", eventStore.preview(system)));

            ChatMessage userMsg = ChatMessage.user(message);
            // Persist user message before tools so checkpoints sit before file.mutate events.
            sessionStore.append(sessionId, userMsg);
            emit.accept(
                    "user.message",
                    Map.of(
                            "id", userMsg.id() == null ? "" : userMsg.id(),
                            "turnId", turnId,
                            "content", message));

            AskUserHandler askUser = (question, options) -> {
                String id = interactions.beginAsk(sessionId, question, options);
                emit.accept(
                        "interaction.required",
                        Map.of(
                                "interactionId", id,
                                "kind", InteractionRegistry.KIND_ASK,
                                "turnId", turnId,
                                "question", question == null ? "" : question,
                                "options", options == null ? List.of() : options));
                return interactions.awaitAsk(id, 600);
            };

            ToolApprover approver = (toolName, summary) -> {
                String id = interactions.beginApproval(sessionId, toolName, summary);
                emit.accept(
                        "interaction.required",
                        Map.of(
                                "interactionId", id,
                                "kind", InteractionRegistry.KIND_APPROVAL,
                                "turnId", turnId,
                                "toolName", toolName == null ? "" : toolName,
                                "summary", summary == null ? "" : summary));
                return interactions.awaitApproval(id, 600);
            };

            AgentService.AgentOutcome outcome = agentService.run(
                    view.messages(),
                    userMsg,
                    sessionId,
                    turnId,
                    askUser,
                    approver,
                    event -> {
                        if (event == null) {
                            return;
                        }
                        emit.accept("tool.status", Map.of("text", event));
                    },
                    token -> emit.accept("text.delta", Map.of("turnId", turnId, "token", token)),
                    emit);

            if (outcome.finalText() == null || outcome.finalText().isBlank()) {
                eventStore.emit("turn.end", ctx, eventStore.mapOf("ok", false, "assistantPreview", ""));
                emit.accept("turn.end", Map.of("ok", false, "assistantPreview", "", "turnId", turnId));
                throw new IllegalStateException("empty model response");
            }

            for (ChatMessage m : outcome.newMessages()) {
                if (m != null && userMsg.id() != null && userMsg.id().equals(m.id())) {
                    continue;
                }
                sessionStore.append(sessionId, m);
            }
            eventStore.emit(
                    "turn.end",
                    ctx,
                    eventStore.mapOf("ok", true, "assistantPreview", eventStore.preview(outcome.finalText())));
            emit.accept(
                    "turn.end",
                    Map.of(
                            "ok", true,
                            "turnId", turnId,
                            "assistantPreview", eventStore.preview(outcome.finalText())));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            eventStore.emit("error", ctx, eventStore.mapOf("where", "AgentTurnService", "message", msg));
            eventStore.emit(
                    "turn.end",
                    ctx,
                    eventStore.mapOf("ok", false, "assistantPreview", eventStore.preview(msg)));
            emit.accept("error", Map.of("message", msg, "turnId", turnId));
            throw e;
        } finally {
            interactions.cancelSession(sessionId);
            busy.remove(sessionId);
            TraceContextHolder.clear();
        }
    }

    private static String newTurnId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
