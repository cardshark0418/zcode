package com.zcode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zcode.memory")
public record MemoryProperties(
        /** Directory for session JSONL files. Empty = ~/.zcode/sessions */
        String dir,
        /** Max user/assistant turns kept as raw dialogue (pairs). */
        int maxTurns,
        /** Soft token budget for request context (heuristic). */
        int contextBudget,
        /** Compact when estimated tokens >= budget * threshold. */
        double compactThreshold,
        /** max_tokens for the summarization call. */
        int compactMaxTokens
) {
    public int safeMaxTurns() {
        return maxTurns <= 0 ? 20 : maxTurns;
    }

    public int safeContextBudget() {
        return contextBudget <= 0 ? 100_000 : contextBudget;
    }

    public double safeCompactThreshold() {
        if (compactThreshold <= 0 || compactThreshold > 1) {
            return 0.8;
        }
        return compactThreshold;
    }

    public int safeCompactMaxTokens() {
        return compactMaxTokens <= 0 ? 2048 : compactMaxTokens;
    }

    public int compactTriggerTokens() {
        return (int) Math.floor(safeContextBudget() * safeCompactThreshold());
    }
}
