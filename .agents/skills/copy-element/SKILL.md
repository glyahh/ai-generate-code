---
name: copy-element
description: Recreate a referenced UI element or page section from a URL or screenshot as a reusable component without copying protected assets.
metadata:
  short-description: Recreate UI elements from references
---

# copy-element

用法：当用户说“抄这个组件/复刻这个页面元素/像某网站一样”时使用。

工作流：
1) 用户提供 URL + 目标元素描述（或截图）。
2) 优先用 MCP：`web-to-mcp`（组件抽取）或 `playwright-mcp`（页面结构/样式抓取）。
3) 生成：可复用组件（含样式、状态、交互、响应式）。

注意：只做“风格与结构参考”，不要复制受版权保护的完整资源（图标/图片/文案）。