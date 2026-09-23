package com.zcode.web;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.util.StringUtils;

/**
 * Windows Vista+ native “Open Folder” dialog (IFileDialog + FOS_PICKFOLDERS) via a
 * long-lived PowerShell worker so Add-Type / COM is warmed once — click → dialog is near-instant.
 */
final class FolderPicker {

    private static final Object LOCK = new Object();
    private static final AtomicBoolean WARMING = new AtomicBoolean(false);

    private static Process worker;
    private static BufferedWriter workerIn;
    private static BufferedReader workerOut;
    private static Path workerScript;

    private FolderPicker() {}

    /** Start the native picker worker in the background (serve only). */
    static void warmUp() {
        if (!isWindows()) {
            return;
        }
        if (!WARMING.compareAndSet(false, true)) {
            return;
        }
        Thread.startVirtualThread(
                () -> {
                    try {
                        ensureWorker();
                    } catch (Exception ignored) {
                        // first pick will retry
                    } finally {
                        WARMING.set(false);
                    }
                });
    }

    /**
     * @return absolute directory path, or {@code null} if the user cancelled
     */
    static Path pickDirectory(String initial) {
        synchronized (LOCK) {
            Path start = resolveStart(initial);
            if (isWindows()) {
                try {
                    return pickWindowsNative(start);
                } catch (Exception e) {
                    throw new IllegalStateException("无法打开文件夹对话框: " + e.getMessage(), e);
                }
            }
            throw new IllegalStateException("当前系统不支持原生文件夹对话框");
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static Path resolveStart(String initial) {
        if (StringUtils.hasText(initial)) {
            Path p = Path.of(initial.trim()).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) {
                return p;
            }
            Path parent = p.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                return parent;
            }
        }
        String userHome = System.getProperty("user.home");
        if (StringUtils.hasText(userHome) && Files.isDirectory(Path.of(userHome))) {
            return Path.of(userHome);
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    private static Path pickWindowsNative(Path start) throws Exception {
        ensureWorker();
        workerIn.write("PICK\t" + start + "\n");
        workerIn.flush();
        String line = workerOut.readLine();
        if (line == null) {
            stopWorker();
            throw new IllegalStateException("文件夹选择器进程已退出");
        }
        line = line.trim();
        if (line.equals("CANCELLED")) {
            return null;
        }
        if (line.startsWith("PICKED\t") || line.startsWith("PICKED ")) {
            String path = line.substring("PICKED".length()).trim();
            if (path.startsWith("\t")) {
                path = path.substring(1);
            }
            path = path.trim();
            if (path.isEmpty()) {
                return null;
            }
            Path picked = Path.of(path).toAbsolutePath().normalize();
            if (!Files.isDirectory(picked)) {
                throw new IllegalStateException("不是有效文件夹: " + picked);
            }
            return picked;
        }
        if (line.startsWith("ERROR")) {
            String msg = line.length() > 5 ? line.substring(5).trim() : "unknown";
            if (msg.startsWith("\t")) {
                msg = msg.substring(1);
            }
            throw new IllegalStateException(msg);
        }
        throw new IllegalStateException("文件夹选择器返回异常: " + line);
    }

    private static void ensureWorker() throws Exception {
        if (worker != null && worker.isAlive() && workerIn != null && workerOut != null) {
            return;
        }
        stopWorker();
        Path script = extractWorkerScript();
        ProcessBuilder pb =
                new ProcessBuilder(
                        "powershell",
                        "-NoProfile",
                        "-STA",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-File",
                        script.toAbsolutePath().toString());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        worker = pb.start();
        workerIn =
                new BufferedWriter(
                        new OutputStreamWriter(worker.getOutputStream(), StandardCharsets.UTF_8));
        workerOut =
                new BufferedReader(
                        new InputStreamReader(worker.getInputStream(), StandardCharsets.UTF_8));

        String ready = workerOut.readLine();
        if (ready == null || !ready.trim().equals("READY")) {
            String extra = ready == null ? "" : ready;
            // drain a bit of error output
            try {
                worker.waitFor(800, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
            stopWorker();
            throw new IllegalStateException("文件夹选择器未能启动" + (extra.isBlank() ? "" : ": " + extra));
        }
    }

    private static Path extractWorkerScript() throws Exception {
        if (workerScript != null && Files.isRegularFile(workerScript)) {
            return workerScript;
        }
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "zcode-folder-picker");
        Files.createDirectories(dir);
        Path script = dir.resolve("folder-picker-worker.ps1");
        try (var in = FolderPicker.class.getResourceAsStream("/folder-picker-worker.ps1")) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource folder-picker-worker.ps1");
            }
            Files.write(script, in.readAllBytes());
        }
        workerScript = script;
        return script;
    }

    private static void stopWorker() {
        try {
            if (workerIn != null) {
                try {
                    workerIn.write("QUIT\n");
                    workerIn.flush();
                } catch (Exception ignored) {
                }
            }
        } finally {
            try {
                if (workerIn != null) {
                    workerIn.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (workerOut != null) {
                    workerOut.close();
                }
            } catch (Exception ignored) {
            }
            if (worker != null) {
                worker.destroyForcibly();
            }
            worker = null;
            workerIn = null;
            workerOut = null;
        }
    }
}
