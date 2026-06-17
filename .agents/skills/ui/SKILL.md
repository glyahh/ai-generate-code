---
name: ui
description: 固定图片模块（Fixed Image Module）集成规范：用注册表 ID + 嵌入语法让 AI 按原图精确渲染，禁止重绘/占位图替换。适用于 /UI、$UI、固定图片、图片模块、和图片文字混排、禁止 AI 重画 logo/插图 等请求；Cursor、Codex、Claude Code 三端兼容。
metadata:
  short-description: Fixed image module — no AI redraw
---

# UI（固定图片模块）

## 触发

- `/UI`、`$UI`、`/ui`、`$ui`
- 「固定图片」「图片模块」「和图片一起显示」「不要 AI 重画」「按我的图渲染」
- 用户给出 JPG/PNG 并要求做成可复用、可嵌入任意内容的模块

未涉及固定图片/禁止重绘时，不要套用本 skill。

## 核心原则（强制）

1. **图片是引用，不是描述** — 用 `moduleId` + 固定 `src`，禁止用自然语言让 AI「画一张类似的」。
2. **单一真相源** — 全项目只维护一份 `imageModuleRegistry`（或等价配置）。
3. **嵌入语法可解析** — 文本与图片混排用机器标记 `[fixed_image module="id"]`，不用 prose 描述布局。
4. **样式归组件** — 尺寸、圆角、caption 写在 `FixedImageModule` 内，不由 AI 每次生成。
5. **禁止占位替换** — 不得用 picsum、placeholder、SVG 重绘、CSS 渐变「模拟」已注册模块。

## 工作流

### Step 1 — 盘点资产与注册

1. 确认图片物理路径（如 `src/picture/brand/logo.jpg`、OSS 固定 URL、`public/assets/`）。
2. 在注册表新增条目（见 `references/fixed-image-module.md`）：

```ts
export const FIXED_IMAGE_MODULES = {
  'brand-logo': {
    id: 'brand-logo',
    src: '/assets/brand/logo.jpg', // 或 import.meta.url / OSS
    alt: '品牌 Logo',
    width: 120,
    height: 40,
    fit: 'contain',
    caption: '',
  },
}
```

3. 每个模块必须有唯一 `id`；`src` 一旦发布**不可随意改路径**（改则同步所有引用）。

### Step 2 — 实现通用组件

创建 `FixedImageModule`（Vue/React/原生 HTML 包装均可，见 reference）：

- Props：`moduleId`（必填）、`caption?`（覆盖注册表）
- 从注册表取 `src/alt/width/height/fit`
- 未知 id → 渲染 `[未知图片模块: xxx]`，**禁止** fallback 到随机图

### Step 3 — 与文字混排（任意内容集成）

**嵌入标记（推荐）：**

```text
这是说明文字，下面紧跟固定 logo：

[fixed_image module="brand-logo"]

继续后面的文字或代码块。
```

实现解析器 `splitContentWithFixedImages()`：

- 正则：`/\[fixed_image\s+module="([^"]+)"\]/g`
- 输出 segment：`{ kind: 'text' | 'fixed_image', ... }`
- 渲染：`text` → 文本；`fixed_image` → `<FixedImageModule module-id="..." />`

可参考仓库内 `visual_edit_selection` 分段模式（结构化块 → 专用 UI，不进 markdown 纯文本）。

### Step 4 — AI 生成网页时的约束

在 prompt / system 中写入（实施时按项目栈二选一或并存）：

**HTML：**

```html
<img src="注册表精确URL" alt="注册表alt" data-glyahh-module="brand-logo" width="120" height="40" />
```

**Vue：**

```vue
<FixedImageModule module-id="brand-logo" />
```

**禁止项：**

- 不得 `https://picsum.photos` 替代已注册模块
- 不得 inline SVG / Canvas / CSS 重绘该图
- 不得改 `src` 除非用户显式更换注册表

### Step 5 — 验收

| 检查项 | 通过标准 |
|--------|----------|
| 视觉 | 与原始 JPG/PNG 一致，无「AI 重画感」 |
| 引用 | 源码/global 搜索模块 id，`src` 仅来自注册表 |
| 混排 | 文本上下/左右均可插入 `[fixed_image ...]` 且解析正确 |
| 历史 | 聊天记录/静态页刷新后仍显示同一 URL |
| 负例 | 无 picsum、无 placeholder、无描述性重绘 |

## 栈适配（按项目自动选择）

| 栈 | 注册表位置建议 | 组件路径建议 |
|----|----------------|--------------|
| Vue 3 | `src/utils/imageModuleRegistry.ts` | `src/components/FixedImageModule.vue` |
| React | `src/utils/imageModuleRegistry.ts` | `src/components/FixedImageModule.tsx` |
| 纯 HTML | `assets/image-modules.json` + 构建脚本 | `<img data-glyahh-module>` + 小脚本挂载 |
| 后端 prompt | `resources/Prompt/` 片段 | 注入「禁止替换固定模块」条款 |

先读项目既有目录（`frontend-design.md`、`CLAUDE.md`、组件树），再落位；**不引入新 UI 库**除非用户要求。

## 交付物

1. 注册表条目（含 id、src、alt、尺寸）
2. `FixedImageModule` 组件（或 HTML 等价物）
3. （若需混排）解析器 + 渲染分支
4. （若 AI 生成站点）prompt 补丁或 system 片段
5. 简短说明：如何在新位置插入 `[fixed_image module="id"]` 或组件标签

## 与相关 skill 的关系

- `/build-frontend`：落地 Vue/React 文件与目录规范
- `/ui-ux-pro-max`：模块**之外**的排版、配色、动效；**不**覆盖固定图片 src
- `/color_diff`：按标注改 UI；若改到固定模块，只改**布局位置**，不换图资源

## 参考

详细模板与多栈示例：`references/fixed-image-module.md`
