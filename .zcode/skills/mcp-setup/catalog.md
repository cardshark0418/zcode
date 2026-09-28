# MCP 已知端点速查（非完整列表）

Agent：**先读本表**，没有再走 SKILL.md 发现流程。可随时把新验证过的条目补进来。

| 平台 | 推荐传输 | 配置要点 | 注意 |
|------|----------|----------|------|
| **GitHub** | HTTP | `url`: `https://api.githubcopilot.com/mcp/`<br>`bearerToken`: `${GITHUB_PERSONAL_ACCESS_TOKEN}`（可用 `gh auth token`） | **不要**用 `npx @modelcontextprotocol/server-github`（易超时/包不可用） |
| **Figma** | HTTP | `url`: `https://mcp.figma.com/mcp` | 官方偏 **OAuth**；PAT / `X-Figma-Token` 常 401；zcode 暂无浏览器 OAuth |
| **本地 echo** | stdio | `command`: `node`<br>`args`: `["scripts/mcp-echo.mjs"]` | 仓库自带，验证管道用 |
| **HTTP echo** | HTTP | `url`: `http://127.0.0.1:8765/` | 先 `node scripts/mcp-http-echo.cjs 8765` |
| **filesystem**（官方参考） | stdio | `@modelcontextprotocol/server-filesystem` + 允许的根目录 | 优先全局/本地安装；避免冷 `npx -y` |
| **git**（参考） | stdio | `uvx mcp-server-git --repository <path>` | 需已装 `uvx`；首次拉包也可能慢 |

## 补充新平台时写什么

验证成功后追加一行：平台、url 或 command、env 名、坑（OAuth-only / 区域网络等）。
