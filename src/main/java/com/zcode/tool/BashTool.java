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
        return "Run a shell command in the workspace. Windows uses PowerShell; other OS use bash -lc. "
                + "Prefer this for git/npm/build. Do not use for reading/writing/deleting files — use read/write/edit/delete/glob/grep.";
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

        List<String> cmd = new ArrayList<>();
        if (isWindows()) {
            cmd.add("powershell.exe");
            cmd.add("-NoProfile");
            cmd.add("-NonInteractive");
            cmd.add("-Command");
            cmd.add(command);
        } else {
            cmd.add("bash");
            cmd.add("-lc");
            cmd.add(command);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(ctx.workspace().toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(agentProperties.safeBashTimeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return ToolResult.error("command timed out after " + agentProperties.safeBashTimeoutSeconds() + "s");
        }
        String out = readLimited(process.getInputStream(), ctx.maxOutputChars());
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

    private static String text(JsonNode input, String field) {
        JsonNode n = input == null ? null : input.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static String readLimited(InputStream in, int maxChars) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        in.transferTo(buf);
        String raw = buf.toString(Charset.defaultCharset());
        // Prefer UTF-8 interpretation when valid enough.
        String utf8 = buf.toString(StandardCharsets.UTF_8);
        String text = utf8.codePoints().filter(cp -> cp != 0xFFFD).count() >= raw.codePoints().filter(cp -> cp != 0xFFFD).count()
                ? utf8
                : raw;
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n...[truncated " + (text.length() - maxChars) + " chars]";
    }
}
