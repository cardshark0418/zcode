package com.zcode.tool;

/**
 * Gate dangerous tools (e.g. bash in default mode). Return true to allow.
 */
@FunctionalInterface
public interface ToolApprover {

    boolean approve(String toolName, String summary);
}
