---
name: ui
description: 固定图片模块（Fixed Image Module）集成规范：用注册表 ID + 嵌入语法让 AI 按原图精确渲染，禁止重绘/占位图替换。适用于 /UI、$UI、固定图片、图片模块、和图片文字混排、禁止 AI 重画 logo/插图 等请求；Cursor、Codex、Claude Code 三端兼容。
---

# UI（固定图片模块）

本仓库完整规范见：**`.agents/skills/ui/SKILL.md`**

实现模板见：**`.agents/skills/ui/references/fixed-image-module.md`**

## 触发

- `/UI`、`$UI`
- 固定图片、图片模块、与文字混排、禁止 AI 重画

## 要点

1. 注册表 `FIXED_IMAGE_MODULES` — `id → src/alt/尺寸`
2. 组件 `FixedImageModule` — 只按 id 渲染，未知 id 报错不 fallback
3. 嵌入语法 `[fixed_image module="id"]` — 与任意文本混排
4. 禁止 picsum / placeholder / SVG 重绘
