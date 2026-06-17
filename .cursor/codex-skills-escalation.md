# Codex 技能：调用方式与仍不显示时的排查

本仓库以 **[`.agents/skills`](../.agents/skills)** 为唯一技能源（勿再在 `%USERPROFILE%\.agents\skills` 放同名副本，以免重复与撞名）。

## 在 Codex 内调用（官方口径）

- 列出/浏览：`/skills`
- 显式提及技能：`$<YAML 里的 name>`，例如 `$check-fronted`、`$glyahh-pptx`

本地 PPT 技能 YAML `name` 为 **`glyahh-pptx`**，与内置/Team 的 `PPTX` 区分，避免侧栏出现多条同名。

## 在 Cursor 主对话里用 `/`（Commands 镜像）

在 Cursor Chat/Agent 输入 `/`，选择与本仓库 [`.cursor/commands/`](commands) 下文件名对应的命令（如 `/check-fronted`）。Codex 侧栏的 `/` 菜单未必与 Cursor Commands 完全一致，以实际 UI 为准。

## 执行清单（计划落地）

完整分步勾选表见 **[`codex-plugin-skill-verification.md`](codex-plugin-skill-verification.md)**（含硬验收、`$name` 对照表、CLI 对照与 `/feedback`、GitHub issue 草稿链接）。

## 验收清单（摘要）

1. 工作区以本仓库**根目录**打开（含 `.agents/skills`）。
2. **新建** Codex 会话，`Developer: Reload Window` 后试 `/skills`、`$check-fronted`、`$glyahh-pptx`。
3. 若仍只有内置技能：升级扩展、`/feedback`，并按 [`codex-github-issue-draft.md`](codex-github-issue-draft.md) 提交 issue。
