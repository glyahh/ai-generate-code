---
name: history-render-regression-fix
overview: 仅修复前端“历史回显”路径中工作流卡片与选择工具胶囊丢失问题，保持实时流式渲染逻辑不变。
todos:
  - id: locate-history-only-branch
    content: 定位并隔离历史回显分支，确保修复仅作用于历史路径
    status: pending
  - id: fix-tool-pill-history-render
    content: 修复历史消息中选择工具胶囊被去重吞掉的问题
    status: pending
  - id: fix-workflow-stage-mapping-history
    content: 修复历史工作流阶段归类，避免只显示初始化卡片
    status: pending
  - id: validate-parity-history-vs-realtime
    content: 验证历史回显与实时渲染结果一致且实时逻辑无回归
    status: pending
isProject: false
---

# 历史回显卡片渲染修复计划

## 目标
- 修复仅在“从 MySQL 回显历史消息”场景出现的两个问题：
  - 工作流卡片只显示 1 条（预期按阶段显示完整进度）。
  - `选择工具` 胶囊只显示首条，后续连续工具选择未渲染。
- 明确不改动实时 SSE 流式正常链路，避免回归。

## 范围与边界
- 仅调整前端历史回放解析与归类逻辑。
- 不改后端工作流执行、不改数据库存储结构、不改接口协议。
- 不触碰样式布局（本轮仅行为修复）。

## 根因判断（已确认）
- 后端日志显示工作流已走到多步（第 4～8 步），生成和构建均成功，说明不是后端执行失败。
- 前端历史路径使用全文重解析，与实时增量解析策略不同；当前历史重解析存在：
  - 同名 `tool_request` 去重导致连续“写入文件”胶囊被吞。
  - 阶段归类对“智能路由”等标签归类不合理，历史模式下阶段集合被压缩。

## 目标改动文件
- [ai-generate-code-frontend/src/page/App/AppChatView.vue](ai-generate-code-frontend/src/page/App/AppChatView.vue)
  - 历史消息 UI 片段构建（`buildUiSegmentsFromFullText` 相关路径）
  - `tool_request` 去重策略按“历史模式”放宽
- [ai-generate-code-frontend/src/utils/workflowChatFilters.ts](ai-generate-code-frontend/src/utils/workflowChatFilters.ts)
  - `classifyWorkflowStage` 的标签归类优先级与关键词映射
  - 保持阶段式卡片输出（初始化 / 图片收集 / 代码生成 / 代码检查 / 就绪）

## 实施步骤
1. **历史胶囊渲染修复（仅历史路径）**
- 在历史回放解析中取消“同名工具全局去重”，改为：
  - 每次 `[选择工具] xxx` 都可生成一个胶囊；
  - 仅过滤紧邻重复噪声（完全相同且无间隔的重复片段）。
- 实时流式路径保留现有去重节流策略。

2. **历史工作流阶段归类修复**
- 优化 `classifyWorkflowStage`：
  - 将“智能路由”从初始化类中剥离，映射到更合理阶段（代码生成前后阶段）并提高 `ready/完成` 判定优先级。
- 确保历史模式的阶段集合不会因为单标签误判被压缩到仅“初始化”。

3. **统一历史与实时的可见结果（不统一实现）**
- 保留两条解析链路，但保证输出一致性：
  - 同一条消息在“实时显示结束后”与“刷新页面历史回显”卡片数量一致。

4. **回归验证**
- 用你提供的数据库样本进行前端历史回放验证：
  - `选择工具` 胶囊每次出现都显示。
  - 工作流卡片按阶段完整显示，不再只有一条。
- 验证实时首轮生成行为不变。

## 验收标准
- **历史回显（刷新页面后）**
  - 同一助手消息中，连续多次 `[选择工具] 写入文件` 均有胶囊卡片。
  - 工作流卡片至少呈现完整阶段链路（初始化、图片收集、代码生成、代码检查、就绪），不再退化为单卡。
- **实时渲染**
  - 首轮对话时现有效果不退化（你当前认为正常的路径保持原样）。

## 预估改动大小
- 文件数：2 个
- 代码规模：约 30–80 行净改动
- 风险等级：中低（解析策略调整，影响集中在历史回放分支）