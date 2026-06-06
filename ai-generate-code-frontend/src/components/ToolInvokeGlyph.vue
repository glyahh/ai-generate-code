<script setup lang="ts">
/**
 * 工具调用提示左侧锚点图标：定位针 + 椭圆底座 + chevron，扁平线稿风格。
 */
interface Props {
  size?: number
}

withDefaults(defineProps<Props>(), {
  size: 18,
})
</script>

<template>
  <svg
    class="tool-invoke-glyph"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="2"
    stroke-linecap="square"
    stroke-linejoin="miter"
    aria-hidden="true"
  >
    <!-- 顶圆 -->
    <circle cx="9" cy="5" r="3" />
    <!-- stem -->
    <line x1="9" y1="8" x2="9" y2="14" />
    <!-- 底椭圆环（顶部开口） -->
    <path d="M4 17.5 A5 2.2 0 0 0 14 17.5" />
    <!-- chevron -->
    <path d="M16 11 L19 13.5 L16 16" />
  </svg>
</template>

<style scoped>
.tool-invoke-glyph {
  flex: 0 0 auto;
  display: block;
}
</style>
