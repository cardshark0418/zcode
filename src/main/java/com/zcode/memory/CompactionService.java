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
 * Overflow → LLM summary + keep a raw tail under token budget (and max-turns cap).
 * Summary is persisted as role=summary; at request time it is injected as a checkpoint
 * user message in {@code messages[]} (not into system).
 */
@Service
public class CompactionService {

    static final String COMPACT_SYSTEM = """
            你正在执行一次「上下文检查点压缩」。请为另一个将接手该任务的编程助手写一份交接摘要。

            必须严格按下面 Markdown 结构输出：保留全部章节、顺序不变；每节用简短条目，不要写成大段散文；某节无内容时写「无」，禁止删节。

            ## 主要请求与意图
            - [用户最初及后续演变的目标；措辞关键处可原文引用]

            ## 关键技术概念
            - [涉及的技术、框架、模式与约定]

            ## 文件与代码
            - [确切路径：为何重要、关键改动或片段]

            ## 错误与修复
            - [错误：如何解决；相关用户反馈]

            ## 未完成事项
            - [已明确要求但尚未完成的工作]

            ## 当前工作
            - [截至本检查点正在进行的具体事项]

            ## 下一步
            - [与最近请求直接对齐的下一步动作；若无则写「无」]

            ## 关键上下文
            - [决策及理由、约束、用户偏好、未决问题、继续所需的数据/示例/引用]
            - [用户明确要求记住的约束，如「记住」「必须」「不要忘」「始终」等]

            规则：
            1. 使用简洁中文；路径、命令、报错原文、标识符、数字、函数签名与语法片段保持原样，不要翻译或改写。
            2. 忠实保留用户反馈与明确指令，尤其是纠正。
            3. 丢掉寒暄、重复试错过程中已过时的中间方案；不要编造材料中没有的信息。
            4. 不要提及「正在压缩 / 正在总结」本身，也不要调用任何工具；只输出检查点正文。
            5. 若材料中已有旧摘要或 <compacted-summary>，视为 PRIOR 检查点：保留仍成立的事实，丢掉过时项，合并进同一结构，不要整段照抄。
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

        int retainTokens = memoryProperties.safeRetainTokens();
        int maxTurns = memoryProperties.safeMaxTurns();
        // Force / token-pressure: keep a smaller tail so more history folds into the summary.
        if (force || (overBudget && !overTurns)) {
            retainTokens = Math.max(512, retainTokens / 2);
            maxTurns = Math.max(2, maxTurns / 2);
        }

        List<ChatMessage> tail = DialogueTail.select(dialogue, retainTokens, maxTurns);
        if (tail.size() >= dialogue.size()) {
            if (!force) {
                return false;
            }
            tail = DialogueTail.select(dialogue, Math.max(512, retainTokens / 2), 2);
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
                        "keptMessages", tail.size(),
                        "retainTokens", retainTokens,
                        "keptTokens", TokenEstimator.estimateMessages(tail)));
        return true;
    }

    private static String buildCompactUserPrompt(String oldSummary, List<ChatMessage> head) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(oldSummary)) {
            sb.append("【旧检查点 / PRIOR】\n").append(oldSummary.trim()).append("\n\n");
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
        sb.append("请按系统要求的固定章节输出更新后的交接检查点（不要寒暄）：");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max) + "...";
    }
}
