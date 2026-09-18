# zcode

Java coding agent CLI（Spring Boot）：终端里对话、调工具改代码，带会话记忆、权限模式和运行时轨迹。

## Features

- **Agent loop** — Anthropic Messages + function calling
- **Tools** — bash, read/write/edit, glob/grep, websearch/webfetch, todowrite, ask_user, skill
- **Memory** — JSONL sessions under `~/.zcode/sessions`，自动/手动压缩
- **Permissions** — `chat` · `read-only` · `default` · `auto-confirm`（Shift+Tab 切换）
- **Trace** — `~/.zcode/sessions/<id>.events.jsonl`，CLI `/trace`
- **Instructions / skills** — `ZCODE.md` / `CLAUDE.md` / `AGENTS.md`，以及 `.zcode/skills/`

可选：`zcode serve` 启动最小网页聊天（目前无 agent/工具，仅作演示）。

## Requirements

- Java 21+
- Maven 3.9+（或本机已配置的 `mvn`）
- Windows 推荐 PowerShell；安装脚本为 `bin/install.ps1`

## Quick start

```powershell
git clone <your-repo-url> zcode
cd zcode

# 配置 API Key（二选一）
$env:ZCODE_LLM_API_KEY = "your-key"
$env:ZCODE_LLM_BASE_URL = "https://api.anthropic.com"   # 或你的兼容网关
# 或复制 example 后编辑本地文件（已 gitignore，默认 profile=local 会自动加载）：
# copy src\main\resources\application-local.yml.example src\main\resources\application-local.yml

powershell -ExecutionPolicy Bypass -File .\bin\install.ps1
```

新开终端：

```powershell
zcode
```

### LLM 环境变量

| 变量 | 说明 | 默认 |
|------|------|------|
| `ZCODE_LLM_API_KEY` | API Key（必填） | 空 |
| `ZCODE_LLM_BASE_URL` | Anthropic-compatible base URL | 空（需自设） |
| `ZCODE_LLM_MODEL` | 模型名 | `claude-opus-4-6` |
| `ZCODE_LLM_API` | `anthropic`（agent 工具需要） | `anthropic` |
| `ZCODE_PERMISSION_MODE` | `chat` / `read-only` / `default` / `auto-confirm` | `auto-confirm` |
| `ZCODE_WORKSPACE` | 工具工作区；空=当前目录 | 空 |

本地覆盖也可使用 `src/main/resources/application-local.yml`（见 example，**不要提交**）。

## CLI commands

| 命令 | 作用 |
|------|------|
| `zcode` | 交互式 agent |
| `zcode serve` | 网页 http://localhost:8080 |
| `/help` | 帮助 |
| `/mode` · `/mode X` | 权限模式 |
| `/tools` · `/skills` · `/session` | 状态 |
| `/trace` · `/trace N` | 运行时事件 |
| `/new` · `/clear` · `/compact` | 会话 |
| `/exit` | 退出 |

输入框下可 **Shift+Tab** 循环权限模式。

## Development

```powershell
powershell -ExecutionPolicy Bypass -File .\bin\install.ps1
```

改完影响 CLI 的代码后请重新 install。约定见仓库内 `ZCODE.md`。

## License

MIT — see [LICENSE](LICENSE).
