package com.zcode.tool;

import com.zcode.config.WorkspaceService;
import java.nio.file.Path;
import org.springframework.util.StringUtils;

/**
 * Per-tool execution context. {@link #workspace()} re-resolves from the session so
 * {@code set_workspace} takes effect for later tools in the same turn.
 */
public final class ToolContext {

    private final WorkspaceService workspaceService;
    private final Path fallbackWorkspace;
    private final int maxOutputChars;
    private final String sessionId;
    private final AskUserHandler askUser;
    private final TodoStore todoStore;
    private final ToolApprover approver;

    public ToolContext(
            WorkspaceService workspaceService,
            Path fallbackWorkspace,
            int maxOutputChars,
            String sessionId,
            AskUserHandler askUser,
            TodoStore todoStore,
            ToolApprover approver) {
        this.workspaceService = workspaceService;
        this.fallbackWorkspace = fallbackWorkspace;
        this.maxOutputChars = maxOutputChars;
        this.sessionId = sessionId;
        this.askUser = askUser;
        this.todoStore = todoStore;
        this.approver = approver;
    }

    /** Legacy / test: fixed workspace snapshot. */
    public ToolContext(Path workspace, int maxOutputChars) {
        this(null, workspace, maxOutputChars, null, null, null, null);
    }

    public Path workspace() {
        if (workspaceService != null && StringUtils.hasText(sessionId)) {
            return workspaceService.forSession(sessionId);
        }
        return fallbackWorkspace;
    }

    public int maxOutputChars() {
        return maxOutputChars;
    }

    public String sessionId() {
        return sessionId;
    }

    public AskUserHandler askUser() {
        return askUser;
    }

    public TodoStore todoStore() {
        return todoStore;
    }

    public ToolApprover approver() {
        return approver;
    }
}
