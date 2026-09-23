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
 * Convert JSONL {@link ChatMessage}s into OpenAI Chat Completions {@code messages}
 * (including {@code tool_calls} / {@code role=tool}).
 */
@Component
public class OpenAiMessageCodec {

    private final ObjectMapper objectMapper;

    public OpenAiMessageCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ArrayNode toMessagesArray(String systemSummary, List<ChatMessage> history) throws Exception {
        ArrayNode messages = objectMapper.createArrayNode();
        if (StringUtils.hasText(systemSummary)) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", systemSummary.trim());
        }
        if (history == null) {
            return messages;
        }
        int i = 0;
        while (i < history.size()) {
            ChatMessage m = history.get(i);
            if (m == null || m.isSummary()) {
                i++;
                continue;
            }
            if ("tool".equals(m.role())) {
                // Orphan tool results — skip (should follow assistant tool_calls).
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
                    if (StringUtils.hasText(m.content())) {
                        row.put("content", m.content());
                    } else {
                        row.putNull("content");
                    }
                    ArrayNode toolCalls = row.putArray("tool_calls");
                    for (ToolCall call : m.toolCalls()) {
                        ObjectNode tc = toolCalls.addObject();
                        tc.put("id", call.id() == null ? "" : call.id());
                        tc.put("type", "function");
                        ObjectNode fn = tc.putObject("function");
                        fn.put("name", call.name() == null ? "" : call.name());
                        fn.put("arguments", StringUtils.hasText(call.inputJson()) ? call.inputJson() : "{}");
                    }
                    i++;
                    while (i < history.size() && "tool".equals(history.get(i).role())) {
                        ChatMessage tr = history.get(i);
                        ObjectNode toolRow = messages.addObject();
                        toolRow.put("role", "tool");
                        toolRow.put("tool_call_id", tr.toolUseId() == null ? "" : tr.toolUseId());
                        toolRow.put("content", tr.content() == null ? "" : tr.content());
                        i++;
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

    public AnthropicMessageCodec.ModelTurn parseResponse(String responseJson) throws Exception {
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return new AnthropicMessageCodec.ModelTurn(null, "", List.of());
        }
        JsonNode choice = choices.get(0);
        String finish = textOrNull(choice.get("finish_reason"));
        JsonNode message = choice.get("message");
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        if (message != null) {
            String content = textOrNull(message.get("content"));
            if (content != null) {
                text.append(content);
            }
            JsonNode toolCalls = message.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    String id = textOrNull(tc.get("id"));
                    JsonNode fn = tc.get("function");
                    String name = fn == null ? null : textOrNull(fn.get("name"));
                    String args = fn == null ? "{}" : textOrNull(fn.get("arguments"));
                    if (!StringUtils.hasText(args)) {
                        args = "{}";
                    }
                    if (StringUtils.hasText(name)) {
                        calls.add(new ToolCall(id, name, args));
                    }
                }
            }
        }
        return new AnthropicMessageCodec.ModelTurn(finish, text.toString(), List.copyOf(calls));
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
