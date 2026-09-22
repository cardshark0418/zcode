package com.zcode.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * Bridges blocking ask_user / tool approval during an agent turn to HTTP replies.
 */
@Component
public class InteractionRegistry {

    public static final String KIND_ASK = "ask_user";
    public static final String KIND_APPROVAL = "tool_approval";

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    public record Pending(
            String id,
            String sessionId,
            String kind,
            Map<String, Object> payload,
            CompletableFuture<Object> future
    ) {}

    public String beginAsk(String sessionId, String question, List<String> options) {
        String id = newId();
        CompletableFuture<Object> future = new CompletableFuture<>();
        pending.put(
                id,
                new Pending(
                        id,
                        sessionId,
                        KIND_ASK,
                        Map.of(
                                "question", question == null ? "" : question,
                                "options", options == null ? List.of() : options),
                        future));
        return id;
    }

    public String beginApproval(String sessionId, String toolName, String summary) {
        String id = newId();
        CompletableFuture<Object> future = new CompletableFuture<>();
        pending.put(
                id,
                new Pending(
                        id,
                        sessionId,
                        KIND_APPROVAL,
                        Map.of(
                                "toolName", toolName == null ? "" : toolName,
                                "summary", summary == null ? "" : summary),
                        future));
        return id;
    }

    public Pending get(String id) {
        return pending.get(id);
    }

    @SuppressWarnings("unchecked")
    public String awaitAsk(String id, long timeoutSeconds) {
        Pending p = pending.get(id);
        if (p == null) {
            return "";
        }
        try {
            Object v = p.future().get(timeoutSeconds, TimeUnit.SECONDS);
            return v == null ? "" : String.valueOf(v);
        } catch (TimeoutException e) {
            p.future().complete("");
            return "";
        } catch (Exception e) {
            return "";
        } finally {
            pending.remove(id);
        }
    }

    public boolean awaitApproval(String id, long timeoutSeconds) {
        Pending p = pending.get(id);
        if (p == null) {
            return false;
        }
        try {
            Object v = p.future().get(timeoutSeconds, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(v);
        } catch (Exception e) {
            return false;
        } finally {
            pending.remove(id);
        }
    }

    public boolean complete(String id, Object value) {
        Pending p = pending.get(id);
        if (p == null) {
            return false;
        }
        return p.future().complete(value);
    }

    public void cancelSession(String sessionId) {
        pending.entrySet().removeIf(e -> {
            if (sessionId.equals(e.getValue().sessionId())) {
                e.getValue().future().complete(e.getValue().kind().equals(KIND_APPROVAL) ? false : "");
                return true;
            }
            return false;
        });
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
