package com.zcode.config;

import java.nio.file.Path;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Project-local data root: {@code <workspace>/.zcode} (never {@code ~/}).
 *
 * <p>Override with {@code -Dzcode.home=...} or {@code ZCODE_HOME}. Workspace comes from
 * {@code zcode.agent.workspace} / {@code ZCODE_WORKSPACE}, else {@code user.dir}.
 */
@Component
public class ZcodeHome {

    private final Path home;
    private final Path workspace;

    public ZcodeHome(AgentProperties agentProperties) {
        this.workspace = resolveWorkspace(agentProperties);
        this.home = resolveHome(this.workspace);
        System.setProperty("zcode.home", this.home.toString());
        System.setProperty("zcode.workspace", this.workspace.toString());
    }

    public Path home() {
        return home;
    }

    public Path workspace() {
        return workspace;
    }

    public Path sessionsDir() {
        return home.resolve("sessions");
    }

    public Path llmJson() {
        return home.resolve("llm.json");
    }

    public Path skillsDir() {
        return home.resolve("skills");
    }

    /** Static resolve for non-Spring helpers (e.g. RequestDump). */
    public static Path resolveHomeStatic() {
        String override = firstNonBlank(System.getProperty("zcode.home"), System.getenv("ZCODE_HOME"));
        if (StringUtils.hasText(override)) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return resolveWorkspaceStatic(null).resolve(".zcode").normalize();
    }

    private static Path resolveHome(Path workspace) {
        String override = firstNonBlank(System.getProperty("zcode.home"), System.getenv("ZCODE_HOME"));
        if (StringUtils.hasText(override)) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return workspace.resolve(".zcode").normalize();
    }

    private static Path resolveWorkspace(AgentProperties props) {
        if (props != null && StringUtils.hasText(props.workspace())) {
            return Path.of(props.workspace()).toAbsolutePath().normalize();
        }
        return resolveWorkspaceStatic(null);
    }

    private static Path resolveWorkspaceStatic(String unused) {
        String ws = firstNonBlank(System.getProperty("zcode.workspace"), System.getenv("ZCODE_WORKSPACE"));
        if (StringUtils.hasText(ws)) {
            return Path.of(ws).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private static String firstNonBlank(String a, String b) {
        if (StringUtils.hasText(a)) {
            return a.trim();
        }
        if (StringUtils.hasText(b)) {
            return b.trim();
        }
        return null;
    }
}
