---
name: mcp-setup
description: >-
  Discover and connect ANY MCP server in zcode (stdio or remote HTTP).
  Use when the user asks to connect/configure an MCP (GitHub, Figma, Slack,
  Notion, filesystem, …) — not only pre-known ones. Self-serve via mcp_manage.
---

# MCP 自助配置（任意平台）

目标：用户说「连 X 的 MCP」时，**自己查清正确连法再配**，不要默认 `npx`，也不要只背 GitHub 一种。

## 能力边界（先认清再动手）

| zcode 支持 | 暂不支持 |
|------------|----------|
| **stdio**：`command` + `args` + `env` / `cwd` | OAuth / 浏览器弹窗登录 |
| **HTTP Streamable**：`url` + `headers` / `bearerToken` | 把普通网页 URL 当 MCP |
| `${ENV}` 展开（headers / bearerToken / env 值） | |

- 配置：`<workspace>/.zcode/mcp.json`（可对照仓库根 `mcp.json.example`）
- 热加载：`mcp_manage` → `status` / `upsert` / `remove` / `reload`
- 工具名：`mcp__{server}__{tool}`
- Token：没有就 `ask_user`；**禁止编造**；优先 `${ENV}`，勿把 PAT 明文写进对话可回放处

## 发现流程（必须按序，适用于任意平台）

### 0. 本地先看

1. `mcp_manage` `status`
2. `read` `.zcode/mcp.json` 与 `mcp.json.example`
3. 本 skill 加载时会附带同目录 `catalog.md`（已知远程端点速查；**没有条目 ≠ 不能连**，继续下面流程）

### 1. 给平台分类（决定优先传输）

| 类型 | 例子 | 优先尝试 |
|------|------|----------|
| **云 SaaS / 官方托管 MCP** | GitHub、Figma、Notion、Linear、Sentry… | **远程 HTTP**（`url` + Bearer）。官方若 **仅 OAuth** → 如实说明 zcode 暂不能完整复刻，不要死磕 PAT |
| **本地工具 / 仓库内 server** | filesystem、git、自写 echo | **stdio**；优先已安装命令（`node`/`uvx`/`docker` 绝对路径），**避免**首次 `npx -y`（易卡满超时） |
| **社区/自托管** | 第三方镜像、内网 URL | 文档里写的 `url` 或 docker/stdio 命令 |

经验法则：名字是一个**在线产品** → 先找 **hosted / remote MCP URL**；名字是一个**本地能力** → 再 stdio。

### 2. 查「正确连法」（搜索策略）

不要搜笼统的 `MCP 是什么`。按下面关键词 **webfetch 官方文档**，websearch 只作线索：

1. `"{Product} MCP server" official` / `"{Product} remote MCP"` / `"{Product} modelcontextprotocol"`
2. `site:github.com {product} mcp`（看 README 的 **Config** / **Cursor** / **Claude Desktop** JSON）
3. 厂商 docs：`developers.{product}.com`、`docs.{product}.com`、`mcp.{product}.com`
4. MCP 目录/注册表线索：`modelcontextprotocol/servers`、厂商「MCP」文档页
5. 从别人贴的 **mcp.json / claude_desktop_config** 片段提取：`command`/`args` **或** `url` + header 名

读文档时只认：

- `url`（https://…/mcp）→ HTTP upsert
- `command` + `args`（及 `env`）→ stdio upsert
- `Authorization` / `Bearer` / 某 `*_TOKEN` 环境变量名 → 记到 `bearerToken` 或 `headers` / `env`

**忽略**：纯营销页、无关中文问答、把「设计文件链接 / 仓库网页」当成 MCP endpoint。

### 3. 选定一种传输后一次配准

**远程 HTTP**

```json
{
  "action": "upsert",
  "name": "<short-id>",
  "url": "https://…/mcp",
  "bearerToken": "${SOME_TOKEN_ENV}"
}
```

或 `headers`: `{ "Authorization": "Bearer ${SOME_TOKEN_ENV}" }` / 文档要求的其它头（如 `X-…-Token`）。

**stdio**

```json
{
  "action": "upsert",
  "name": "<short-id>",
  "command": "node|uvx|docker|绝对路径",
  "args": ["…"],
  "env": { "SOME_TOKEN": "${SOME_TOKEN_ENV}" }
}
```

Windows：若必须用 npm 包，用可解析的 `npx.cmd` 绝对路径；**第一次** `npx -y` 下载失败/超时时，立刻改走 HTTP 或本地已缓存包，**禁止**同一 stdio 命令连 upsert 三次。

### 4. 验证与失败切换（关键）

1. upsert/reload 后看 `ok=true` 且 `tools>0`，再调 `mcp__*`
2. `ok=false` 时 **读 status 里的 message**，按表切换，不要盲重试：

| 失败信号 | 下一步 |
|----------|--------|
| 超时 / timed out / npx 挂起 | 放弃该 stdio；改查 **remote URL**；或改用已安装二进制/`uvx` 且包已缓存 |
| HTTP 401/403 | Token 错或需要 OAuth → `ask_user` 要正确密钥；若文档写 OAuth-only → 停止并说明 |
| CreateProcess / 找不到命令 | 补绝对路径或换 `cmd /c`；仍失败则换传输 |
| 连接成功但 0 tools | `tools/list` 异常；核对 URL 是否真是 MCP（不是官网首页） |

3. 同平台 **最多尝试 2 种不同连法**（例如 HTTP 一次 + stdio 一次）；仍不行 → 汇总文档依据与错误原文，问用户要缺失信息（token、自托管 URL），不要无限 websearch。

## 安全

- `default` 下 `mcp_manage` / `mcp__*` 可能要确认。
- Token 放 env / `${ENV}`；不要提交 git；不要在 ask_user 问题里回显完整 secret。

## 与 Cursor 的差别（对用户说明时用）

Cursor「贴链接就能用」= IDE **已经连好并登录** 该 MCP；链接多半是工具参数。  
zcode 必须先有 `mcp.json` 里可握手的 server，才会出现 `mcp__*`。
