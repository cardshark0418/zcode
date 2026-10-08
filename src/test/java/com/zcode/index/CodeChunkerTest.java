package com.zcode.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CodeChunkerTest {

    @Test
    void smallFileIsSingleChunk() {
        String content = "a\nb\nc\n";
        List<CodeChunk> chunks = CodeChunker.chunk("A.java", content, 400, 200, 40);
        assertEquals(1, chunks.size());
        assertEquals(1, chunks.getFirst().startLine());
        assertEquals(3, chunks.getFirst().endLine());
        assertEquals("A.java", chunks.getFirst().relativePath());
    }

    @Test
    void largeFileUsesWindows() {
        String content = IntStream.rangeClosed(1, 500).mapToObj(i -> "L" + i).collect(Collectors.joining("\n"));
        List<CodeChunk> chunks = CodeChunker.chunk("Big.java", content, 400, 200, 40);
        assertTrue(chunks.size() > 1);
        assertEquals(1, chunks.getFirst().startLine());
        assertEquals(200, chunks.getFirst().endLine());
        // step = 160 → second window starts at line 161
        assertEquals(161, chunks.get(1).startLine());
        assertEquals(500, chunks.getLast().endLine());
    }

    @Test
    void collectionNameIsStable() {
        String a = CodeIndexService.collectionName(java.nio.file.Path.of("D:/aZhangChen/zcode"));
        String b = CodeIndexService.collectionName(java.nio.file.Path.of("D:/aZhangChen/zcode"));
        assertEquals(a, b);
        assertTrue(a.startsWith("zcode_"));
        assertEquals(22, a.length()); // zcode_ + 16 hex
    }
}
