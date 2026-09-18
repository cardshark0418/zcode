package com.zcode.cli;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Claude-inspired CUI helpers: banner, prompts, tool lines, spinner.
 */
public final class Cui {

    private static final String[] SPIN = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static boolean assistantStreamLineStart = true;

    private Cui() {}

    public static void banner(String model, String sessionId, String workspace, List<String> tools) {
        String shortSession = sessionId == null ? "-" : (sessionId.length() <= 8 ? sessionId : sessionId.substring(0, 8));
        String shortWs = shortenPath(workspace, 52);
        println();
        println(CuiStyle.RULE + "╭──────────────────────────────────────────────────────────────╮" + CuiStyle.RESET);
        println(CuiStyle.RULE + "│" + CuiStyle.RESET + "  "
                + CuiStyle.ACCENT_BOLD + "zcode" + CuiStyle.RESET
                + CuiStyle.MUTED + "  coding agent" + CuiStyle.RESET
                + padVisible(44, "zcode  coding agent")
                + CuiStyle.RULE + "│" + CuiStyle.RESET);
        println(CuiStyle.RULE + "╰──────────────────────────────────────────────────────────────╯" + CuiStyle.RESET);
        meta("model", model);
        meta("session", shortSession);
        meta("cwd", shortWs);
        if (tools != null && !tools.isEmpty()) {
            meta("tools", String.join(" · ", tools));
        }
        println(CuiStyle.MUTED + "  /help  ·  /tools  ·  /exit" + CuiStyle.RESET);
        println();
    }

    public static void userPrompt() {
        print(CuiStyle.ACCENT_BOLD + "❯" + CuiStyle.RESET + " ");
    }

    public static void askPrompt() {
        print(CuiStyle.WARN + "?" + CuiStyle.RESET + " ");
    }

    public static void assistantStart() {
        println(CuiStyle.ACCENT + "✦" + CuiStyle.RESET);
    }

    /** Begin a live-streamed assistant reply (raw tokens, not re-rendered markdown). */
    public static void assistantStreamBegin() {
        clearLine();
        assistantStart();
        assistantStreamLineStart = true;
    }

