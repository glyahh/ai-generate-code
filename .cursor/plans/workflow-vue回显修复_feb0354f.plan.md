---
name: workflow-vue回显修复
overview: 修复 workflow + Vue 模式下仅显示进度卡片、不回显代码的问题；并在可控风险下补充“代码生成中...”后的实时工具调用卡片。实现优先采用 workflow 专属改造，尽量不改 ai service 与公共模板逻辑。
todos:
  - id: backend-vue-final-echo
    content: 在 WorkflowCodeGeneratorFacade 增加 VUE 目录回显逻辑并过滤无关文件
    status: pending
  - id: workflow-realtime-bridge
    content: 打通 CodeGenWorkflow/CodeGeneratorNode 到 WorkflowCodeGeneratorFacade 的流式 chunk 透传
    status: pending
  - id: frontend-compat-guard
    content: 前端验证并补充 workflow 模式下工具卡片与回显兼容保护
    status: pending
  - id: tests-regression
    content: 补充/更新后端与前端回归验证并执行真实链路测试
    status: pending
  - id: cleanup-temp-code
    content: 清理调试日志和一次性测试代码，保持提交干净
    status: pending
isProject: false
---

# Workflow Vue 回显与工具卡片改造计划

## 目标与验收
- 在 `workflow` 模式 + `VUE` 生成时，前端最终能看到完整多文件代码回显（不再只有工作流步骤卡片）。
- 保持现有工作流步骤卡片逻辑不回退，历史回放也能展示对应代码内容。
- 在你选定的实现档位下，尽量支持“代码生成中...”阶段实时出现工具调用卡片。

## 实现边界（按你的约束）
- 尽量不改 `AiCodeGeneratorFacade`、`aiCodeGeneratorService` 以及 workflow/非 workflow 共用的基础逻辑。
- 尽量不改系统提示词模板与常用模板代码（如 `Prompt/` 下公共模板）。
- 优先在 workflow 专属层实现：`WorkflowCodeGeneratorFacade`、`CodeGenWorkflow`、`CodeGeneratorNode` 与前端 workflow 展示层。
- 代码组织遵循高内聚低耦合：新增能力优先封装成 workflow 内部私有方法/适配器，不向公共层扩散。
- `WorkflowContext`、workflow 入口编排、workflow 节点与工具实现尽量不动；若必须改动，控制在最小必要行数，并保持对外契约不变。

## 现状定位
- 工作流链路使用 `[workflow]` 进度文本推送，前端会将这些行提取成步骤卡片：[`d:\mainJava\all Code\program\glyahh-ai-generate-code\ai-generate-code-frontend\src\utils\workflowChatFilters.ts`](d:\mainJava\all Code\program\glyahh-ai-generate-code\ai-generate-code-frontend\src\utils\workflowChatFilters.ts)
- `workflow` 后端门面当前仅对 `HTML/MULTI_FILE` 做最终文件回显，`VUE` 未回显：[`d:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\core\WorkflowCodeGeneratorFacade.java`](d:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\core\WorkflowCodeGeneratorFacade.java)
- 非 workflow 的 Vue 链路通过 `JsonMessageStreamHandler` 将 `tool_request/tool_executed` 转成人类可读卡片协议，前端可解析并展示：[`d:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\core\handler\JsonMessageStreamHandler.java`](d:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\core\handler\JsonMessageStreamHandler.java)

## 改造方案
1. 后端先补“最终完整回显”（必须项）
- 在 `WorkflowCodeGeneratorFacade` 扩展 `VUE` 分支：读取 `generatedCodeDir` 下的 Vue 项目文件，按文件顺序输出 Markdown fenced code block（含文件名标题），与当前前端 `markdown/code` 渲染兼容。
- 过滤不需要回显的目录（如 `node_modules`、构建产物目录），仅回显源码与关键配置文件，避免消息过大。
- 保留现有 `[workflow]` 与 `[workflow_notice]` 语义，确保步骤卡片继续可用。

2. workflow 实时工具卡片（最小改动优先，分层降级）
- 首选方案（零侵入 workflow 节点/工具）：仅在 `WorkflowCodeGeneratorFacade` 的“最终回显阶段”补充可解析卡片协议，保证稳定回显完整文件。
- 可选增强（你确认后再做）：若必须“生成中实时卡片”，才最小化修改 workflow 内部（优先不改 `WorkflowContext` 结构，不改工具实现，只做可选回调透传）。
- 任何涉及 `CodeGenWorkflow` / `CodeGeneratorNode` 的改动都要求：
  - 不改变现有方法语义与默认行为；
  - 不新增跨层依赖；
  - 保持回滚简单（可单点撤销）。

3. 前端最小兼容增强
- 在 `AppChatView.vue` 仅做保护性调整：确保 workflow 模式下收到工具调用协议时，不会被 workflow 行过滤误伤，且 `generatedFiles` 能持续更新。
- 保持历史回放路径：`stripAssistantNoiseLines + buildUiSegmentsFromFullText` 仍可从完整 `message` 复建工具卡片与代码块。

4. 清理与回归
- 删除调试日志、一次性探针、临时测试代码，避免把排障产物带入主干。
- 对新增/改动测试只保留长期有效的回归测试。

## 测试计划（按真实链路）
- 后端单测/集成测试：
  - `workflow + VUE` 结束后消息中包含多文件回显块。
  - code-generator 阶段会透传工具调用协议（至少覆盖 `writeFile`）。
- 前端联调：
  - 进入 `genMode=workflow`，`VUE` 项目可同时看到工作流步骤卡片 + 工具调用卡片 + 最终完整代码回显。
  - 历史回放刷新后仍可恢复上述显示。
- 端到端实测（你提供的数据）：
  - 账号：`普通用户` / `11451400`
  - prompt：`帮我生成一个介绍电子科技大学成都学院的网站,不超过200行`
  - 使用 `workflow` + `MultiFile` 跑一遍，再补 `workflow + VUE` 跑一遍确认本次修复点。

## 风险与兜底
- 实时透传会增加 SSE 数据量；若出现前端卡顿或链路不稳，保底策略是保留“最终完整回显”并将实时工具卡片降级为“阶段结束批量回放”。
- 文件回显需控制体积，避免超长消息导致前端渲染压力。
- 若发现必须修改共用 ai service 才能落地，将先暂停实现并给出最小变更点清单，待你确认后再动共享层。
- 若发现必须显著改动 `WorkflowContext`、入口编排、节点或工具，默认不实施该方案，改为门面层/前端层替代实现。