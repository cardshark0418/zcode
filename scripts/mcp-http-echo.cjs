#!/usr/bin/env node
/**
 * Minimal Streamable-HTTP MCP echo server for zcode tests (.cjs = CommonJS).
 *
 *   node scripts/mcp-http-echo.cjs [port]
 */
"use strict";

const http = require("http");
const PORT = parseInt(process.argv[2] || "8765", 10);
const PROTOCOL = "2024-11-05";
const sessions = new Set();

function sendJson(res, status, obj, sessionId) {
  const body = Buffer.from(JSON.stringify(obj), "utf8");
  const headers = {
    "Content-Type": "application/json",
    "Content-Length": body.length,
  };
  if (sessionId) headers["Mcp-Session-Id"] = sessionId;
  res.writeHead(status, headers);
  res.end(body);
}

const server = http.createServer((req, res) => {
  if (req.method === "DELETE") {
    const sid = req.headers["mcp-session-id"];
    if (sid) sessions.delete(sid);
    res.writeHead(204);
    res.end();
    return;
  }
  if (req.method !== "POST") {
    res.writeHead(405);
    res.end();
    return;
  }
  let buf = "";
  req.on("data", (c) => (buf += c));
  req.on("end", () => {
    let msg;
    try {
      msg = JSON.parse(buf || "{}");
    } catch {
      sendJson(res, 400, { jsonrpc: "2.0", id: null, error: { code: -32700, message: "parse error" } });
      return;
    }
    const { id, method, params } = msg;
    const auth = req.headers["authorization"] || "";
    if (process.env.ZCODE_MCP_HTTP_AUTH === "1" && auth !== "Bearer test-token") {
      sendJson(res, 401, { jsonrpc: "2.0", id, error: { code: -32000, message: "unauthorized" } });
      return;
    }

    if (method === "initialize") {
      const sid = "sess-" + Math.random().toString(16).slice(2);
      sessions.add(sid);
      sendJson(
        res,
        200,
        {
          jsonrpc: "2.0",
          id,
          result: {
            protocolVersion: PROTOCOL,
            capabilities: { tools: {} },
            serverInfo: { name: "zcode-http-echo", version: "0.1.0" },
          },
        },
        sid
      );
      return;
    }
    if (method === "notifications/initialized") {
      res.writeHead(202);
      res.end();
      return;
    }
    if (method === "tools/list") {
      sendJson(res, 200, {
        jsonrpc: "2.0",
        id,
        result: {
          tools: [
            {
              name: "echo",
              description: "Echo text over HTTP MCP",
              inputSchema: {
                type: "object",
                properties: { text: { type: "string" } },
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
      sendJson(res, 200, {
        jsonrpc: "2.0",
        id,
        result: { content: [{ type: "text", text: String(text) }], isError: false },
      });
      return;
    }
    if (method === "ping") {
      sendJson(res, 200, { jsonrpc: "2.0", id, result: {} });
      return;
    }
    sendJson(res, 200, {
      jsonrpc: "2.0",
      id,
      error: { code: -32601, message: "Method not found: " + method },
    });
  });
});

server.listen(PORT, "127.0.0.1", () => {
  process.stderr.write("mcp-http-echo listening on http://127.0.0.1:" + PORT + "/\n");
});
