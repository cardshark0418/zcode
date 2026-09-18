package com.zcode.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
