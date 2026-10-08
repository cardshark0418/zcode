package com.zcode.index;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local path→hash (+ point ids) sidecar for stale checks. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodeIndexManifest {

    public String workspace;
    public String collection;
    public String embedModel;
    public int vectorSize;
    public Map<String, FileEntry> files = new LinkedHashMap<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileEntry {
        public String hash;
        public List<String> pointIds;
    }

    public static Path pathFor(Path workspace) {
        return workspace.resolve(".zcode").resolve("code-index.json");
    }

    public static CodeIndexManifest load(ObjectMapper mapper, Path workspace) throws Exception {
        Path file = pathFor(workspace);
        if (!Files.isRegularFile(file)) {
            CodeIndexManifest empty = new CodeIndexManifest();
            empty.workspace = workspace.toAbsolutePath().normalize().toString();
            empty.files = new LinkedHashMap<>();
            return empty;
        }
        CodeIndexManifest m = mapper.readValue(Files.readString(file), CodeIndexManifest.class);
        if (m.files == null) {
            m.files = new LinkedHashMap<>();
        }
        return m;
    }

    public void save(ObjectMapper mapper, Path workspace) throws Exception {
        Path file = pathFor(workspace);
        Files.createDirectories(file.getParent());
        ObjectMapper pretty = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(file, pretty.writeValueAsString(this));
    }
}