    public static void assistantStreamToken(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (assistantStreamLineStart) {
                print("  ");
                assistantStreamLineStart = false;
            }
            print(String.valueOf(c));
            if (c == '\n') {
                assistantStreamLineStart = true;
            }
        }
    }

    public static void assistantStreamEnd() {
        if (!assistantStreamLineStart) {
            println();
            assistantStreamLineStart = true;
        }
    }

    public static void assistantMarkdown(String md) {
        String rendered = MarkdownAnsi.render(md == null ? "" : md);
        for (String line : rendered.split("\n", -1)) {
            println("  " + line);
        }
    }

    public static void toolEvent(String raw) {
        clearLine();
        if (raw == null || raw.isBlank()) {
            return;
        }
        String line = raw.trim();
        if (line.startsWith("skill ·")) {
            println(CuiStyle.OK + "  ✓ " + CuiStyle.RESET + "skill loaded into context");
            return;
        }
        if (line.startsWith("todos ·")) {
            String body = line.substring("todos ·".length()).trim();
            todoBoard(body);
            return;
        }
        if (line.startsWith("tool · ")) {
            String rest = line.substring("tool · ".length()).trim();
            int sp = rest.indexOf(' ');
            String name = sp < 0 ? rest : rest.substring(0, sp);
            String args = sp < 0 ? "" : rest.substring(sp + 1).trim();
            if ("todowrite".equals(name)) {
                println(CuiStyle.TOOL + "  ● " + CuiStyle.BOLD + "todowrite" + CuiStyle.RESET
                        + CuiStyle.MUTED + "  updating tasks" + CuiStyle.RESET);
                return;
            }
            if ("ask_user".equals(name)) {
                println(CuiStyle.WARN + "  ● " + CuiStyle.BOLD + "ask_user" + CuiStyle.RESET
                        + CuiStyle.MUTED + "  waiting for you" + CuiStyle.RESET);
                return;
            }
            if ("skill".equals(name)) {
                println(CuiStyle.TOOL + "  ● " + CuiStyle.BOLD + "skill" + CuiStyle.RESET
                        + (args.isEmpty() ? CuiStyle.MUTED + "  list/load" + CuiStyle.RESET
                        : CuiStyle.MUTED + "  " + truncate(args, 60) + CuiStyle.RESET));
                return;
            }
            println(CuiStyle.TOOL + "  ● " + CuiStyle.BOLD + name + CuiStyle.RESET
                    + (args.isEmpty() ? "" : CuiStyle.MUTED + "  " + truncate(args, 70) + CuiStyle.RESET));
            return;
        }
        // model narration before tools
        println(CuiStyle.MUTED + "  " + truncate(line, 100) + CuiStyle.RESET);
    }

    /** Render todowrite output as a Claude-like checklist. */
    public static void todoBoard(String body) {
        clearLine();
        println(CuiStyle.ACCENT + "  tasks" + CuiStyle.RESET + CuiStyle.MUTED + " ───────────────" + CuiStyle.RESET);
        if (body == null || body.isBlank() || "(empty todo list)".equals(body.trim())) {
            println(CuiStyle.MUTED + "    (empty)" + CuiStyle.RESET);
            return;
        }
        for (String rawLine : body.split("\n")) {
            String t = rawLine.trim();
            if (t.isEmpty()) {
                continue;
            }
            // Expected: - [pending] id: content
            String status = "pending";
            String rest = t;
            if (t.startsWith("- ")) {
                rest = t.substring(2).trim();
            }
            if (rest.startsWith("[") && rest.contains("]")) {
                int end = rest.indexOf(']');
                status = rest.substring(1, end).trim().toLowerCase();
                rest = rest.substring(end + 1).trim();
                if (rest.startsWith(":")) {
                    rest = rest.substring(1).trim();
                }
                // strip leading "id: " if present
                int colon = rest.indexOf(':');
                if (colon > 0 && colon < 8) {
                    rest = rest.substring(colon + 1).trim();
                }
            }
            String mark;
            String color;
            switch (status) {
                case "completed", "complete", "done" -> {
                    mark = "✔";
                    color = CuiStyle.OK;
                }
                case "in_progress", "doing", "active" -> {
                    mark = "►";
                    color = CuiStyle.ACCENT;
                }
                default -> {
                    mark = "○";
                    color = CuiStyle.MUTED;
                }
            }
            println("    " + color + mark + CuiStyle.RESET + " " + rest);
        }
    }

    public static void status(String msg) {
        clearLine();
        println(CuiStyle.MUTED + "  · " + msg + CuiStyle.RESET);
    }

    public static void ok(String msg) {
        clearLine();
        println(CuiStyle.OK + "  ✓ " + CuiStyle.RESET + msg);
    }

    public static void error(String msg) {
        clearLine();
        println(CuiStyle.ERR + "  ✗ " + CuiStyle.RESET + msg);
    }

    public static void askBox(String question, List<String> options) {
        clearLine();
        println();
        println(CuiStyle.WARN + "  ask" + CuiStyle.RESET + CuiStyle.MUTED + " ─────────────────" + CuiStyle.RESET);
        println("  " + question);
        if (options != null && !options.isEmpty()) {
            for (int i = 0; i < options.size(); i++) {
                println(CuiStyle.MUTED + "    " + (i + 1) + ")" + CuiStyle.RESET + " " + options.get(i));
            }
            println(CuiStyle.DIM + "    reply with number or text" + CuiStyle.RESET);
        }
    }

    public static void help() {
        println();
        println(CuiStyle.ACCENT_BOLD + "  commands" + CuiStyle.RESET);
        row("/help", "this help");
        row("/tools", "list tools + workspace");
        row("/skills", "list discovered skills");
        row("/mode", "show permission mode");
        row("/mode X", "chat|read-only|default|auto-confirm");
        row("/session", "session path & summary");
        row("/trace", "latest turn runtime events");
        row("/trace N", "last N events");
        row("/new", "new empty session");
        row("/clear", "clear current session");
        row("/compact", "force memory compaction");
        row("/exit", "quit");
        println();
        println(CuiStyle.ACCENT_BOLD + "  permission modes" + CuiStyle.RESET);
        println(CuiStyle.MUTED + "  shift+tab     cycle modes under the prompt" + CuiStyle.RESET);
        println(CuiStyle.MUTED + "  chat          conversation only" + CuiStyle.RESET);
        println(CuiStyle.MUTED + "  read-only     read/search/web (no write/bash)" + CuiStyle.RESET);
        println(CuiStyle.MUTED + "  default       edit files; bash asks y/n" + CuiStyle.RESET);
        println(CuiStyle.MUTED + "  auto-confirm  full tools, auto-approve" + CuiStyle.RESET);
        println();
        println(CuiStyle.ACCENT_BOLD + "  tools" + CuiStyle.RESET);
        println(CuiStyle.MUTED + "  bash · read · write · edit · glob · grep" + CuiStyle.RESET);
        println(CuiStyle.MUTED + "  webfetch · websearch · todowrite · ask_user · skill" + CuiStyle.RESET);
        println();
        println(CuiStyle.ACCENT_BOLD + "  instructions" + CuiStyle.RESET);
        println(CuiStyle.MUTED + "  ZCODE.md / CLAUDE.md / AGENTS.md (project) · ~/.zcode/ZCODE.md (user)" + CuiStyle.RESET);
        println(CuiStyle.MUTED + "  skills: .zcode/skills/<name>/SKILL.md" + CuiStyle.RESET);
        println();
        println(CuiStyle.ACCENT_BOLD + "  trace" + CuiStyle.RESET);
        println(CuiStyle.MUTED + "  ~/.zcode/sessions/<id>.events.jsonl" + CuiStyle.RESET);
        println();
    }

    public static void traceEvents(List<com.zcode.trace.TraceEvent> events) {
        clearLine();
        println();
        println(CuiStyle.ACCENT_BOLD + "  trace" + CuiStyle.RESET + CuiStyle.MUTED + " ───────────────" + CuiStyle.RESET);
        if (events == null || events.isEmpty()) {
            println(CuiStyle.MUTED + "    (no events)" + CuiStyle.RESET);
            println();
            return;
        }
        for (com.zcode.trace.TraceEvent e : events) {
            String type = e.type() == null ? "?" : e.type();
            String detail = formatPayload(e.payload());
            println("    " + CuiStyle.TOOL + type + CuiStyle.RESET
                    + (detail.isEmpty() ? "" : CuiStyle.MUTED + "  " + detail + CuiStyle.RESET));
        }
        println();
    }

    private static String formatPayload(java.util.Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Object name = payload.get("name");
        Object ok = payload.get("ok");
        Object latency = payload.get("latencyMs");
        Object stop = payload.get("stopReason");
        Object preview = payload.get("textPreview");
        if (preview == null) {
            preview = payload.get("userPreview");
        }
        if (preview == null) {
            preview = payload.get("assistantPreview");
        }
        if (preview == null) {
            preview = payload.get("inputPreview");
        }
        if (preview == null) {
            preview = payload.get("outputPreview");
        }
        if (preview == null) {
            preview = payload.get("message");
        }
        Object reason = payload.get("reason");
        Object tools = payload.get("toolCallNames");
        if (name != null) {
            sb.append(name).append(' ');
        }
        if (ok != null) {
            sb.append(Boolean.TRUE.equals(ok) ? "ok " : "fail ");
        }
        if (stop != null) {
            sb.append(stop).append(' ');
        }
        if (reason != null) {
            sb.append(reason).append(' ');
        }
        if (latency != null) {
            sb.append(latency).append("ms ");
        }
        if (tools != null) {
            sb.append("tools=").append(tools).append(' ');
        }
        if (preview != null) {
            String p = String.valueOf(preview).replace('\n', ' ');
            if (p.length() > 80) {
                p = p.substring(0, 79) + "…";
            }
            sb.append(p);
        }
        return sb.toString().trim();
    }

    public static Spinner spinner(String label) {
        return new Spinner(label);
    }

    public static final class Spinner implements AutoCloseable {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread thread;

        Spinner(String label) {
            thread = new Thread(() -> {
                int i = 0;
                while (running.get()) {
                    String frame = SPIN[i % SPIN.length];
                    print("\r" + CuiStyle.ACCENT + "  " + frame + CuiStyle.RESET
                            + CuiStyle.MUTED + " " + label + "…" + CuiStyle.RESET + "   ");
                    System.out.flush();
                    i++;
                    try {
                        Thread.sleep(80);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "zcode-spinner");
            thread.setDaemon(true);
            thread.start();
        }

        public void pauseClear() {
            // leave a clean line for upcoming output
            clearLine();
        }

        @Override
        public void close() {
            running.set(false);
            try {
                thread.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            clearLine();
        }
    }

    private static void meta(String key, String value) {
        println("  " + CuiStyle.MUTED + key + CuiStyle.RESET + "  " + CuiStyle.TEXT + (value == null ? "-" : value) + CuiStyle.RESET);
    }

    private static void row(String cmd, String desc) {
        println("  " + CuiStyle.ACCENT + cmd + CuiStyle.RESET
                + padVisible(14, cmd)
                + CuiStyle.MUTED + desc + CuiStyle.RESET);
    }

    private static String padVisible(int width, String visible) {
        int pad = Math.max(1, width - visible.length());
        return " ".repeat(pad);
    }

    private static String shortenPath(String path, int max) {
        if (path == null) {
            return "-";
        }
        String p = path.replace('\\', '/');
        if (p.length() <= max) {
            return p;
        }
        return "…" + p.substring(p.length() - (max - 1));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static void clearLine() {
        print("\r" + " ".repeat(96) + "\r");
        System.out.flush();
    }

    private static void print(String s) {
        System.out.print(s);
        System.out.flush();
    }

    private static void println() {
        System.out.println();
    }

    private static void println(String s) {
        System.out.println(s);
    }
}
