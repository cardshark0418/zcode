package com.zcode.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcode.config.MemoryProperties;
import com.zcode.config.ZcodeHome;
import com.zcode.trace.TraceContextHolder;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Session history as JSONL under {@code <workspace>/.zcode/sessions} (or configured dir).
 */
@Component
public class SessionStore {

    public static final String DEFAULT_TITLE = "新对话";
    private static final int TITLE_MAX_CHARS = 40;

    private final ObjectMapper objectMapper;
    private final Path sessionsDir;
    private final int maxTurns;
    private final int retainTokens;
    private final ZcodeHome zcodeHome;

    public SessionStore(ObjectMapper objectMapper, MemoryProperties memoryProperties, ZcodeHome zcodeHome) {
        this.objectMapper = objectMapper;
        this.maxTurns = memoryProperties.safeMaxTurns();
        this.retainTokens = memoryProperties.safeRetainTokens();
        this.zcodeHome = zcodeHome;
        if (StringUtils.hasText(memoryProperties.dir())) {
            this.sessionsDir = Path.of(memoryProperties.dir());
        } else {
            this.sessionsDir = zcodeHome.sessionsDir();
        }
    }

    public Path sessionsDir() {
        return sessionsDir;
    }

    public int maxTurns() {
        return maxTurns;
    }

    public String createSession() throws IOException {
        return createSession(null);
    }

