package com.zcode.tool;

import java.nio.file.Path;
import java.util.List;

public record ToolContext(
        Path workspace,
        int maxOutputChars,
        String sessionId,
        AskUserHandler askUser,
        TodoStore todoStore,
        ToolApprover approver
) {
    public ToolContext(Path workspace, int maxOutputChars) {
        this(workspace, maxOutputChars, null, null, null, null);
    }
}
