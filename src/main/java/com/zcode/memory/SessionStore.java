package com.zcode.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcode.config.MemoryProperties;
import com.zcode.trace.TraceContextHolder;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Session history as JSONL under ~/.zcode/sessions (or configured dir).
 */
@Component
public class SessionStore {

    public static final String DEFAULT_TITLE = "新对话";
    private static final int TITLE_MAX_CHARS = 40;

    private final ObjectMapper objectMapper;
    private final Path sessionsDir;
    private final int maxTurns;

    public SessionStore(ObjectMapper objectMapper, MemoryProperties memoryProperties) {
        this.objectMapper = objectMapper;
        this.maxTurns = memoryProperties.safeMaxTurns();
        if (StringUtils.hasText(memoryProperties.dir())) {
            this.sessionsDir = Path.of(memoryProperties.dir());
        } else {
            this.sessionsDir = Path.of(System.getProperty("user.home"), ".zcode", "sessions");
        }
    }

    public Path sessionsDir() {
        return sessionsDir;
    }

    public int maxTurns() {
        return maxTurns;
    }

    public String createSession() throws IOException {
        ensureDir();
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Files.writeString(sessionFile(id), "", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        writeMeta(id, DEFAULT_TITLE, false);
        setActive(id);
        return id;
    }

    public String resumeOrCreate() throws IOException {
        ensureDir();
        Path active = activeFile();
        if (Files.isRegularFile(active)) {
            String id = Files.readString(active, StandardCharsets.UTF_8).trim();
            if (!id.isEmpty() && Files.isRegularFile(sessionFile(id))) {
                return id;
            }
        }
        return createSession();
    }

    public void setActive(String sessionId) throws IOException {
        ensureDir();
        Files.writeString(activeFile(), sessionId + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    public List<ChatMessage> load(String sessionId) throws IOException {
        if (isEventLog(sessionId)) {
            return SessionProjection.toMessages(loadEvents(sessionId), objectMapper);
        }
        return loadLegacyMessages(sessionId);
    }

    /** Unified session log (event-sourced). Empty for legacy ChatMessage-only files. */
    public List<SessionEvent> loadEvents(String sessionId) throws IOException {
        Path file = sessionFile(sessionId);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        if (!isEventLog(sessionId)) {
            return List.of();
        }
        List<SessionEvent> out = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                out.add(objectMapper.readValue(line, SessionEvent.class));
            }
        }
        return List.copyOf(out);
    }

    /**
     * True when the session JSONL is event-sourced (v2). Empty files are treated as event logs
     * so newly created sessions use the unified format.
     */
    public boolean isEventLog(String sessionId) throws IOException {
        Path file = sessionFile(sessionId);
        if (!Files.isRegularFile(file)) {
            return true;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                var node = objectMapper.readTree(line);
                if (node.hasNonNull("type") && !node.has("role")) {
                    return true;
                }
                if (node.hasNonNull("role") && !node.has("type")) {
                    return false;
                }
                // ambiguous — prefer event if v==2
                return node.path("v").asInt(0) >= SessionEvent.VERSION;
            }
        }
        return true;
    }

