---
name: ui-ux-pro-max
description: Improve UI and UX with information architecture, component guidance, design tokens, and implementation-focused recommendations. Includes FWA-grade immersive brand site pattern (Hubtown reference).
metadata:
  short-description: Upgrade UI and UX quality
---

# ui-ux-pro-max

用法：当用户说「优化 UI/UX / 做得更高级 / 像大厂 / 重做界面 / Hubtown / FWA / 滚动叙事品牌站」时使用。

## 工作流

1. **明确**：平台（Web/移动端）、技术栈（React/Vue/Nuxt/Next/Tailwind 等）、品牌风格、行业。
2. **设计系统（推荐）**：若本机已安装完整 skill（含 `scripts/search.py`），执行：
   ```bash
   python3 skills/ui-ux-pro-max/scripts/search.py "<产品类型> <行业> <风格关键词>" --design-system -p "项目名"
   ```
3. **沉浸式 / 获奖级品牌站**：若用户要 Hubtown、FWA、Awwards、沉浸式地产/奢侈品官网等质感，**必须先读**  
   [`references/immersive-brand-site-hubtown.md`](references/immersive-brand-site-hubtown.md)  
   再补充 search：`luxury real estate immersive dark scroll narrative`、`--domain ux`、`--stack nuxtjs`。
4. **产出**：
   - 信息架构与关键页面列表
   - 组件拆分与交互细节（loading/empty/error/状态）
   - 颜色/字体/间距/栅格建议（可用 token/变量）
5. **落地**：直接改代码并跑最小验证（lint/build）。

## 原则（优先级）

| 优先级 | 类别 |
|--------|------|
| CRITICAL | 无障碍（对比度、焦点、键盘、aria） |
| CRITICAL | 触控（≥44px、click 优先于纯 hover） |
| HIGH | 性能（图片、减少动效、`prefers-reduced-motion`） |
| HIGH | 响应式布局 |
| MEDIUM | 字体/配色、动效时长 150–300ms（微交互） |

## 沉浸式品牌站（Hubtown）速查

- **栈**：Nuxt 3 / Vue 3 SSR + Lenis + GSAP +（可选）Three.js/Theatre.js + Sanity CMS  
- **视觉**：深蓝 `#020A19` + 浅蓝 `#D5E0FF`、mono 标签、斜切 `clip-path` 按钮、玻璃态 blur  
- **结构**：品牌 Loader → 全屏章节滚动 → 地区/数据锚点 → WebGL 作背景层  
- **落地分层**：Tier A（字体+滚动+reveal）→ Tier B（Loader+斜切组件+CMS）→ Tier C（WebGL+Theatre）  
- **详述**：见 [`references/immersive-brand-site-hubtown.md`](references/immersive-brand-site-hubtown.md)

## 完整数据与脚本

本仓库 `.agents/skills/ui-ux-pro-max/` 含 **Hubtown 参考**；若需 67 风格 / CSV 检索 / `search.py`，请使用用户本机  
`~/.claude/skills/ui-ux-pro-max/`（与 Claude/Codex 附带的完整 skill 同步）。

## 交付前检查（摘要）

- 不用 emoji 当图标；可点击处有 `cursor-pointer`
- 浅色模式正文对比度 ≥ 4.5:1；玻璃卡片在浅色下可见
- 375 / 768 / 1024 / 1440 无横向滚动
- `prefers-reduced-motion` 已处理；沉浸式站需 WebGL/HTML 降级
