package com.zcode.memory;

/**
 * Rough token estimate for budget checks. Biased high for CJK so we compact early.
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.codePointCount(0, text.length()));
    }

    public static int estimateMessages(Iterable<ChatMessage> messages) {
        int total = 0;
        if (messages == null) {
            return 0;
        }
        for (ChatMessage m : messages) {
            if (m == null) {
                continue;
            }
            total += 4; // role / framing overhead
            total += estimate(m.content());
        }
        return total;
    }
}
