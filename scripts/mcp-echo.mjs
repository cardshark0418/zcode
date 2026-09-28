#!/usr/bin/env node
/**
 * Minimal MCP stdio echo server for zcode smoke tests (Content-Length framing).
 *
 *   node scripts/mcp-echo.mjs
 */
"use strict";

const PROTOCOL = "2024-11-05";

function writeMessage(obj) {
  const body = Buffer.from(JSON.stringify(obj), "utf8");
  process.stdout.write(`Content-Length: ${body.length}\r\n\r\n`);
  process.stdout.write(body);
}

let buf = Buffer.alloc(0);

process.stdin.on("data", (chunk) => {
  buf = Buffer.concat([buf, chunk]);
  while (true) {
    const sep = findHeaderSep(buf);
    if (sep < 0) break;
    const header = buf.slice(0, sep.index).toString("ascii");
    const m = /Content-Length:\s*(\d+)/i.exec(header);
    if (!m) {
      buf = buf.slice(sep.index + sep.len);
      continue;
    }
    const len = parseInt(m[1], 10);
    const bodyStart = sep.index + sep.len;
    if (buf.length < bodyStart + len) break;
    const json = buf.slice(bodyStart, bodyStart + len).toString("utf8");
    buf = buf.slice(bodyStart + len);
    try {
      handle(JSON.parse(json));
    } catch (e) {
      process.stderr.write(String(e) + "\n");
    }
  }
});

function findHeaderSep(buffer) {
  for (let i = 0; i < buffer.length - 3; i++) {
    if (
      buffer[i] === 0x0d &&
      buffer[i + 1] === 0x0a &&
      buffer[i + 2] === 0x0d &&
      buffer[i + 3] === 0x0a
    ) {
      return { index: i, len: 4 };
    }
  }
  for (let i = 0; i < buffer.length - 1; i++) {
    if (buffer[i] === 0x0a && buffer[i + 1] === 0x0a) {
      return { index: i, len: 2 };
    }
  }
  return -1;
}

function handle(msg) {
  if (!msg || typeof msg !== "object") return;
  const { id, method, params } = msg;
  if (method === "initialize") {
    writeMessage({
      jsonrpc: "2.0",
      id,
      result: {
        protocolVersion: PROTOCOL,
        capabilities: { tools: {} },
        serverInfo: { name: "zcode-echo", version: "0.1.0" },
      },
    });
    return;
  }
  if (method === "notifications/initialized") return;
  if (method === "tools/list") {
    writeMessage({
      jsonrpc: "2.0",
      id,
      result: {
        tools: [
          {
            name: "echo",
            description: "Echo back the text argument",
            inputSchema: {
              type: "object",
              properties: {
                text: { type: "string", description: "Text to echo" },
              },
              required: ["text"],
            },
          },
        ],
      },
    });
    return;
  }
  if (method === "tools/call") {
    const text = (params && params.arguments && params.arguments.text) || "";
    writeMessage({
      jsonrpc: "2.0",
      id,
      result: {
        content: [{ type: "text", text: String(text) }],
        isError: false,
      },
    });
    return;
  }
  if (method === "ping") {
    writeMessage({ jsonrpc: "2.0", id, result: {} });
    return;
  }
  if (id !== undefined) {
    writeMessage({
      jsonrpc: "2.0",
      id,
      error: { code: -32601, message: "Method not found: " + method },
    });
  }
}
