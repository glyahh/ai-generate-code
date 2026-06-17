# check_git_code

按本仓库 Skill 执行：请先完整阅读并严格遵循以下 SKILL 文档（任一即可，内容已镜像同步）：

- `.agents/skills/check_git_code/SKILL.md`（Codex 路径）
- `.cursor/skills/check_git_code/SKILL.md`（Cursor 路径）

再处理用户后续请求。

**审查范围（强制）**

- 仅 `git add` 后的**暂存区**：`git diff --staged` / `git diff --cached`
- 不审查未暂存、未跟踪文件；暂存区为空则提示先 `git add` 并停止

**输出（强制）**

- 报告开头用 **5W**（Who / What / When / Where / Why）说明暂存变更
- Step 1 可联动 `.agents/skills/git_commit/SKILL.md`，但 diff 采集仍只限 `--staged`

- 在 Codex 输入框可显式提及：`$check_git_code`
- 在 Cursor 中可显式调用：`/check_git_code`
