package com.zcode.tool;

public record ToolResult(boolean ok, String output) {

    public static ToolResult ok(String output) {
        return new ToolResult(true, output == null ? "" : output);
    }

    public static ToolResult error(String message) {
        return new ToolResult(false, message == null ? "error" : message);
    }

    public String forModel() {
        if (ok) {
            return output;
        }
        return "ERROR: " + output;
    }
}
