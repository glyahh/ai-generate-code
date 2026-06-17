---
name: workflow-history-cards-fix
overview: 修复工作流卡片在历史回显场景下的步骤缺失问题：成功场景固定展示5步，失败场景按推断阶段停止并标记失败步。
todos:
  - id: add-history-outcome-parser
    content: 在 workflowChatFilters 中新增历史终态(success/failed/unknown)判定函数
    status: pending
  - id: update-history-normalization
    content: 扩展 normalizeWorkflowStepsForUi：成功固定5步，失败到失败步并改文案
    status: pending
  - id: wire-outcome-in-chat-view
    content: 在 AppChatView 的历史渲染与缓存路径接入终态判定
    status: pending
  - id: validate-regression-scenarios
    content: 按成功历史/失败历史/流式实时三类场景做前端回归验证
    status: pending
isProject: false
---

# 修复工作流历史回显卡片缺失

## 目标
- 历史消息回显时（非流式）：
  - 若判定为成功，固定展示 5 步并全部为“已完成！”。
  - 若判定为失败，按文本可推断的阶段展示到失败步，并将失败步文案改为“<步骤名>出现问题”。
- 兼容当前流式展示逻辑，不影响实时生成过程中的卡片更新。

## 现状与根因
- 前端历史回显依赖 `message` 文本推断步骤。
- 后端入库前会清理 `[workflow]` 行，导致历史消息缺少完整步骤轨迹，只能显示零散步骤（当前常见为“初始化已完成/代码生成已完成”）。
- 关键逻辑位于：
  - [ai-generate-code-frontend/src/utils/workflowChatFilters.ts](ai-generate-code-frontend/src/utils/workflowChatFilters.ts)
  - [ai-generate-code-frontend/src/page/App/AppChatView.vue](ai-generate-code-frontend/src/page/App/AppChatView.vue)

## 实施方案
- 在 `workflowChatFilters` 增加“历史终态解析”能力：
  - 新增历史消息状态判定函数：`success | failed | unknown`。
  - 基于文本特征识别成功（如“代码生成完成/工作流结束/构建成功”）与失败（如“失败/异常/error/中断/出现问题”等）。
- 扩展卡片归一化函数（`normalizeWorkflowStepsForUi`）以支持历史终态策略：
  - `historyMode + success`：直接输出固定 5 步全完成。
  - `historyMode + failed`：
    - 用现有 stage 分类结果确定已到达阶段。
    - 失败步文案改为“<步骤名>出现问题”。
    - 不再展示失败步之后的阶段。
  - 其余保持现有行为。
- 在 `AppChatView.vue` 的历史渲染入口传入“历史终态”信息：
  - 统一在 `precomputeMessageRenderCaches` 与 `getWorkflowStepsForMessage` 中调用终态判定。
  - 保持缓存版本键稳定，避免重复重算导致闪烁。

## 验收标准
- 成功历史消息：工作流卡片固定显示 5 行，依次为：
  1. 初始化已完成！
  2. 图片收集已完成！
  3. 代码生成已完成！
  4. 代码检查已完成！
  5. 就绪！
- 失败历史消息：
  - 仅显示到失败步；失败步文案为“<步骤名>出现问题”。
  - 示例：若失败发生在代码生成步，显示前三步，其中第 3 步为“代码生成出现问题”。
- 流式新会话不回归：仍按当前实时推进逻辑展示（不被历史策略污染）。
- 历史折叠/展开、卡片缓存与慢提示逻辑不异常。

## 目标改动文件
- [ai-generate-code-frontend/src/utils/workflowChatFilters.ts](ai-generate-code-frontend/src/utils/workflowChatFilters.ts)
  - 增加历史终态判定与失败步文案生成。
  - 调整 `normalizeWorkflowStepsForUi` 入参与历史分支逻辑。
- [ai-generate-code-frontend/src/page/App/AppChatView.vue](ai-generate-code-frontend/src/page/App/AppChatView.vue)
  - 在历史路径调用归一化时传入终态信息。
  - 轻微调整缓存 version 组成，确保终态变化可触发重算。

## 预估改动大小
- `workflowChatFilters.ts`：约 60-100 行（新增判定函数与历史分支）
- `AppChatView.vue`：约 20-40 行（终态接线 + 缓存键补充）
- 总计：约 80-140 行，纯前端逻辑改动，无接口变更。