---
name: workflow卡片与Vue脏回显修复
overview: 修复 workflow 模式下前端步骤卡片“生成代码中…”不推进、以及 Vue workflow 回显脏代码导致页面卡死的问题。核心思路是把“进度”与“代码回显”从纯文本混流改为可解析的结构化事件，并对 Vue 回显做强限制/改为按需拉取。
todos:
  - id: inspect-sse-format
    content: 为 workflow 增加 `workflow-step` 结构化 SSE 事件并兼容旧文本
    status: pending
  - id: frontend-workflow-step-listener
    content: 前端监听 `workflow-step` 事件并稳定推进步骤卡片
    status: pending
  - id: disable-vue-mass-echo
    content: 禁用/严格限制 Vue workflow 的全量文件 Markdown 回显，避免脏代码与卡死
    status: pending
  - id: harden-multifile-delimiters
    content: 确保 workflow 进度行与代码块输出永不粘连，提升解析鲁棒性
    status: pending
isProject: false
---

## 背景判断（基于你给的日志与当前实现）
- `/chat/gen/workflow` 的前端步骤卡片推进依赖 SSE 文本里解析 `"[workflow] 第 n 步完成：xxx"`（见 `ai-generate-code-frontend/src/utils/workflowChatFilters.ts`）。
- 后端工作流真实进入 `code_quality_check` 时，前端仍显示“生成代码中…”，且你反馈是**实时流式**阶段卡住（不是历史回放）。这通常意味着：前端在流式阶段**没有成功解析到一个“代码生成”阶段的 step 行**（因为只有解析到 `code_generating`，UI 才会在 streaming 时自动前移到 `code_checking`）。
- 你截图里的 `span` 脏内容 + 随后卡死，和当前后端 `WorkflowCodeGeneratorFacade` 在 Vue 场景把整个项目文件用 Markdown code fence 批量回显（`emitVueGeneratedCode`）高度一致：内容量大、分片可能打断 fence，前端 `parseMarkdownWithCode`/segment 合并会出现“半个围栏”导致渲染异常并拖垮页面。

## 目标与验收
- **多文件（MULTI_FILE）workflow**：进入质检阶段时，前端步骤卡片应稳定显示到“代码检查中…”，不再长期停留在“生成代码中…”。
- **Vue workflow**：不再向对话区塞入“### 文件名 + ```vue ...```”这种巨量回显；前端不再出现 `span` 等脏文本；切出页面不再卡死。
- **兼容性**：旧的 `[workflow] ...` 文本仍可用于历史回放；新增结构化事件不破坏现有 `onmessage` 流程。

## 实施方案
### 1) 后端：把 workflow 进度从“混在文本里”升级为结构化 SSE 事件（同时保留旧文本）
- 改动点：`src/main/java/com/dbts/glyahhaigeneratecode/controller/ChatToGenCodeController.java`
- 当前 `toSseEvent()` 对所有 chunk 都用默认 `message` 事件输出；我们要为 workflow 专门输出：
  - `event: workflow-step`，data 为 JSON：`{"step":n,"label":"代码生成"}`
  - 仍保留一份兼容文本：`"[workflow] 第 n 步完成：代码生成\n"`
- 进度的生成来源：继续使用 `CodeGenWorkflow.executeWorkflow` 的 `progressConsumer.accept(...)`，但在 `ChatToGenCodeController.toSseEvent()` 或 workflow 专用包装处识别以 `[workflow]` 开头的行，转为 `workflow-step` 事件（避免前端 parse fence 时被污染）。

### 2) 前端：监听 `workflow-step` 事件，直接更新步骤数组（不再依赖“从 markdown 里捞出来”）
- 改动点：`ai-generate-code-frontend/src/page/App/AppChatView.vue`
- 在创建 `EventSource` 后新增：
  - `es.addEventListener('workflow-step', (event) => { ... })`
  - 解析 JSON，调用 `mergeWorkflowSteps` 写入 `msg.workflowSteps`
- 同时保留现有 `onmessage` 的文本解析逻辑，用于：
  - 非 workflow 模式
  - 历史回放
  - 兼容旧后端

### 3) Vue workflow：彻底禁止“全量文件 Markdown 回显”，改为轻量摘要 + 按需查看
- 改动点：`src/main/java/com/dbts/glyahhaigeneratecode/core/WorkflowCodeGeneratorFacade.java`
- 策略：
  - **对 `CodeGenTypeEnum.VUE`**：`emitGeneratedCodeIfPresent()` 不再调用 `emitVueGeneratedCode()`。
  - 仅输出一条轻量提示（例如：`[workflow] 代码生成完成。` + `生成目录: ...` 用 notice 事件或隐藏行，不进入 UI 文本）。
  - 如果你仍需要“回显代码块”，只回显白名单核心文件（例如 `src/App.vue`、`src/main.ts`、`index.html`、`package.json`、`vite.config.*`）并设置总字节/文件数上限（例如 50 个文件、总 256KB）。
- 目的：直接消灭 `span` 脏回显与卡死根因（大量 fence + 分片 + 重渲染）。

### 4) multiFile workflow：避免进度行被后续大块代码粘连
- 改动点：`src/main/java/com/dbts/glyahhaigeneratecode/core/WorkflowCodeGeneratorFacade.java`
- 确保所有 `[workflow]` 行：
  - **独占 chunk**（单独 `sink.next`）
  - **强制以 `\n` 结尾**（现有 `normalizeWorkflowLine` 保留）
  - 在输出任何 `### file` code fence 之前，先输出完进度与 done 通知
- 这样即使前端仍走文本解析，也不会被 `### index.html` 等粘连破坏正则。

## 影响文件清单（预计）
- 后端：
  - `src/main/java/com/dbts/glyahhaigeneratecode/controller/ChatToGenCodeController.java`
  - `src/main/java/com/dbts/glyahhaigeneratecode/core/WorkflowCodeGeneratorFacade.java`
- 前端：
  - `ai-generate-code-frontend/src/page/App/AppChatView.vue`
  - （可能无需改）`ai-generate-code-frontend/src/utils/workflowChatFilters.ts`

## 测试/验证（不在 plan 阶段执行）
- 本地启动后端 + 前端，走两条用例：
  - MULTI_FILE + workflow：观察卡片依次到“代码检查中…”并最终“就绪”。
  - VUE + workflow：对话区不出现大段 `### xxx` 文件回显；切出标签页不冻结；预览可刷新看到构建产物。

## 清理
- 若为定位加入临时代码/日志/测试文件，测试通过后删除，保持仓库整洁。