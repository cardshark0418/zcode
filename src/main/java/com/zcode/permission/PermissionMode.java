package com.zcode.permission;

import java.util.Locale;
import java.util.Set;

/**
 * Runtime permission presets for the agent tool surface.
 */
public enum PermissionMode {
    /** Conversation only — no tools offered to the model. */
    CHAT("chat", "conversation only, no tools"),

    /** Read / search / web / skill — no file writes or shell. */
    READ_ONLY("read-only", "read-only tools (no write/edit/bash)"),

    /** File edits allowed; bash requires interactive approval. */
    DEFAULT("default", "edit workspace files; bash needs confirm"),

    /** All tools auto-approved. */
    AUTO_CONFIRM("auto-confirm", "full tools, no prompts");

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
            case DEFAULT -> "bash".equals(name);
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

    /** Hint for system prompt. */
    public String systemHint() {
        return switch (this) {
            case CHAT -> "Permission mode: chat. You have no tools. Answer from context only.";
            case READ_ONLY ->
                    "Permission mode: read-only. You may read/search/web/skill, but must NOT write files or run shell.";
            case DEFAULT ->
                    "Permission mode: default. You may edit files in the workspace. Shell (bash) requires user approval.";
            case AUTO_CONFIRM -> "Permission mode: auto-confirm. Full tools available without prompts.";
        };
    }
}
