package com.zcode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zcode.agent.SkillCatalog;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SkillTool implements Tool {

    private final SkillCatalog skillCatalog;

    public SkillTool(SkillCatalog skillCatalog) {
        this.skillCatalog = skillCatalog;
    }

    @Override
    public String name() {
        return "skill";
    }

    @Override
    public String description() {
        return "Load a named Agent Skill (SKILL.md) into context, or list available skills when name is omitted. "
                + "Use when a specialized workflow/skill matches the user task. "
                + "Skills live under .zcode/skills/<name>/SKILL.md (or .agents/skills/<name>/SKILL.md).";
    }

    @Override
    public JsonNode inputSchema(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("name")
                .put("type", "string")
                .put("description", "Skill name to load. Omit or use \"list\" to list skills.");
        return root;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext ctx) {
        String name = text(input, "name");
        try {
            if (!StringUtils.hasText(name) || "list".equalsIgnoreCase(name.trim())) {
                return ToolResult.ok(skillCatalog.catalogText(ctx.workspace()));
            }
            return ToolResult.ok(skillCatalog.load(ctx.workspace(), name.trim()));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static String text(JsonNode input, String field) {
        JsonNode n = input == null ? null : input.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}
