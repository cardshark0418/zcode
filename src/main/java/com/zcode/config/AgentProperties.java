package com.zcode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zcode.agent")
public record AgentProperties(
        /** Empty = process user.dir */
        String workspace,
        int maxIterations,
        int bashTimeoutSeconds,
        int maxToolOutputChars,
        boolean enabled,
        /** chat | read-only | default | auto-confirm */
        String permissionMode
) {
    public int safeMaxIterations() {
        return maxIterations <= 0 ? 20 : maxIterations;
    }

    public int safeBashTimeoutSeconds() {
        return bashTimeoutSeconds <= 0 ? 60 : bashTimeoutSeconds;
    }

    public int safeMaxToolOutputChars() {
        return maxToolOutputChars <= 0 ? 30_000 : maxToolOutputChars;
    }

    public boolean toolsEnabled() {
        return enabled;
    }

    public String permissionMode() {
        return permissionMode == null || permissionMode.isBlank() ? "auto-confirm" : permissionMode;
    }
}
