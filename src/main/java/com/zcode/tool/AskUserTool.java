package com.zcode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AskUserTool implements Tool {

    @Override
    public String name() {
        return "ask_user";
    }

    @Override
    public String description() {
        return "Ask the human a clarifying question when a user-owned choice is required "
                + "(or material ambiguity that tools cannot resolve). "
                + "Optional options[] presents choices; otherwise free-text. "
                + "Do not use for facts you can discover with read/glob/grep/bash.";
    }

    @Override
    public JsonNode inputSchema(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("question").put("type", "string");
        ObjectNode options = props.putObject("options");
        options.put("type", "array");
        options.putObject("items").put("type", "string");
        root.putArray("required").add("question");
        return root;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext ctx) {
        if (ctx.askUser() == null) {
            return ToolResult.error("ask_user unavailable in this UI");
        }
        String question = text(input, "question");
        if (question == null || question.isBlank()) {
            return ToolResult.error("question is required");
        }
        List<String> options = new ArrayList<>();
        JsonNode opts = input.get("options");
        if (opts != null && opts.isArray()) {
            for (JsonNode o : opts) {
                if (o != null && !o.asText().isBlank()) {
                    options.add(o.asText());
                }
            }
        }
        String answer = ctx.askUser().ask(question, options);
        if (answer == null || answer.isBlank()) {
            return ToolResult.error("user provided empty answer");
        }
        // If user typed a number matching an option, expand it.
        if (!options.isEmpty()) {
            try {
                int idx = Integer.parseInt(answer.trim());
                if (idx >= 1 && idx <= options.size()) {
                    answer = options.get(idx - 1);
                }
            } catch (NumberFormatException ignored) {
                // keep free text
            }
        }
        return ToolResult.ok("User answered: " + answer);
    }

    private static String text(JsonNode input, String field) {
        JsonNode n = input == null ? null : input.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}
