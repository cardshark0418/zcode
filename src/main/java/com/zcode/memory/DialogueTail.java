package com.zcode.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * Select a raw dialogue tail under a token budget, cutting only at plain-user turn
 * boundaries so tool_use / tool_result chains stay intact.
 */
public final class DialogueTail {

    private DialogueTail() {}

    /**
     * @param retainTokens max estimated tokens for the tail (at least one plain-user turn kept when possible)
     * @param maxTurns max plain-user turns in the tail (hard cap)
     */
    public static List<ChatMessage> select(List<ChatMessage> dialogue, int retainTokens, int maxTurns) {
        if (dialogue == null || dialogue.isEmpty()) {
            return List.of();
        }
        List<Integer> plainUserIdx = new ArrayList<>();
        for (int i = 0; i < dialogue.size(); i++) {
            if (dialogue.get(i).isPlainUser()) {
                plainUserIdx.add(i);
            }
        }
        if (plainUserIdx.isEmpty()) {
            return trimToTokensFromEnd(dialogue, Math.max(1, retainTokens));
        }

        int turnCap = Math.max(1, maxTurns);
        int tokenCap = Math.max(1, retainTokens);
        int chosen = plainUserIdx.size() - 1; // at least newest user turn

        for (int t = plainUserIdx.size() - 2; t >= 0; t--) {
            int plainCount = plainUserIdx.size() - t;
            if (plainCount > turnCap) {
                break;
            }
            int start = plainUserIdx.get(t);
            List<ChatMessage> candidate = dialogue.subList(start, dialogue.size());
            if (TokenEstimator.estimateMessages(candidate) > tokenCap) {
                break;
            }
            chosen = t;
        }

        int start = plainUserIdx.get(chosen);
        return List.copyOf(dialogue.subList(start, dialogue.size()));
    }

    /** When there is no plain user (tool-only residue), keep a suffix by tokens. */
    private static List<ChatMessage> trimToTokensFromEnd(List<ChatMessage> dialogue, int tokenCap) {
        int end = dialogue.size();
        int start = end;
        int used = 0;
        for (int i = end - 1; i >= 0; i--) {
            int cost = 4 + TokenEstimator.estimate(dialogue.get(i).content());
            if (start < end && used + cost > tokenCap) {
                break;
            }
            used += cost;
            start = i;
        }
        return List.copyOf(dialogue.subList(start, end));
    }
}
