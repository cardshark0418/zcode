package com.zcode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zcode.config.AgentProperties;
import com.zcode.config.WorkspaceService;
import com.zcode.permission.PermissionMode;
import com.zcode.permission.PermissionService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final TodoStore todoStore;
    private final PermissionService permissionService;
    private final WorkspaceService workspaceService;

    public ToolRegistry(
            List<Tool> toolList,
            AgentProperties agentProperties,
            ObjectMapper objectMapper,
            TodoStore todoStore,
            PermissionService permissionService,
            WorkspaceService workspaceService) {
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
        this.todoStore = todoStore;
        this.permissionService = permissionService;
        this.workspaceService = workspaceService;
        for (Tool tool : toolList) {
            tools.put(tool.name(), tool);
        }
    }

    public boolean enabled() {
        return agentProperties.toolsEnabled() && permissionService.mode().toolsEnabled();
    }

    public PermissionMode mode() {
        return permissionService.mode();
    }

    public Path workspace() {
        return workspaceService.current();
    }

    public Path workspaceForSession(String sessionId) {
        return workspaceService.forSession(sessionId);
    }

    public ToolContext context(String sessionId, AskUserHandler askUser, ToolApprover approver) {
        Path fallback = workspaceForSession(sessionId);
        return new ToolContext(
                workspaceService,
                fallback,
                agentProperties.safeMaxToolOutputChars(),
                sessionId,
                askUser,
                todoStore,
                approver);
    }

    public ArrayNode anthropicToolsArray() {
        PermissionMode mode = permissionService.mode();
        ArrayNode arr = objectMapper.createArrayNode();
        for (Tool tool : tools.values()) {
            if (!mode.allows(tool.name())) {
                continue;
            }
            ObjectNode node = arr.addObject();
            node.put("name", tool.name());
            node.put("description", tool.description());
            node.set("input_schema", tool.inputSchema(objectMapper));
        }
        return arr;
    }

    /** OpenAI Chat Completions {@code tools} array ({@code type=function}). */
    public ArrayNode openaiToolsArray() {
        PermissionMode mode = permissionService.mode();
        ArrayNode arr = objectMapper.createArrayNode();
        for (Tool tool : tools.values()) {
            if (!mode.allows(tool.name())) {
                continue;
            }
            ObjectNode node = arr.addObject();
            node.put("type", "function");
            ObjectNode fn = node.putObject("function");
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            fn.set("parameters", tool.inputSchema(objectMapper));
        }
        return arr;
    }

    public ToolResult execute(String name, JsonNode input, ToolContext ctx) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return ToolResult.error("unknown tool: " + name);
        }
        PermissionMode mode = permissionService.mode();
        if (!mode.allows(name)) {
            return ToolResult.error("tool '" + name + "' blocked in permission mode '" + mode.id() + "'");
        }
        if (mode.requiresApproval(name)) {
            String summary = summarize(name, input);
            ToolApprover approver = ctx == null ? null : ctx.approver();
            if (approver == null) {
                return ToolResult.error("tool '" + name + "' requires approval but no approver is available");
            }
            if (!approver.approve(name, summary)) {
                return ToolResult.error("user denied tool '" + name + "'");
            }
        }
        try {
            return tool.execute(input == null ? objectMapper.createObjectNode() : input, ctx);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResult.error(msg);
        }
    }

    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    /** Tools allowed under the current permission mode. */
    public List<String> allowedNames() {
        PermissionMode mode = permissionService.mode();
        List<String> out = new ArrayList<>();
        for (String name : tools.keySet()) {
            if (mode.allows(name)) {
                out.add(name);
            }
        }
        return List.copyOf(out);
    }

    private static String summarize(String name, JsonNode input) {
        if (input == null || input.isNull() || input.isEmpty()) {
            return name;
        }
        String raw = input.toString().replace('\n', ' ');
        if (raw.length() > 160) {
            raw = raw.substring(0, 157) + "...";
        }
        return name + " " + raw;
    }
}
