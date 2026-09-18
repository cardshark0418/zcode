package com.zcode.memory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One line in a session JSONL file.
 * Roles: user | assistant | tool | summary
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
        String id,
        String role,
        String content,
        long ts,
        String coversUntil,
        List<ToolCall> toolCalls,
        String toolUseId
) {

    public static ChatMessage user(String content) {
        return new ChatMessage(newId(), "user", content, System.currentTimeMillis(), null, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(newId(), "assistant", content, System.currentTimeMillis(), null, null, null);
    }

    public static ChatMessage assistantWithTools(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(
                newId(),
                "assistant",
                content,
                System.currentTimeMillis(),
                null,
                toolCalls == null || toolCalls.isEmpty() ? null : List.copyOf(toolCalls),
                null);
    }

    public static ChatMessage toolResult(String toolUseId, String content) {
        return new ChatMessage(newId(), "tool", content, System.currentTimeMillis(), null, null, toolUseId);
    }

    public static ChatMessage summary(String content, String coversUntil) {
        return new ChatMessage(newId(), "summary", content, System.currentTimeMillis(), coversUntil, null, null);
    }

    @JsonIgnore
    public boolean isDialogue() {
        return "user".equals(role) || "assistant".equals(role) || "tool".equals(role);
    }

    @JsonIgnore
    public boolean isPlainUser() {
        return "user".equals(role);
    }

    @JsonIgnore
    public boolean isSummary() {
        return "summary".equals(role);
    }

    @JsonIgnore
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    private static String newId() {
        return Long.toString(System.currentTimeMillis(), 36) + "-" + Integer.toHexString((int) (Math.random() * 0xffff));
    }
}
