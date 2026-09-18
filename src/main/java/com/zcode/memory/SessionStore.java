package com.zcode.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcode.config.MemoryProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Session history as JSONL under ~/.zcode/sessions (or configured dir).
 */
@Component
public class SessionStore {

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

    public void clear(String sessionId) throws IOException {
        ensureDir();
        Files.writeString(sessionFile(sessionId), "", StandardCharsets.UTF_8);
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

    private Path activeFile() {
        return sessionsDir.resolve("active");
    }

    private void ensureDir() throws IOException {
        Files.createDirectories(sessionsDir);
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