    private List<ChatMessage> loadLegacyMessages(String sessionId) throws IOException {
        Path file = sessionFile(sessionId);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<ChatMessage> out = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                out.add(objectMapper.readValue(line, ChatMessage.class));
            }
        }
        return out;
    }

    public void append(String sessionId, ChatMessage message) throws IOException {
        if (message == null) {
            return;
        }
        if (isEventLog(sessionId)) {
            String turnId = TraceContextHolder.turnId();
            appendEvent(SessionEvent.fromMessage(sessionId, turnId, message));
            return;
        }
        ensureDir();
        String line = objectMapper.writeValueAsString(message) + "\n";
        Files.writeString(
                sessionFile(sessionId),
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
    }

    public void appendEvent(SessionEvent event) throws IOException {
        if (event == null) {
            return;
        }
        ensureDir();
        String line = objectMapper.writeValueAsString(event) + "\n";
        Files.writeString(
                sessionFile(event.sessionId()),
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
    }

    /** Append previously discarded events (undo restore). Preserves original ids/timestamps. */
    public void appendEvents(String sessionId, List<SessionEvent> events) throws IOException {
        if (events == null || events.isEmpty()) {
            return;
        }
        ensureDir();
        StringBuilder sb = new StringBuilder();
        for (SessionEvent event : events) {
            if (event == null) {
                continue;
            }
            sb.append(objectMapper.writeValueAsString(event)).append('\n');
        }
        if (sb.isEmpty()) {
            return;
        }
        Files.writeString(
                sessionFile(sessionId),
                sb.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
    }

    /**
     * Keep events through {@code eventId} (inclusive); drop everything after.
     * Matches either SessionEvent.id or payload message id.
     */
    public void truncateAfter(String sessionId, String eventId) throws IOException {
        if (!isEventLog(sessionId)) {
            throw new IllegalStateException("truncateAfter requires an event-sourced session");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId required");
        }
        List<SessionEvent> events = loadEvents(sessionId);
        int keepThrough = -1;
        for (int i = 0; i < events.size(); i++) {
            SessionEvent e = events.get(i);
            if (eventId.equals(e.id())) {
                keepThrough = i;
                break;
            }
            Map<?, ?> p = e.payload();
            if (p != null && eventId.equals(String.valueOf(p.get("id")))) {
                keepThrough = i;
                break;
            }
        }
        if (keepThrough < 0) {
            throw new IllegalArgumentException("event not found: " + eventId);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= keepThrough; i++) {
            sb.append(objectMapper.writeValueAsString(events.get(i))).append('\n');
        }
        Files.writeString(sessionFile(sessionId), sb.toString(), StandardCharsets.UTF_8);
    }

    public void clear(String sessionId) throws IOException {
        ensureDir();
        Files.writeString(sessionFile(sessionId), "", StandardCharsets.UTF_8);
    }

    /** Delete session transcript, title meta, and events log. */
    public void deleteSession(String sessionId) throws IOException {
        if (!exists(sessionId)) {
            throw new IllegalArgumentException("unknown session: " + sessionId);
        }
        Files.deleteIfExists(sessionFile(sessionId));
        Files.deleteIfExists(metaFile(sessionId));
        Files.deleteIfExists(sessionsDir.resolve(sessionId + ".events.jsonl"));
        deleteCkptDir(sessionId);
        Path active = activeFile();
        if (Files.isRegularFile(active)) {
            String id = Files.readString(active, StandardCharsets.UTF_8).trim();
            if (sessionId.equals(id)) {
                Files.deleteIfExists(active);
            }
        }
    }

    private void deleteCkptDir(String sessionId) {
        Path dir = sessionsDir.resolve(sessionId + ".ckpt");
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /**
     * Latest summary (if any) + dialogue that was not folded into it.
     * Uses {@code coversUntil}: every user/assistant message after that id stays as raw tail
     * (even if those lines appear before the summary line in the file).
     */
    public SessionSnapshot snapshot(List<ChatMessage> all) {
        int lastSummaryIdx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).isSummary()) {
                lastSummaryIdx = i;
            }
        }
        if (lastSummaryIdx < 0) {
            List<ChatMessage> dialogue = new ArrayList<>();
            for (ChatMessage m : all) {
                if (m.isDialogue()) {
                    dialogue.add(m);
                }
            }
            return new SessionSnapshot(null, List.copyOf(dialogue));
        }

        ChatMessage summaryMsg = all.get(lastSummaryIdx);
        String summary = summaryMsg.content();
        int coverIdx = indexOfId(all, summaryMsg.coversUntil());

        List<ChatMessage> dialogue = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (i == lastSummaryIdx) {
                continue;
            }
            ChatMessage m = all.get(i);
            if (!m.isDialogue()) {
                continue;
            }
            // No coversUntil → fall back to lines after the summary event only.
            if (coverIdx < 0) {
                if (i > lastSummaryIdx) {
                    dialogue.add(m);
                }
            } else if (i > coverIdx) {
                dialogue.add(m);
            }
        }
        return new SessionSnapshot(summary, List.copyOf(dialogue));
    }

    private static int indexOfId(List<ChatMessage> all, String id) {
        if (id == null || id.isBlank()) {
            return -1;
        }
        for (int i = 0; i < all.size(); i++) {
            if (id.equals(all.get(i).id())) {
                return i;
            }
        }
        return -1;
    }

    public SessionSnapshot snapshot(String sessionId) throws IOException {
        return snapshot(load(sessionId));
    }

    /**
     * Build model context: system summary + last {@code maxTurns} raw pairs.
     */
    public ContextView buildContextView(String sessionId) throws IOException {
        SessionSnapshot snap = snapshot(sessionId);
        return new ContextView(snap.summary(), truncateDialogue(snap.dialogue()));
    }

    /**
     * Keep the last {@code maxTurns} plain user turns (and everything after that cut),
     * so tool_use / tool_result chains stay intact.
     */
    public List<ChatMessage> truncateDialogue(List<ChatMessage> dialogue) {
        if (dialogue.isEmpty()) {
            return List.of();
        }
        List<Integer> plainUserIdx = new ArrayList<>();
        for (int i = 0; i < dialogue.size(); i++) {
            if (dialogue.get(i).isPlainUser()) {
                plainUserIdx.add(i);
            }
        }
        if (plainUserIdx.size() <= maxTurns) {
            return List.copyOf(dialogue);
        }
        int start = plainUserIdx.get(plainUserIdx.size() - maxTurns);
        return List.copyOf(dialogue.subList(start, dialogue.size()));
    }

    /** @deprecated use {@link #truncateDialogue(List)} */
    public List<ChatMessage> truncateForModel(List<ChatMessage> all) {
        SessionSnapshot snap = snapshot(all);
        return truncateDialogue(snap.dialogue());
    }

    public Path sessionFile(String sessionId) {
        return sessionsDir.resolve(sessionId + ".jsonl");
    }

    public boolean exists(String sessionId) {
        return sessionId != null && !sessionId.isBlank() && Files.isRegularFile(sessionFile(sessionId));
    }

    /** Newest sessions first (by file mtime). */
    public List<SessionInfo> listSessions() throws IOException {
        ensureDir();
        List<SessionInfo> out = new ArrayList<>();
        try (var stream = Files.list(sessionsDir)) {
            stream.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".jsonl") && !name.endsWith(".events.jsonl");
                    })
                    .forEach(p -> {
                        try {
                            String id = p.getFileName().toString().replace(".jsonl", "");
                            long mtime = Files.getLastModifiedTime(p).toMillis();
                            int count = countDialogue(id);
                            out.add(new SessionInfo(id, readTitle(id), mtime, count));
                        } catch (IOException ignored) {
                            // skip unreadable
                        }
                    });
        }
        out.sort((a, b) -> Long.compare(b.mtimeMs(), a.mtimeMs()));
        return List.copyOf(out);
    }

    /** Count plain user questions (not assistant/tool/summary lines). */
    private int countDialogue(String sessionId) throws IOException {
        if (isEventLog(sessionId)) {
            int n = 0;
            for (SessionEvent e : loadEvents(sessionId)) {
                if (SessionEvent.USER_MESSAGE.equals(e.type())) {
                    n++;
                }
            }
            return n;
        }
        int n = 0;
        for (ChatMessage m : loadLegacyMessages(sessionId)) {
            if (m.isPlainUser()) {
                n++;
            }
        }
        return n;
    }

    public String readTitle(String sessionId) {
        var meta = readMeta(sessionId);
        if (meta == null || !meta.hasNonNull("title")) {
            return null;
        }
        String t = meta.get("title").asText();
        return t == null || t.isBlank() ? null : t.trim();
    }

    public boolean isTitleRenamed(String sessionId) {
        var meta = readMeta(sessionId);
        return meta != null && meta.path("renamed").asBoolean(false);
    }

    /**
     * User rename. Empty title clears the custom name and restores auto title
     * (first user message if any, otherwise {@link #DEFAULT_TITLE}).
     */
    public void setTitle(String sessionId, String title) throws IOException {
        if (!exists(sessionId)) {
            throw new IllegalArgumentException("unknown session: " + sessionId);
        }
        ensureDir();
        String cleaned = title == null ? "" : title.trim();
        if (cleaned.isEmpty()) {
            String auto = firstUserTitle(sessionId);
            writeMeta(sessionId, auto != null ? auto : DEFAULT_TITLE, false);
            return;
        }
        writeMeta(sessionId, cleaned, true);
    }

    /**
     * After the first user turn: if the user has not renamed the session, set the title
     * from this message (replacing {@link #DEFAULT_TITLE}). Returns the effective title.
     */
    public String maybeAutoTitleFromUserMessage(String sessionId, String userMessage) throws IOException {
        if (!exists(sessionId) || userMessage == null || userMessage.isBlank()) {
            return readTitle(sessionId);
        }
        if (isTitleRenamed(sessionId)) {
            return readTitle(sessionId);
        }
        String current = readTitle(sessionId);
        if (current != null && !current.isBlank() && !DEFAULT_TITLE.equals(current)) {
            return current;
        }
        String next = firstUserTitle(sessionId);
        if (next == null) {
            next = truncateTitle(userMessage);
        }
        if (next == null || next.isBlank()) {
            return current;
        }
        writeMeta(sessionId, next, false);
        return next;
    }

    public Path metaFile(String sessionId) {
        return sessionsDir.resolve(sessionId + ".meta.json");
    }

    public record SessionInfo(String id, String title, long mtimeMs, int messageCount) {
        public String displayName() {
            return title != null && !title.isBlank() ? title : id;
        }
    }

    private Path activeFile() {
        return sessionsDir.resolve("active");
    }

    private void ensureDir() throws IOException {
        Files.createDirectories(sessionsDir);
    }

    private com.fasterxml.jackson.databind.JsonNode readMeta(String sessionId) {
        Path meta = metaFile(sessionId);
        if (!Files.isRegularFile(meta)) {
            return null;
        }
        try {
            return objectMapper.readTree(Files.readString(meta, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeMeta(String sessionId, String title, boolean renamed) throws IOException {
        ensureDir();
        var node = objectMapper.createObjectNode();
        node.put("title", title);
        node.put("renamed", renamed);
        Files.writeString(metaFile(sessionId), objectMapper.writeValueAsString(node), StandardCharsets.UTF_8);
    }

    private String firstUserTitle(String sessionId) throws IOException {
        for (ChatMessage m : load(sessionId)) {
            if (m.isPlainUser() && m.content() != null && !m.content().isBlank()) {
                return truncateTitle(m.content());
            }
        }
        return null;
    }

    static String truncateTitle(String text) {
        String s = text.replace('\r', ' ').replace('\n', ' ').trim().replaceAll("\\s+", " ");
        if (s.length() <= TITLE_MAX_CHARS) {
            return s;
        }
        return s.substring(0, TITLE_MAX_CHARS - 1) + "…";
    }

    public record SessionSnapshot(String summary, List<ChatMessage> dialogue) {
        public SessionSnapshot {
            dialogue = dialogue == null ? List.of() : List.copyOf(dialogue);
        }

        public boolean hasSummary() {
            return summary != null && !summary.isBlank();
        }
    }
}
