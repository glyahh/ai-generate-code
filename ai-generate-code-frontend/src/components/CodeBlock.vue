<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

interface Props {
  code: string
  language?: string
  isStreaming?: boolean // 是否正在流式输出中
  /** 多文件工作流：对应后端 `### 文件名` 的展示名 */
  fileLabel?: string
}

const props = withDefaults(defineProps<Props>(), {
  language: 'text',
  isStreaming: false,
})

const codeRef = ref<HTMLElement | null>(null)

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

function withStashedTokens(
  source: string,
  builders: Array<{ re: RegExp; className: string }>,
): { text: string; restore: (s: string) => string } {
  const stash: string[] = []
  let result = source
  builders.forEach(({ re, className }) => {
    result = result.replace(re, (m) => {
      const id = stash.length
      stash.push(`<span class="${className}">${m}</span>`)
      return `__TOK_${id}__`
    })
  })
  return {
    text: result,
    restore: (s: string) => s.replace(/__TOK_(\d+)__/g, (_, i) => stash[Number(i)] ?? ''),
  }
}

function withStashedHighlightedSpans(
  source: string,
): { text: string; restore: (s: string) => string } {
  const stash: string[] = []
  const text = source.replace(/<span class="code-[^"]+">[\s\S]*?<\/span>/g, (m) => {
    const id = stash.length
    stash.push(m)
    return `__SPAN_${id}__`
  })
  return {
    text,
    restore: (s: string) => s.replace(/__SPAN_(\d+)__/g, (_, i) => stash[Number(i)] ?? ''),
  }
}