    /** @param workspace absolute path; null/blank → project default workspace */
    public String createSession(String workspace) throws IOException {
        ensureDir();
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Files.writeString(sessionFile(id), "", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Path ws =
                StringUtils.hasText(workspace)
                        ? Path.of(workspace.trim()).toAbsolutePath().normalize()
                        : zcodeHome.workspace();
        writeMeta(id, DEFAULT_TITLE, false, ws.toString());
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
     * Build model context: last {@code maxTurns} raw pairs, with compaction summary (if any)
     * prepended as a checkpoint {@code user} message in the dialogue (not in system).
     * Trailing unanswered user lines are omitted so a new turn cannot re-execute a restored intent.
     */
    public ContextView buildContextView(String sessionId) throws IOException {
        SessionSnapshot snap = snapshot(sessionId);
        List<ChatMessage> dialogue =
                truncateDialogue(withoutTrailingUnansweredUsers(snap.dialogue()));
        if (snap.hasSummary()) {
            List<ChatMessage> withCheckpoint = new ArrayList<>(dialogue.size() + 1);
            withCheckpoint.add(CompactionCheckpoint.asUserMessage(snap.summary()));
            withCheckpoint.addAll(dialogue);
            dialogue = List.copyOf(withCheckpoint);
        }
        return new ContextView(snap.summary(), dialogue);
    }

    /**
     * Drop trailing plain-user messages that have no assistant/tool reply after them.
     * Typical after restore-to-user-checkpoint: the kept user line is an open order.
     *
     * @return ids of dropped user messages (empty if nothing changed)
     */
    public List<String> dropTrailingUnansweredUserMessages(String sessionId) throws IOException {
        if (!exists(sessionId)) {
            return List.of();
        }
        if (isEventLog(sessionId)) {
            return dropTrailingUnansweredFromEventLog(sessionId);
        }
        return dropTrailingUnansweredFromLegacy(sessionId);
    }

    /** Strip trailing unanswered plain-user messages from an in-memory dialogue list. */
    public static List<ChatMessage> withoutTrailingUnansweredUsers(List<ChatMessage> dialogue) {
        if (dialogue == null || dialogue.isEmpty()) {
            return dialogue == null ? List.of() : dialogue;
        }
        int keep = dialogue.size();
        while (keep > 0 && dialogue.get(keep - 1).isPlainUser()) {
            keep--;
        }
        if (keep == dialogue.size()) {
            return dialogue;
        }
        return List.copyOf(dialogue.subList(0, keep));
    }

    private List<String> dropTrailingUnansweredFromEventLog(String sessionId) throws IOException {
        List<SessionEvent> events = loadEvents(sessionId);
        List<ChatMessage> dialogue = SessionProjection.toMessages(events, objectMapper);
        int keepMsg = dialogue.size();
        while (keepMsg > 0 && dialogue.get(keepMsg - 1).isPlainUser()) {
            keepMsg--;
        }
        if (keepMsg == dialogue.size()) {
            return List.of();
        }
        List<String> droppedIds = new ArrayList<>();
        for (int i = keepMsg; i < dialogue.size(); i++) {
            String id = dialogue.get(i).id();
            if (id != null && !id.isBlank()) {
                droppedIds.add(id);
            }
        }

        int dropFrom;
        if (keepMsg == 0) {
            dropFrom = 0;
            for (int i = 0; i < events.size(); i++) {
                if (SessionEvent.USER_MESSAGE.equals(events.get(i).type())) {
                    dropFrom = i;
                    String turnId = events.get(i).turnId();
                    while (dropFrom > 0) {
                        SessionEvent prev = events.get(dropFrom - 1);
                        if (turnId != null && turnId.equals(prev.turnId())) {
                            dropFrom--;
                        } else {
                            break;
                        }
                    }
                    break;
                }
            }
        } else {
            String lastKeepId = dialogue.get(keepMsg - 1).id();
            int lastKeepIdx = indexOfEventOrPayloadId(events, lastKeepId);
            if (lastKeepIdx < 0) {
                return List.of();
            }
            dropFrom = events.size();
            for (int i = lastKeepIdx + 1; i < events.size(); i++) {
                if (SessionEvent.USER_MESSAGE.equals(events.get(i).type())) {
                    dropFrom = i;
                    String turnId = events.get(i).turnId();
                    while (dropFrom > lastKeepIdx + 1) {
                        SessionEvent prev = events.get(dropFrom - 1);
                        if (turnId != null && turnId.equals(prev.turnId())) {
                            dropFrom--;
                        } else {
                            break;
                        }
                    }
                    break;
                }
            }
        }

        if (dropFrom >= events.size()) {
            return List.of();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dropFrom; i++) {
            sb.append(objectMapper.writeValueAsString(events.get(i))).append('\n');
        }
        Files.writeString(sessionFile(sessionId), sb.toString(), StandardCharsets.UTF_8);
        return List.copyOf(droppedIds);
    }

    private List<String> dropTrailingUnansweredFromLegacy(String sessionId) throws IOException {
        List<ChatMessage> all = loadLegacyMessages(sessionId);
        int keep = all.size();
        while (keep > 0 && all.get(keep - 1).isPlainUser()) {
            keep--;
        }
        if (keep == all.size()) {
            return List.of();
        }
        List<String> droppedIds = new ArrayList<>();
        for (int i = keep; i < all.size(); i++) {
            String id = all.get(i).id();
            if (id != null && !id.isBlank()) {
                droppedIds.add(id);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keep; i++) {
            sb.append(objectMapper.writeValueAsString(all.get(i))).append('\n');
        }
        Files.writeString(sessionFile(sessionId), sb.toString(), StandardCharsets.UTF_8);
        return List.copyOf(droppedIds);
    }

    private static int indexOfEventOrPayloadId(List<SessionEvent> events, String id) {
        if (id == null || id.isBlank() || events == null) {
            return -1;
        }
        for (int i = 0; i < events.size(); i++) {
            SessionEvent e = events.get(i);
            if (id.equals(e.id())) {
                return i;
            }
            Map<?, ?> p = e.payload();
            if (p != null && id.equals(String.valueOf(p.get("id")))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Keep a raw tail under {@code retainTokens} and at most {@code maxTurns} plain-user turns,
     * cutting only at user boundaries so tool_use / tool_result chains stay intact.
     */
    public List<ChatMessage> truncateDialogue(List<ChatMessage> dialogue) {
        return DialogueTail.select(dialogue, retainTokens, maxTurns);
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
                            out.add(new SessionInfo(id, readTitle(id), readWorkspace(id), mtime, count));
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
            writeMeta(sessionId, auto != null ? auto : DEFAULT_TITLE, false, readWorkspace(sessionId));
            return;
        }
        writeMeta(sessionId, cleaned, true, readWorkspace(sessionId));
    }

    public String readWorkspace(String sessionId) {
        var meta = readMeta(sessionId);
        if (meta != null && meta.hasNonNull("workspace")) {
            String w = meta.get("workspace").asText();
            if (w != null && !w.isBlank()) {
                return Path.of(w.trim()).toAbsolutePath().normalize().toString();
            }
        }
        return zcodeHome.workspace().toString();
    }

    public void setWorkspace(String sessionId, String workspace) throws IOException {
        if (!exists(sessionId)) {
            throw new IllegalArgumentException("unknown session: " + sessionId);
        }
        if (!StringUtils.hasText(workspace)) {
            throw new IllegalArgumentException("workspace is required");
        }
        Path abs = Path.of(workspace.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(abs)) {
            throw new IllegalArgumentException("not a directory: " + abs);
        }
        String title = readTitle(sessionId);
        boolean renamed = isTitleRenamed(sessionId);
        writeMeta(sessionId, title != null ? title : DEFAULT_TITLE, renamed, abs.toString());
    }

    /** Distinct workspace paths used by existing sessions (newest first). */
    public List<String> listKnownWorkspaces() throws IOException {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        String def = zcodeHome.workspace().toString();
        set.add(def);
        for (SessionInfo s : listSessions()) {
            if (StringUtils.hasText(s.workspace())) {
                set.add(Path.of(s.workspace()).toAbsolutePath().normalize().toString());
            }
        }
        return List.copyOf(set);
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
        writeMeta(sessionId, next, false, readWorkspace(sessionId));
        return next;
    }

    public Path metaFile(String sessionId) {
        return sessionsDir.resolve(sessionId + ".meta.json");
    }

    public record SessionInfo(String id, String title, String workspace, long mtimeMs, int messageCount) {
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

    private void writeMeta(String sessionId, String title, boolean renamed, String workspace) throws IOException {
        ensureDir();
        var node = objectMapper.createObjectNode();
        node.put("title", title == null || title.isBlank() ? DEFAULT_TITLE : title);
        node.put("renamed", renamed);
        String ws = workspace;
        if (!StringUtils.hasText(ws)) {
            ws = zcodeHome.workspace().toString();
        }
        node.put("workspace", Path.of(ws).toAbsolutePath().normalize().toString());
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
