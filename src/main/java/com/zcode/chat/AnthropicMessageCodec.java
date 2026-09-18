package com.zcode.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zcode.memory.ChatMessage;
import com.zcode.memory.ToolCall;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Convert JSONL {@link ChatMessage}s into Anthropic Messages API {@code messages} array.
 */
@Component
public class AnthropicMessageCodec {

    private final ObjectMapper objectMapper;

    public AnthropicMessageCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ArrayNode toMessagesArray(List<ChatMessage> history) throws Exception {
        ArrayNode messages = objectMapper.createArrayNode();
        int i = 0;
        while (i < history.size()) {
            ChatMessage m = history.get(i);
            if (m == null || m.isSummary()) {
                i++;
                continue;
            }
            if ("tool".equals(m.role())) {
                // Orphan tool results — skip (should follow assistant tool_use).
                i++;
                continue;
            }
            if ("user".equals(m.role())) {
                ObjectNode row = messages.addObject();
                row.put("role", "user");
                row.put("content", m.content() == null ? "" : m.content());
                i++;
                continue;
            }
            if ("assistant".equals(m.role())) {
                ObjectNode row = messages.addObject();
                row.put("role", "assistant");
                if (m.hasToolCalls()) {
                    ArrayNode content = row.putArray("content");
                    if (StringUtils.hasText(m.content())) {
                        ObjectNode text = content.addObject();
                        text.put("type", "text");
                        text.put("text", m.content());
                    }
                    for (ToolCall call : m.toolCalls()) {
                        ObjectNode tu = content.addObject();
                        tu.put("type", "tool_use");
                        tu.put("id", call.id());
                        tu.put("name", call.name());
                        tu.set("input", parseInput(call.inputJson()));
                    }
                    i++;
                    // Collect following tool results into one user message.
                    ArrayNode toolResults = objectMapper.createArrayNode();
                    while (i < history.size() && "tool".equals(history.get(i).role())) {
                        ChatMessage tr = history.get(i);
                        ObjectNode block = toolResults.addObject();
                        block.put("type", "tool_result");
                        block.put("tool_use_id", tr.toolUseId() == null ? "" : tr.toolUseId());
                        block.put("content", tr.content() == null ? "" : tr.content());
                        i++;
                    }
                    if (!toolResults.isEmpty()) {
                        ObjectNode userRow = messages.addObject();
                        userRow.put("role", "user");
                        userRow.set("content", toolResults);
                    }
                } else {
                    row.put("content", m.content() == null ? "" : m.content());
                    i++;
                }
                continue;
            }
            i++;
        }
        return messages;
    }

    private JsonNode parseInput(String inputJson) throws Exception {
        if (!StringUtils.hasText(inputJson)) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(inputJson);
    }

    public record ModelTurn(String stopReason, String text, List<ToolCall> toolCalls) {
        public boolean wantsTools() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    public ModelTurn parseResponse(String responseJson) throws Exception {
        JsonNode root = objectMapper.readTree(responseJson);
        String stopReason = textOrNull(root.get("stop_reason"));
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        JsonNode content = root.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode block : content) {
                String type = textOrNull(block.get("type"));
                if ("text".equals(type)) {
                    String t = textOrNull(block.get("text"));
                    if (t != null) {
                        text.append(t);
                    }
                } else if ("tool_use".equals(type)) {
                    String id = textOrNull(block.get("id"));
                    String name = textOrNull(block.get("name"));
                    JsonNode input = block.get("input");
                    String inputJson = input == null ? "{}" : objectMapper.writeValueAsString(input);
                    calls.add(new ToolCall(id, name, inputJson));
                }
            }
        }
        return new ModelTurn(stopReason, text.toString(), List.copyOf(calls));
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
