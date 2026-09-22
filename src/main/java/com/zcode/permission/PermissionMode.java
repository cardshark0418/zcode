package com.zcode.permission;

import java.util.Locale;
import java.util.Set;

/**
 * Runtime permission presets for the agent tool surface.
 */
public enum PermissionMode {
    /** Conversation only — no tools offered to the model. */
    CHAT("chat", "仅对话，不使用工具"),

    /** Read / search / web / skill — no file writes or shell. */
    READ_ONLY("read-only", "只读工具（不可写入/编辑/终端）"),

    /** File edits allowed; bash requires interactive approval. */
    DEFAULT("default", "可编辑工作区文件；bash 需确认"),

    /** All tools auto-approved. */
    AUTO_CONFIRM("auto-confirm", "全部工具可用，无需确认");

    private static final Set<String> READ_TOOLS = Set.of(
            "read", "glob", "grep", "webfetch", "websearch", "skill", "ask_user", "todowrite");

    private final String id;
    private final String description;

    PermissionMode(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    /** Claude-style footer: {@code auto-confirm mode on}. */
    public String statusLabel() {
        return id + " mode on";
    }

    public boolean toolsEnabled() {
        return this != CHAT;
    }

    public boolean allows(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        String name = toolName.trim().toLowerCase(Locale.ROOT);
        return switch (this) {
            case CHAT -> false;
            case READ_ONLY -> READ_TOOLS.contains(name);
            case DEFAULT, AUTO_CONFIRM -> true;
        };
    }

    /** Interactive y/n before execute (tool must still be {@link #allows}). */
    public boolean requiresApproval(String toolName) {
        if (toolName == null || !allows(toolName)) {
            return false;
        }
        String name = toolName.trim().toLowerCase(Locale.ROOT);
        return switch (this) {
            case CHAT, READ_ONLY, AUTO_CONFIRM -> false;
            case DEFAULT -> "bash".equals(name) || "delete".equals(name);
        };
    }

    public static PermissionMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO_CONFIRM;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "chat" -> CHAT;
            case "read-only", "readonly", "ro" -> READ_ONLY;
            case "default", "ask", "workspace", "edit", "ws" -> DEFAULT;
            case "auto-confirm", "autoconfirm", "auto", "agent", "full", "danger" -> AUTO_CONFIRM;
            default -> throw new IllegalArgumentException(
                    "unknown mode '" + raw + "' (chat|read-only|default|auto-confirm)");
        };
    }

    public static String helpList() {
        StringBuilder sb = new StringBuilder();
        for (PermissionMode m : values()) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("  ").append(m.id).append("  — ").append(m.description);
        }
        return sb.toString();
    }

    /** 写入系统提示的权限说明。 */
    public String systemHint() {
        return switch (this) {
            case CHAT -> "工具：chat 模式下禁用。仅根据上下文回答，不要调用工具。";
            case READ_ONLY ->
                    "可用工具：read、glob、grep、webfetch、websearch、skill、ask_user、todowrite。"
                            + "禁止写文件、edit、delete 或运行 bash。";
            case DEFAULT ->
                    "工具：全开。可编辑/删除工作区文件。运行 bash 或 delete 前需要用户确认。";
            case AUTO_CONFIRM -> "工具：全开且自动批准（无需确认）。";
        };
    }
}
