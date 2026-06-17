---
name: SSE工作流卡片与多文件代码流式输出修复
overview: 在不动 LangGraph4j 工作流底层、不动各 Node/Tool 的前提下，仅靠门面层拼接与前端解析，让工作流阶段卡片尽量「随进度出现」；代码允许工作流结束后一次性展示。并修复历史回显时工作流步骤挤成一行、换行丢失的问题。
todos:
  - id: facade-newline-and-bootstrap
    content: 在 WorkflowCodeGeneratorFacade 中为每条进度 sink.next 追加换行；可选在 executeWorkflow 前先发一条纯文本「阶段路线图」占位（门面拼接），提升首屏反馈。
    status: completed
  - id: facade-async-workflow
    content: 仅在 WorkflowCodeGeneratorFacade 内将 executeWorkflow 放到异步线程执行，避免同线程长时间阻塞导致 SSE 早期 chunk 无法及时 flush（不动 CodeGenWorkflow/CodeGeneratorNode）。
    status: completed
  - id: frontend-parse-concatenated-workflow
    content: 增强 parseWorkflowStepsFromText（及必要时 filterAssistantSseChunkForUi）用全局/多匹配解析，兼容「无换行、多个 [workflow] 粘在一行」的历史与异常 chunk。
    status: completed
  - id: frontend-carry-flush-optional
    content: 若验收仍觉卡片滞后，再对 workflowChatFilters 做小幅阈值 flush（仅影响 UI 文本，不碰后端 node）。
    status: cancelled
  - id: e2e-test-real-flow
    content: workflow + MULTI_FILE：流式过程中阶段卡片逐步更新；代码可在结束后一次性出现；历史回放卡片分行正常。按用户给定账号与 prompt 跑通。
    status: completed
isProject: false
---

## 约束（本轮已确认）

- **禁止改动**：`LangGraph4j` 工作流图/遍历逻辑、`CodeGenWorkflow`、`*Node`、各类 Tool、`WorkflowContext` 等「底层与节点」代码。
- **允许改动**：门面层与对外拼装逻辑（首选 [`WorkflowCodeGeneratorFacade.java`](d:/mainJava/all%20Code/program/glyahh-ai-generate-code/src/main/java/com/dbts/glyahhaigeneratecode/core/WorkflowCodeGeneratorFacade.java)），以及必要时与「落库全文格式」相关的极薄层（如 [`SimpleTextStreamHandler`](d:/mainJava/all%20Code/program/glyahh-ai-generate-code/src/main/java/com/dbts/glyahhaigeneratecode/core/handler/SimpleTextStreamHandler.java) ——若你坚持严格只动门面，则优先用「门面每条消息带 `\n`」解决落库粘连，避免动 Handler）。
- **体验优先级**：用户要**先看到工作流进行到哪一阶段**（卡片流式/递进）；**代码可以最后一次性全部展示**（接受不逐 token 流式）。

## 根因补充：历史回显「换行没了」、步骤粘成一行

- 进度原文来自 `CodeGenWorkflow` 的 `progressConsumer.accept("[workflow] 第 n 步完成：" + label)`，**字符串本身不带行尾 `\n`**。
- 持久化时 [`SimpleTextStreamHandler`](d:/mainJava/all%20Code/program/glyahh-ai-generate-code/src/main/java/com/dbts/glyahhaigeneratecode/core/handler/SimpleTextStreamHandler.java) 对每条 `chunk` 做 `aiResponseBuilder.append(chunk)`，**chunk 之间无分隔符** → 库里的 AI 消息变成 `[workflow]…[workflow]…` 一整行。
- 前端 [`parseWorkflowStepsFromText`](d:/mainJava/all%20Code/program/glyahh-ai-generate-code/ai-generate-code-frontend/src/utils/workflowChatFilters.ts) 按行 `split`，**单行内多个 `[workflow]`** 时解析退化，表现为卡片区「一行糊在一起」（与你截图一致）。

## 方案 A（主方案，符合「只动门面」）

### 1) 门面层：每条对外推送的进度统一带换行

