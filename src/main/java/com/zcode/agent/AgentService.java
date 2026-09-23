package com.zcode.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.zcode.chat.AnthropicMessageCodec;
import com.zcode.chat.ChatService;
import com.zcode.config.AgentProperties;
import com.zcode.config.LlmRuntime;
import com.zcode.memory.ChatMessage;
import com.zcode.memory.ToolCall;
import com.zcode.tool.AskUserHandler;
import com.zcode.tool.ToolApprover;
import com.zcode.tool.ToolContext;
import com.zcode.tool.ToolRegistry;
import com.zcode.tool.ToolResult;
import com.zcode.trace.EventStore;
import com.zcode.trace.TraceContext;
import com.zcode.trace.TraceContextHolder;
import com.zcode.permission.PermissionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentService {

    private final ChatService chatService;
    private final ToolRegistry toolRegistry;
    private final AgentProperties agentProperties;
    private final LlmRuntime llmProperties;
    private final ObjectMapper objectMapper;
    private final InstructionsLoader instructionsLoader;
    private final SkillCatalog skillCatalog;
    private final EventStore eventStore;
    private final PermissionService permissionService;

    public AgentService(
            ChatService chatService,
            ToolRegistry toolRegistry,
            AgentProperties agentProperties,
            LlmRuntime llmProperties,
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
        return run(
                systemSummary,
                prior,
                userMsg,
                sessionId,
                turnId,
                askUser,
                approver,
                onEvent,
                onPartialText,
                null);
    }

    public AgentOutcome run(
            String systemSummary,
            List<ChatMessage> prior,
            ChatMessage userMsg,
            String sessionId,
            String turnId,
            AskUserHandler askUser,
            ToolApprover approver,
            Consumer<String> onEvent,
            Consumer<String> onPartialText,
            BiConsumer<String, Map<String, Object>> onStructured) {

        TraceContext trace = TraceContext.of(sessionId, turnId);
        if (TraceContextHolder.get() == null) {
            TraceContextHolder.set(trace);
        }
        List<ChatMessage> working = new ArrayList<>(prior);
        working.add(userMsg);
        List<ChatMessage> persisted = new ArrayList<>();
        persisted.add(userMsg);

        String system = buildSystem(systemSummary, sessionId);
        boolean toolsOn = toolRegistry.enabled();
        ArrayNode tools = null;
        if (toolsOn) {
            tools = llmProperties.anthropic()
                    ? toolRegistry.anthropicToolsArray()
                    : toolRegistry.openaiToolsArray();
        }
        int maxIter = agentProperties.safeMaxIterations();
        ToolContext toolCtx = toolRegistry.context(sessionId, askUser, approver);

        String lastText = "";
        for (int iter = 0; iter < maxIter; iter++) {
            AnthropicMessageCodec.ModelTurn turn = chatService.completeAgentTurn(
                    system,
                    working,
                    tools,
                    trace,
                    onPartialText,
                    retryMsg -> {
                        if (onEvent != null) {
                            onEvent.accept(retryMsg);
                        }
                        if (onStructured != null) {
                            onStructured.accept("status", Map.of("message", retryMsg));
                        }
                    });

            if (turn.wantsTools()) {
                ChatMessage assistantToolMsg = ChatMessage.assistantWithTools(turn.text(), turn.toolCalls());
                working.add(assistantToolMsg);
                persisted.add(assistantToolMsg);
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
                    Map<String, Object> startPayload = toolStartPayload(call);
                    eventStore.emit("tool.start", trace, startPayload);
                    if (onStructured != null) {
                        onStructured.accept("tool.start", startPayload);
                    }
                    long toolStarted = System.currentTimeMillis();
                    ToolResult result = toolRegistry.execute(call.name(), parse(call.inputJson()), toolCtx);
                    Map<String, Object> endStored = new LinkedHashMap<>(startPayload);
                    endStored.put("ok", result.ok());
                    endStored.put("latencyMs", System.currentTimeMillis() - toolStarted);
                    endStored.put("outputPreview", eventStore.preview(result.forModel()));
                    endStored.put("output", truncate(result.forModel(), 50_000));
                    eventStore.emit("tool.end", trace, endStored);
                    if (onStructured != null) {
                        Map<String, Object> endPayload = new LinkedHashMap<>(endStored);
                        onStructured.accept("tool.end", endPayload);
                    }
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
            ChatMessage finalAssistant = ChatMessage.assistant(lastText);
            working.add(finalAssistant);
            persisted.add(finalAssistant);
            return new AgentOutcome(lastText, List.copyOf(persisted));
        }

        throw new IllegalStateException("agent exceeded maxIterations=" + maxIter);
    }

    /** Public for request.header logging / trajectory projection. */
    public String buildSystemPrompt(String summary) {
        return buildSystem(summary, TraceContextHolder.sessionId());
    }

    private String buildSystem(String summary, String sessionId) {
        Path workspace =
                StringUtils.hasText(sessionId)
                        ? toolRegistry.workspaceForSession(sessionId)
                        : toolRegistry.workspace();
        var mode = permissionService.mode();
        String model = StringUtils.hasText(llmProperties.model()) ? llmProperties.model() : "unknown";

        StringBuilder sb = new StringBuilder();
        sb.append("你是 zcode，一个编程代理（coding agent）。\n\n");

        sb.append("## 运行环境\n");
        sb.append("- 工作区（工具根目录）：").append(workspace).append('\n');
        sb.append("- 模型：").append(model).append('\n');
        sb.append("- 权限模式：").append(mode.id()).append(" — ").append(mode.description()).append('\n');
        sb.append("- 若用户说「网页」「这个页面」且未另指目标，默认指 zcode Web UI：http://localhost:8080\n");
        sb.append("- 不要臆造工作区以外的路径；相对路径一律相对工作区解析。\n");
        sb.append("- 不要假设进程当前目录等于工作区；工具路径以工作区为准。\n");
        sb.append("- 允许并鼓励直接改当前工作区内的源码（含 zcode 自己）；像 Cursor 一样就地改文件。\n");
        if (isZcodeRepo(workspace)) {
            sb.append("- 当前工作区就是 zcode 仓库：改 `src/main/resources/static/**` 后刷新浏览器即可（serve 热读磁盘）；")
                    .append("改 Java 后执行 `powershell -File bin/install.ps1`，再让用户重启 `zcode serve`。\n");
            sb.append("- 改 zcode 自身时优先 `skill` 加载 `zcode-dev`。\n");
        }
        sb.append('\n');

        sb.append("## 工具\n");
        sb.append(mode.systemHint()).append('\n');
        if (mode.toolsEnabled()) {
            sb.append("- 探索优先：改文件或跑命令前，先用 glob / grep / read。\n");
            sb.append("- edit：old_string 必须能唯一匹配（否则设 replace_all=true）。\n");
            sb.append("- write：新建或整文件覆盖；对已有文件的小改动优先用 edit。\n");
            sb.append("- delete：删除工作区内的文件（不要用 bash rm，以便检查点可回滚）。\n");
            sb.append("- 改写已有文件前先 read（除非本回合刚用 write 创建）。\n");
            sb.append("- bash：用于构建、测试、git 等；路径加引号；Windows 上优先用 PowerShell 友好命令。\n");
            sb.append("- 核对中文文案时优先用 grep/read 查文件，不要用 PowerShell -match 直接比对中文（易乱码误判）。\n");
            if (!isZcodeRepo(workspace)) {
                sb.append("- 若工作区不是本仓库：改完对方项目的 Web 静态资源按其项目方式构建/刷新；")
                        .append("不要假设需要 zcode 的 install。\n");
            }
            sb.append("- websearch / webfetch：查实时信息（文档、版本、天气、新闻等）。")
                    .append("禁止声称自己没有网络或查不了。\n");
            sb.append("- todowrite：多步骤任务。ask_user：仅用于需要用户拍板的选择。")
                    .append("skill：匹配时加载已列出的技能。\n");
            sb.append("- 工具/命令非零退出视为失败（用户主动中断除外）；简要报告错误并自行恢复或询问用户。\n");
        }
        sb.append('\n');

        sb.append("## 风格\n");
        sb.append("- 简洁直接。默认使用用户的语言，除非对方另有要求。\n");
        sb.append("- 少空话，多给可执行结果。\n");
        sb.append("- 创建或修改文件时，用反引号标出主要路径，便于界面高亮。\n");

        String instructions = instructionsLoader.loadCombined(workspace);
        if (StringUtils.hasText(instructions)) {
            sb.append("\n## 项目 / 用户说明\n");
            sb.append("除非用户明确覆盖，否则遵守以下说明。\n\n");
            sb.append(instructions.trim()).append('\n');
        }

        var skills = skillCatalog.list(workspace);
        if (!skills.isEmpty()) {
            sb.append("\n## 可用技能\n");
            sb.append("调用工具 `skill`，参数 {\"name\":\"...\"} 加载完整说明。\n");
            for (var s : skills) {
                sb.append("- ").append(s.name());
                if (StringUtils.hasText(s.description())) {
                    sb.append("：").append(s.description());
                }
                sb.append('\n');
            }
        }

        if (StringUtils.hasText(summary)) {
            sb.append("\n## 会话记忆摘要\n");
            sb.append(summary.trim()).append('\n');
        }
        return sb.toString();
    }

    private Map<String, Object> toolStartPayload(ToolCall call) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", call.name());
        m.put("inputPreview", eventStore.preview(call.inputJson()));
        var in = parse(call.inputJson());
        String name = call.name() == null ? "" : call.name().toLowerCase();
        switch (name) {
            case "edit" -> {
                putField(m, "path", in, "path");
                putField(m, "oldString", in, "old_string", 6000);
                putField(m, "newString", in, "new_string", 6000);
            }
            case "write" -> {
                putField(m, "path", in, "path");
                putField(m, "content", in, "content", 6000);
            }
            case "delete" -> putField(m, "path", in, "path");
            case "bash" -> putField(m, "command", in, "command", 4000);
            case "read", "glob" -> putField(m, "path", in, "path");
            case "grep" -> {
                putField(m, "pattern", in, "pattern");
                putField(m, "path", in, "path");
            }
            case "webfetch" -> putField(m, "url", in, "url");
            case "websearch" -> putField(m, "query", in, "query");
            default -> {
            }
        }
        return m;
    }

    private static void putField(Map<String, Object> m, String key, com.fasterxml.jackson.databind.JsonNode in, String field) {
        putField(m, key, in, field, Integer.MAX_VALUE);
    }

    private static void putField(
            Map<String, Object> m, String key, com.fasterxml.jackson.databind.JsonNode in, String field, int max) {
        if (in == null || !in.has(field) || in.get(field).isNull()) {
            return;
        }
        String v = in.get(field).asText();
        if (v == null) {
            return;
        }
        m.put(key, truncate(v, max));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
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

    /** True when the session workspace looks like this zcode repository. */
    private static boolean isZcodeRepo(Path workspace) {
        if (workspace == null) {
            return false;
        }
        Path root = workspace.toAbsolutePath().normalize();
        return Files.isRegularFile(root.resolve("pom.xml"))
                && Files.isDirectory(root.resolve("src").resolve("main").resolve("java").resolve("com").resolve("zcode"));
    }
}
