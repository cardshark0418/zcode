# zcode

Java coding agent CLI（Spring Boot）：终端里对话、调工具改代码，带会话记忆、权限模式和运行时轨迹。

## Features

- **Agent loop** — Anthropic Messages + function calling
- **Tools** — bash, read/write/edit, glob/grep, code_search (semantic, Qdrant), websearch/webfetch, todowrite, ask_user, skill, mcp
- **Memory** — JSONL sessions under `<workspace>/.zcode/sessions`，自动/手动压缩
- **Permissions** — `chat` · `read-only` · `default` · `auto-confirm`（Shift+Tab 切换）
- **Trace** — `.zcode/sessions/<id>.events.jsonl`，CLI `/trace`
- **Instructions / skills** — `ZCODE.md` / `CLAUDE.md` / `AGENTS.md`，以及 `.zcode/skills/`
- **Headless `run`** — `zcode run --workspace <dir> --prompt-file prompt.md`（评测/脚本用）
- **Eval suite** — `eval/` 固定 20 道 Java 小任务，见 [eval/README.md](eval/README.md)

可选：`zcode serve` 打开 Web 控制台（对标 harness 风格：侧栏会话、流式对话、工具行、权限模式、人机确认）。

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
.\bin\zcode.cmd
.\bin\zcode.cmd serve
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
| `ZCODE_QDRANT_URL` | 本机 Qdrant（`code_search`） | `http://127.0.0.1:6333` |
| `ZCODE_EMBED_BASE_URL` | Embedding API base（OpenAI 兼容） | 空则回退 LLM base-url |
| `ZCODE_EMBED_API_KEY` | Embedding API Key | 空则回退 `ZCODE_LLM_API_KEY` |
| `ZCODE_EMBED_MODEL` | Embedding 模型名 | `text-embedding-3-small` |
| `ZCODE_CODE_INDEX_ENABLED` | 是否启用 `code_search` | `true` |

`code_search`：自然语言语义检索工作区代码。需本机 [Qdrant](https://qdrant.tech/) + embedding API；索引清单在 `<workspace>/.zcode/code-index.json`。搜前按文件 hash 增量同步（有文件数/时间预算）；`write`/`edit`/`delete` 成功后异步单文件更新向量。失败时请用 `grep`/`glob`。

### 启动本机 Qdrant（Docker）

先打开 **Docker Desktop**，再在仓库根目录：

```powershell
powershell -ExecutionPolicy Bypass -File .\bin\qdrant-up.ps1
# 或: docker compose up -d
```

停止：`.\bin\qdrant-down.ps1` 或 `docker compose down`。数据目录 `data/qdrant/`（已 gitignore）。默认 URL：`http://127.0.0.1:6333`。

本地覆盖也可使用 `src/main/resources/application-local.yml`（见 example，**不要提交**）。

## CLI commands

| 命令 | 作用 |
|------|------|
| `.\bin\zcode.cmd` | 交互式 agent |
| `.\bin\zcode.cmd serve` | 网页 http://localhost:8080 |
| `.\bin\zcode.cmd run --workspace <dir> --prompt-file <file>` | 无头单轮（eval） |
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
