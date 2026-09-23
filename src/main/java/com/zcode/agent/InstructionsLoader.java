package com.zcode.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Project instructions auto-injected into the system prompt.
 *
 * <p>First existing under workspace: {@code ZCODE.md}, {@code CLAUDE.md},
 * {@code AGENTS.md}, {@code .zcode/instructions.md}.
 */
@Component
public class InstructionsLoader {

    private static final String[] PROJECT_NAMES = {
            "ZCODE.md",
            "CLAUDE.md",
            "AGENTS.md",
            ".zcode/instructions.md"
    };

    private static final int DEFAULT_MAX_CHARS = 48_000;

    public String loadCombined(Path workspace) {
        return loadCombined(workspace, DEFAULT_MAX_CHARS);
    }

    public String loadCombined(Path workspace, int maxChars) {
        List<String> parts = new ArrayList<>();
        if (workspace != null) {
            for (String name : PROJECT_NAMES) {
                Path p = workspace.resolve(name).normalize();
                if (Files.isRegularFile(p)) {
                    readIfPresent(p, "project " + name, parts);
                    break;
                }
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        String joined = String.join("\n\n", parts);
        if (joined.length() <= maxChars) {
            return joined;
        }
        return joined.substring(0, maxChars) + "\n\n...[instructions truncated]";
    }

    public List<String> describeSources(Path workspace) {
        List<String> found = new ArrayList<>();
        if (workspace != null) {
            for (String name : PROJECT_NAMES) {
                Path p = workspace.resolve(name);
                if (Files.isRegularFile(p)) {
                    found.add(p.toString());
                    break;
                }
            }
        }
        return found;
    }

    private static void readIfPresent(Path file, String label, List<String> parts) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (!StringUtils.hasText(text)) {
                return;
            }
            parts.add("### " + label + "\n" + text);
        } catch (IOException ignored) {
            // skip unreadable
        }
    }
}
