package com.zcode.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcode.chat.AnthropicMessageCodec;
import com.zcode.chat.ChatService;
import com.zcode.config.AgentProperties;
import com.zcode.config.LlmProperties;
import com.zcode.memory.ChatMessage;
import com.zcode.memory.ToolCall;
import com.zcode.tool.AskUserHandler;
import com.zcode.tool.ToolApprover;
import com.zcode.tool.ToolContext;
import com.zcode.tool.ToolRegistry;
import com.zcode.tool.ToolResult;
import com.zcode.trace.EventStore;
import com.zcode.trace.TraceContext;
import com.zcode.permission.PermissionService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentService {

    private final ChatService chatService;
    private final ToolRegistry toolRegistry;
    private final AgentProperties agentProperties;
    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final InstructionsLoader instructionsLoader;
    private final SkillCatalog skillCatalog;
    private final EventStore eventStore;
    private final PermissionService permissionService;

    public AgentService(
            ChatService chatService,
            ToolRegistry toolRegistry,
            AgentProperties agentProperties,
            LlmProperties llmProperties,
            ObjectMapper objectMapper,
            InstructionsLoader instructionsLoader,
            SkillCatalog skillCatalog,
            EventStore eventStore,
            PermissionService permissionService) {
        this.chatService = chatService;
        this.toolRegistry = toolRegistry;
        this.agentProperties = agentProperties;
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
        this.instructionsLoader = instructionsLoader;
        this.skillCatalog = skillCatalog;
        this.eventStore = eventStore;
        this.permissionService = permissionService;
    }

    public record AgentOutcome(String finalText, List<ChatMessage> newMessages) {}

    public AgentOutcome run(
            String systemSummary,
            List<ChatMessage> prior,
            ChatMessage userMsg,
            String sessionId,
            String turnId,
            AskUserHandler askUser,
            ToolApprover approver,
            Consumer<String> onEvent,
            Consumer<String> onPartialText) {

        TraceContext trace = TraceContext.of(sessionId, turnId);
        List<ChatMessage> working = new ArrayList<>(prior);
        working.add(userMsg);
        List<ChatMessage> persisted = new ArrayList<>();
        persisted.add(userMsg);

        String system = buildSystem(systemSummary);
        boolean toolsOn = toolRegistry.enabled() && llmProperties.anthropic();
        int maxIter = agentProperties.safeMaxIterations();
        ToolContext toolCtx = toolRegistry.context(sessionId, askUser, approver);

        String lastText = "";
        for (int iter = 0; iter < maxIter; iter++) {
            AnthropicMessageCodec.ModelTurn turn = chatService.completeAgentTurn(
                    system,
                    working,
                    toolsOn ? toolRegistry.anthropicToolsArray() : null,
                    trace,
                    onPartialText);

            if (turn.wantsTools()) {
                ChatMessage assistantToolMsg = ChatMessage.assistantWithTools(turn.text(), turn.toolCalls());
                working.add(assistantToolMsg);
                persisted.add(assistantToolMsg);
                // preamble text already streamed via onPartialText
                for (ToolCall call : turn.toolCalls()) {
                    if (onEvent != null) {
                        if ("todowrite".equals(call.name())) {
                            onEvent.accept("tool · todowrite");
                        } else if ("ask_user".equals(call.name())) {
                            onEvent.accept("tool · ask_user");
                        } else if ("skill".equals(call.name())) {
                            onEvent.accept("tool · skill " + shortInput(call.inputJson()));
                        } else {
                            onEvent.accept("tool · " + call.name() + " " + shortInput(call.inputJson()));
                        }
                    }
                    eventStore.emit(
                            "tool.start",
                            trace,
                            eventStore.mapOf(
                                    "name", call.name(),
                                    "inputPreview", eventStore.preview(call.inputJson())));
                    long toolStarted = System.currentTimeMillis();
                    ToolResult result = toolRegistry.execute(call.name(), parse(call.inputJson()), toolCtx);
                    eventStore.emit(
                            "tool.end",
                            trace,
                            eventStore.mapOf(
                                    "name", call.name(),
                                    "ok", result.ok(),
                                    "latencyMs", System.currentTimeMillis() - toolStarted,
                                    "outputPreview", eventStore.preview(result.forModel())));
                    if (onEvent != null && "todowrite".equals(call.name()) && result.ok()) {
                        onEvent.accept("todos ·\n" + result.forModel());
                    }
                    if (onEvent != null && "skill".equals(call.name()) && result.ok()) {
                        onEvent.accept("skill · loaded");
                    }
                    ChatMessage toolMsg = ChatMessage.toolResult(call.id(), result.forModel());
                    working.add(toolMsg);
                    persisted.add(toolMsg);
                }
                continue;
            }

            lastText = turn.text() == null ? "" : turn.text();
            // final text already streamed via onPartialText
            ChatMessage finalAssistant = ChatMessage.assistant(lastText);
            working.add(finalAssistant);
            persisted.add(finalAssistant);
            return new AgentOutcome(lastText, List.copyOf(persisted));
        }

        throw new IllegalStateException("agent exceeded maxIterations=" + maxIter);
    }

    private String buildSystem(String summary) {
        Path workspace = toolRegistry.workspace();
        StringBuilder sb = new StringBuilder();
        sb.append("You are zcode, a coding agent in workspace: ")
                .append(workspace)
                .append(".\n");
        sb.append("You HAVE live internet tools: websearch and webfetch. ")
                .append("For weather, news, docs, versions, or any realtime/factual question — ")
                .append("CALL websearch (then webfetch if needed). ")
                .append("NEVER say you cannot look up live information or lack weather/search capability.\n");
        sb.append("Other tools: bash, read, write, edit, glob, grep, todowrite, ask_user, skill.\n");
        sb.append("Prefer read/glob/grep before edit/write. Use todowrite for multi-step tasks. ")
                .append("Use ask_user only for user-owned choices. ")
                .append("Use skill to load specialized workflows when a listed skill matches.\n");
        sb.append("Keep answers concise. Respond in the user's language unless asked otherwise.\n");
        sb.append(permissionService.mode().systemHint()).append('\n');

        String instructions = instructionsLoader.loadCombined(workspace);
        if (StringUtils.hasText(instructions)) {
            sb.append("\n## Project / user instructions (CLAUDE.md-style)\n")
                    .append("Follow these unless the user explicitly overrides them.\n\n")
                    .append(instructions)
                    .append('\n');
        }

        var skills = skillCatalog.list(workspace);
        if (!skills.isEmpty()) {
            sb.append("\n## Available skills\n");
            sb.append("Call tool `skill` with {\"name\":\"...\"} to load full instructions. Catalog:\n");
            for (var s : skills) {
                sb.append("- ").append(s.name());
                if (StringUtils.hasText(s.description())) {
                    sb.append(": ").append(s.description());
                }
                sb.append('\n');
            }
        }

        if (StringUtils.hasText(summary)) {
            sb.append("\n## Session memory summary\n").append(summary.trim()).append('\n');
        }
        return sb.toString();
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String json) {
        try {
            if (!StringUtils.hasText(json)) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private static String shortInput(String inputJson) {
        if (inputJson == null) {
            return "";
        }
        String s = inputJson.replace('\n', ' ').trim();
        return s.length() > 120 ? s.substring(0, 117) + "..." : s;
    }
}
