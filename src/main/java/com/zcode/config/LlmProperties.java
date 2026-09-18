package com.zcode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zcode.llm")
public record LlmProperties(
        /** openai = /chat/completions ; anthropic = /v1/messages */
        String api,
        String baseUrl,
        String apiKey,
        String model,
        int maxTokens,
        int timeoutSeconds
) {
    public boolean anthropic() {
        return api == null || api.isBlank() || "anthropic".equalsIgnoreCase(api);
    }
}
