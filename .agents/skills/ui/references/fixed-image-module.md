# Fixed Image Module — 实现参考

## 1. 注册表（TypeScript）

```ts
// src/utils/imageModuleRegistry.ts
export interface FixedImageModuleDef {
  id: string
  src: string
  alt: string
  width?: number
  height?: number
  fit?: 'cover' | 'contain' | 'fill' | 'none'
  caption?: string
  className?: string
}

export const FIXED_IMAGE_MODULES: Record<string, FixedImageModuleDef> = {
  'brand-logo': {
    id: 'brand-logo',
    src: new URL('../picture/brand/logo.jpg', import.meta.url).href,
    alt: '品牌 Logo',
    width: 120,
    height: 40,
    fit: 'contain',
  },
}

export function getFixedImageModule(id: string): FixedImageModuleDef | undefined {
  return FIXED_IMAGE_MODULES[id]
}
```

## 2. Vue 3 组件

```vue
<!-- src/components/FixedImageModule.vue -->
<script setup lang="ts">
import { computed } from 'vue'
import { getFixedImageModule } from '@/utils/imageModuleRegistry'

const props = defineProps<{
  moduleId: string
  caption?: string
}>()

const def = computed(() => getFixedImageModule(props.moduleId))
</script>

<template>
  <figure v-if="def" class="fixed-image-module" :class="def.className">
    <img
      :src="def.src"
      :alt="def.alt"
      :width="def.width"
      :height="def.height"
      :style="{ objectFit: def.fit ?? 'contain', maxWidth: '100%' }"
      loading="lazy"
      draggable="false"
      :data-glyahh-module="def.id"
    />
    <figcaption v-if="caption ?? def.caption">{{ caption ?? def.caption }}</figcaption>
  </figure>
  <span v-else class="fixed-image-module--missing">[未知图片模块: {{ moduleId }}]</span>
</template>

<style scoped>
.fixed-image-module {
  display: inline-flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  margin: 0;
  vertical-align: middle;
}
.fixed-image-module img {
  display: block;
}
.fixed-image-module--missing {
  font-size: 12px;
  color: #b45309;
}
</style>
```

## 3. 内容解析器

```ts
// src/utils/fixedImageEmbedParser.ts
const FIXED_IMAGE_RE = /\[fixed_image\s+module="([^"]+)"\]/g

export type ContentSegment =
  | { kind: 'text'; content: string }
  | { kind: 'fixed_image'; moduleId: string }

export function splitContentWithFixedImages(raw: string): ContentSegment[] {
  const text = (raw ?? '').replace(/\r\n/g, '\n')
  const segments: ContentSegment[] = []
  let lastIndex = 0
  let match: RegExpExecArray | null

  FIXED_IMAGE_RE.lastIndex = 0
  while ((match = FIXED_IMAGE_RE.exec(text)) !== null) {
    const before = text.slice(lastIndex, match.index)
    if (before) segments.push({ kind: 'text', content: before })
    segments.push({ kind: 'fixed_image', moduleId: match[1] ?? '' })
    lastIndex = match.index + match[0].length
  }

  const tail = text.slice(lastIndex)
  if (tail) segments.push({ kind: 'text', content: tail })
  if (segments.length === 0) segments.push({ kind: 'text', content: '' })
  return segments
}
```

## 4. 渲染示例（Vue 模板片段）

```vue
<template v-for="(seg, i) in splitContentWithFixedImages(content)" :key="i">
  <FixedImageModule v-if="seg.kind === 'fixed_image'" :module-id="seg.moduleId" />
  <span v-else class="text-segment">{{ seg.content }}</span>
</template>
```

## 5. React 等价物（简版）

```tsx
// FixedImageModule.tsx
import { FIXED_IMAGE_MODULES } from '@/utils/imageModuleRegistry'

export function FixedImageModule({ moduleId, caption }: { moduleId: string; caption?: string }) {
  const def = FIXED_IMAGE_MODULES[moduleId]
  if (!def) return <span>[未知图片模块: {moduleId}]</span>
  return (
    <figure className="fixed-image-module">
      <img src={def.src} alt={def.alt} width={def.width} height={def.height} data-glyahh-module={def.id} />
      {(caption ?? def.caption) && <figcaption>{caption ?? def.caption}</figcaption>}
    </figure>
  )
}
```

## 6. 纯 HTML + JSON 注册表

`public/assets/image-modules.json`:

```json
{
  "brand-logo": {
    "src": "/assets/brand/logo.jpg",
    "alt": "品牌 Logo",
    "width": 120,
    "height": 40
  }
}
```

页面内：

```html
<p>标题旁 logo：</p>
<img data-glyahh-module="brand-logo" alt="" />
<script>
  fetch('/assets/image-modules.json')
    .then((r) => r.json())
    .then((registry) => {
      document.querySelectorAll('[data-glyahh-module]').forEach((el) => {
        const id = el.getAttribute('data-glyahh-module')
        const def = registry[id]
        if (!def) return
        el.src = def.src
        el.alt = def.alt
        if (def.width) el.width = def.width
        if (def.height) el.height = def.height
      })
    })
</script>
```

## 7. 给 AI 的 system 片段（可复制进 Prompt）

```text
[fixed_image_policy]
- 消息或规格中的 [fixed_image module="ID"] 表示已注册固定图片，必须使用注册表精确 src。
- 禁止：picsum、placeholder、SVG 重绘、CSS 模拟、改写 alt 语义后换图。
- HTML: <img src="精确URL" data-glyahh-module="ID" />
- Vue:  <FixedImageModule module-id="ID" />
- 仅允许调整模块在布局中的位置（margin/flex），不允许更换图片资源。
```

## 8. 新增模块检查清单

- [ ] 图片文件已入库（git 或 OSS 固定路径）
- [ ] `FIXED_IMAGE_MODULES` 已注册 `id`
- [ ] 组件/解析器可解析该 `id`
- [ ] prompt 中写了 `[fixed_image module="id"]` 或组件调用，而非文字描述图样
- [ ] 构建后 URL 可访问（Vite `?url` import 或 `public/` 路径）
