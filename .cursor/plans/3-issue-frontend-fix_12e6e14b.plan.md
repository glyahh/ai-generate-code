---
name: 3-issue-frontend-fix
overview: 仅前端最小改动，解决 (1) 输入框上方与工具卡片下方的空白；(2) Mermaid 误报文案；(3) 在"选择工具"胶囊右侧加 shimmer 写入动效。
todos:
  - id: fix-blank
    content: "改 .app-chat-page 的 height:100vh 为 min-height: calc(100vh - 64px - 48px - 80px)；删除 .code-block-container 与 .code-block 的 min-height（图一图二空白）"
    status: pending
  - id: fix-mermaid-text
    content: 把工作流卡片的 'Mermaid构造异常（不影响生成）' 改为 '存在 Mermaid 构造失败（不影响生成）'
    status: pending
  - id: shimmer-anim
    content: "在 tool_request 胶囊右侧加 shimmer 光带 + 三点闪烁 + 文案，新增 isToolRequestPending(m, idx)；样式色板与现有 #22c55e/#0ea5e9 风格统一"
    status: pending
  - id: verify
    content: 本地启动 npm run dev，复现首轮 Vue 生成 → 验证三处效果，无新增 lint 报错
    status: pending
isProject: false
---

# 前端 3 处最小改动修复

只动一个文件：`ai-generate-code-frontend/src/page/App/AppChatView.vue`。无后端改动、无新增组件、无依赖变化。

## 1. 空白问题（图一 + 图二，最小改动）

两条空白根因都已定位。

**图一（输入框上方大空白）**：[ai-generate-code-frontend/src/page/App/AppChatView.vue](ai-generate-code-frontend/src/page/App/AppChatView.vue) 第 2804-2810 行 `.app-chat-page` 强制 `height: 100vh`，叠加外层 `BasicLayout` 的 `min-height: calc(100vh - 64px - 48px)` + `padding-bottom: 80px`，总高溢出。

```2804:2810:ai-generate-code-frontend/src/page/App/AppChatView.vue
.app-chat-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100vh;
  overflow: hidden;
}
```

改为：

```css
.app-chat-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: calc(100vh - 64px - 48px - 80px); /* 减去 BasicLayout header/padding/bottom */
  overflow: hidden;
}
```

**图二（"使用工具"卡片下方空白）**：[ai-generate-code-frontend/src/page/App/AppChatView.vue](ai-generate-code-frontend/src/page/App/AppChatView.vue) 第 3408-3421 行 `.code-block-container` `min-height: 200px` + `.code-block` `min-height: 180px`，流式中代码量少时撑出大块空高。

```3408:3421:ai-generate-code-frontend/src/page/App/AppChatView.vue
.bubble-content :deep(.code-block-container) {
  max-width: 100%;
  width: 100%;
  margin: 0;
  border-radius: 6px;
  flex: 1;
  min-height: 200px;
}

.bubble-content :deep(.code-block-container .code-block) {
  max-height: none;
  flex: 1;
  min-height: 180px;
}
```

把两个 `min-height` 删除即可（`flex: 1` 仍能在父容器有约束时撑开；流式无内容则自然贴合）。

## 2. Mermaid 误报文案

不动后端 `mermaidError` 触发逻辑（保留你"重复构建是有意"的偏好），只把警示语义从 "异常" 改为 "失败"，避免误导。

[ai-generate-code-frontend/src/page/App/AppChatView.vue](ai-generate-code-frontend/src/page/App/AppChatView.vue) 第 2542-2544 行：

```2542:2544:ai-generate-code-frontend/src/page/App/AppChatView.vue
<div v-if="hasMermaidNoticeForMessage(m)" class="workflow-mermaid-notice">
  Mermaid构造异常（不影响生成）
</div>
```

改为：

```html
<div v-if="hasMermaidNoticeForMessage(m)" class="workflow-mermaid-notice">
  存在 Mermaid 构造失败（不影响生成）
</div>
```

`hasMermaidNoticeForMessage` 与后端 `mermaidError` 链路保持不变。

## 3. "选择工具" 胶囊右侧 shimmer 动效（仅 tool_request）

