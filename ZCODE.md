# zcode

Java coding-agent CLI for this repo.

## Conventions

- Prefer small, focused changes matching existing style.
- Do not commit secrets (`application-local.yml` api keys). Use `application-local.yml.example` as a template.
- After code changes that affect the installed CLI, rebuild with `bin/install.ps1`.
- Workspace tools must stay inside the project unless the user asks otherwise.

## Commands

- `zcode` — interactive agent CLI
- `zcode serve` — optional web UI (chat skeleton only)
