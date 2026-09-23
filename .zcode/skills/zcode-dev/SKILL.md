---
name: zcode-dev
description: How to build, install, and dogfood the zcode Java agent in this repo (self-modify like Cursor).
---

# zcode-dev

Use this skill when changing **zcode itself** (workspace = this repo).

## Dogfooding (like Cursor)

- Default session workspace is already this repo. Edit source with `read` / `edit` / `write` / `bash` freely.
- **Web UI** (`src/main/resources/static/**`): `zcode serve` reads these from disk first — **refresh the browser** after edits (no install needed).
- **Java** (`src/main/java/**`): must rebuild the fat jar, then restart serve.

## Build & install

```powershell
powershell -File bin/install.ps1
```

Installs fat jar to `<repo>/.zcode/zcode.jar` (project-local).

Then restart serve (stop the old process, run again):

```powershell
.\bin\zcode.cmd serve
```

## Layout

- `com.zcode.agent` — agent loop, instructions, skills
- `com.zcode.tool` — function-calling tools
- `com.zcode.cli` — terminal CUI
- `com.zcode.web` — HTTP / SSE / static UI
- `com.zcode.memory` — JSONL session + compaction
- `com.zcode.chat` — Anthropic/OpenAI HTTP client
- `.zcode/skills/<name>/SKILL.md` — project skills (editable)

## Quick checks

- `/tools` lists tools including `skill`
- Project instructions load from `ZCODE.md` / `CLAUDE.md` / `AGENTS.md`
- After static edits: hard-refresh `http://localhost:8080`
- After Java edits: `bin/install.ps1` + restart `serve`
