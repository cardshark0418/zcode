package com.zcode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zcode.trace")
public record TraceProperties(boolean enabled, int previewChars) {

    public boolean isEnabled() {
        return enabled;
    }

    public int safePreviewChars() {
        return previewChars <= 0 ? 500 : previewChars;
    }
}