- 在 [`WorkflowCodeGeneratorFacade.generateAndSaveCodeStream`](d:/mainJava/all%20Code/program/glyahh-ai-generate-code/src/main/java/com/dbts/glyahhaigeneratecode/core/WorkflowCodeGeneratorFacade.java) 里包装 `progress` 回调：对 `CodeGenWorkflow` 传来的每一行 `msg`，执行 `sink.next(msg.endsWith("\n") ? msg : msg + "\n")`（或对 `[workflow]` 前缀统一补 `\n`）。
- **效果**：SSE 侧仍是多条 message；**落库全文**在 `SimpleTextStreamHandler` 里自然按行分隔，历史回放 `parseWorkflowStepsFromText` 恢复正常。

### 2) 门面层：异步执行 `executeWorkflow`，尽早 flush 早期阶段

- 在 `Flux.create` 内用 `CompletableFuture.runAsync` / `Schedulers.boundedElastic` 调用现有 `workflow.executeWorkflow(..., progress)`，主线程只负责 subscribe 与背压；**不改** `CodeGenWorkflow` 与任何 Node。
- **效果**：图片收集、提示词增强等前几步的 `sink.next` 更容易在「代码生成」长耗时开始前到达浏览器，卡片逐步追加；**代码生成步**仍只在节点完成后才有一条「完成」事件（底层未改，无法在中途拆子阶段，与需求一致：代码可最后一次性出）。

### 3) 门面层（可选）：会话开始时发一条「阶段路线图」纯文本

- 在调用 `executeWorkflow` 之前 `sink.next` 一条仅展示用的多行说明（例如列出将经历的阶段名），**不冒充**真实进度，避免与 `[workflow] 第 n 步完成` 混淆；若你不喜欢「计划清单」，可省略此项。

### 4) 代码输出：保持现有「工作流结束后读盘回显」

- **不修改** `emitGeneratedCodeIfPresent` 的「一次性多文件」行为，与「代码最后一起显示」一致。

## 方案 B（前端兜底，兼容已污染的历史数据）

- 扩展 [`parseWorkflowStepsFromText`](d:/mainJava/all%20Code/program/glyahh-ai-generate-code/ai-generate-code-frontend/src/utils/workflowChatFilters.ts)（及必要时流式路径）：对整段文本用 **全局正则** 或按 `(?=\[workflow\])` 拆分，提取所有 `第 n 步完成：label`，**不要求**每条占一行。
- **效果**：即使库里已是「一行糊住」的旧消息，卡片仍能拆成多行展示；与新门面补 `\n` 双保险。

### 可选小幅：filterAssistantSseChunkForUi 的 carry

- 子代理已指出：无换行 chunk 可能导致 `uiText` 长期为空；若方案 A+B 后仍有个别环境滞后，再对 `workflowChatFilters` 做「长度阈值 flush」——列为可选 todo，避免过度改动。

## 明确不做的项（相对旧版计划）

- 不向 `WorkflowContext` 注入 SSE 回调、不修改 `CodeGeneratorNode` 的 `blockLast`、不转发模型 token、不调整 `CodeQualityCheckNode`（除非你后续单独开需求）。这些属于「大改」，本轮按你的要求放弃。

## 验收标准

1. **实时对话**：`/chat/gen/workflow` 下，工作流卡片随 `[workflow] 第 n 步完成` 多条事件递进；代码可在整段 workflow 结束后一次性出现。
2. **历史回放**：同一会话写入 DB 后再次打开，工作流卡片为多行、多步骤，不出现「1 初始化[workflow] 第 2 步…」挤在一格 label 里的现象。
3. **回归**：非 workflow 的 `/chat/gen/code`、Vue JSON 流式链路不受影响。

## 建议自测数据

- 账号：普通用户 / `11451400`；应用 `MULTI_FILE`；`genMode=workflow`。
- Prompt：`帮我生成一个介绍电子科技大学成都学院的网站,不超过200行`（或你常用用例）。

## 风险

- 仅异步 + 补换行：**不能**在「代码生成」节点内部再拆子进度（例如生成到第几个文件），除非未来允许改 Node 或单独起并行任务；当前与「代码最后一起显示」一致，可接受。
