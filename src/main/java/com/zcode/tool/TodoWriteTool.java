package com.zcode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TodoWriteTool implements Tool {

    @Override
    public String name() {
        return "todowrite";
    }

    @Override
    public String description() {
        return "Create or update the session todo list for multi-step work. "
                + "Pass todos[{id,content,status}] where status is pending|in_progress|completed. "
                + "merge=true updates by id; merge=false replaces the whole list.";
    }

    @Override
    public JsonNode inputSchema(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ObjectNode todos = props.putObject("todos");
        todos.put("type", "array");
        ObjectNode item = todos.putObject("items");
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");
        ip.putObject("id").put("type", "string");
        ip.putObject("content").put("type", "string");
        ip.putObject("status").put("type", "string").put("description", "pending | in_progress | completed");
        item.putArray("required").add("id").add("content");
        props.putObject("merge").put("type", "boolean").put("description", "Update by id when true (default true)");
        root.putArray("required").add("todos");
        return root;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext ctx) {
        if (ctx.todoStore() == null || ctx.sessionId() == null) {
            return ToolResult.error("todo store unavailable");
        }
        JsonNode todosNode = input.get("todos");
        if (todosNode == null || !todosNode.isArray() || todosNode.isEmpty()) {
            return ToolResult.error("todos array is required");
        }
        List<TodoStore.TodoItem> items = new ArrayList<>();
        for (JsonNode n : todosNode) {
            String id = text(n, "id");
            String content = text(n, "content");
            String status = text(n, "status");
            if (id == null || content == null) {
                continue;
            }
            items.add(new TodoStore.TodoItem(id, content, status == null ? "pending" : status));
        }
        boolean merge = input.path("merge").asBoolean(true);
        List<TodoStore.TodoItem> next = merge
                ? ctx.todoStore().merge(ctx.sessionId(), items)
                : ctx.todoStore().replace(ctx.sessionId(), items);
        return ToolResult.ok(format(next));
    }

    private static String format(List<TodoStore.TodoItem> items) {
        if (items.isEmpty()) {
            return "(empty todo list)";
        }
        StringBuilder sb = new StringBuilder();
        for (TodoStore.TodoItem t : items) {
            sb.append("- [").append(t.status()).append("] ").append(t.id()).append(": ").append(t.content()).append('\n');
        }
        return sb.toString().trim();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
