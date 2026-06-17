# Codex 插件 × 本仓库 skills：执行清单（对应排查计划）

仓库侧已校验：**14** 个 `.agents/skills/*/SKILL.md` 均含唯一 `name` 与 `description`（PowerShell 校验输出：`VALID: 14 skills`）。

硬验收只能在 **Cursor → Codex 插件面板** 内由你完成（下列勾选）。

---

## 1）fresh-session：新开会话（勿 Resume 旧任务）

- [ ] 在 Codex 侧栏 **新建任务/新对话**，不要打开此前「排查 skill…」等长跑旧线程（避免陈旧技能上下文，参见 openai/codex#16607）。
- [ ] 在输入框依次试：`/skills`、`$check-fronted`、`$glyahh-pptx`。

**硬验收判断**：模型回复明显遵循对应 [`SKILL.md`](../.agents/skills) 中的流程（例如 check-fronted 会走前端审计步骤）。

---

## 2）reload-workspace：重载与工作区根

- [ ] 命令面板执行 **`Developer: Reload Window`**。
- [ ] 仍异常则 **完全退出 Cursor 再启动**。
- [ ] 使用 **文件 → 打开文件夹**，根目录为 **`glyahh-ai-generate-code`**（必须能看见仓库根下的 `.agents/skills`）。
- [ ] 若是 **multi-root 工作区**，改为 **仅打开该单仓库** 再测一遍。

---

## 3）cli-vs-extension：CLI 与扩展对照（可选但强烈推荐）

本机若 **未安装** Codex CLI：可到 [Codex 官方文档](https://developers.openai.com/codex) 按指引安装 CLI，然后在仓库根执行：

```powershell
cd "d:\mainJava\all Code\program\glyahh-ai-generate-code"
codex --version
# 若子命令支持列出 skills，对比扩展侧栏数量（扩展少于 CLI 时更像 openai/codex#14785）
```

- [ ] 已对比 CLI 与扩展可见技能数（或注明本机未装 CLI）。

---

## 4）upstream-feedback：上游反馈（扩展长期偏短时）

- [ ] Cursor / Codex 扩展升级到最新。
- [ ] 在 Codex 内使用 **`/feedback`** 上传日志。
- [ ] 复制 [`.cursor/codex-github-issue-draft.md`](codex-github-issue-draft.md) 填好后发到 [openai/codex issues](https://github.com/openai/codex/issues)（避免重复可先搜索 Cursor + skills）。

---

## 5）fallback-cursor-commands：临时兜底（不计入硬验收）

插件仍失败时，在 **Cursor 主对话**（非 Codex）输入 `/`，使用 [`.cursor/commands/`](commands) 中同名命令，助手会按 `.agents/skills/.../SKILL.md` 执行。**插件一旦可用，应以 Codex 内 `$name` / `/skills` 为准。**

---

## 仓库内 skill 的 YAML `name`（供 `$` 引用）

| 文件夹 | `$` 引用 |
|--------|----------|
| check-fronted | `$check-fronted` |
| copy-element | `$copy-element` |
| cr-information | `$cr-information` |
| debug | `$debug` |
| deploy | `$deploy` |
| github-search | `$github-search` |
| git_commit | `$git_commit` |
| humanizer | `$humanizer` |
| humanizer_codex | `$humanizer_codex` |
| new-techno | `$new-techno` |
| pptx | `$glyahh-pptx` |
| search | `$search` |
| ui-ux-pro-max | `$ui-ux-pro-max` |
| xue-xi-tong | `$xue-xi-tong` |
