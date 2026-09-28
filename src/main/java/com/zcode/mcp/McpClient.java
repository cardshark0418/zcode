package com.zcode.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/** Transport-agnostic MCP session (stdio or HTTP). */
interface McpClient extends AutoCloseable {

    void initialize() throws Exception;

    JsonNode listTools() throws Exception;

    JsonNode callTool(String toolName, JsonNode arguments, long timeoutMs) throws Exception;

    boolean alive();

    @Override
    void close();
}
