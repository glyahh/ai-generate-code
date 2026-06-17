---
name: check_git_code
description: 提交前的只读代码审查：仅审查 git 暂存区（git add）变更，用 5W 精准说明修改内容，再对照 CLAUDE.md 查逻辑与根因级风险；/check_git_code 或 $check_git_code 唤起；Cursor 与 Codex 双兼容。默认不跑测试/不启动服务。
metadata:
  short-description: Staged-only git diff review with 5W summary
---

# check_git_code

用法：当用户说「提交前帮我检查 / 审查 git diff / 怕污染主分支 / 上传 GitHub 前把关」或 `/check_git_code`、`$check_git_code` 时使用。

## 目标

一般来说用这个 skill 都是在会话中已有的部分提示词后调用，执行时可结合上文提示词与**暂存区 diff** 推测修改意图（未 `git add` 的内容不在审查范围内）。
帮助用户提交**逻辑自洽、与仓库架构一致、可回溯**的变更，优先发现会污染线上或主分支的**根因级问题**（数据模型错位、静默迁移失败、配置与运行时行为不一致），而不是堆叠兜底补丁。

## 默认约束

- **只读审查**：不修改业务代码，除非用户明确要求修复。
- **不跑实测**：不 `mvn test`、不 `npm run dev/build`、不用 Playwright/浏览器，除非用户明确说「可以跑测试/启动服务」。
- **不新建测试**：不添加 `*Test.java` / 前端测试文件，除非用户明确要求。
- **只信 git 暂存区**：审查范围**仅限**已 `git add` 进入 index 的变更；以 `git diff --staged` / `git diff --cached` 为唯一 diff 来源。
- **不扫未暂存**：不审查工作区未暂存修改（`git diff` 无 `--staged`）、不审查未跟踪文件（`??`）；会话附件、截图、打开的文件列表**不能**扩大审查范围。
- **本地提交也算**：暂存区内容即使用户计划只本地 `commit`、不上传 GitHub，仍按同样规则审查。

## 工作流（必须按顺序）

### Step 1 — 变更采集（联动 git_commit，仅暂存区）

1. 完整阅读并遵循 `.agents/skills/git_commit/SKILL.md`（或用户全局 `git_commit` skill），但**采集命令只针对暂存区**。
2. 运行：
   - `git status`（区分 staged / unstaged / untracked，**仅 staged 进入后续步骤**）
   - `git diff --staged --name-only`（或 `git diff --cached --name-only`）
   - `git diff --staged`（完整 diff）
3. **暂存区为空时**：明确告知「当前无已暂存变更，请先 `git add <paths>` 后再审查」，**停止**后续 Step 2–4；不得改用 `git diff`（未暂存）或扫描未跟踪文件代替。
4. 用 **5W** 归纳暂存变更（见 Step 4 模板）；按模块分组列出路径。
5. **有歧义必问**：配置意图不明、暂存区是否误含 `application-local.yml`/密钥、数据迁移策略等——用 1–3 个具体问题向用户确认，**不得臆测**。

### Step 2 — 逻辑与疏漏审查

对**暂存区中的每个变更文件**（及直接调用方）：

- 读 `git diff --staged` 与必要上下文（调用链、数据结构、边界条件）。
- 检查：空指针/键名错位、迁移半完成、静默失败、并发与资源生命周期、与既有约定冲突。
- 区分 **P0（会错数据/错模型/破坏主链路）**、**P1（体验或运维风险）**、**P2（可后续改进）**。

### Step 3 — 架构对照（联动仓库文档）

1. 若存在根目录 `CLAUDE.md`，必读并与**暂存变更**对照（后端入口、Parser/Saver、Workflow、配置位与 `langchain4j.open-ai.*` 映射等）。
2. 暂存区含前端变更时，参考 `ai-generate-code-frontend/` 与 `frontend-design.md`（若存在）。
3. 给出**改进方案**：说明改哪里、为何能治本；避免「多加 try-catch」式建议。

### Step 4 — 输出报告（固定结构）

使用下列模板，**中文**、务实、每条可追溯到**暂存区** diff 或文件路径：

```text
【5W 变更说明】
（必须基于 git diff --staged，精准、可核对；禁止臆测未暂存文件）

- **Who（谁）**：变更主体/维护角色；涉及哪些层（Controller、Service、前端页面、配置、Skill 等）。
- **What（什么）**：暂存区具体改了什么（能力、接口、表结构、常量、行为）；按目录或模块分条，每条锚定 1+ 个已暂存路径。
- **When（何时）**：行为在何时/何触发点变化（如生成前、SSE 结束、加载记忆、提交前钩子）。
- **Where（哪里）**：变更落在哪些目录、运行时链路（如 /chat SSE、ChatHistoryService、workflowChatFilters）。
- **Why（为何）**：要解决的问题或目标；与改前对比一句话。

【暂存文件清单】
（来自 git diff --staged --name-only，与审查范围一致）

【提交范围建议】
- 本次审查覆盖的路径：（= 暂存清单；若无则写「无」）
- 建议从暂存区移除勿提交的路径：（密钥、__pycache__、.local、误 add 的大目录等；仅针对已在 index 中的项）

发现问题共 N 个

分布于文件夹 <path1>，大致功能是 -> <一句话>
…

具体说明:
1. [P0|P1|P2] <结论>: <根因与影响>（锚点：<暂存区内的文件或配置键>）
2. …

【架构对齐与改进方案】
1. …
2. …

【待你确认】
（仅 Step 1 未决项；无则写「无」）
```

- 5W 五段缺一不可；表述须**具体**（避免「优化了代码」「修复了 bug」等空泛句）。
- 编号从 1 递增；同一文件夹可合并为一条分布行。

### Step 5 — 可选后续（仅当用户要求）

- 用户说「帮我修」→ 最小改动修复 P0，再复述验证建议（仍可不跑测）。
- 用户说「写 commit」→ 转用 `git_commit` 产出 message，不重复审查。

## 拒绝范围

- 不把「未跑测试」伪装成「已通过」；无实测则明确写「未执行运行时验证」。
- 不为通过审查而建议大范围重构或新增与**暂存 diff** 无关的功能。
- 不主动审查、不建议提交未 `git add` 的文件（含未跟踪的 `.agents/`、`.cursor/` 大目录等），除非用户另行要求扩大范围。
- 不提交 `.local/`、`application-local.yml`、API Key、缓存目录（若已误暂存，在报告中标记建议 `git restore --staged`）。

## 双源同步约定

本 skill 内容须保持一致（除文末镜像声明）：

- `.agents/skills/check_git_code/SKILL.md`（Codex）
- `.cursor/skills/check_git_code/SKILL.md`（Cursor）

任一方修改后须同步另一方。Cursor 命令入口：`.cursor/commands/check_git_code.md`。
