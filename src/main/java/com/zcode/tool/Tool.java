package com.zcode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public interface Tool {

    String name();

    String description();

    /** JSON Schema object for Anthropic {@code input_schema}. */
    JsonNode inputSchema(ObjectMapper mapper);

    ToolResult execute(JsonNode input, ToolContext ctx) throws Exception;
}
