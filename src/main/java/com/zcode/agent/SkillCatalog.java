package com.zcode.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Discovers Agent Skills ({@code SKILL.md} under skill folders).
 *
 * Search roots (later overrides earlier on same name):
 * <ul>
 *   <li>{@code ~/.zcode/skills/&lt;name&gt;/SKILL.md}</li>
 *   <li>{@code &lt;workspace&gt;/.agents/skills/&lt;name&gt;/SKILL.md}</li>
 *   <li>{@code &lt;workspace&gt;/.zcode/skills/&lt;name&gt;/SKILL.md}</li>
 * </ul>
 */
@Component
public class SkillCatalog {

    private static final Pattern FRONT_NAME = Pattern.compile("(?m)^name:\\s*[\"']?([^\"'\\n]+)[\"']?");
    private static final Pattern FRONT_DESC = Pattern.compile("(?m)^description:\\s*[\"']?([^\"'\\n]+)[\"']?");
    private static final int MAX_SKILL_CHARS = 40_000;

    public record SkillInfo(String name, String description, Path path) {}

    public Map<String, SkillInfo> discover(Path workspace) {
        Map<String, SkillInfo> map = new LinkedHashMap<>();
        scanRoot(Path.of(System.getProperty("user.home"), ".zcode", "skills"), map);
        if (workspace != null) {
            scanRoot(workspace.resolve(".agents").resolve("skills"), map);
            scanRoot(workspace.resolve(".zcode").resolve("skills"), map);
        }
        return map;
    }

    public List<SkillInfo> list(Path workspace) {
        return discover(workspace).values().stream()
                .sorted(Comparator.comparing(s -> s.name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    public String catalogText(Path workspace) {
        List<SkillInfo> skills = list(workspace);
        if (skills.isEmpty()) {
            return "(no skills found — put SKILL.md under .zcode/skills/<name>/ or ~/.zcode/skills/<name>/)";
        }
        StringBuilder sb = new StringBuilder();
        for (SkillInfo s : skills) {
            sb.append("- ").append(s.name());
            if (StringUtils.hasText(s.description())) {
                sb.append(": ").append(s.description());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    public String load(Path workspace, String name) throws IOException {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("skill name is required");
        }
        SkillInfo info = discover(workspace).get(name.trim());
        if (info == null) {
            // case-insensitive fallback
            for (var e : discover(workspace).entrySet()) {
                if (e.getKey().equalsIgnoreCase(name.trim())) {
                    info = e.getValue();
                    break;
                }
            }
        }
        if (info == null) {
            throw new IllegalArgumentException("unknown skill: " + name + "\nAvailable:\n" + catalogText(workspace));
        }
        String raw = Files.readString(info.path(), StandardCharsets.UTF_8);
        String body = stripFrontmatter(raw).trim();
        if (body.length() > MAX_SKILL_CHARS) {
            body = body.substring(0, MAX_SKILL_CHARS) + "\n\n...[skill truncated]";
        }
        return "# Skill: " + info.name() + "\n\n" + body;
    }

    private void scanRoot(Path root, Map<String, SkillInfo> map) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                Path skillFile = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(skillFile)) {
                    return;
                }
                try {
                    String raw = Files.readString(skillFile, StandardCharsets.UTF_8);
                    String folderName = dir.getFileName().toString();
                    String name = folderName;
                    String desc = "";
                    if (raw.startsWith("---")) {
                        int end = raw.indexOf("---", 3);
                        if (end > 0) {
                            String fm = raw.substring(3, end);
                            Matcher nm = FRONT_NAME.matcher(fm);
                            if (nm.find()) {
                                name = nm.group(1).trim();
                            }
                            Matcher dm = FRONT_DESC.matcher(fm);
                            if (dm.find()) {
                                desc = dm.group(1).trim();
                            }
                        }
                    }
                    if (!StringUtils.hasText(desc)) {
                        desc = firstMeaningfulLine(stripFrontmatter(raw));
                    }
                    map.put(name, new SkillInfo(name, desc, skillFile));
                } catch (IOException ignored) {
                    // skip
                }
            });
        } catch (IOException ignored) {
            // skip
        }
    }

    private static String stripFrontmatter(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim();
        if (!t.startsWith("---")) {
            return raw;
        }
        int end = t.indexOf("---", 3);
        if (end < 0) {
            return raw;
        }
        return t.substring(end + 3).trim();
    }

    private static String firstMeaningfulLine(String body) {
        if (body == null) {
            return "";
        }
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            return t.length() > 120 ? t.substring(0, 117) + "…" : t;
        }
        return "";
    }
}
