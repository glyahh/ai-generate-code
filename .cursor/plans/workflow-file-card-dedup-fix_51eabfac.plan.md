---
name: workflow-file-card-dedup-fix
overview: 修复“首轮工具调用卡片重复渲染且MySQL也保存异常数量”的根因：后端对 tool_request/tool_executed 做幂等与落库清洗，保证历史回放与实时渲染都只出现真实的7次写入。
todos:
  - id: confirm-scope
    content: 明确修复范围为后端幂等+持久化清洗（前端仅保留可选兜底）
    status: pending
  - id: implement-dedup-view
    content: （可选兜底）前端增加轻量段落去重，避免异常数据再次放大
    status: pending
  - id: sse-idempotency-guard
    content: 在后端SSE/工具调用协议层增加幂等去重（按toolCallId/文件路径/阶段）
    status: pending
  - id: copy-update
    content: （可选）同步更新工具区文案，降低“选择工具/使用工具”歧义
    status: pending
  - id: verify-cases
    content: 覆盖首轮Vue(writeFile-only)场景回归：实时SSE、落库、历史回放三处一致
    status: pending
isProject: false
---

# 修复首轮工具调用卡片重复（端到端）计划

## 目标
在**用户第一次与 AI 对话（首轮 Vue writeFile-only 工具调用）**时，保证：
- 前端“选择工具/使用工具/[工具调用]写入文件”等工具卡片数量与真实写入次数一致（本例应为 7）。
- MySQL `chat_history` 中持久化的 assistant 内容不包含重复的工具调用块，历史回放不再重复渲染。
- 若发生重试/重连，卡片仍保持幂等（同一 `toolCallId` 不会生成多张卡片）。

## 根因结论（已完成并发排查）
- 你提供的后端日志中“第二次 `chat/completions` 请求”属于函数调用协议续写（`assistant.tool_calls -> tool 回传 -> 再次补全`），本身是正常行为，不是根因。
- 重复根因不在历史压缩，也不在 AI service 缓存命中串上下文。
- 核心根因模块是 `src/main/java/com/dbts/glyahhaigeneratecode/core/WorkflowCodeGeneratorFacade.java` 的 `adaptWorkflowCodeChunk`：
  - `TOOL_REQUEST` 仅在 `toolCallId` 非空时去重，空/缺失 ID 分片可能重复输出“选择工具/使用工具”。
  - `TOOL_EXECUTED` 分支没有做 `toolCallId` 级幂等，重复输入会被重复拼接到 assistant 文本。
- 重复文本会被 `src/main/java/com/dbts/glyahhaigeneratecode/core/handler/WorkflowTextStreamHandler.java` 一次性落库到 `chat_history`，导致历史回放稳定复现重复卡片。
- 前端 `AppChatView.vue` 仅存在“history + session 双源拼接”条件性放大，不是 MySQL 出现重复工具卡片的源头。

## 已确认排除项
- `ChatHistoryServiceImpl` 的会话压缩/消息截断当前主要作用于 Redis memory，不会直接把重复工具块写回 MySQL，非主因。
- `aiCodeGeneratorServiceFactory` 的缓存 key 和 memoryId 以 `appId` 隔离，未发现可解释本次现象的跨应用上下文污染证据。

## 目标改动文件
- 后端（主修复）：
  - `src/main/java/com/dbts/glyahhaigeneratecode/core/WorkflowCodeGeneratorFacade.java`
  - `src/main/java/com/dbts/glyahhaigeneratecode/core/handler/WorkflowTextStreamHandler.java`
  - `src/main/java/com/dbts/glyahhaigeneratecode/core/AiCodeGeneratorFacade.java`（核对首轮 Vue synthetic/native 工具事件幂等边界）
  - `src/main/java/com/dbts/glyahhaigeneratecode/service/impl/ChatHistoryServiceImpl.java`（如需补充落库前清洗入口）
- 前端（仅兜底，可选）：
  - `ai-generate-code-frontend/src/page/App/AppChatView.vue`

## 具体实施步骤
1. 在 `WorkflowCodeGeneratorFacade.adaptWorkflowCodeChunk` 增加统一幂等：
   - `TOOL_REQUEST`：即使 `toolCallId` 缺失也做保底去重（基于工具名 + 参数摘要 + 时间窗口）。
   - `TOOL_EXECUTED`：新增 `toolCallId` 级去重集合，确保同一调用只拼接一次执行结果。
2. 在 `WorkflowTextStreamHandler` 落库前增加协议清洗（仅清洗重复工具阶段块，不改业务正文）。
3. 复核 `AiCodeGeneratorFacade` 的首轮 Vue synthetic/native 逻辑，避免边界场景下重复 emitted。
4. （可选）前端仅做兜底展示去重，防止历史脏数据继续放大。
5. 回归验证：首轮 7 文件、刷新历史、SSE 重连/补发场景均保持卡片数量正确。

## 验收标准
- **首轮 Vue writeFile-only**：工具卡片中 `[工具调用] 写入文件` **严格为 7 张**，且与实际 `tool_calls` 数一致。
- `chat_history`（MySQL）中该轮 assistant 内容不包含同一 `toolCallId` 的重复工具阶段块；刷新后历史回放卡片数量不变。
- “选择工具/使用工具”阶段提示不重复（同一 `toolCallId` 只出现一次）。
- workflow-step 可继续双通道存在，但不会导致工具卡片重复。
- 不引入新增后端异常与前端 lint/类型错误。

## 预估改动大小
- 后端：约 80-220 行（`toolCallId` 幂等 + 落库前清洗 + 必要日志）。
- 前端兜底（可选）：约 20-60 行。
- 总体：中等改动，风险可控，重点在 workflow 工具协议链路。

## 风险与回滚
- 风险：幂等键设计不当可能误合并“不同轮次/不同 assistant 消息”的合法工具调用。
- 缓解：幂等作用域限定在同一 `appId + streamId/assistantMessage`，优先按 `toolCallId`，无 ID 时只做短窗口弱去重；必要时开关回退旧逻辑。