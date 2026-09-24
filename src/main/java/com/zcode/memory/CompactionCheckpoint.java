package com.zcode.memory;

import org.springframework.util.StringUtils;

/**
 * Frames a compaction summary as a conversation checkpoint message (Codex / DSH style):
 * the summary is sent as a {@code user} message in {@code messages[]}, not inside system.
 */
public final class CompactionCheckpoint {

    public static final String OPEN_TAG = "<compacted-summary>";
    public static final String CLOSE_TAG = "</compacted-summary>";

    /**
     * Preamble so the model treats the block as established background (DSH-style).
     */
    public static final String PREAMBLE =
            "这是一条自动生成的上下文检查点，用于压缩更早的对话以释放上下文。"
                    + "请将其中内容视为既定背景，在其基础上继续，不要复述检查点本身，也不要提及已发生压缩。"
                    + "请直接从检查点之后的消息继续任务。";

    private CompactionCheckpoint() {}

    /** Full text for a checkpoint user message. */
    public static String frame(String summary) {
        String body = summary == null ? "" : summary.trim();
        return PREAMBLE + "\n\n" + OPEN_TAG + "\n" + body + "\n" + CLOSE_TAG;
    }

    /** Ephemeral user message injected at request build time (not persisted as role=user). */
    public static ChatMessage asUserMessage(String summary) {
        return ChatMessage.user(frame(summary));
    }

    public static boolean isCheckpointContent(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        String t = content.trim();
        return t.startsWith(PREAMBLE) || t.contains(OPEN_TAG);
    }
}
