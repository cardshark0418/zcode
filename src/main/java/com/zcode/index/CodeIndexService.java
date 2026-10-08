package com.zcode.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Stale-check sync + semantic search over the workspace via Qdrant. */
@Service
public class CodeIndexService {

    private static final Logger log = LoggerFactory.getLogger(CodeIndexService.class);

    private final CodeIndexProperties props;
    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrant;
    private final ObjectMapper mapper;
    private final Object lock = new Object();
    private final ExecutorService indexExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zcode-code-index");
        t.setDaemon(true);
        return t;
    });

    public CodeIndexService(
            CodeIndexProperties props,
            EmbeddingClient embeddingClient,
            QdrantClient qdrant,
            ObjectMapper mapper) {
        this.props = props;
        this.embeddingClient = embeddingClient;
        this.qdrant = qdrant;
        this.mapper = mapper;
    }

    @PreDestroy
    public void shutdown() {
        indexExecutor.shutdown();
        try {
            if (!indexExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                indexExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            indexExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public boolean enabled() {
        return props.safeEnabled();
    }

    /**
     * After write/edit: re-embed one file asynchronously. Failures are logged only — tools must not
     * fail because of indexing.
     */
    public void notifyFileChangedAsync(Path workspace, Path file) {
        if (!props.safeEnabled() || workspace == null || file == null) {
            return;
        }
        Path root = workspace.toAbsolutePath().normalize();
        Path abs = file.toAbsolutePath().normalize();
        indexExecutor.execute(() -> {
            try {
                notifyFileChanged(root, abs);
            } catch (Exception e) {
                log.warn("code-index upsert skipped for {}: {}", abs, e.getMessage());
            }
        });
    }

    /** After delete: drop vectors for one file asynchronously. */
    public void notifyFileDeletedAsync(Path workspace, Path file) {
        if (!props.safeEnabled() || workspace == null || file == null) {
            return;
        }
        Path root = workspace.toAbsolutePath().normalize();
        Path abs = file.toAbsolutePath().normalize();
        indexExecutor.execute(() -> {
            try {
                notifyFileDeleted(root, abs);
            } catch (Exception e) {
                log.warn("code-index delete skipped for {}: {}", abs, e.getMessage());
            }
        });
    }

    void notifyFileChanged(Path root, Path absFile) throws Exception {
        if (!absFile.startsWith(root)) {
            return;
        }
        if (!readyForMutation()) {
            return;
        }
        String rel = root.relativize(absFile).toString().replace('\\', '/');
        Set<String> exts = extensionSet();
        synchronized (lock) {
            CodeIndexManifest manifest = prepareManifest(root);
            if (!Files.isRegularFile(absFile)) {
                removeFileUnlocked(manifest, rel);
                manifest.save(mapper, root);
                return;
            }
            if (!IndexFileFilter.includeFile(absFile, exts)) {
                // Became ineligible (or never was): drop if previously indexed.
                if (manifest.files.containsKey(rel)) {
                    removeFileUnlocked(manifest, rel);
                    manifest.save(mapper, root);
                }
                return;
            }
            long size = Files.size(absFile);
            if (size > props.safeMaxFileBytes()) {
                if (manifest.files.containsKey(rel)) {
                    removeFileUnlocked(manifest, rel);
                    manifest.save(mapper, root);
                }
                return;
            }
            byte[] bytes = Files.readAllBytes(absFile);
            String hash = sha256(bytes);
            CodeIndexManifest.FileEntry prev = manifest.files.get(rel);
            if (prev != null && hash.equals(prev.hash)) {
                return;
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            indexFileUnlocked(manifest, root, rel, hash, content);
            manifest.save(mapper, root);
            log.debug("code-index upserted {}", rel);
        }
    }

    void notifyFileDeleted(Path root, Path absFile) throws Exception {
        if (!absFile.startsWith(root)) {
            return;
        }
        if (!readyForMutation()) {
            return;
        }
        String rel = root.relativize(absFile).toString().replace('\\', '/');
        synchronized (lock) {
            CodeIndexManifest manifest = prepareManifest(root);
            if (!manifest.files.containsKey(rel)) {
                return;
            }
            removeFileUnlocked(manifest, rel);
            manifest.save(mapper, root);
            log.debug("code-index removed {}", rel);
        }
    }

    public SearchOutcome search(Path workspace, String query) throws Exception {
        if (!props.safeEnabled()) {
            return SearchOutcome.error(
                    "code_search is disabled (zcode.code-index.enabled=false). Use grep/glob instead.");
        }
        if (query == null || query.isBlank()) {
            return SearchOutcome.error("query is required");
        }
        Path root = workspace.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return SearchOutcome.error("workspace is not a directory: " + root);
        }

        try {
            qdrant.ping();
        } catch (Exception e) {
            return SearchOutcome.error(e.getMessage() + " Use grep/glob meanwhile.");
        }

        try {
            embeddingClient.requireConfigured();
        } catch (Exception e) {
            return SearchOutcome.error(e.getMessage() + " Use grep/glob meanwhile.");
        }

        SyncReport sync;
        try {
            sync = syncStale(root);
        } catch (Exception e) {
            return SearchOutcome.error("index sync failed: " + e.getMessage() + " Use grep/glob meanwhile.");
        }

        CodeIndexManifest manifest;
        synchronized (lock) {
            manifest = CodeIndexManifest.load(mapper, root);
        }
        if (manifest.collection == null || manifest.collection.isBlank() || manifest.vectorSize <= 0) {
            String note = sync.note();
            return SearchOutcome.error(
                    "index is empty or incomplete"
                            + (note.isBlank() ? "" : " (" + note + ")")
                            + ". Try again after files are indexed, or use grep/glob.");
        }

        List<float[]> qVec;
        try {
            qVec = embeddingClient.embed(List.of(query.trim()));
        } catch (Exception e) {
            return SearchOutcome.error("embedding failed: " + e.getMessage() + " Use grep/glob meanwhile.");
        }

        List<QdrantClient.SearchHit> hits;
        try {
            hits = qdrant.search(manifest.collection, qVec.getFirst(), props.safeTopK());
        } catch (Exception e) {
            return SearchOutcome.error("Qdrant search failed: " + e.getMessage() + " Use grep/glob meanwhile.");
        }

        return SearchOutcome.ok(hits, sync);
    }

    SyncReport syncStale(Path root) throws Exception {
        long deadline = System.currentTimeMillis() + props.safeMaxSyncMs();
        int budget = props.safeMaxFilesPerSync();
        Set<String> exts = extensionSet();

        Map<String, String> disk = scanWorkspace(root, exts);
        synchronized (lock) {
            CodeIndexManifest manifest = prepareManifest(root);

            List<String> toDelete = new ArrayList<>();
            for (String path : new ArrayList<>(manifest.files.keySet())) {
                if (!disk.containsKey(path)) {
                    toDelete.add(path);
                }
            }
            for (String path : toDelete) {
                removeFileUnlocked(manifest, path);
            }

            List<String> stale = new ArrayList<>();
            for (Map.Entry<String, String> e : disk.entrySet()) {
                CodeIndexManifest.FileEntry prev = manifest.files.get(e.getKey());
                if (prev == null || prev.hash == null || !prev.hash.equals(e.getValue())) {
                    stale.add(e.getKey());
                }
            }

            int updated = 0;
            int remaining = stale.size();
            boolean budgetHit = false;
            for (String rel : stale) {
                if (updated >= budget || System.currentTimeMillis() > deadline) {
                    budgetHit = true;
                    remaining = stale.size() - updated;
                    break;
                }
                Path file = root.resolve(rel);
                String hash = disk.get(rel);
                String content = Files.readString(file, StandardCharsets.UTF_8);
                indexFileUnlocked(manifest, root, rel, hash, content);
                updated++;
            }

            manifest.save(mapper, root);

            String note = "";
            if (budgetHit) {
                note = "index sync budget hit: updated " + updated + " file(s), ~" + remaining
                        + " still stale — results may be incomplete";
            } else if (updated > 0 || !toDelete.isEmpty()) {
                note = "index sync: updated " + updated + ", removed " + toDelete.size();
            }
            return new SyncReport(updated, toDelete.size(), Math.max(0, stale.size() - updated), note);
        }
    }

    private boolean readyForMutation() {
        try {
            qdrant.ping();
            embeddingClient.requireConfigured();
            return true;
        } catch (Exception e) {
            log.debug("code-index mutation skipped (backend not ready): {}", e.getMessage());
            return false;
        }
    }

    private CodeIndexManifest prepareManifest(Path root) throws Exception {
        CodeIndexManifest manifest = CodeIndexManifest.load(mapper, root);
        manifest.workspace = root.toString();
        manifest.collection = collectionName(root);
        manifest.embedModel = embeddingClient.resolveModel();
        return manifest;
    }

    private void indexFileUnlocked(
            CodeIndexManifest manifest, Path root, String rel, String hash, String content) throws Exception {
        String collection = manifest.collection;
        List<CodeChunk> chunks = CodeChunker.chunk(
                rel,
                content,
                props.safeSmallFileMaxLines(),
                props.safeWindowLines(),
                props.safeOverlapLines());
        if (chunks.isEmpty()) {
            removeFileUnlocked(manifest, rel);
            return;
        }

        removePointsOnly(manifest, collection, rel);

        List<float[]> vectors = embedBatched(chunks.stream().map(CodeChunk::text).toList());
        if (manifest.vectorSize <= 0) {
            manifest.vectorSize = vectors.getFirst().length;
        }
        qdrant.ensureCollection(collection, manifest.vectorSize);

        List<QdrantClient.Point> points = new ArrayList<>(chunks.size());
        List<String> pointIds = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            CodeChunk c = chunks.get(i);
            UUID id = pointId(collection, c.relativePath(), c.startLine(), c.endLine());
            pointIds.add(id.toString());
            points.add(new QdrantClient.Point(
                    id, vectors.get(i), c.relativePath(), c.startLine(), c.endLine(), c.text(), hash));
        }
        qdrant.upsert(collection, points);

        CodeIndexManifest.FileEntry entry = new CodeIndexManifest.FileEntry();
        entry.hash = hash;
        entry.pointIds = pointIds;
        manifest.files.put(rel, entry);
    }

    private void removeFileUnlocked(CodeIndexManifest manifest, String rel) {
        removePointsOnly(manifest, manifest.collection, rel);
        manifest.files.remove(rel);
    }

    private void removePointsOnly(CodeIndexManifest manifest, String collection, String rel) {
        CodeIndexManifest.FileEntry prev = manifest.files.get(rel);
        if (prev == null || prev.pointIds == null || prev.pointIds.isEmpty()) {
            return;
        }
        List<UUID> ids = new ArrayList<>();
        for (String id : prev.pointIds) {
            try {
                ids.add(UUID.fromString(id));
            } catch (Exception ignored) {
                // skip
            }
        }
        if (ids.isEmpty() || collection == null || collection.isBlank()) {
            return;
        }
        try {
            qdrant.deletePoints(collection, ids);
        } catch (Exception ignored) {
            // collection may not exist yet
        }
    }

    private Set<String> extensionSet() {
        return props.safeExtensions().stream()
                .map(s -> s.toLowerCase(Locale.ROOT).replace(".", ""))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<float[]> embedBatched(List<String> texts) throws Exception {
        // Bailian text-embedding-v4 batch limit is 10; keep headroom for other providers.
        final int batch = 10;
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += batch) {
            List<String> slice = texts.subList(i, Math.min(texts.size(), i + batch));
            out.addAll(embeddingClient.embed(slice));
        }
        return out;
    }

    private Map<String, String> scanWorkspace(Path root, Set<String> exts) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        int maxBytes = props.safeMaxFileBytes();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (IndexFileFilter.skipDirectory(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.size() > maxBytes) {
                    return FileVisitResult.CONTINUE;
                }
                if (!IndexFileFilter.includeFile(file, exts)) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    String rel = root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
                    byte[] bytes = Files.readAllBytes(file);
                    out.put(rel, sha256(bytes));
                } catch (Exception ignored) {
                    // skip unreadable
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    static String collectionName(Path workspace) {
        String abs = workspace.toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        String hex = sha256(abs.getBytes(StandardCharsets.UTF_8));
        return "zcode_" + hex.substring(0, 16);
    }

    static UUID pointId(String collection, String path, int startLine, int endLine) {
        String key = collection + "|" + path.replace('\\', '/') + "|" + startLine + "|" + endLine;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record SyncReport(int updated, int removed, int stillStale, String note) {}

    public record SearchOutcome(boolean ok, String error, List<QdrantClient.SearchHit> hits, SyncReport sync) {
        static SearchOutcome ok(List<QdrantClient.SearchHit> hits, SyncReport sync) {
            return new SearchOutcome(true, null, hits, sync);
        }

        static SearchOutcome error(String msg) {
            return new SearchOutcome(false, msg, List.of(), null);
        }
    }
}
