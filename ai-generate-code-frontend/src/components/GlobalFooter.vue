<script setup lang="ts">
</script>

<template>
  <div class="global-footer">
    <span class="footer-text">Ai-generate-code By glyahh</span>
  </div>
</template>

<style scoped>
.global-footer {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.95);
  border-top: 1px solid rgba(0, 0, 0, 0.06);
  z-index: 100;
}

.footer-text {
  font-size: 14px;
  color: rgba(0, 0, 0, 0.65);
}

:root.dark .global-footer,
[data-theme='dark'] .global-footer {
  background: rgba(22, 22, 22, 0.95);
  border-top-color: rgba(255, 255, 255, 0.08);
}

:root.dark .footer-text,
[data-theme='dark'] .footer-text {
  color: rgba(255, 255, 255, 0.65);
}
</style>
