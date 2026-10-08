package com.zcode.cli;

import com.zcode.agent.AgentService;
import com.zcode.config.WorkspaceService;
import com.zcode.memory.ChatMessage;
import com.zcode.memory.ContextView;
import com.zcode.memory.SessionStore;
import com.zcode.permission.PermissionMode;
import com.zcode.permission.PermissionService;
import com.zcode.trace.EventStore;
import com.zcode.trace.TraceContext;
import com.zcode.trace.TraceContextHolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * One-shot non-interactive agent turn for eval / scripting.
 *
 * <pre>
 * zcode run --workspace &lt;dir&gt; --prompt "..."
 * zcode run --workspace &lt;dir&gt; --prompt-file prompt.md
 * </pre>
 */
@Component
public class HeadlessRun {

    private final AgentService agentService;
    private final SessionStore sessionStore;
    private final EventStore eventStore;
    private final PermissionService permissionService;
    private final WorkspaceService workspaceService;

    public HeadlessRun(
            AgentService agentService,
            SessionStore sessionStore,
            EventStore eventStore,
            PermissionService permissionService,
            WorkspaceService workspaceService) {
        this.agentService = agentService;
        this.sessionStore = sessionStore;
        this.eventStore = eventStore;
        this.permissionService = permissionService;
        this.workspaceService = workspaceService;
    }

    public int execute(String[] args) {
        try {
            Args parsed = Args.parse(args);
            Path workspace = workspaceService.requireDirectory(parsed.workspace);
            String prompt = resolvePrompt(parsed);
            if (prompt.isBlank()) {
                System.err.println("[zcode run] prompt is empty");
                return 2;
            }

            permissionService.setMode(PermissionMode.AUTO_CONFIRM);

            String sessionId = sessionStore.createSession(workspace.toString());
            String turnId = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            TraceContext ctx = TraceContext.of(sessionId, turnId);
            TraceContextHolder.set(ctx);

            try {
                eventStore.emit(
                        "turn.start",
                        ctx,
                        eventStore.mapOf("userPreview", eventStore.preview(prompt), "mode", "headless"));

                ContextView view = sessionStore.buildContextView(sessionId);
                ChatMessage userMsg = ChatMessage.user(prompt);
                sessionStore.append(sessionId, userMsg);

                AgentService.AgentOutcome outcome = agentService.run(
                        view.messages(),
                        userMsg,
                        sessionId,
                        turnId,
                        (question, options) ->
                                "[eval] ask_user not available in headless mode: " + question,
                        (tool, summary) -> true,
                        event -> {
                            if (event != null && !event.isBlank()) {
                                System.err.println(event);
                            }
                        },
                        token -> {
                            if (token != null) {
                                System.out.print(token);
                            }
                        });

                if (outcome.finalText() == null || outcome.finalText().isBlank()) {
                    eventStore.emit("turn.end", ctx, eventStore.mapOf("ok", false));
                    System.err.println("\n[zcode run] empty model response");
                    return 1;
                }

                for (ChatMessage m : outcome.newMessages()) {
                    if (m != null && userMsg.id() != null && userMsg.id().equals(m.id())) {
                        continue;
                    }
                    sessionStore.append(sessionId, m);
                }
                eventStore.emit(
                        "turn.end",
                        ctx,
                        eventStore.mapOf("ok", true, "assistantPreview", eventStore.preview(outcome.finalText())));
                if (!outcome.finalText().endsWith("\n")) {
                    System.out.println();
                }
                System.err.println("[zcode run] session=" + sessionId + " workspace=" + workspace);
                return 0;
            } finally {
                TraceContextHolder.clear();
            }
        } catch (IllegalArgumentException ex) {
            System.err.println("[zcode run] " + ex.getMessage());
            printUsage();
            return 2;
        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            System.err.println("[zcode run] failed: " + msg);
            return 1;
        }
    }

    private static String resolvePrompt(Args parsed) throws Exception {
        if (parsed.promptFile != null) {
            return Files.readString(Path.of(parsed.promptFile), StandardCharsets.UTF_8).trim();
        }
        return parsed.prompt == null ? "" : parsed.prompt.trim();
    }

    private static void printUsage() {
        System.err.println(
                """
                usage:
                  zcode run --workspace <dir> --prompt <text>
                  zcode run --workspace <dir> --prompt-file <path>
                env:
                  ZCODE_AGENT_MAX_ITERATIONS   recommended 30-60 for eval
                  ZCODE_PERMISSION_MODE        forced to auto-confirm for run
                """);
    }

    private record Args(String workspace, String prompt, String promptFile) {
        static Args parse(String[] raw) {
            String workspace = null;
            String prompt = null;
            String promptFile = null;
            List<String> positional = new ArrayList<>();
            for (int i = 0; i < raw.length; i++) {
                String a = raw[i];
                if ("--workspace".equals(a) || "-w".equals(a)) {
                    workspace = needValue(raw, ++i, a);
                } else if ("--prompt".equals(a) || "-p".equals(a)) {
                    prompt = needValue(raw, ++i, a);
                } else if ("--prompt-file".equals(a) || "-f".equals(a)) {
                    promptFile = needValue(raw, ++i, a);
                } else if ("--help".equals(a) || "-h".equals(a)) {
                    throw new IllegalArgumentException("help");
                } else if (a.startsWith("-")) {
                    throw new IllegalArgumentException("unknown flag: " + a);
                } else {
                    positional.add(a);
                }
            }
            if (workspace == null && !positional.isEmpty()) {
                workspace = positional.get(0);
            }
            if (prompt == null && promptFile == null && positional.size() >= 2) {
                prompt = positional.get(1);
            }
            if (workspace == null || workspace.isBlank()) {
                throw new IllegalArgumentException("--workspace is required");
            }
            if ((prompt == null || prompt.isBlank()) && (promptFile == null || promptFile.isBlank())) {
                throw new IllegalArgumentException("--prompt or --prompt-file is required");
            }
            if (prompt != null && promptFile != null) {
                throw new IllegalArgumentException("use only one of --prompt / --prompt-file");
            }
            return new Args(workspace, prompt, promptFile);
        }

        private static String needValue(String[] raw, int idx, String flag) {
            if (idx >= raw.length) {
                throw new IllegalArgumentException(flag + " needs a value");
            }
            return raw[idx];
        }
    }
}
