package com.zcode.web;

import com.zcode.agent.InstructionsLoader;
import com.zcode.agent.SkillCatalog;
import com.zcode.config.LlmRuntime;
import com.zcode.config.ZcodeHome;
import com.zcode.permission.PermissionMode;
import com.zcode.permission.PermissionService;
import com.zcode.tool.ToolRegistry;
import com.zcode.trace.EventStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final ZcodeHome zcodeHome;

    public MetaController(
            LlmRuntime llmRuntime,
            ToolRegistry toolRegistry,
            PermissionService permissionService,
            InstructionsLoader instructionsLoader,
            SkillCatalog skillCatalog,
            EventStore eventStore,
            ZcodeHome zcodeHome) {
        this.llmRuntime = llmRuntime;
        this.toolRegistry = toolRegistry;
        this.permissionService = permissionService;
        this.instructionsLoader = instructionsLoader;
        this.skillCatalog = skillCatalog;
        this.eventStore = eventStore;
        this.zcodeHome = zcodeHome;
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
        m.put(
                "skills",
                skillCatalog.list(toolRegistry.workspace()).stream()
                        .map(
                                s -> {
                                    Map<String, Object> row = new LinkedHashMap<>();
                                    row.put("name", s.name());
                                    row.put("description", s.description() == null ? "" : s.description());
                                    row.put("path", s.path() == null ? "" : s.path().toString());
                                    return row;
                                })
                        .toList());
        m.put("instructions", instructionsLoader.describeSources(toolRegistry.workspace()));
        m.put("traceEnabled", eventStore.enabled());
        m.put("modes", java.util.Arrays.stream(PermissionMode.values())
                .map(x -> Map.of("id", x.id(), "description", x.description()))
                .toList());
        m.put("llm", llmRuntime.describe());
        m.put("skillsDir", zcodeHome.skillsDir().toString());
        m.put("zcodeHome", zcodeHome.home().toString());
        m.put("defaultWorkspace", zcodeHome.workspace().toString());
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

    @PostMapping("/llm/providers")
    public Map<String, Object> addProvider(@RequestBody(required = false) LlmRuntime.AddRequest body) throws IOException {
        return llmRuntime.add(body);
    }

    @PutMapping("/llm/active")
    public Map<String, Object> selectProvider(@RequestBody LlmRuntime.SelectRequest body) throws IOException {
        return llmRuntime.select(body == null ? null : body.id());
    }

    @DeleteMapping("/llm/providers/{id}")
    public Map<String, Object> deleteProvider(@PathVariable String id) throws IOException {
        return llmRuntime.delete(id);
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

    /** Reveal {@code <workspace>/.zcode/skills} in the OS file manager. */
    @PostMapping("/skills/open-dir")
    public Map<String, Object> openSkillsDir() throws IOException {
        Path dir = zcodeHome.skillsDir();
        Files.createDirectories(dir);
        String abs = dir.toAbsolutePath().toString();
        Thread.startVirtualThread(() -> {
            try {
                openDirFallback(Path.of(abs));
            } catch (Exception ignored) {
                // best-effort
            }
        });
        return Map.of("ok", true, "path", abs);
    }

    private static void openDirFallback(Path dir) throws IOException {
        String abs = dir.toAbsolutePath().toString();
        String os = System.getProperty("os.name", "").toLowerCase();
        ProcessBuilder pb;
        if (os.contains("win")) {
            pb = new ProcessBuilder("cmd", "/c", "start", "", "explorer.exe", abs);
        } else if (os.contains("mac")) {
            pb = new ProcessBuilder("open", abs);
        } else {
            pb = new ProcessBuilder("xdg-open", abs);
        }
        pb.redirectErrorStream(true);
        pb.start();
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
