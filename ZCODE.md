# zcode

Java coding-agent CLI for this repo. You may edit this repo in-place (dogfood like Cursor).

## Conventions

- Prefer small, focused changes matching existing style.
- Do not commit secrets (`application-local.yml` api keys). Use `application-local.yml.example` as a template.
- **Web UI** (`src/main/resources/static/**`): with `zcode serve` running, refresh the browser — files are read from disk.
- **Java**: after changes run `bin/install.ps1`, then restart `zcode serve`.
- Load skill `zcode-dev` when modifying zcode itself.
- Workspace tools must stay inside the project unless the user asks otherwise.

## Commands

- `zcode` — interactive agent CLI
- `zcode serve` — web UI on http://localhost:8080
