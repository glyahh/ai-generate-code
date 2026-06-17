---
name: xue-xi-tong
description: Guide browser automation workflows for Chaoxing Xuexitong tasks while keeping risky login or verification steps manual.
metadata:
  short-description: Automate Xuexitong browser tasks
---

# xue-xi-tong

用法：当用户说“xue-xi-tong/学习通/刷课/自动化学习”时使用。

说明：这类需求可能涉及账号登录与网页自动化。

工作流：
1) 明确你要做的动作（登录、打开课程、播放、签到、答题等）。
2) 优先用 MCP `playwright-mcp` 做浏览器自动化（本地可控、可回放）。
3) 任何涉及账号/验证码/风控的步骤，默认让用户手动完成关键确认。

输出：
- 自动化脚本/步骤 + 失败时的手动兜底方案。

## 交付方式（强制，优先级高于通用“写文件”习惯）

**默认只在对话里给出可复制代码，不向仓库落盘。**

- 除非用户**明确要求**「写到某路径 / 保存文件 / 提交到项目」，否则：
  - **禁止**在 `learn/`、`temp/` 或任意目录新建/修改作业 HTML、JS、截图等文件；
  - **禁止**为完成作业而 `Write` / 批量生成本地示例页；
  - 全部以**完整、可一键复制**的代码块输出在回复中。
- 用户说「给我代码」「能复制的」「不要写本地」等，一律按本节约束执行。
- 代码块须完整（含 `<!DOCTYPE>` 或完整脚本），用户复制后可直接运行，无需再拼片段。

### HTML / JS 作业（如轮播图、DOM 练习）

必须同时提供两份（均放在回复里，不写文件）：

1. **普通完整 HTML**（单文件，浏览器直接打开或学习通编辑器粘贴）；
2. **控制台一键版**：`document.open(); document.write(\`...\`); document.close();`  
   - 字符串内 `</script>` 必须写成 `<\/script>`，避免提前闭合。

可选：简短说明截图要点（截哪几行核心逻辑、如何验证右移/左移/圆点），**不要**替用户生成截图文件。

限制（新增）：
1) 当用户显式要求使用 `/humanizer` 时，按 `/.agents/skills/humanizer/SKILL.md` 的流程润色输出，但不改变事实。
2) 优先级规则：用户在当前消息中的实际指令 > 本 Skill 提示词 > 其他通用提示。
3) 若是 JavaScript 作业场景，默认采用简洁实用风格：保留关键逻辑，避免不必要注释与过复杂结构。
4) 输出打印遵循“最小必要”原则：除非用户明确要求中文提示，否则只打印必要变量值（如 `16`，而非“你猜的数是16”）。