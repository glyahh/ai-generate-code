<script setup lang="ts">
import toolFlashlightSrc from '@/assets/tool-flashlight.png'

/**
 * 工具卡片头部衔接图标：使用用户提供的透明底手电 PNG，不做绘制或滤镜处理。
 */
interface Props {
  size?: number
}

withDefaults(defineProps<Props>(), {
  size: 14,
})
</script>

<template>
  <img
    class="tool-flashlight-glyph"
    :src="toolFlashlightSrc"
    :width="size"
    :height="size"
    alt=""
    aria-hidden="true"
    draggable="false"
  />
</template>

<style scoped>
.tool-flashlight-glyph {
  flex: 0 0 auto;
  display: block;
  object-fit: contain;
}
</style>
