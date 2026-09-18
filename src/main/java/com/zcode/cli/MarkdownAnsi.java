package com.zcode.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight Markdown → ANSI for Windows Terminal / modern cmd.
 * Not a full CommonMark parser — covers the patterns models usually emit.
 */
public final class MarkdownAnsi {

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String ITALIC = "\u001B[3m";
    private static final String CODE = "\u001B[38;5;180m";
    private static final String HEADING = "\u001B[1;38;5;216m";
    private static final String BULLET = "\u001B[38;5;216m";

    private static final Pattern FENCE = Pattern.compile("(?s)```([a-zA-Z0-9_+-]*)\\n(.*?)```");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD_STAR = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern BOLD_UNDER = Pattern.compile("__(.+?)__");
    private static final Pattern ITALIC_STAR = Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)");
    private static final Pattern HEADING_LINE = Pattern.compile("^(#{1,6})\\s+(.+)$");

    private MarkdownAnsi() {}

    public static String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        // Protect fenced code blocks, render the rest, then restore.
        List<String> fences = new ArrayList<>();
        String withPlaceholders = replaceAll(FENCE, markdown, m -> {
            String lang = m.group(1) == null ? "" : m.group(1);
            String body = m.group(2) == null ? "" : m.group(2);
            String block = CODE + (lang.isBlank() ? "" : DIM + lang + RESET + CODE + "\n") + body.stripTrailing() + RESET;
            fences.add(block);
            return "\u0000FENCE" + (fences.size() - 1) + "\u0000";
        });

        StringBuilder out = new StringBuilder();
        for (String line : withPlaceholders.split("\n", -1)) {
            if (line.startsWith("\u0000FENCE")) {
                out.append(line).append('\n');
                continue;
            }
            Matcher hm = HEADING_LINE.matcher(line);
            if (hm.matches()) {
                out.append(HEADING).append(hm.group(2)).append(RESET).append('\n');
                continue;
            }
            String trimmed = line;
            if (trimmed.matches("^\\s*[-*]\\s+.+")) {
                int idx = indexOfBullet(trimmed);
                String prefix = trimmed.substring(0, idx);
                String rest = trimmed.substring(idx + 1).replaceFirst("^\\s+", "");
                out.append(prefix).append(BULLET).append("• ").append(RESET).append(inline(rest)).append('\n');
                continue;
            }
            if (trimmed.matches("^\\s*\\d+\\.\\s+.+")) {
                out.append(inline(trimmed)).append('\n');
                continue;
            }
            out.append(inline(trimmed)).append('\n');
        }

        String rendered = out.toString();
        if (rendered.endsWith("\n") && !markdown.endsWith("\n")) {
            rendered = rendered.substring(0, rendered.length() - 1);
        }
        for (int i = 0; i < fences.size(); i++) {
            rendered = rendered.replace("\u0000FENCE" + i + "\u0000", fences.get(i));
        }
        return rendered;
    }

    private static String inline(String s) {
        String x = s;
        x = replaceAll(BOLD_STAR, x, m -> BOLD + m.group(1) + RESET);
        x = replaceAll(BOLD_UNDER, x, m -> BOLD + m.group(1) + RESET);
        x = replaceAll(ITALIC_STAR, x, m -> ITALIC + m.group(1) + RESET);
        x = replaceAll(INLINE_CODE, x, m -> CODE + m.group(1) + RESET);
        return x;
    }

    private static int indexOfBullet(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '-' || c == '*') {
                return i;
            }
        }
        return 0;
    }

    private interface Replacer {
        String apply(Matcher m);
    }

    private static String replaceAll(Pattern pattern, String input, Replacer replacer) {
        Matcher m = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
