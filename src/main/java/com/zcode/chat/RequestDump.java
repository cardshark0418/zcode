package com.zcode.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the last LLM request body under ~/.zcode for inspection.
 */
final class RequestDump {

    private RequestDump() {}

    static void write(ObjectMapper mapper, String name, String bodyJson) {
        try {
            Path dir = Path.of(System.getProperty("user.home"), ".zcode");
            Files.createDirectories(dir);
            Object pretty = mapper.readTree(bodyJson);
            String out = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(pretty);
            Files.writeString(dir.resolve(name), out + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // debug aid only — never break chat
        }
    }
}
