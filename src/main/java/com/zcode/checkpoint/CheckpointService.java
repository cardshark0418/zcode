package com.zcode.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zcode.config.AgentProperties;
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
import org.springframework.stereotype.Service;

/**
 * Tracks edit/write/delete mutations, restores workspace + session to a user-message checkpoint,
 * and keeps a single undo pack until the next user message.
 */
@Service
public class CheckpointService {

    private final SessionStore sessionStore;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;

    public CheckpointService(
            SessionStore sessionStore, AgentProperties agentProperties, ObjectMapper objectMapper) {
        this.sessionStore = sessionStore;
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
    }

    public Path ckptDir(String sessionId) {
        return sessionStore.sessionsDir().resolve(sessionId + ".ckpt");
    }

    public void recordMutate(Path absoluteFile, byte[] before, byte[] after) {
        String sessionId = TraceContextHolder.sessionId();
        String turnId = TraceContextHolder.turnId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            if (!sessionStore.isEventLog(sessionId)) {
                return;
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
        } catch (Exception ignored) {
            // checkpoint must never break the agent
        }
    }

    public record RestoreResult(int filesRestored, boolean undoAvailable) {}

    public RestoreResult restoreTo(String sessionId, String eventId) throws IOException {
        if (!sessionStore.isEventLog(sessionId)) {
            throw new IllegalStateException("checkpoint restore requires an event-sourced session");
        }
        List<SessionEvent> events = sessionStore.loadEvents(sessionId);
        int idx = indexOfUserEvent(events, eventId);
        if (idx < 0) {
            throw new IllegalArgumentException("unknown user checkpoint: " + eventId);
        }

        Map<String, String> targetBefore = new LinkedHashMap<>();
        for (int i = idx + 1; i < events.size(); i++) {
            SessionEvent e = events.get(i);
            if (!SessionEvent.FILE_MUTATE.equals(e.type())) {
                continue;
            }
            Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
            String path = str(p.get("path"));
            if (path == null || path.isBlank() || targetBefore.containsKey(path)) {
                continue;
            }
            Object before = p.get("before");
            targetBefore.put(path, before == null ? null : str(before));
        }

        Path workspace = workspace();
        BlobStore blobs = new BlobStore(ckptDir(sessionId));
        Files.createDirectories(ckptDir(sessionId));

        ObjectNode undo = objectMapper.createObjectNode();
        undo.put("checkpointEventId", events.get(idx).id());
        ArrayNode files = undo.putArray("files");
        for (String rel : targetBefore.keySet()) {
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
        List<SessionEvent> discarded = events.subList(idx + 1, events.size());
        for (SessionEvent e : discarded) {
            tail.add(objectMapper.valueToTree(e));
        }
        Files.writeString(undoFile(sessionId), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(undo), StandardCharsets.UTF_8);

        int restored = 0;
        for (Map.Entry<String, String> entry : targetBefore.entrySet()) {
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

        sessionStore.truncateAfter(sessionId, events.get(idx).id());
        return new RestoreResult(restored, true);
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

    private Path undoFile(String sessionId) {
        return ckptDir(sessionId).resolve("undo.json");
    }

    private Path workspace() {
        if (agentProperties.workspace() != null && !agentProperties.workspace().isBlank()) {
            return Path.of(agentProperties.workspace()).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
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
}
