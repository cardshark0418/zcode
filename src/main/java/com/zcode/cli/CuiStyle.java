package com.zcode.cli;

/**
 * Claude-like terminal palette (warm amber accent, muted chrome).
 */
public final class CuiStyle {

    public static final String RESET = "\u001B[0m";
    public static final String BOLD = "\u001B[1m";
    public static final String DIM = "\u001B[2m";
    public static final String ITALIC = "\u001B[3m";

    /** Claude-ish warm accent */
    public static final String ACCENT = "\u001B[38;5;216m";
    public static final String ACCENT_BOLD = "\u001B[1;38;5;216m";
    public static final String MUTED = "\u001B[38;5;245m";
    public static final String TEXT = "\u001B[38;5;252m";
    public static final String OK = "\u001B[38;5;114m";
    public static final String WARN = "\u001B[38;5;221m";
    public static final String ERR = "\u001B[38;5;203m";
    public static final String TOOL = "\u001B[38;5;117m";
    public static final String RULE = "\u001B[38;5;238m";

    private CuiStyle() {}

    public static String accent(String s) {
        return ACCENT_BOLD + s + RESET;
    }

    public static String muted(String s) {
        return MUTED + s + RESET;
    }

    public static String dim(String s) {
        return DIM + s + RESET;
    }
}
