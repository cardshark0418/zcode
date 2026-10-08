package com.zcode.index;

/** One embeddable slice of a workspace file (1-based line range). */
public record CodeChunk(String relativePath, int startLine, int endLine, String text) {}
