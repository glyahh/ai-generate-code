# git_commit

按本仓库 skill 执行：请先完整阅读并严格遵循以下路径中的流程与约束（优先级从高到低）：

1. `.agents/skills/git_commit/SKILL.md`（Codex / 仓库主源）
2. `.claude/skills/git_commit/SKILL.md`（Claude Code 项目级）
3. `.cursor/skills/git_commit/SKILL.md`（Cursor 项目级）

定稿前可按 skill 要求联动 `.agents/skills/humanizer/SKILL.md` 润色说明性文字，且保留 diff 可追溯的技术锚点与专业性。

## 调用方式

| 环境 | 触发 |
|------|------|
| Cursor Chat/Agent | `/git_commit` |
| Codex 插件 | `$git_commit` 或 `/skills` 浏览 |
| Claude Code | 提及 `/git_commit` 或「写提交说明 / commit message / PR 描述」 |
