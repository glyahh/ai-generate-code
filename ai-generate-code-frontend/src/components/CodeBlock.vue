<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

interface Props {
  code: string
  language?: string
  isStreaming?: boolean // 是否正在流式输出中
}

const props = withDefaults(defineProps<Props>(), {
  language: 'text',
  isStreaming: false,
})

const codeRef = ref<HTMLElement | null>(null)

/**
 * 简单的语法高亮（基于关键词匹配）
 * 如果项目安装了 highlight.js，可以替换为更强大的高亮方案
 */
const highlightedCode = computed(() => {
  const raw = props.code
  const lang = props.language.toLowerCase()

  // 先对代码中的 HTML 特殊字符进行转义，防止被浏览器当作真实标签渲染
  // 这样无论是 HTML 代码还是包含 < > 的其他代码，都能安全展示
  const escaped = raw
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')

  // 简单的关键词高亮（适用于常见语言）
  let highlighted = escaped

  // Vue 单文件组件：按 HTML 模板 + 属性高亮，兼容 <template>/<script>/<style>
  if (lang === 'vue') {
    highlighted = escaped
      // 高亮块级标签名
      .replace(
        /(&lt;\/?)(template|script|style)(\s*[^&]*?)(&gt;)/g,
        '$1<span class="code-tag code-tag-block">$2</span>$3$4',
      )
      // 其他标签
      .replace(
        /(&lt;\/?)([\w-]+)(\s*[^&]*?)(&gt;)/g,
        '$1<span class="code-tag">$2</span>$3$4',
      )
      // 属性与属性值
      .replace(
        /(\w+)(\s*=\s*)(['"])([^'"]*?)(\3)/g,
        '<span class="code-attr">$1</span>$2$3<span class="code-value">$4</span>$5',
      )
      // 行内注释（例如 // 或 /* */ 出现在 <script> 中）
      .replace(/(\/\/.*$)/gm, '<span class="code-comment">$1</span>')
      .replace(/(\/\*[\s\S]*?\*\/)/g, '<span class="code-comment">$1</span>')
  }
  // CSS 关键词
  else if (lang === 'css' || lang === 'scss' || lang === 'less') {
    highlighted = escaped
      .replace(/(\w+)(\s*:\s*)/g, '<span class="code-property">$1</span>$2')
      .replace(/(:\s*)([^;]+)(;)/g, '$1<span class="code-value">$2</span>$3')
      .replace(/(\/\*[\s\S]*?\*\/)/g, '<span class="code-comment">$1</span>')
      .replace(/([.#])([\w-]+)/g, '$1<span class="code-selector">$2</span>')
  }
  // JavaScript/TypeScript：先注释和字符串，再关键词，避免嵌套导致乱码
  else if (lang === 'javascript' || lang === 'js' || lang === 'typescript' || lang === 'ts') {
    highlighted = escaped
      .replace(/(\/\/.*$)/gm, '<span class="code-comment">$1</span>')
      .replace(/(\/\*[\s\S]*?\*\/)/g, '<span class="code-comment">$1</span>')
      .replace(/(['"`])((?:\\.|(?!\1)[^\\])*?)(\1)/g, '<span class="code-string">$1$2$3</span>')
    const keywords = ['function', 'const', 'let', 'var', 'if', 'else', 'return', 'import', 'export', 'from', 'async', 'await', 'class', 'extends', 'new', 'this', 'true', 'false', 'null', 'undefined', 'typeof', 'instanceof', 'in', 'of', 'try', 'catch', 'finally', 'throw', 'switch', 'case', 'break', 'default', 'continue', 'for', 'while', 'do', 'get', 'set', 'static', 'constructor', 'super', '=>']
    keywords.forEach((keyword) => {
      const regex = new RegExp(`\\b(${keyword})\\b`, 'g')
      highlighted = highlighted.replace(regex, '<span class="code-keyword">$1</span>')
    })
  }
  // HTML 标签
  else if (lang === 'html' || lang === 'xml') {
    highlighted = escaped
      .replace(/(&lt;\/?)([\w-]+)(\s*[^&]*?)(&gt;)/g, '$1<span class="code-tag">$2</span>$3$4')
      .replace(/(\w+)(\s*=\s*)(['"])([^'"]*?)(\3)/g, '<span class="code-attr">$1</span>$2$3<span class="code-value">$4</span>$5')
  }
  // 默认：只高亮注释
  else {
    highlighted = escaped
      .replace(/(\/\/.*$)/gm, '<span class="code-comment">$1</span>')
      .replace(/(\/\*[\s\S]*?\*\/)/g, '<span class="code-comment">$1</span>')
  }

  return highlighted
})

/**
 * 复制代码到剪贴板
 */
async function copyCode() {
  try {
    await navigator.clipboard.writeText(props.code)
    // 可以添加一个提示消息
    console.log('代码已复制到剪贴板')
  } catch (err) {
    console.error('复制失败:', err)
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
      <span class="code-language">{{ language || 'text' }}</span>
      <button class="copy-button" @click="copyCode" title="复制代码">
        📋
      </button>
    </div>
    <pre ref="codeRef" class="code-block">
      <code v-html="highlightedCode"></code>
      <span v-if="isStreaming" class="streaming-cursor">▋</span>
    </pre>
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
  color: #ce9178;
}

:deep(.code-comment) {
  color: #6a9955;
  font-style: italic;
}

:deep(.code-property) {
  color: #9cdcfe;
}

:deep(.code-value) {
  color: #ce9178;
}

:deep(.code-selector) {
  color: #d7ba7d;
}

:deep(.code-tag) {
  color: #569cd6;
}

:deep(.code-attr) {
  color: #92c5f7;
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
