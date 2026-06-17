---
name: workflow-card-history-display-fix
overview: 不改后端存储结构，只在历史回显 service 层解析 message 文本并校验五阶段状态；成功全绿，失败阶段红色且后续阶段灰色。
todos:
  - id: service-parse-message
    content: 在历史回显 service 层新增 message 解析与阶段状态判定逻辑
    status: pending
  - id: stage-error-mapping
    content: 建立各阶段错误关键词映射，命中则该阶段标红并将后续阶段置灰
    status: pending
  - id: frontend-color-render
    content: 前端按 service 返回状态渲染字体颜色（绿/红/灰）
    status: pending
  - id: regression-cases
    content: 覆盖成功、图片阶段失败、代码生成阶段失败三类回归用例
    status: pending
isProject: false
---

# 工作流卡片历史回显修复方案

## 约束与原则（按你的要求）
- 不做后端大改，不新增表字段，不改持久化结构。
- 仅基于 `chat_history.message`（按 `appId` 查询到的历史消息）做阶段识别与失败判定。
- 后端代码若异常，应在 message 中出现对应阶段错误提醒；service 负责把该错误映射到阶段状态。
- 颜色语义只用于前端字体：绿色=通过，红色=失败，灰色=未执行。

## 目标与验收
- 历史回显不再出现“只显示2步”。
- 固定展示5个阶段：初始化、图片收集、代码生成、代码检查、就绪。
- 若 message 未命中任何阶段异常提示，则 5 阶段全部绿色。
- 若命中某阶段异常提示，则该阶段红色，后续阶段灰色，前序阶段绿色。

## 关键改动点
- 在历史回显 service 层增加 `message` 解析器，输出固定五阶段状态数组。
- 建立阶段错误关键词映射（如“图片/图像/插画/logo + 失败/报错/error”归入图片收集阶段）。
- 对“代码已生成完毕/写入文件/项目已生成完毕”等成功特征做通过判定，避免误报。
- 前端卡片不再自行猜测流程结果，只消费 service 返回的阶段状态。

## 涉及文件
- 历史查询转换：[`D:/mainJava/all Code/program/glyahh-ai-generate-code/src/main/java/com/dbts/glyahhaigeneratecode/service/impl/ChatHistoryServiceImpl.java`](D:/mainJava/all Code/program/glyahh-ai-generate-code/src/main/java/com/dbts/glyahhaigeneratecode/service/impl/ChatHistoryServiceImpl.java)
- 前端步骤过滤与归一化：[`D:/mainJava/all Code/program/glyahh-ai-generate-code/ai-generate-code-frontend/src/utils/workflowChatFilters.ts`](D:/mainJava/all Code/program/glyahh-ai-generate-code/ai-generate-code-frontend/src/utils/workflowChatFilters.ts)
- 前端卡片渲染入口：[`D:/mainJava/all Code/program/glyahh-ai-generate-code/ai-generate-code-frontend/src/page/App/AppChatView.vue`](D:/mainJava/all Code/program/glyahh-ai-generate-code/ai-generate-code-frontend/src/page/App/AppChatView.vue)

## 实施步骤
1. 在 `ChatHistoryServiceImpl` 中聚合同一 `appId` 的历史 AI message 文本，新增“阶段状态验证器”。
2. 验证器按固定顺序输出5阶段：`init`、`image`、`codeGen`、`codeCheck`、`ready`，默认先置为绿色候选。
3. 通过关键词规则检测错误所属阶段：命中后将该阶段置红、其后全部置灰、其前保持绿，并记录失败提示文案。
4. 若未命中任何错误且存在“生成成功”证据（如“项目已生成完毕”“写入文件”等），则5阶段全绿。
5. 前端 `workflowChatFilters` 调整为优先读取 service 返回阶段状态，旧文本推断仅作兜底。
6. 前端 `AppChatView` 统一使用字体颜色渲染：绿（通过）/红（失败）/灰（未执行）。
7. 回归验证三类场景：全成功、图片阶段失败、代码生成阶段失败。

## 风险与兼容
- 纯文本判定存在歧义：通过“阶段关键词 + 失败关键词”组合降低误判。
- 某些 message 只含成功内容但无明显阶段文本：按你的规则判为全绿。
- 历史老数据格式不统一：保留前端旧兜底解析，避免页面空卡片。