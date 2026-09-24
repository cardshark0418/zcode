package com.zcode.memory;

import java.util.List;

/**
 * What we send to the model after compaction + tail truncation.
 *
 * <p>{@code compactionSummary} is the raw stored summary (for UI / tracing). When present,
 * {@link #messages()} already begins with a framed checkpoint {@code user} message — the summary
 * is <em>not</em> placed in the system prompt.
 */
public record ContextView(String compactionSummary, List<ChatMessage> messages) {

    /** @deprecated use {@link #compactionSummary()} */
    @Deprecated
    public String systemSummary() {
        return compactionSummary;
    }

    public ContextView {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public boolean hasSummary() {
        return compactionSummary != null && !compactionSummary.isBlank();
    }
}
