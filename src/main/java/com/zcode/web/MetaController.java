package com.zcode.web;

import com.zcode.agent.InstructionsLoader;
import com.zcode.agent.SkillCatalog;
import com.zcode.config.LlmRuntime;
import com.zcode.permission.PermissionMode;
import com.zcode.permission.PermissionService;
import com.zcode.tool.ToolRegistry;
import com.zcode.trace.EventStore;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MetaController {

    private final LlmRuntime llmRuntime;
    private final ToolRegistry toolRegistry;
    private final PermissionService permissionService;
    private final InstructionsLoader instructionsLoader;
    private final SkillCatalog skillCatalog;
    private final EventStore eventStore;

    public MetaController(
            LlmRuntime llmRuntime,
            ToolRegistry toolRegistry,
            PermissionService permissionService,
            InstructionsLoader instructionsLoader,
            SkillCatalog skillCatalog,
            EventStore eventStore) {
        this.llmRuntime = llmRuntime;
        this.toolRegistry = toolRegistry;
        this.permissionService = permissionService;
        this.instructionsLoader = instructionsLoader;
        this.skillCatalog = skillCatalog;
        this.eventStore = eventStore;
    }

    public record ModeBody(String mode) {}

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        PermissionMode mode = permissionService.mode();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", llmRuntime.model());
        m.put("workspace", toolRegistry.workspace().toString());
        m.put("permissionMode", mode.id());
        m.put("permissionDescription", mode.description());
        m.put("toolsEnabled", toolRegistry.enabled());
        m.put("tools", toolRegistry.allowedNames());
        m.put("skills", skillCatalog.list(toolRegistry.workspace()).stream().map(s -> s.name()).toList());
        m.put("instructions", instructionsLoader.describeSources(toolRegistry.workspace()));
        m.put("traceEnabled", eventStore.enabled());
        m.put("modes", java.util.Arrays.stream(PermissionMode.values())
                .map(x -> Map.of("id", x.id(), "description", x.description()))
                .toList());
        m.put("llm", llmRuntime.describe());
        return m;
    }

    @GetMapping("/llm")
    public Map<String, Object> getLlm() {
        return llmRuntime.describe();
    }

    @PutMapping("/llm")
    public Map<String, Object> setLlm(@RequestBody LlmRuntime.UpdateRequest body) throws IOException {
        return llmRuntime.update(body);
    }

    @GetMapping("/permission/mode")
    public Map<String, Object> getMode() {
        PermissionMode mode = permissionService.mode();
        return Map.of("id", mode.id(), "description", mode.description());
    }

    @PutMapping("/permission/mode")
    public Map<String, Object> setMode(@RequestBody ModeBody body) {
        PermissionMode mode = permissionService.setMode(body == null ? null : body.mode());
        return Map.of("id", mode.id(), "description", mode.description());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException ex) {
        return ResponseEntity.status(409).body(Map.of("error", ex.getMessage()));
    }
}
