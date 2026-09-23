package com.zcode.config;

import com.zcode.memory.SessionStore;
import com.zcode.trace.TraceContextHolder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves the effective tool workspace: per-session override, else project default.
 */
@Component
public class WorkspaceService {

    private final ZcodeHome zcodeHome;
    private final SessionStore sessionStore;
    private final AgentProperties agentProperties;

    public WorkspaceService(ZcodeHome zcodeHome, SessionStore sessionStore, AgentProperties agentProperties) {
        this.zcodeHome = zcodeHome;
        this.sessionStore = sessionStore;
        this.agentProperties = agentProperties;
    }

    /** Default for new chats: the zcode project folder (or configured ZCODE_WORKSPACE). */
    public Path defaultWorkspace() {
        return zcodeHome.workspace();
    }

    public Path forSession(String sessionId) {
        if (StringUtils.hasText(sessionId)) {
            String stored = sessionStore.readWorkspace(sessionId);
            if (StringUtils.hasText(stored)) {
                return Path.of(stored).toAbsolutePath().normalize();
            }
        }
        return defaultWorkspace();
    }

    /** Prefer the workspace of the active agent turn / session. */
    public Path current() {
        String sid = TraceContextHolder.sessionId();
        if (StringUtils.hasText(sid) && sessionStore.exists(sid)) {
            return forSession(sid);
        }
        if (StringUtils.hasText(agentProperties.workspace())) {
            return Path.of(agentProperties.workspace()).toAbsolutePath().normalize();
        }
        return defaultWorkspace();
    }

    public Path requireDirectory(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("workspace path is required");
        }
        Path abs = Path.of(raw.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(abs)) {
            throw new IllegalArgumentException("not a directory: " + abs);
        }
        return abs;
    }
}
