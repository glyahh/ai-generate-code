<script setup lang="ts">
/**
 * 「结束工具调用」专用状态行：图二 list-row 语义，左文案条 + 右下粗勾选，无卡片容器。
 */
interface Props {
  /** 流式中且其后尚无后续段时为 true，显示收尾态 */
  pending?: boolean
}

withDefaults(defineProps<Props>(), {
  pending: false,
})
</script>

<template>
  <div
    class="tool-exit-row"
    :class="{ 'tool-exit-row--pending': pending }"
    role="status"
    :aria-label="pending ? '工具调用收尾中' : '工具调用已结束'"
  >
    <div class="tool-exit-lines">
      <div class="tool-exit-line tool-exit-line--primary">
        <span class="tool-exit-text tool-exit-text--primary">工具调用</span>
        <span class="tool-exit-bar tool-exit-bar--full" aria-hidden="true" />
      </div>
      <div class="tool-exit-line tool-exit-line--secondary">
        <span class="tool-exit-text tool-exit-text--secondary">
          {{ pending ? '收尾中' : '已结束' }}
        </span>
        <span class="tool-exit-bar tool-exit-bar--short" aria-hidden="true" />
      </div>
    </div>

    <svg
      v-if="!pending"
      class="tool-exit-check"
      viewBox="0 0 24 24"
      width="20"
      height="20"
      fill="none"
      stroke="currentColor"
      stroke-width="2.5"
      stroke-linecap="square"
      stroke-linejoin="miter"
      aria-hidden="true"
    >
      <path d="M5 13 L10 18 L19 7" />
    </svg>
  </div>
</template>

<style scoped>
.tool-exit-row {
  align-self: flex-start;
  display: flex;
  align-items: flex-end;
  gap: 12px;
  padding: 2px 0 4px;
  min-width: 0;
}

.tool-exit-lines {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  flex: 1 1 auto;
  max-width: 220px;
}

.tool-exit-line {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.tool-exit-text {
  line-height: 1.2;
  letter-spacing: 0.02em;
}

.tool-exit-text--primary {
  font-size: 12px;
  font-weight: 500;
  color: #334155;
}

.tool-exit-text--secondary {
  font-size: 11px;
  color: #64748b;
}

.tool-exit-bar {
  display: block;
  height: 2px;
  background: #0f172a;
}

.tool-exit-bar--full {
  width: 100%;
}

.tool-exit-bar--short {
  width: 50%;
}

.tool-exit-check {
  flex: 0 0 auto;
  color: #0f172a;
  margin-bottom: 1px;
}

.tool-exit-row--pending .tool-exit-bar {
  background: var(--bg-mute, #cbd5e1);
}

.tool-exit-row--pending .tool-exit-text--secondary {
  color: #94a3b8;
}

@media (prefers-reduced-motion: no-preference) {
  .tool-exit-row--pending .tool-exit-bar--short {
    animation: tool-exit-pulse 1.4s ease-in-out infinite;
  }
}

@keyframes tool-exit-pulse {
  0%,
  100% {
    opacity: 0.45;
  }
  50% {
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .tool-exit-row--pending .tool-exit-bar--short {
    animation: none;
  }
}
</style>