function highlightJsLike(raw: string): string {
  const escaped = escapeHtml(raw)
  const { text, restore } = withStashedTokens(escaped, [
    { re: /\/\*[\s\S]*?\*\//g, className: 'code-comment' },
    { re: /\/\/[^\r\n]*/g, className: 'code-comment' },
    { re: /(["'`])(?:\\.|(?!\1)[\s\S])*?\1/g, className: 'code-string' },
  ])

  const highlightedPrimary = text
    .replace(
      /\b(const|let|var|function|return|if|else|switch|case|break|default|for|while|do|continue|import|from|export|class|extends|new|this|async|await|try|catch|finally|throw|typeof|instanceof|in|of|static|get|set|super|null|undefined|true|false)\b/g,
      '<span class="code-keyword">$1</span>',
    )
    .replace(/\b(\d+(?:\.\d+)?)\b/g, '<span class="code-number">$1</span>')

  // 先保护已插入的高亮 span，再做后续替换，避免把 <span ...> 标签本身二次改写。
  const { text: safeText, restore: restoreSpans } = withStashedHighlightedSpans(highlightedPrimary)

  const highlightedFinal = safeText
    .replace(/([=+\-*/%<>!&|^~?:]+)/g, '<span class="code-operator">$1</span>')
    .replace(/\b([A-Za-z_$][\w$]*)(?=\s*\()/g, '<span class="code-function">$1</span>')

  return restore(restoreSpans(highlightedFinal))
}

function highlightCss(raw: string): string {
  const escaped = escapeHtml(raw)
  const { text, restore } = withStashedTokens(escaped, [
    { re: /\/\*[\s\S]*?\*\//g, className: 'code-comment' },
    { re: /(["'])(?:\\.|(?!\1)[\s\S])*?\1/g, className: 'code-string' },
  ])

  const highlighted = text
    .replace(/([.#][\w-]+)/g, '<span class="code-selector">$1</span>')
    .replace(/(@[\w-]+)/g, '<span class="code-keyword">$1</span>')
    .replace(/([\w-]+)(\s*:)/g, '<span class="code-property">$1</span>$2')
    .replace(/(:\s*)([^;}{\n]+)(;?)/g, '$1<span class="code-value">$2</span>$3')
    .replace(/\b(\d+(?:\.\d+)?(?:px|em|rem|vh|vw|%|s|ms|deg)?)\b/g, '<span class="code-number">$1</span>')

  return restore(highlighted)
}

function highlightHtml(raw: string): string {
  const escaped = escapeHtml(raw)
  return escaped
    .replace(/&lt;!--[\s\S]*?--&gt;/g, '<span class="code-comment">$&</span>')
    .replace(/(&lt;\/?)([\w-]+)([\s\S]*?)(&gt;)/g, (_, p1, p2, p3, p4) => {
      const attrs = String(p3).replace(
        /([:@\w-]+)(\s*=\s*)(["'])([\s\S]*?)(\3)/g,
        '<span class="code-attr">$1</span>$2$3<span class="code-string">$4</span>$5',
      )
      return `${p1}<span class="code-tag">${p2}</span>${attrs}${p4}`
    })
}

function highlightVue(raw: string): string {
  const blockRe = /(<script\b[^>]*>)([\s\S]*?)(<\/script>)|(<style\b[^>]*>)([\s\S]*?)(<\/style>)/gi
  let cursor = 0
  let out = ''
  let m: RegExpExecArray | null

  while ((m = blockRe.exec(raw)) !== null) {
    const idx = m.index
    if (idx > cursor) out += highlightHtml(raw.slice(cursor, idx))
    if (m[1] != null) {
      out += highlightHtml(m[1])
      out += highlightJsLike(m[2] ?? '')
      out += highlightHtml(m[3] ?? '')
    } else if (m[4] != null) {
      out += highlightHtml(m[4])
      out += highlightCss(m[5] ?? '')
      out += highlightHtml(m[6] ?? '')
    }
    cursor = idx + m[0].length
  }
  if (cursor < raw.length) out += highlightHtml(raw.slice(cursor))
  return out
}

const highlightedCode = computed(() => {
  const raw = props.code ?? ''
  const lang = (props.language ?? 'text').toLowerCase()
  if (lang === 'vue') return highlightVue(raw)
  if (lang === 'javascript' || lang === 'js' || lang === 'typescript' || lang === 'ts') return highlightJsLike(raw)
  if (lang === 'css' || lang === 'scss' || lang === 'less') return highlightCss(raw)
  if (lang === 'html' || lang === 'xml') return highlightHtml(raw)
  return highlightJsLike(raw)
})

/**
 * 复制代码到剪贴板
 */
async function copyCode() {
  try {
    await navigator.clipboard.writeText(props.code)
  } catch {
    // 剪贴板权限等由调用方处理
  }
}

function scrollToTop() {
  if (!codeRef.value) return
  codeRef.value.scrollTo({ top: 0, behavior: 'smooth' })
}

function scrollToBottom() {
  if (!codeRef.value) return
  codeRef.value.scrollTo({ top: codeRef.value.scrollHeight, behavior: 'smooth' })
}
</script>

<template>
  <div class="code-block-container">
    <div class="code-block-header">
      <div class="code-block-header-left">
        <span v-if="fileLabel" class="code-file-label">{{ fileLabel }}</span>
        <span class="code-language">{{ language || 'text' }}</span>
      </div>
      <button class="copy-button" @click="copyCode" title="复制代码">
        📋
      </button>
    </div>
    <pre ref="codeRef" class="code-block"><code v-html="highlightedCode"></code><span v-if="isStreaming" class="streaming-cursor">▋</span></pre>
    <button class="scroll-button scroll-button-top" @click="scrollToTop" title="回到顶部">
      ↑
    </button>
    <button class="scroll-button scroll-button-bottom" @click="scrollToBottom" title="跳到底部">
      ↓
    </button>
  </div>
</template>

<style scoped>
.code-block-container {
  margin: 12px 0;
  border-radius: 8px;
  overflow: hidden;
  background: #1e1e1e;
  border: 1px solid #3e3e3e;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  width: 100%;
  max-width: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
  position: relative;
}

.code-block-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: #2d2d2d;
  border-bottom: 1px solid #3e3e3e;
}

.code-block-header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.code-file-label {
  font-size: 12px;
  color: #c8c8c8;
  font-weight: 600;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 42vw;
}

.code-language {
  font-size: 12px;
  color: #858585;
  font-weight: 500;
  text-transform: uppercase;
}

.copy-button {
  background: transparent;
  border: none;
  color: #858585;
  cursor: pointer;
  font-size: 14px;
  padding: 4px 8px;
  border-radius: 4px;
  transition: all 0.2s;
}

.copy-button:hover {
  background: #3e3e3e;
  color: #fff;
}

.code-block {
  margin: 0;
  padding: 16px;
  background: #1e1e1e;
  color: #d4d4d4;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre;
  word-wrap: normal;
  overflow: auto;
  max-height: 60vh;
  /* 内嵌式、尽量淡的滚动条 */
  scrollbar-width: thin;
  scrollbar-color: rgba(255, 255, 255, 0.2) transparent;
}

.code-block::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

.code-block::-webkit-scrollbar-track {
  background: transparent;
}

.code-block::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.15);
  border-radius: 4px;
}

.code-block::-webkit-scrollbar-thumb:hover {
  background: rgba(255, 255, 255, 0.25);
}

.code-block::-webkit-scrollbar-corner {
  background: transparent;
}

.code-block code {
  display: block;
  width: 100%;
  min-width: min-content;
}

.scroll-button {
  position: absolute;
  right: 6px;
  width: 22px;
  height: 22px;
  border-radius: 999px;
  border: none;
  background: rgba(15, 23, 42, 0.65);
  color: #f9fafb;
  font-size: 12px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.4);
  opacity: 0.0;
  transform: translateY(0);
  transition: opacity 0.18s ease, transform 0.18s ease, background 0.18s ease;
}

.scroll-button-top {
  top: 10px;
}

.scroll-button-bottom {
  bottom: 10px;
}

.code-block-container:hover .scroll-button {
  opacity: 1;
}

.scroll-button:hover {
  background: rgba(37, 99, 235, 0.9);
}

/* 语法高亮颜色 */
:deep(.code-keyword) {
  color: #569cd6;
  font-weight: 500;
}

:deep(.code-string) {
  color: #e6b450;
}

:deep(.code-comment) {
  color: #7f8ea3;
  font-style: italic;
}

:deep(.code-property) {
  color: #7dcfff;
}

:deep(.code-value) {
  color: #f9a8d4;
}

:deep(.code-selector) {
  color: #8be9fd;
}

:deep(.code-tag) {
  color: #ff7aa2;
}

:deep(.code-attr) {
  color: #9ccfd8;
}

:deep(.code-number) {
  color: #c4b5fd;
}

:deep(.code-operator) {
  color: #f38ba8;
}

:deep(.code-function) {
  color: #7ee787;
}

.streaming-cursor {
  display: inline-block;
  color: #d4d4d4;
  animation: blink 1s infinite;
  margin-left: 2px;
}

@keyframes blink {

  0%,
  50% {
    opacity: 1;
  }

  51%,
  100% {
    opacity: 0;
  }
}
</style>
