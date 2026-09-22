package com.zcode.cli;

import com.zcode.agent.AgentService;
import com.zcode.agent.InstructionsLoader;
import com.zcode.agent.SkillCatalog;
import com.zcode.checkpoint.CheckpointService;
import com.zcode.config.LlmRuntime;
import com.zcode.memory.ChatMessage;
import com.zcode.memory.CompactionService;
import com.zcode.memory.ContextView;
import com.zcode.memory.SessionStore;
import com.zcode.tool.ToolRegistry;
import com.zcode.permission.PermissionMode;
import com.zcode.permission.PermissionService;
import com.zcode.trace.EventStore;
import com.zcode.trace.TraceContext;
import com.zcode.trace.TraceContextHolder;
import com.zcode.trace.TraceEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.fusesource.jansi.AnsiConsole;
import org.springframework.stereotype.Component;

/**
 * Claude-inspired terminal CUI for zcode.
 */
@Component
public class InteractiveCli {

    private final AgentService agentService;
    private final LlmRuntime llmProperties;
    private final SessionStore sessionStore;
    private final CompactionService compactionService;
    private final ToolRegistry toolRegistry;
    private final InstructionsLoader instructionsLoader;
    private final SkillCatalog skillCatalog;
    private final EventStore eventStore;
    private final PermissionService permissionService;
    private final CheckpointService checkpointService;

    private String sessionId;
    private BufferedReader activeReader;
    private CliLineEditor lineEditor;
    private final AtomicReference<Cui.Spinner> activeSpinner = new AtomicReference<>();

    public InteractiveCli(
            AgentService agentService,
            LlmRuntime llmProperties,
            SessionStore sessionStore,
            CompactionService compactionService,
            ToolRegistry toolRegistry,
            InstructionsLoader instructionsLoader,
            SkillCatalog skillCatalog,
            EventStore eventStore,
            PermissionService permissionService,
            CheckpointService checkpointService) {
        this.agentService = agentService;
        this.llmProperties = llmProperties;
        this.sessionStore = sessionStore;
        this.compactionService = compactionService;
        this.toolRegistry = toolRegistry;
        this.instructionsLoader = instructionsLoader;
        this.skillCatalog = skillCatalog;
        this.eventStore = eventStore;
        this.permissionService = permissionService;
        this.checkpointService = checkpointService;
    }

    public void start() {
        Charset charset = StandardCharsets.UTF_8;
        AnsiConsole.systemInstall();
        System.setOut(new PrintStream(AnsiConsole.out(), true, charset));
        System.setErr(new PrintStream(AnsiConsole.err(), true, charset));

        try {
            sessionId = sessionStore.resumeOrCreate();
        } catch (IOException ex) {
            Cui.error("failed to open session store: " + ex.getMessage());
            return;
        }

        emitSessionStart();

        Cui.banner(
                llmProperties.model(),
                sessionId,
                toolRegistry.workspace().toString(),
                toolRegistry.enabled() ? toolRegistry.allowedNames() : List.of());
        Cui.status("mode          " + permissionService.mode().id()
                + "  ·  shift+tab to cycle  ·  /mode");
        var instr = instructionsLoader.describeSources(toolRegistry.workspace());
        if (!instr.isEmpty()) {
            Cui.status("instructions  " + String.join(" · ", instr.stream().map(p -> {
                String s = p.replace('\\', '/');
                int i = s.lastIndexOf('/');
                return i >= 0 ? s.substring(i + 1) : s;
            }).toList()));
        }
        int skillCount = skillCatalog.list(toolRegistry.workspace()).size();
        if (skillCount > 0) {
            Cui.status("skills        " + skillCount + " available (/skills)");
        }
        if (eventStore.enabled()) {
            Cui.status("trace         on (/trace)");
        }
        System.out.println();

        try {
            loopWithReader(charset);
        } catch (IOException ex) {
            Cui.error("failed to read stdin: " + ex.getMessage());
        } finally {
            AnsiConsole.systemUninstall();
        }
    }

    private void emitSessionStart() {
        eventStore.emit(
                "session.start",
                TraceContext.of(sessionId, null),
                eventStore.mapOf(
                        "model", llmProperties.model(),
                        "workspace", toolRegistry.workspace().toString(),
                        "mode", permissionService.mode().id(),
                        "tools", toolRegistry.enabled() ? toolRegistry.allowedNames() : List.of()));
    }

