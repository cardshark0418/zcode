package com.zcode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zcode.memory")
public record MemoryProperties(
        /** Directory for session JSONL files. Empty = &lt;workspace&gt;/.zcode/sessions */
        String dir,
        /** Hard cap on plain-user turns kept as raw dialogue (with retain budget). */
        int maxTurns,
        /** Soft token budget for request context (heuristic). */
        int contextBudget,
        /** Compact when estimated tokens >= budget * threshold. */
        double compactThreshold,
        /** max_tokens for the summarization call. */
        int compactMaxTokens,
        /**
         * Fraction of {@link #contextBudget} kept as raw tail after compaction / when
         * truncating for the model. Ignored when {@link #retainTokens} &gt; 0.
         */
        double retainRatio,
        /** Absolute raw-tail token budget; 0 = derive from contextBudget * retainRatio. */
        int retainTokens
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

    /** Default ~16% of context budget (DSH-style), never above the compact trigger. */
    public double safeRetainRatio() {
        if (retainRatio <= 0 || retainRatio > 1) {
            return 0.16;
        }
        return retainRatio;
    }

    public int safeRetainTokens() {
        int budget = safeContextBudget();
        int fromRatio = (int) Math.floor(budget * safeRetainRatio());
        int absolute = retainTokens > 0 ? retainTokens : fromRatio;
        int trigger = compactTriggerTokens();
        // Tail must leave room for summary + new turns; keep strictly below trigger.
        int capped = Math.min(absolute, Math.max(1, trigger - 1));
        return Math.max(512, capped);
    }
}
