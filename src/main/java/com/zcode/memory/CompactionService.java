package com.zcode.memory;

import com.zcode.chat.ChatService;
import com.zcode.config.MemoryProperties;
import com.zcode.trace.EventStore;
import com.zcode.trace.TraceContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Overflow → LLM summary (system) + keep last N raw turns.
 */
@Service
public class CompactionService {

    static final String COMPACT_SYSTEM = """
            你是会话记忆压缩器。根据给定的对话材料，写出一份给后续助手用的「系统摘要」。
            要求：
            1. 必须保留硬事实：工作区/项目路径、环境与工具约定、已确认的技术选型、当前目标、未完成事项。
            2. 必须保留用户明确要求记住的约束（如「记住」「必须」「不要忘」「始终」等）。
            3. 丢掉过时方案、已纠正的错误、寒暄、重复试错细节。
            4. 用简洁中文分点书写；不要寒暄；不要编造材料中没有的信息。
            5. 若已有旧摘要，在其基础上合并更新，而不是从零重写后丢失旧硬事实。
            """;

    private final SessionStore sessionStore;
    private final ChatService chatService;
    private final MemoryProperties memoryProperties;
    private final EventStore eventStore;

    public CompactionService(
            SessionStore sessionStore,
            ChatService chatService,
            MemoryProperties memoryProperties,
            EventStore eventStore) {
        this.sessionStore = sessionStore;
        this.chatService = chatService;
        this.memoryProperties = memoryProperties;
        this.eventStore = eventStore;
    }

    /**
     * Compact if over turn count or token budget. Returns true if a summary was written.
     */
    public boolean maybeCompact(String sessionId, String upcomingUserMessage) throws IOException {
        return compact(sessionId, upcomingUserMessage, false, null);
    }

    public boolean maybeCompact(String sessionId, String upcomingUserMessage, String turnId) throws IOException {
        return compact(sessionId, upcomingUserMessage, false, turnId);
    }

    /** Force compact when there is anything older than the raw tail. */
    public boolean forceCompact(String sessionId) throws IOException {
        return compact(sessionId, "", true, null);
    }

    private boolean compact(String sessionId, String upcomingUserMessage, boolean force, String turnId)
            throws IOException {
        SessionStore.SessionSnapshot snap = sessionStore.snapshot(sessionId);
        List<ChatMessage> dialogue = snap.dialogue();
        if (dialogue.isEmpty()) {
            return false;
        }

        int plainUsers = 0;
        for (ChatMessage m : dialogue) {
            if (m.isPlainUser()) {
                plainUsers++;
            }
        }
        int estimated = TokenEstimator.estimate(snap.summary())
                + TokenEstimator.estimateMessages(dialogue)
                + TokenEstimator.estimate(upcomingUserMessage);
        boolean overTurns = plainUsers > memoryProperties.safeMaxTurns();
        boolean overBudget = estimated >= memoryProperties.compactTriggerTokens();

        if (!force && !overTurns && !overBudget) {
            return false;
        }

        int keepPairs = memoryProperties.safeMaxTurns();
        if (force && !overTurns && !overBudget) {
            keepPairs = Math.max(2, memoryProperties.safeMaxTurns() / 2);
        } else if (overBudget && !overTurns) {
            keepPairs = Math.max(2, memoryProperties.safeMaxTurns() / 2);
        }

        List<ChatMessage> tail = takeLastPlainUserTurns(dialogue, keepPairs);
        if (tail.size() >= dialogue.size()) {
            if (!force) {
                return false;
            }
            tail = takeLastPlainUserTurns(dialogue, 2);
            if (tail.size() >= dialogue.size()) {
                return false;
            }
        }

        List<ChatMessage> head = new ArrayList<>(dialogue.subList(0, dialogue.size() - tail.size()));
        if (head.isEmpty()) {
            return false;
        }

        String reason = force ? "force" : (overTurns ? "over_turns" : "over_budget");
        String userPrompt = buildCompactUserPrompt(snap.summary(), head);
        String newSummary = chatService.complete(
                COMPACT_SYSTEM,
                userPrompt,
                memoryProperties.safeCompactMaxTokens());
        if (!StringUtils.hasText(newSummary)) {
            throw new IllegalStateException("compaction produced empty summary");
        }

        String coversUntil = head.get(head.size() - 1).id();
        sessionStore.append(sessionId, ChatMessage.summary(newSummary.trim(), coversUntil));
        eventStore.emit(
                "compact",
                TraceContext.of(sessionId, turnId),
                eventStore.mapOf(
                        "reason", reason,
                        "summaryChars", newSummary.trim().length(),
                        "foldedMessages", head.size(),
                        "keptMessages", tail.size()));
        return true;
    }

    private static List<ChatMessage> takeLastPlainUserTurns(List<ChatMessage> dialogue, int turns) {
        List<Integer> plainUserIdx = new ArrayList<>();
        for (int i = 0; i < dialogue.size(); i++) {
            if (dialogue.get(i).isPlainUser()) {
                plainUserIdx.add(i);
            }
        }
        if (plainUserIdx.isEmpty()) {
            return List.copyOf(dialogue);
        }
        int keep = Math.max(1, turns);
        if (plainUserIdx.size() <= keep) {
            return List.copyOf(dialogue);
        }
        int start = plainUserIdx.get(plainUserIdx.size() - keep);
        return List.copyOf(dialogue.subList(start, dialogue.size()));
    }

    private static String buildCompactUserPrompt(String oldSummary, List<ChatMessage> head) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(oldSummary)) {
            sb.append("【旧摘要】\n").append(oldSummary.trim()).append("\n\n");
        }
        sb.append("【需折叠的对话】\n");
        for (ChatMessage m : head) {
            if ("tool".equals(m.role())) {
                sb.append("tool_result(").append(m.toolUseId()).append("): ")
                        .append(m.content() == null ? "" : truncate(m.content(), 500))
                        .append("\n\n");
            } else if (m.hasToolCalls()) {
                sb.append("assistant: ").append(m.content() == null ? "" : m.content()).append('\n');
                m.toolCalls().forEach(c -> sb.append("  tool_use ").append(c.name()).append(" ")
                        .append(c.inputJson()).append('\n'));
                sb.append('\n');
            } else {
                sb.append(m.role()).append(": ").append(m.content() == null ? "" : m.content()).append("\n\n");
            }
        }
        sb.append("请输出更新后的系统摘要：");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max) + "...";
    }
}
