---
name: exam_codex
description: 对用户已自实现方案做 4 路并发只读对照 + Playwright MCP 真实场景验收 + 仅清理本轮一次性测试代码的闭环 skill；显式 $exam_codex 或 /exam_codex 唤起；Cursor 与 Codex 双兼容。
metadata:
  short-description: 验收已实现方案 + 清理一次性测试 + 报告
---

# exam_codex

用法：当用户说"我自己已经实现了解决方案，帮我验证 / /exam_codex / 启用 N 个子 agent 并发验证 / 真实场景测试是否通过 / 删除本次构造测试用的文件"等触发词时使用。

## 触发场景

- 我自己已经实现了解决方案，帮我验证
- /exam_codex
- 启用 N 个子 agent 并发验证
- 真实场景测试是否通过
- 删除本次构造测试用的文件

## 输入前置

- 用户已实现的代码改动（diff / git status / 文件路径）
- 配套 plan 文件（路径或行内引用）
- 验收标准（DoD / UI 行为 / 关键日志位）

## 工作流（5 步）

### Step 1 — 4 路并发只读 explore 子 agent

并发启动 4 个 `subagent_type: explore`、`readonly: true` 的子 agent，每个报告 ≤ 200-300 行：

- A — 改动差异 vs plan 对照：用 git diff / 直接读用户已改文件，逐条核对计划中的 a/b/c... 项是否实现，给出"是 / 否 / 部分"判断 + 关键代码片段引用（含起止行号）。
- B — 一次性测试代码清单梳理：跑 `git status --porcelain`，对未跟踪 `*Test.java` 文件读头部 30 行 + `@Test` 注解，分类 A 应删（空文件 / 仅 println / 无断言压测）、B 长期回归（业务断言清晰）、C 待人工裁定（依赖外部 AI / 网络的集成烟测）。
- C — skill 标准结构 / 工作流模板抽取：当任务涉及沉淀流程能力时，调研项目内 skill 三件套规范（`.agents/skills/<name>/{SKILL.md, agents/openai.yaml, .openskills.json}`）与 Cursor 命令入口模式（`.cursor/skills/<name>/SKILL.md` + `.cursor/commands/<name>.md`）。
- D — 会话事实重建：从 transcript 中按关键词抽取用户三大诉求 + 解决路径 + 验证方法，整理 ≤ 200 行事实摘要。

### Step 2 — 关键歧义先用 AskQuestion 收敛

当 Step 1 报告中出现"删除范围 / 测试方式 / 文件位置"等高风险歧义时，用 `AskQuestion` 给出 1-2 个 P0 多选题（每题 ≤ 4 个选项），不解决歧义就不动手。

### Step 3 — Playwright MCP 浏览器脚本式真实场景验收

- dev 未起则中断并提示用户手动启 `cd ai-generate-code-frontend && npm run dev`，不擅自起服务 / backend。
- 用 `take_snapshot`（DOM 结构）替代频繁 `take_screenshot`，token 优先：全程 ≤ 2 次 snapshot（流式中 + 流式后）+ ≤ 1 张关键 `take_screenshot`。
- 在快照里 grep DOM 字符串验收：UI 文案 / 关键 class / 动效元素是否按预期出现 / 消失。
- 任一项不通过 → 中断并产出报告，不进入 Step 4。

### Step 4 — 仅清理"本次构造测试用的"一次性垃圾

- 默认仅删空文件 / 无断言压测式测试，B / C 类长期回归一律保留。
- 动手前列清单（相对路径 + 一句话理由）+ 用户二次确认。
- 不动 `*.pyc`、`*.openskills.json.bak-*` 等缓存（不属于"测试文件"范畴）。

### Step 5 — 产出 ≤ 30 行验收报告

包含：

- **结论**：通过 / 不通过 + 原因
- **证据**：DOM 文本片段 / 命令输出 / 截图位点
- **风险**：P0 / P1 / P2
- **剩余事项**：待人工确认项 / 后续动作

## 拒绝范围

- 不改后端业务逻辑（含 Mermaid 解析与 `mermaidError` 标志）
- 不动"重复构建"机制
- 不新增长期回归测试体系
- 不擅自起 dev / backend
- 不删 B / C 类回归测试

## 完成判定（DoD）

1. 三项 UI 效果（或用户指定的多项验收点）全部通过
2. 一次性垃圾删干净，长期回归测试 0 误删
3. 报告可追溯：结论 + 证据 + 风险 + 剩余事项
4. 不产生任何业务代码改动

## 双源同步约定

本 skill 同时存放在以下两个路径，**内容必须保持一致**（除文末镜像声明）：

- `.agents/skills/exam_codex/SKILL.md`（Codex 路径）
- `.cursor/skills/exam_codex/SKILL.md`（Cursor 路径）

任一方修改后，必须同步另一方，避免 Codex / Cursor 行为漂移。

> 本文件镜像自 `.agents/skills/exam_codex/SKILL.md`，修改时务必同步另一份。
