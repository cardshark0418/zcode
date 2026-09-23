package com.zcode.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zcode.config.WorkspaceService;
import com.zcode.memory.SessionEvent;
import com.zcode.memory.SessionStore;
import com.zcode.trace.TraceContextHolder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.stereotype.Service;

/**
 * Tracks edit/write/delete mutations, restores workspace + session to a user-message checkpoint,
 * and keeps a single undo pack until the next user message.
 */
@Service
public class CheckpointService {

    private static final Logger log = Logger.getLogger(CheckpointService.class.getName());

    private final SessionStore sessionStore;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    public CheckpointService(
            SessionStore sessionStore, WorkspaceService workspaceService, ObjectMapper objectMapper) {
        this.sessionStore = sessionStore;
        this.workspaceService = workspaceService;
        this.objectMapper = objectMapper;
    }

    public Path ckptDir(String sessionId) {
        return sessionStore.sessionsDir().resolve(sessionId + ".ckpt");
    }

    /**
     * @return true if the mutate was recorded; false if checkpointing failed (tool should warn)
     */
    public boolean recordMutate(Path absoluteFile, byte[] before, byte[] after) {
        String sessionId = TraceContextHolder.sessionId();
        String turnId = TraceContextHolder.turnId();
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            if (!sessionStore.isEventLog(sessionId)) {
                return false;
            }
            Path workspace = workspace();
            String rel = relativize(workspace, absoluteFile);
            BlobStore blobs = new BlobStore(ckptDir(sessionId));
            String beforeHash = before == null ? null : blobs.put(before);
            String afterHash = after == null ? null : blobs.put(after);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("path", rel);
            if (beforeHash != null) {
                payload.put("before", beforeHash);
            }
            if (afterHash != null) {
                payload.put("after", afterHash);
            }
            sessionStore.appendEvent(SessionEvent.of(sessionId, turnId, SessionEvent.FILE_MUTATE, payload));
            return true;
        } catch (Exception e) {
            log.log(
                    Level.WARNING,
                    "checkpoint recordMutate failed for " + absoluteFile + ": " + e.getMessage(),
                    e);
            return false;
        }
    }

    public record Conflict(String path, String reason) {}

    public record RestoreResult(
            int filesRestored, boolean undoAvailable, List<Conflict> conflicts, boolean needsConfirm) {}

    /** Dry-run: list paths whose disk state is neither checkpoint-before nor last agent-after. */
    public List<Conflict> findConflicts(String sessionId, String eventId) throws IOException {
        MutatePlan plan = buildPlan(sessionId, eventId);
        return detectConflicts(plan);
    }

    /**
     * @param force when false and local conflicts exist, do not mutate; return {@code needsConfirm=true}
     */
    public RestoreResult restoreTo(String sessionId, String eventId, boolean force) throws IOException {
        MutatePlan plan = buildPlan(sessionId, eventId);
        List<Conflict> conflicts = detectConflicts(plan);
        if (!force) {
            // Dry-run only — never mutate until force=true.
            return new RestoreResult(0, false, List.copyOf(conflicts), true);
        }

        Path workspace = plan.workspace();
        BlobStore blobs = new BlobStore(ckptDir(sessionId));
        Files.createDirectories(ckptDir(sessionId));

        ObjectNode undo = objectMapper.createObjectNode();
        undo.put("checkpointEventId", plan.checkpointEventId());
        ArrayNode files = undo.putArray("files");
        for (String rel : plan.targetBefore().keySet()) {
            Path abs = workspace.resolve(rel).normalize();
            ObjectNode row = files.addObject();
            row.put("path", rel);
            if (Files.isRegularFile(abs)) {
                row.put("contentHash", blobs.put(Files.readAllBytes(abs)));
                row.put("existed", true);
            } else {
                row.putNull("contentHash");
                row.put("existed", false);
            }
        }

        ArrayNode tail = undo.putArray("tailEvents");
        for (SessionEvent e : plan.discarded()) {
            tail.add(objectMapper.valueToTree(e));
        }
        Files.writeString(
                undoFile(sessionId),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(undo) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        int restored = 0;
        for (Map.Entry<String, String> entry : plan.targetBefore().entrySet()) {
            Path abs = workspace.resolve(entry.getKey()).normalize();
            if (!abs.startsWith(workspace)) {
                continue;
            }
            String beforeHash = entry.getValue();
            if (beforeHash == null || beforeHash.isBlank()) {
                Files.deleteIfExists(abs);
            } else {
                byte[] content = blobs.get(beforeHash);
                Path parent = abs.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(abs, content);
            }
            restored++;
        }

        sessionStore.truncateAfter(sessionId, plan.checkpointEventId());
        return new RestoreResult(restored, true, List.copyOf(conflicts), false);
    }

    /** Backward-compatible force restore. */
    public RestoreResult restoreTo(String sessionId, String eventId) throws IOException {
        return restoreTo(sessionId, eventId, true);
    }

    public void undoRestore(String sessionId) throws IOException {
        Path file = undoFile(sessionId);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("no undo snapshot");
        }
        ObjectNode undo = (ObjectNode) objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
        Path workspace = workspace();
        BlobStore blobs = new BlobStore(ckptDir(sessionId));

        ArrayNode files = (ArrayNode) undo.get("files");
        if (files != null) {
            for (var node : files) {
                String rel = node.path("path").asText(null);
                if (rel == null || rel.isBlank()) {
                    continue;
                }
                Path abs = workspace.resolve(rel).normalize();
                if (!abs.startsWith(workspace)) {
                    continue;
                }
                boolean existed = node.path("existed").asBoolean(false);
                String hash = node.path("contentHash").isNull() ? null : node.path("contentHash").asText(null);
                if (!existed || hash == null || hash.isBlank()) {
                    Files.deleteIfExists(abs);
                } else {
                    byte[] content = blobs.get(hash);
                    Path parent = abs.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.write(abs, content);
                }
            }
        }

        ArrayNode tail = (ArrayNode) undo.get("tailEvents");
        if (tail != null) {
            List<SessionEvent> toAppend = new ArrayList<>();
            for (var node : tail) {
                toAppend.add(objectMapper.treeToValue(node, SessionEvent.class));
            }
            sessionStore.appendEvents(sessionId, toAppend);
        }
        clearUndo(sessionId);
    }

    public boolean hasUndo(String sessionId) {
        return Files.isRegularFile(undoFile(sessionId));
    }

    public void clearUndo(String sessionId) {
        try {
            Files.deleteIfExists(undoFile(sessionId));
        } catch (IOException ignored) {
        }
    }

    public void deleteSessionData(String sessionId) {
        clearUndo(sessionId);
        Path dir = ckptDir(sessionId);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    private record MutatePlan(
            Path workspace,
            String checkpointEventId,
            Map<String, String> targetBefore,
            Map<String, String> lastAfter,
            List<SessionEvent> discarded) {}

    private MutatePlan buildPlan(String sessionId, String eventId) throws IOException {
        if (!sessionStore.isEventLog(sessionId)) {
            throw new IllegalStateException("checkpoint restore requires an event-sourced session");
        }
        List<SessionEvent> events = sessionStore.loadEvents(sessionId);
        int idx = indexOfUserEvent(events, eventId);
        if (idx < 0) {
            throw new IllegalArgumentException("unknown user checkpoint: " + eventId);
        }

        Map<String, String> targetBefore = new LinkedHashMap<>();
        Map<String, String> lastAfter = new LinkedHashMap<>();
        for (int i = idx + 1; i < events.size(); i++) {
            SessionEvent e = events.get(i);
            if (!SessionEvent.FILE_MUTATE.equals(e.type())) {
                continue;
            }
            Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
            String path = str(p.get("path"));
            if (path == null || path.isBlank()) {
                continue;
            }
            Object before = p.get("before");
            Object after = p.get("after");
            if (!targetBefore.containsKey(path)) {
                targetBefore.put(path, before == null ? null : str(before));
            }
            lastAfter.put(path, after == null ? null : str(after));
        }

        List<SessionEvent> discarded = List.copyOf(events.subList(idx + 1, events.size()));
        return new MutatePlan(workspace(), events.get(idx).id(), targetBefore, lastAfter, discarded);
    }

    private List<Conflict> detectConflicts(MutatePlan plan) throws IOException {
        List<Conflict> conflicts = new ArrayList<>();
        Path workspace = plan.workspace();
        for (String rel : plan.targetBefore().keySet()) {
            Path abs = workspace.resolve(rel).normalize();
            if (!abs.startsWith(workspace)) {
                continue;
            }
            String before = blankToNull(plan.targetBefore().get(rel));
            String after = blankToNull(plan.lastAfter().get(rel));
            boolean exists = Files.isRegularFile(abs);
            String current = exists ? BlobStore.sha256(Files.readAllBytes(abs)) : null;

            boolean matchesBefore = Objects.equals(current, before);
            boolean matchesAfter = Objects.equals(current, after);
            if (matchesBefore || matchesAfter) {
                continue;
            }

            String reason;
            if (!exists && after != null) {
                reason = "本地已删除（回滚将写回检查点内容）";
            } else if (exists && after == null && before == null) {
                reason = "本地存在该文件（回滚将删除）";
            } else if (exists) {
                reason = "本地内容与 AI 改动不一致（可能被手改过）";
            } else {
                reason = "本地状态与检查点不一致";
            }
            conflicts.add(new Conflict(rel, reason));
        }
        return conflicts;
    }

    private Path undoFile(String sessionId) {
        return ckptDir(sessionId).resolve("undo.json");
    }

    private Path workspace() {
        String sid = TraceContextHolder.sessionId();
        if (sid != null && !sid.isBlank()) {
            return workspaceService.forSession(sid);
        }
        return workspaceService.current();
    }

    private static String relativize(Path workspace, Path absoluteFile) {
        Path abs = absoluteFile.toAbsolutePath().normalize();
        Path root = workspace.toAbsolutePath().normalize();
        if (!abs.startsWith(root)) {
            return abs.toString().replace('\\', '/');
        }
        return root.relativize(abs).toString().replace('\\', '/');
    }

    private static int indexOfUserEvent(List<SessionEvent> events, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return -1;
        }
        for (int i = 0; i < events.size(); i++) {
            SessionEvent e = events.get(i);
            if (!SessionEvent.USER_MESSAGE.equals(e.type())) {
                continue;
            }
            if (eventId.equals(e.id())) {
                return i;
            }
            Map<String, Object> p = e.payload();
            if (p != null && eventId.equals(str(p.get("id")))) {
                return i;
            }
        }
        return -1;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
