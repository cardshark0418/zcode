package com.zcode.checkpoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Content-addressed blobs under {@code <session>.ckpt/blobs/<sha256>}.
 */
final class BlobStore {

    private final Path blobsDir;

    BlobStore(Path ckptDir) {
        this.blobsDir = ckptDir.resolve("blobs");
    }

    Path blobsDir() {
        return blobsDir;
    }

    String put(byte[] bytes) throws IOException {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes required");
        }
        String hash = sha256(bytes);
        Files.createDirectories(blobsDir);
        Path dest = blobsDir.resolve(hash);
        if (!Files.isRegularFile(dest)) {
            Files.write(dest, bytes);
        }
        return hash;
    }

    byte[] get(String hash) throws IOException {
        if (hash == null || hash.isBlank()) {
            return null;
        }
        Path dest = blobsDir.resolve(hash);
        if (!Files.isRegularFile(dest)) {
            throw new IOException("missing blob: " + hash);
        }
        return Files.readAllBytes(dest);
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
