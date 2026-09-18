package com.zcode.memory;

import java.util.List;

/**
 * What we send to the model after compaction + tail truncation.
 */
public record ContextView(String systemSummary, List<ChatMessage> messages) {

    public ContextView {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public boolean hasSummary() {
        return systemSummary != null && !systemSummary.isBlank();
    }
}