    private void loopWithReader(Charset charset) throws IOException {
        CliLineEditor editor = null;
        try {
            editor = CliLineEditor.open(charset, permissionService, mode -> eventStore.emit(
                    "permission.mode",
                    TraceContext.of(sessionId, null),
                    eventStore.mapOf("mode", mode.id(), "via", "shift-tab")));
        } catch (Exception ignored) {
            editor = null;
        }

        if (editor != null) {
            this.lineEditor = editor;
            try {
                while (true) {
                    String line = lineEditor.readUserLine();
                    if (line == null) {
                        break;
                    }
                    if (!handleLine(line)) {
                        break;
                    }
                }
            } finally {
                try {
                    lineEditor.close();
                } catch (Exception ignored) {
                }
                this.lineEditor = null;
            }
            return;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, charset))) {
            this.activeReader = reader;
            while (true) {
                Cui.userPrompt();
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                if (!handleLine(line)) {
                    break;
                }
            }
        } finally {
            this.activeReader = null;
        }
    }

    private boolean handleLine(String line) {
        String input = line.trim();
        if (input.isEmpty()) {
            return true;
        }
        if (isExit(input)) {
            System.out.println();
            Cui.status("bye");
            System.out.println();
            return false;
        }
        if (isHelp(input)) {
            Cui.help();
            return true;
        }
        if ("/new".equalsIgnoreCase(input)) {
            return cmdNew();
        }
        if ("/clear".equalsIgnoreCase(input)) {
            return cmdClear();
        }
        if ("/session".equalsIgnoreCase(input)) {
            printSession();
            return true;
        }
        if ("/compact".equalsIgnoreCase(input)) {
            return cmdCompact();
        }
        if ("/tools".equalsIgnoreCase(input)) {
            printTools();
            return true;
        }
        if ("/skills".equalsIgnoreCase(input)) {
            printSkills();
            return true;
        }
        if (input.toLowerCase().startsWith("/mode")) {
            return cmdMode(input);
        }
        if (input.toLowerCase().startsWith("/trace")) {
            return cmdTrace(input);
        }

        String turnId = newTurnId();
        TraceContext ctx = TraceContext.of(sessionId, turnId);
        TraceContextHolder.set(ctx);
        stopSpinner();
        try {
            checkpointService.clearUndo(sessionId);

            eventStore.emit(
                    "turn.start",
                    ctx,
                    eventStore.mapOf("userPreview", eventStore.preview(input)));

            sessionStore.maybeAutoTitleFromUserMessage(sessionId, input);

            if (compactionService.maybeCompact(sessionId, input, turnId)) {
                Cui.status("memory compacted");
            }

            ContextView view = sessionStore.buildContextView(sessionId);
            String system = agentService.buildSystemPrompt(view.systemSummary());
            eventStore.emit(
                    "request.header",
                    ctx,
                    eventStore.mapOf(
                            "system", system,
                            "messageCount", view.messages().size(),
                            "hasSummary", view.hasSummary()));
            ChatMessage userMsg = ChatMessage.user(input);
            sessionStore.append(sessionId, userMsg);

            activeSpinner.set(Cui.spinner("thinking"));

            final boolean[] streamStarted = {false};
            AgentService.AgentOutcome outcome = agentService.run(
                    view.systemSummary(),
                    view.messages(),
                    userMsg,
                    sessionId,
                    turnId,
                    this::askUser,
                    this::approveTool,
                    event -> {
                        if (streamStarted[0]) {
                            Cui.assistantStreamEnd();
                            streamStarted[0] = false;
                        }
                        stopSpinner();
                        Cui.toolEvent(event);
                        if (event != null && event.startsWith("tool · ask_user")) {
                            return;
                        }
                        activeSpinner.set(Cui.spinner("thinking"));
                    },
                    token -> {
                        stopSpinner();
                        if (!streamStarted[0]) {
                            System.out.println();
                            Cui.assistantStreamBegin();
                            streamStarted[0] = true;
                        }
                        Cui.assistantStreamToken(token);
                    });

            if (streamStarted[0]) {
                Cui.assistantStreamEnd();
            }
            stopSpinner();

            if (outcome.finalText() == null || outcome.finalText().isBlank()) {
                Cui.error("empty model response");
                eventStore.emit(
                        "turn.end",
                        TraceContext.of(sessionId, turnId),
                        eventStore.mapOf("ok", false, "assistantPreview", ""));
            } else {
                for (ChatMessage m : outcome.newMessages()) {
                    if (m != null && userMsg.id() != null && userMsg.id().equals(m.id())) {
                        continue;
                    }
                    sessionStore.append(sessionId, m);
                }
                eventStore.emit(
                        "turn.end",
                        TraceContext.of(sessionId, turnId),
                        eventStore.mapOf(
                                "ok", true,
                                "assistantPreview", eventStore.preview(outcome.finalText())));
            }
            System.out.println();
        } catch (Exception ex) {
            stopSpinner();
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            eventStore.emit(
                    "error",
                    TraceContext.of(sessionId, turnId),
                    eventStore.mapOf("where", "InteractiveCli.handleLine", "message", msg));
            eventStore.emit(
                    "turn.end",
                    TraceContext.of(sessionId, turnId),
                    eventStore.mapOf("ok", false, "assistantPreview", eventStore.preview(msg)));
            if (msg.contains("502") || msg.toLowerCase().contains("bad gateway")) {
                Cui.error("upstream 502 — try again");
            } else {
                Cui.error(msg);
            }
            System.out.println();
        } finally {
            TraceContextHolder.clear();
        }
        return true;
    }

    private boolean cmdTrace(String input) {
        try {
            String[] parts = input.trim().split("\\s+");
            if (parts.length >= 2) {
                int n = Integer.parseInt(parts[1]);
                List<TraceEvent> events = eventStore.load(sessionId, Math.max(1, n));
                Cui.traceEvents(events);
                Path file = sessionStore.isEventLog(sessionId)
                        ? sessionStore.sessionFile(sessionId)
                        : eventStore.eventsFile(sessionId);
                Cui.status("file  " + file);
                System.out.println();
            } else {
                List<TraceEvent> events = eventStore.loadLatestTurn(sessionId);
                Cui.traceEvents(events);
                Path file = sessionStore.isEventLog(sessionId)
                        ? sessionStore.sessionFile(sessionId)
                        : eventStore.eventsFile(sessionId);
                Cui.status("file  " + file);
                System.out.println();
            }
        } catch (NumberFormatException e) {
            Cui.error("usage: /trace  or  /trace N");
            System.out.println();
        } catch (Exception e) {
            Cui.error(e.getMessage());
            System.out.println();
        }
        return true;
    }

    private boolean cmdMode(String input) {
        String[] parts = input.trim().split("\\s+");
        if (parts.length == 1) {
            PermissionMode m = permissionService.mode();
            System.out.println();
            Cui.status("mode  " + m.id() + "  — " + m.description());
            System.out.println(CuiStyle.MUTED + PermissionMode.helpList() + CuiStyle.RESET);
            System.out.println();
            Cui.status("usage  /mode chat|read-only|default|auto-confirm");
            System.out.println();
            return true;
        }
        try {
            PermissionMode m = permissionService.setMode(parts[1]);
            eventStore.emit(
                    "permission.mode",
                    TraceContext.of(sessionId, null),
                    eventStore.mapOf("mode", m.id()));
            Cui.ok("mode → " + m.id() + "  (" + m.description() + ")");
            if (lineEditor != null) {
                lineEditor.refreshStatus();
            }
            System.out.println();
        } catch (IllegalArgumentException ex) {
            Cui.error(ex.getMessage());
            System.out.println();
        }
        return true;
    }

    private boolean approveTool(String toolName, String summary) {
        stopSpinner();
        if (lineEditor != null) {
            lineEditor.hideStatus();
        }
        Cui.askBox("allow tool `" + toolName + "`?\n  " + summary, List.of("yes", "no"));
        Cui.status("y / n");
        try {
            String line;
            if (lineEditor != null) {
                line = lineEditor.readAskLine(CuiStyle.WARN + "?" + CuiStyle.RESET + " ");
            } else {
                Cui.askPrompt();
                line = readInteractiveLine();
            }
            if (line == null) {
                return false;
            }
            String s = line.trim().toLowerCase();
            return s.equals("y") || s.equals("yes") || s.equals("1");
        } catch (IOException e) {
            return false;
        } finally {
            if (lineEditor != null) {
                lineEditor.refreshStatus();
            }
        }
    }

    private String askUser(String question, List<String> options) {
        stopSpinner();
        if (lineEditor != null) {
            lineEditor.hideStatus();
        }
        Cui.askBox(question, options);
        Cui.status("your turn — type an answer");
        try {
            String line;
            if (lineEditor != null) {
                line = lineEditor.readAskLine(CuiStyle.WARN + "?" + CuiStyle.RESET + " ");
            } else {
                Cui.askPrompt();
                line = readInteractiveLine();
            }
            return line == null ? "" : line.trim();
        } catch (IOException e) {
            return "";
        } finally {
            if (lineEditor != null) {
                lineEditor.refreshStatus();
            }
            activeSpinner.set(Cui.spinner("thinking"));
        }
    }

    private String readInteractiveLine() throws IOException {
        if (lineEditor != null) {
            return lineEditor.readAskLine(CuiStyle.WARN + "?" + CuiStyle.RESET + " ");
        }
        if (activeReader == null) {
            return "";
        }
        return activeReader.readLine();
    }

    private void stopSpinner() {
        Cui.Spinner sp = activeSpinner.getAndSet(null);
        if (sp != null) {
            sp.close();
        }
    }

    private boolean cmdNew() {
        try {
            sessionId = sessionStore.createSession();
            emitSessionStart();
            Cui.ok("new session " + CuiStyle.muted(sessionId));
            System.out.println();
        } catch (IOException ex) {
            Cui.error(ex.getMessage());
        }
        return true;
    }

    private boolean cmdClear() {
        try {
            sessionStore.clear(sessionId);
            Cui.ok("cleared " + CuiStyle.muted(sessionId));
            System.out.println();
        } catch (IOException ex) {
            Cui.error(ex.getMessage());
        }
        return true;
    }

    private boolean cmdCompact() {
        try {
            boolean did = compactionService.forceCompact(sessionId);
            if (did) {
                Cui.ok("memory compacted");
            } else {
                Cui.status("nothing to compact");
            }
            System.out.println();
        } catch (Exception ex) {
            Cui.error(ex.getMessage());
            System.out.println();
        }
        return true;
    }

    private void printSkills() {
        System.out.println();
        var skills = skillCatalog.list(toolRegistry.workspace());
        if (skills.isEmpty()) {
            Cui.status("no skills — add .zcode/skills/<name>/SKILL.md");
        } else {
            for (var s : skills) {
                System.out.println(CuiStyle.TOOL + "  ● " + CuiStyle.RESET + s.name()
                        + (s.description() == null || s.description().isBlank()
                        ? ""
                        : CuiStyle.MUTED + "  " + s.description() + CuiStyle.RESET));
            }
        }
        System.out.println();
    }

    private void printTools() {
        System.out.println();
        Cui.status("workspace  " + toolRegistry.workspace());
        Cui.status("mode       " + permissionService.mode().id()
                + "  — " + permissionService.mode().description());
        if (toolRegistry.enabled()) {
            for (String name : toolRegistry.allowedNames()) {
                System.out.println(CuiStyle.TOOL + "  ● " + CuiStyle.RESET + name);
            }
        } else if (!permissionService.mode().toolsEnabled()) {
            Cui.status("tools disabled in chat mode (/mode auto-confirm to enable)");
        } else {
            Cui.status("tools disabled");
        }
        System.out.println();
    }

    private void printSession() {
        try {
            var snap = sessionStore.snapshot(sessionId);
            System.out.println();
            Cui.status("session    " + sessionId);
            Cui.status("mode       " + permissionService.mode().id());
            Cui.status("file       " + sessionStore.sessionFile(sessionId));
            Cui.status("events     " + eventStore.eventsFile(sessionId));
            Cui.status("summary    " + (snap.hasSummary() ? "yes (" + snap.summary().length() + " chars)" : "no"));
            Cui.status("raw tail   " + snap.dialogue().size() + " msgs");
            Cui.status("workspace  " + toolRegistry.workspace());
            System.out.println();
        } catch (IOException ex) {
            Cui.error(ex.getMessage());
        }
    }

    private static String newTurnId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static boolean isExit(String input) {
        String s = input.toLowerCase();
        return "/exit".equals(s) || "/quit".equals(s) || "exit".equals(s) || "quit".equals(s);
    }

    private static boolean isHelp(String input) {
        return "/help".equalsIgnoreCase(input) || "help".equalsIgnoreCase(input);
    }
}
