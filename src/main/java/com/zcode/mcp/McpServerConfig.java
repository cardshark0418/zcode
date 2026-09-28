package com.zcode.mcp;

import java.util.List;
import java.util.Map;

/**
 * One MCP server entry from {@code .zcode/mcp.json}.
 *
 * <p>Stdio: {@code command} (+ optional {@code args}/{@code env}/{@code cwd}).
 * HTTP: {@code url} (+ optional {@code headers} / {@code bearerToken}).
 */
public record McpServerConfig(
        String name,
        String command,
        List<String> args,
        Map<String, String> env,
        String cwd,
        String url,
        Map<String, String> headers,
        String bearerToken,
        boolean disabled
) {
    public McpServerConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("mcp server name required");
        }
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        boolean stdio = command != null && !command.isBlank();
        boolean http = url != null && !url.isBlank();
        if (!disabled && !stdio && !http) {
            throw new IllegalArgumentException(
                    "mcp server '" + name + "' needs command (stdio) or url (http)");
        }
        if (!disabled && stdio && http) {
            throw new IllegalArgumentException(
                    "mcp server '" + name + "' cannot set both command and url");
        }
    }

    public boolean isHttp() {
        return url != null && !url.isBlank();
    }

    public boolean isStdio() {
        return command != null && !command.isBlank();
    }
}
