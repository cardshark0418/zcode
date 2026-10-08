package com.zcode.index;

import java.util.ArrayList;
import java.util.List;

/** Split file text into chunks: whole file if small, else overlapping line windows. */
public final class CodeChunker {

    private CodeChunker() {}

    public static List<CodeChunk> chunk(
            String relativePath, String content, int smallFileMaxLines, int windowLines, int overlapLines) {
        List<String> lines = content.replace("\r\n", "\n").replace('\r', '\n').lines().toList();
        if (lines.isEmpty()) {
            return List.of();
        }
        String path = relativePath.replace('\\', '/');
        if (lines.size() <= smallFileMaxLines) {
            return List.of(new CodeChunk(path, 1, lines.size(), String.join("\n", lines)));
        }
        int window = Math.max(1, windowLines);
        int overlap = Math.min(Math.max(0, overlapLines), window - 1);
        int step = Math.max(1, window - overlap);
        List<CodeChunk> out = new ArrayList<>();
        for (int start = 0; start < lines.size(); start += step) {
            int end = Math.min(lines.size(), start + window);
            List<String> slice = lines.subList(start, end);
            out.add(new CodeChunk(path, start + 1, end, String.join("\n", slice)));
            if (end >= lines.size()) {
                break;
            }
        }
        return out;
    }
}
