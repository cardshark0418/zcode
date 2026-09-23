package com.zcode.tool;

import com.zcode.config.ZcodeHome;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class TodoStore {

    private final ObjectMapper objectMapper;
    private final ZcodeHome zcodeHome;
    private final Map<String, List<TodoItem>> cache = new ConcurrentHashMap<>();

    public TodoStore(ObjectMapper objectMapper, ZcodeHome zcodeHome) {
        this.objectMapper = objectMapper;
        this.zcodeHome = zcodeHome;
    }

    public synchronized List<TodoItem> get(String sessionId) {
        return new ArrayList<>(cache.computeIfAbsent(sessionId, this::load));
    }

    public synchronized List<TodoItem> replace(String sessionId, List<TodoItem> items) {
        List<TodoItem> copy = items == null ? List.of() : List.copyOf(items);
        cache.put(sessionId, new ArrayList<>(copy));
        save(sessionId, copy);
        return copy;
    }

    public synchronized List<TodoItem> merge(String sessionId, List<TodoItem> updates) {
        Map<String, TodoItem> map = new java.util.LinkedHashMap<>();
        for (TodoItem t : get(sessionId)) {
            map.put(t.id(), t);
        }
        if (updates != null) {
            for (TodoItem t : updates) {
                if (t == null || t.id() == null || t.id().isBlank()) {
                    continue;
                }
                map.put(t.id(), t);
            }
        }
        List<TodoItem> next = List.copyOf(map.values());
        cache.put(sessionId, new ArrayList<>(next));
        save(sessionId, next);
        return next;
    }

    private List<TodoItem> load(String sessionId) {
        try {
            Path file = file(sessionId);
            if (!Files.isRegularFile(file)) {
                return new ArrayList<>();
            }
            TodoItem[] arr = objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), TodoItem[].class);
            return arr == null ? new ArrayList<>() : new ArrayList<>(List.of(arr));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void save(String sessionId, List<TodoItem> items) {
        try {
            Path file = file(sessionId);
            Files.createDirectories(file.getParent());
            Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(items), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // best-effort persistence
        }
    }

    private Path file(String sessionId) {
        return zcodeHome.sessionsDir().resolve(sessionId + ".todos.json");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TodoItem(String id, String content, String status) {
        public TodoItem {
            if (status == null || status.isBlank()) {
                status = "pending";
            }
        }
    }
}
