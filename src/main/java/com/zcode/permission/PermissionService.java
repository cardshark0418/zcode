package com.zcode.permission;

import com.zcode.config.AgentProperties;
import org.springframework.stereotype.Component;

/**
 * Process-wide current permission mode (CLI {@code /mode} can change it).
 */
@Component
public class PermissionService {

    private volatile PermissionMode mode;

    public PermissionService(AgentProperties agentProperties) {
        this.mode = PermissionMode.parse(agentProperties.permissionMode());
    }

    public PermissionMode mode() {
        return mode;
    }

    public PermissionMode setMode(PermissionMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode is required");
        }
        this.mode = mode;
        return this.mode;
    }

    public PermissionMode setMode(String raw) {
        return setMode(PermissionMode.parse(raw));
    }

    /** Cycle chat → read-only → default → auto-confirm → … (Shift+Tab). */
    public PermissionMode cycle() {
        PermissionMode[] all = PermissionMode.values();
        mode = all[(mode.ordinal() + 1) % all.length];
        return mode;
    }
}
