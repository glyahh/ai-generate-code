# 沉浸式高端品牌站模式（Hubtown 案例）

> 参考站点：[Hubtown Limited](https://hubtown.co.in/)（Unseen Studio × Hubtown，FWA Site of the Day）  
> 触发词：`Hubtown`、`FWA`、`Awwards`、`滚动叙事`、`沉浸式品牌站`、`像大厂官网`、`WebGL 背景`、`像素 loader`

## 何时启用本参考

用户要的是 **观感「贵」、像获奖站** 的品牌官网/地产/奢侈品落地页，而不是 SaaS 后台或快速 MVP 时：

1. 先读本文件，再跑 `search.py --design-system`（关键词见文末）
2. 明确 **预算/周期**：此类站多为 **数周～数月、多角色协作**；勿承诺「几天复刻 100%」
3. 默认栈：**Nuxt 3 / Vue 3 + SSR**；动效 **Lenis + GSAP**；可选 **Three.js + Theatre.js**；内容 **Sanity 等 Headless CMS**

## 技术栈（Hubtown 实测）

| 层级 | 选型 | 作用 |
|------|------|------|
| 框架 | Nuxt 3（`#__nuxt`、`/_nuxt/`） | SSR + 路由 + 构建 |
| 3D 编排 | Theatre.js（`theatreProjectName`、状态 JSON） | 镜头/时间轴，非手搓每帧 |
| CMS | Sanity | 文案区块、预览编辑 |
| 滚动 | Lenis（容器 class `lenis`） | 惯性滚动，统一节奏 |
| 动效 | GSAP + 自定义 SVG/CSS | 分词 reveal、描边、位移 |
| 部署 | Netlify 等 | 静态/边缘托管 |

**不是** Webflow 拖拽站；是定制前端 + CMS + 可选 WebGL。

## 视觉设计语言（可复用 token）

| Token | 示例值 | 用途 |
|-------|--------|------|
| `--bg-dark` | `#020A19` | 主背景 |
| `--text-off-blue` | `#D5E0FF` | 主文字/描边 |
| `--glass` | `rgba(213,224,255,0.05)` + `backdrop-filter: blur(20px)` | 卡片/按钮底 |
| 标签字 | `font-mono`、小字号、`tracking` 加宽、`uppercase` | 「09 PROJECTS」类元信息 |
| 标题 | 粗体 Grotesk 系、大行高对比 | Hero 分行排版 |

**标志性 UI：**

- **斜切边框按钮**（`clip-path` + SVG `beveled-box`）
- **像素格 / 描边字 Loader**（品牌名拆成 SVG path + `stroke-dashoffset`）
- **全屏章节滚动**（每屏一个主题词 + Prev/Next + 底部进度）

## 体验结构（信息架构）

1. **Loader**：进度数字 + 品牌图形动画 → `Ready to Explore`
2. **Hero / 价值主张**：大标题分行（`We build / the future / of real estate`）
3. **滚动章节**：Future → Innovation → Collaboration → …（每章独立视觉）
4. **数据锚点**：地区/项目数（如 Central Suburbs · 09 PROJECTS）
5. **常驻栏**：Sound、Prev/Next、Chat（WhatsApp 等）
6. **WebGL 层**：模糊/遮罩叠在内容下，**装饰为主**，核心文案仍 HTML

## 动效原则（与 ui-ux-pro-max 对齐）

| 规则 | 做法 | 避免 |
|------|------|------|
| 性能 | 只动画 `transform`、`opacity` | 动画 `width`/`height`/`top` |
| 密度 | 每屏 1～2 个焦点动效 | 全页 `animate-bounce` |
| 滚动 | Lenis + ScrollTrigger 编排章节 | 无序 parallax 堆叠 |
| 文字 | 遮罩滑入 / 逐词 reveal（保留 `aria-label`） | 纯图片标题、无无障碍 |
| 降级 | `prefers-reduced-motion` 跳过 WebGL/长动画 | 强制 scroll-jacking |
| 3D | 仅 1～2 个关键画面；Draco 压缩模型 | 整站重度 WebGL |

## 实现分层（推荐落地顺序）

### Tier A — 约 30% 气质（1～2 人可做）

- 双色 + mono 标签 typography
- Lenis 平滑滚动
- GSAP 标题/段落 reveal
- 简单全屏分屏（CSS `100dvh` + snap 或 ScrollTrigger pin）
- 统一按钮 hover（颜色/边框，**不用 scale 顶布局**）

### Tier B — 约 60% 气质

- 定制 Loader（SVG 或 Canvas 2D）
- `clip-path` 斜切组件库（2～3 变体）
- 玻璃态导航 + 浮动边距（非贴边 `top-0`）
- Sanity/Contentful 接文案

### Tier C — 接近 Hubtown（需专业分工）

- Three.js 场景 + Theatre.js 时间轴
- 地区/项目 3D 或 2.5D 地图交互
- 音效开关、自定义滚动条、多断点精细调校
- FWA 级性能与 SEO（SSR meta、结构化数据）

## 反模式（勿做）

- 用通用 AI 紫渐变 + Inter + 卡片网格冒充「获奖站」
- 无 Loader 直接进入重 WebGL（白屏/上下文失败）
- 移动端仍强制完整 3D（应降级为静态图/视频）
- 忽视对比度：浅蓝字 on 深蓝底需测 **≥ 4.5:1**
- emoji 当图标；hover 用 `scale` 导致布局抖动

## search.py 推荐查询

```bash
# 设计系统
python3 skills/ui-ux-pro-max/scripts/search.py "luxury real estate immersive dark scroll narrative brand" --design-system -p "Immersive Brand"

# 补充域
python3 skills/ui-ux-pro-max/scripts/search.py "scroll storytelling reduced motion" --domain ux
python3 skills/ui-ux-pro-max/scripts/search.py "glassmorphism dark OLED" --domain style
python3 skills/ui-ux-pro-max/scripts/search.py "immersive hero chapters" --domain landing
python3 skills/ui-ux-pro-max/scripts/search.py "webgl client only" --stack nuxtjs
```

## 与本仓库前端的关系

glyahh 前端为 **Vue 3 + Vite + Ant Design Vue**。若只做「气质借鉴」：

- 用 **Pinia + 路由** 代替 Nuxt 亦可
- Lenis/GSAP 在 `onMounted` 初始化，路由切换时 `kill()` 重建
- **不要** 为聊天/管理后台全站上 WebGL；仅限营销落地页路由

## 验收清单（沉浸式品牌站）

- [ ] Loader 可跳过或 ≤ 3s（`reduced-motion` 直接进内容）
- [ ] 375 / 768 / 1440 无横向滚动；触控目标 ≥ 44px
- [ ] 键盘可到达导航与 CTA；焦点环可见
- [ ] WebGL 失败时有 HTML 降级
- [ ] LCP 元素非全屏 3D；关键文案在首屏 HTML
- [ ] CMS 改文案不破坏动效绑定（data-attribute 或稳定 class）
