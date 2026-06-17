# GitHub Issue 草稿（openai/codex）— Cursor 内嵌 Codex 未发现仓库 `.agents/skills`

标题建议：`[Cursor / VS Code extension] Repo .agents/skills not listed or not injectable vs CLI`

正文模板（请替换尖括号）：

---

**Environment**

- IDE: Cursor `<Cursor 版本>`
- Codex extension: `<扩展版本>`
- OS: Windows `<版本>`
- Repo root: `D:\mainJava\all Code\program\glyahh-ai-generate-code`
- Trust: repo marked `trusted` in `%USERPROFILE%\.codex\config.toml`

**Problem**

Local skills under `.agents/skills/` (14 folders, each with `SKILL.md` and YAML `name` + `description`) do not appear in the Codex sidebar / `$` autocomplete, or are not injected into the session. Sidebar may only show bundled `PPTX` / `Skill Installer`.

**Expected**

Same discovery as documented: repo-root `.agents/skills` scanned; `$<name>` works in extension input.

**What we tried**

- New Codex session (not resumed old thread)
- Reload Window / restart Cursor
- Single-folder workspace opened at repo root
- Skill names unique (including local pptx as `glyahh-pptx`)

**Optional**

- Codex CLI skill count vs extension count: CLI `<n>`, Extension `<m>` (if CLI installed)

**References**

- Possibly related: https://github.com/openai/codex/issues/14785 https://github.com/openai/codex/issues/16607

---
