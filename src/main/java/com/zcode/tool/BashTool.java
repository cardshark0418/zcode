package com.zcode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zcode.config.AgentProperties;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class BashTool implements Tool {

    private final AgentProperties agentProperties;

    public BashTool(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "Run a shell command in the workspace. Windows uses PowerShell (UTF-8); other OS use bash -lc. "
                + "Prefer this for git/npm/build. Do not use for reading/writing/deleting files — use read/write/edit/delete/glob/grep. "
                + "Commands that rewrite the working tree via shell/git are blocked so checkpoints stay reliable. "
                + "For Chinese text checks prefer grep/read on files instead of PowerShell -match with Chinese literals.";
    }

    @Override
    public JsonNode inputSchema(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("command").put("type", "string").put("description", "Shell command to run");
        root.putArray("required").add("command");
        return root;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext ctx) throws Exception {
        String command = text(input, "command");
        if (command == null || command.isBlank()) {
            return ToolResult.error("command is required");
        }
        String blocked = fileMutationBlockReason(command);
        if (blocked != null) {
            return ToolResult.error(blocked);
        }

        List<String> cmd = new ArrayList<>();
        Charset outCharset;
        if (isWindows()) {
            // -EncodedCommand is UTF-16LE base64 — Chinese in the script is not mangled by the console code page.
            // Force UTF-8 for pipeline / native exe output so Java can decode reliably.
            String script =
                    "$OutputEncoding = [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false; "
                            + "[Console]::InputEncoding = New-Object System.Text.UTF8Encoding $false; "
                            + "try { chcp 65001 | Out-Null } catch {}; "
                            + command;
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            cmd.add("powershell.exe");
            cmd.add("-NoProfile");
            cmd.add("-NonInteractive");
            cmd.add("-EncodedCommand");
            cmd.add(encoded);
            outCharset = StandardCharsets.UTF_8;
        } else {
            cmd.add("bash");
            cmd.add("-lc");
            cmd.add(command);
            outCharset = StandardCharsets.UTF_8;
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(ctx.workspace().toFile());
        pb.redirectErrorStream(true);
        if (isWindows()) {
            pb.environment().put("PYTHONIOENCODING", "utf-8");
        }
        Process process = pb.start();
        boolean finished = process.waitFor(agentProperties.safeBashTimeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return ToolResult.error("command timed out after " + agentProperties.safeBashTimeoutSeconds() + "s");
        }
        String out = readLimited(process.getInputStream(), ctx.maxOutputChars(), outCharset);
        int code = process.exitValue();
        String body = out.isEmpty() ? "(no output)" : out;
        if (code != 0) {
            return ToolResult.error("exit " + code + "\n" + body);
        }
        return ToolResult.ok(body);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Block shell/git commands that mutate workspace files outside write/edit/delete
     * (those are what checkpoints track).
     */
    static String fileMutationBlockReason(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String[] patterns = {
            "(?i)\\bgit\\s+(checkout|restore|reset|clean|stash\\s+pop|stash\\s+apply|apply)\\b",
            "(?i)\\b(Set-Content|Add-Content|Out-File|New-Item|Remove-Item|Move-Item|Copy-Item|Rename-Item|ni|ri|mi|cpi|rni)\\b",
            "(?i)(^|[;&|\\n])\\s*(rm|del|erase|rmdir|rd|mv|cp|chmod)\\b",
            "(?i)(?:>{1,2}|\\|\\s*Out-File)\\s*['\"]?[^\\s|&;]+",
            "(?i)\\btee\\b\\s+[^\\s|&;]+",
            "(?i)\\b(sed|perl|ruby|python|py|node)\\b[^\\n;|&]*\\s-i\\b"
        };
        for (String p : patterns) {
            if (java.util.regex.Pattern.compile(p).matcher(command).find()) {
                return "已拦截：请用 write/edit/delete 改文件（以便检查点可回滚），不要用 bash/git 直接改工作区。"
                        + " 构建/测试/git status|diff|log|add|commit 仍可用。";
            }
        }
        return null;
    }

    private static String text(JsonNode input, String field) {
        JsonNode n = input == null ? null : input.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static String readLimited(InputStream in, int maxChars, Charset preferred) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        in.transferTo(buf);
        byte[] bytes = buf.toByteArray();
        String primary = new String(bytes, preferred);
        if (preferred.equals(StandardCharsets.UTF_8)) {
            // If UTF-8 looks badly broken, fall back to platform default (legacy GBK consoles).
            String fallback = new String(bytes, Charset.defaultCharset());
            long badPrimary = primary.codePoints().filter(cp -> cp == 0xFFFD).count();
            long badFallback = fallback.codePoints().filter(cp -> cp == 0xFFFD).count();
            if (badPrimary > badFallback) {
                primary = fallback;
            }
        }
        if (primary.length() <= maxChars) {
            return primary;
        }
        return primary.substring(0, maxChars) + "\n...[truncated " + (primary.length() - maxChars) + " chars]";
    }
}
