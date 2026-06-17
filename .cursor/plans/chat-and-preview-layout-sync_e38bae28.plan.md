---
name: chat-and-preview-layout-sync
overview: 优化应用聊天页左侧对话区与右侧预览 iframe 的布局，使两侧高度始终一致，左侧消息在超出固定高度后内部滚动且默认自动滚动到底部。
todos:
  - id: inspect-layout-current
    content: 确认 `AppChatView.vue` 中 `main-content`、`chat-panel`、`preview-panel`、`chat-messages` 的现有 flex/grid 和 overflow 配置，以避免与新方案冲突
    status: completed
  - id: update-chat-panel-layout
    content: 调整聊天面板布局，使 `chat-messages` 固定高度并内部滚动，`chat-input-bar` 固定在底部
    status: completed
  - id: sync-preview-panel-height
    content: 确保预览面板使用与聊天面板一致的 flex 高度策略，使 iframe 高度由父容器控制
    status: completed
  - id: wire-auto-scroll-to-container
    content: 检查并确认自动滚动逻辑只针对 `chat-messages` 容器，保持“自动吸底 + 手动滚动关闭”的既有交互
    status: completed
  - id: visual-regression-test
    content: 在少量消息和大量消息两种场景下验证左右高度一致性与滚动行为，必要时微调样式
    status: completed
isProject: false
---

## 目标
- **布局目标**：在应用聊天页中，将左侧“聊天+工作流卡片”与右侧 iframe 预览区固定在同一可视高度内，避免左侧内容无限向下撑高整个页面。
- **交互目标**：当左侧消息内容超出可视高度时，仅在左侧聊天区内部出现滚动条，并默认自动滚动到底部；右侧 iframe 保持原有大小，高度与左侧视觉保持一致。
- **兼容现状**：复用现有的自动滚动逻辑（`shouldAutoScroll`、`chatMessagesRef`、`handleChatScroll` 等），尽量只做布局和样式级改造，减少对业务逻辑的影响。

## 核心改动点
- **主要文件**：
  - 聊天页 Vue 组件：[ai-generate-code-frontend/src/page/App/AppChatView.vue](ai-generate-code-frontend/src/page/App/AppChatView.vue)
  - 根布局仅参考，无需修改：[ai-generate-code-frontend/src/App.vue](ai-generate-code-frontend/src/App.vue)

### 1. 容器高度与网格布局约束
- **保持页面整体高度为视口内可滚动的最小高度**：
  - 继续使用 `.app-chat-page` 的 `min-height: calc(100vh - 64px - 48px - 80px)`，但确保其子元素不再通过内容撑高页面高度，而是在内部产生滚动。
- **约束主内容区域高度**：
  - 在 `.main-content` 上保留 `flex: 1; min-height: 0; overflow: hidden;`，确保 `main` 内的高度被固定在视口范围内，由内部网格两列分摊高度。
  - 使用当前 `grid-template-columns: minmax(0, 1.1fr) minmax(0, 1.1fr)` 保持左右列接近 1:1 宽度，无需调整列宽比例。

### 2. 左右面板高度同步策略
- **统一面板高度**：
  - 确认 `.chat-panel` 与 `.preview-panel` 都使用：
    - `display: flex; flex-direction: column; flex: 1; min-height: 0; overflow: hidden;`
  - 如当前缺失 `flex: 1;`，在计划中补上，使两侧面板在各自的网格单元内高度拉满并保持一致。
- **右侧 iframe 高度**：
  - 保持 `.preview-body { flex: 1; overflow: hidden; }` 和 `.preview-iframe { width: 100%; height: 100%; }`，让 iframe 高度由面板高度决定，从而与左侧聊天区同步。

### 3. 左侧聊天消息区滚动行为
- **固定可视高度**：
  - 将 `chat-messages` 容器设置为：
    - `flex: 1; min-height: 0; overflow-y: auto;`，使其在 `chat-panel` 内部占据剩余空间，超出内容时只在此容器内部滚动。
  - 保证 `chat-input-bar` 固定在 `chat-panel` 底部，不随消息滚动：
    - `chat-panel`：`display: flex; flex-direction: column;`
    - `chat-messages`：位于中间，`flex: 1;`，内部滚动。
- **自动滚动到底部**：
  - 利用已有的 `chatMessagesRef`、`shouldAutoScroll` 和 `showScrollToBottom`：
    - 在消息列表渲染或 SSE 流更新之后，调用现有“滚动到底部”的函数，使滚动目标改为 `chat-messages` 容器（而不是整个窗口）。
    - 保持当前的“如果用户手动往上滚，则关闭自动吸底并展示 `↓` 按钮”的逻辑，仅确认 `handleChatScroll` 绑定在 `chatMessagesRef` 上保持生效。

### 4. 滚动条与视觉一致性
- **滚动条展示**：
  - 左侧 `chat-messages` 容器开启纵向滚动条，右侧 iframe 区域不额外出现纵向滚动（只依赖 iframe 内部自己的滚动）。
  - 如现有全局样式中有自定义滚动条，可以复用；否则使用浏览器默认滚动条，无需额外美化。
- **顶栏与工具条固定**：
  - 顶部 `.top-bar` 保持在 `main` 顶部，不随聊天内容滚动。
  - `generated-files-bar`、工作流卡片等仍在 `chat-messages` 内，随内容一起滚动，整体视觉保持不变，只是现在限制在可视高度内。

### 5. 行为验证与边界场景
- **测试场景**：
  - 少量消息：左侧不滚动，左右高度一致；右侧 iframe 正常展示。
  - 大量消息 / 多轮工作流卡片：
    - 只出现左侧内部滚动条，整页高度保持在视口范围内。
    - 新消息到来时自动滚动到底部；用户向上滚动时自动滚动关闭，并出现“回到底部”按钮。
  - 切换可视化编辑模式、刷新预览、导出对话等操作时，确认布局不跳变，左右高度始终同步。

- **回滚策略**：
  - 所有改动集中在 `AppChatView.vue` 的 `<style scoped>` 区域和极少量与滚动目标相关的代码位置，如出现问题可通过还原该文件的样式部分快速回退。