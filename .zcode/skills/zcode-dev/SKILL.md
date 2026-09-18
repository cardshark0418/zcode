---
name: zcode-dev
description: How to build, install, and smoke-test the zcode Java CLI in this repo.
---

# zcode-dev

Use this skill when changing zcode itself.

## Build & install

```powershell
powershell -File D:\aZhangChen\zcode\bin\install.ps1
```

Installs fat jar to `%USERPROFILE%\.zcode\zcode.jar`.

## Layout

- `com.zcode.agent` — agent loop, instructions, skills
- `com.zcode.tool` — function-calling tools
- `com.zcode.cli` — terminal CUI
- `com.zcode.memory` — JSONL session + compaction
- `com.zcode.chat` — Anthropic/OpenAI HTTP client

## Quick checks

- `/tools` lists tools including `skill`
- Project instructions load from `ZCODE.md` / `CLAUDE.md` / `AGENTS.md`
- Skills live in `.zcode/skills/<name>/SKILL.md`
