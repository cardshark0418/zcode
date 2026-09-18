package com.zcode.tool;

import java.util.List;
import java.util.function.BiFunction;

/**
 * Ask the human mid-run. options may be empty (free-text answer).
 * Returns the user's reply string.
 */
@FunctionalInterface
public interface AskUserHandler extends BiFunction<String, List<String>, String> {

    default String ask(String question, List<String> options) {
        return apply(question, options == null ? List.of() : options);
    }
}