**触发条件**：当前 `tool_request` 段为该消息中最后一个、且消息流式仍在进行（`isStreamActiveForMessage(m) === true`）时，胶囊右侧追加 shimmer 光带 + 闪烁圆点 + "代码努力写入中…" 文案；流结束自动隐藏。

### 模板改动

[ai-generate-code-frontend/src/page/App/AppChatView.vue](ai-generate-code-frontend/src/page/App/AppChatView.vue) 第 2573-2577 行：

```2573:2577:ai-generate-code-frontend/src/page/App/AppChatView.vue
<!-- TOOL_REQUEST：选择工具卡片（一次性） -->
<div v-if="segment.kind === 'tool_request'" class="tool-hint-pill">
  <span class="tool-hint-label">选择工具</span>
  <span class="tool-hint-main">{{ segment.toolName }}</span>
</div>
```

改为：

```html
<div v-if="segment.kind === 'tool_request'" class="tool-hint-pill">
  <span class="tool-hint-label">选择工具</span>
  <span class="tool-hint-main">{{ segment.toolName }}</span>
  <span
    v-if="isToolRequestPending(m, idx)"
    class="tool-hint-shimmer"
    aria-label="代码生成中"
  >
    <i class="dot" /><i class="dot" /><i class="dot" />
    <span class="shimmer-text">代码努力写入中</span>
  </span>
</div>
```

### 脚本改动

在 `<script setup>` 现有的 `isStreamActiveForMessage` 附近新增：

```ts
function isToolRequestPending(m: ChatMessage, idx: number): boolean {
  if (!isStreamActiveForMessage(m)) return false
  const segs = getMessageUiSegments(m)
  // 仅最后一个 tool_request 才显示动效，避免历史 pill 永远闪
  for (let i = segs.length - 1; i > idx; i--) {
    if (segs[i].kind === 'tool_request') return false
  }
  return true
}
```

### 样式（与现有 pill 风格统一：999px 圆角、淡灰底、雾化光晕）

在第 3631-3660 行 `.tool-hint-pill` 样式块附近追加：

```css
.tool-hint-shimmer {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 1px 8px 1px 6px;
  margin-left: 4px;
  border-radius: 999px;
  background: linear-gradient(
    90deg,
    rgba(34, 197, 94, 0.12) 0%,
    rgba(14, 165, 233, 0.18) 50%,
    rgba(34, 197, 94, 0.12) 100%
  );
  background-size: 200% 100%;
  animation: tool-hint-shimmer-slide 1.6s linear infinite;
  font-size: 11px;
  color: #0f172a;
}
.tool-hint-shimmer .dot {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #22c55e;
  animation: tool-hint-dot-blink 1.2s ease-in-out infinite;
}
.tool-hint-shimmer .dot:nth-child(2) { animation-delay: 0.18s; background: #0ea5e9; }
.tool-hint-shimmer .dot:nth-child(3) { animation-delay: 0.36s; background: #22c55e; }
.tool-hint-shimmer .shimmer-text {
  font-weight: 500;
  letter-spacing: 0.02em;
  white-space: nowrap;
}
@keyframes tool-hint-shimmer-slide {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
@keyframes tool-hint-dot-blink {
  0%, 100% { opacity: 0.35; transform: scale(0.85); }
  50%      { opacity: 1;    transform: scale(1.1); }
}
```

色板取自项目主调（绿 `#22c55e` → 青蓝 `#0ea5e9`），与既有 `.tool-hint-pill` 的 999px 胶囊外观一致，符合"轻玻璃/柔科技"调性。

## 验收

- 首轮生成完毕后输入框上方不再出现大片空白（图一）。
- "使用工具" 写入文件卡片在流式中不再出现 200px 空高带（图二）。
- 工作流卡片右上角文案变为 "存在 Mermaid 构造失败（不影响生成）"。
- 第二轮 / 后续生成期间，最新一个 "选择工具 xxx" 胶囊右侧出现 shimmer + 三点闪烁 + "代码努力写入中"，流式 done 后自动消失；历史胶囊不闪。

## 不在范围内的事项

- 后端 `mermaidError` 设置逻辑、Mermaid CLI 错误本身（`B --> D[人工智能)` 这种 LLM 拼写错），按你的要求不动。
- 重复构建按你的要求保留。
