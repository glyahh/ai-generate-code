<script setup lang="ts">
import { computed } from 'vue'
import ToolInvokeGlyph from '@/components/ToolInvokeGlyph.vue'
import type { ToolWaitStatusMotion } from '@/utils/toolWaitStatus'

/**
 * 普通 [选择工具] 提示行：左图标 + 工具名 + 延迟 status（写入/修改文件为文案+图标，其它工具为光影文案）。
 */
interface Props {
  toolName: string
  pending?: boolean
  /** 父组件判定：pending 满阈值后为 true */
  showStatus?: boolean
  statusText?: string
  statusIconSrc?: string
  statusIconMotion?: ToolWaitStatusMotion
}

const props = withDefaults(defineProps<Props>(), {
  pending: false,
  showStatus: false,
  statusText: '工具执行中',
  statusIconSrc: '',
  statusIconMotion: 'none',
})

const ariaLabel = computed(() => {
  const base = `选择工具 ${props.toolName}`
  return props.showStatus ? `${base}，${props.statusText}` : base
})

const useIconStatus = computed(() => Boolean(props.statusIconSrc))

const iconMotionClass = computed(() => {
  if (props.statusIconMotion === 'write-jitter-fast') {
    return 'tool-request-status__icon--write-jitter-fast'
  }
  if (props.statusIconMotion === 'write-jitter') {
    return 'tool-request-status__icon--write-jitter'
  }
  return ''
})
</script>

<template>
  <div
    class="tool-request-row"
    :class="{ 'tool-request-row--pending': pending }"
    role="status"
    :aria-label="ariaLabel"
  >
    <ToolInvokeGlyph :size="18" class="tool-request-glyph" />

    <div class="tool-request-body">
      <div class="tool-request-head">
        <span class="tool-request-name">{{ toolName }}</span>
        <span
          v-if="showStatus"
          class="tool-request-status"
          :class="useIconStatus ? 'tool-request-status--icon' : 'tool-request-status--think'"
        >
          <span class="tool-request-status__text">{{ statusText }}</span>
          <img
            v-if="statusIconSrc"
            :src="statusIconSrc"
            class="tool-request-status__icon"
            :class="iconMotionClass"
            alt=""
            aria-hidden="true"
          />
        </span>
      </div>
      <span class="tool-request-track" aria-hidden="true" />
    </div>
  </div>
</template>

<style scoped>
.tool-request-row {
  align-self: flex-start;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 2px 0 4px;
  min-width: 0;
}

.tool-request-glyph {
  margin-top: 1px;
  color: #475569;
}

.tool-request-row--pending .tool-request-glyph {
  color: #94a3b8;
}

.tool-request-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  flex: 1 1 auto;
  max-width: 320px;
}

.tool-request-head {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.tool-request-name {
  font-size: 12px;
  font-weight: 500;
  color: #334155;
  letter-spacing: 0.02em;
  line-height: 1.2;
}

.tool-request-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  animation: tool-request-status-fade-in 200ms ease-out both;
}

.tool-request-status__text {
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.02em;
  white-space: nowrap;
  line-height: 1.2;
}

.tool-request-status--icon .tool-request-status__text {
  color: #64748b;
}

.tool-request-status__icon {
  width: 20px;
  height: 20px;
  flex: 0 0 auto;
  object-fit: contain;
  display: block;
}

@media (prefers-reduced-motion: no-preference) {
  .tool-request-status__icon--write-jitter {
    animation: tool-status-icon-write-jitter 1.15s ease-in-out infinite;
  }

  .tool-request-status__icon--write-jitter-fast {
    animation: tool-status-icon-write-jitter-fast 0.85s ease-in-out infinite;
  }
}

.tool-request-status--think .tool-request-status__text {
  background: linear-gradient(
    90deg,
    #94a3b8 0%,
    #cbd5e1 35%,
    #f1f5f9 50%,
    #cbd5e1 65%,
    #94a3b8 100%
  );
  background-size: 220% 100%;
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  animation: tool-request-think-sweep 2.4s ease-in-out infinite;
}

.tool-request-track {
  display: block;
  width: 100%;
  height: 2px;
  background: #0f172a;
}

.tool-request-row--pending .tool-request-track {
  background: var(--bg-mute, #cbd5e1);
}

@media (prefers-reduced-motion: no-preference) {
  .tool-request-row--pending .tool-request-track {
    animation: tool-request-track-pulse 1.4s ease-in-out infinite;
  }
}

@keyframes tool-request-status-fade-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

@keyframes tool-status-icon-write-jitter {
  0%,
  100% {
    transform: translate(0, 0);
  }
  20% {
    transform: translate(0.4px, -0.6px);
  }
  40% {
    transform: translate(-0.5px, 0.3px);
  }
  60% {
    transform: translate(0.6px, 0.5px);
  }
  80% {
    transform: translate(-0.3px, -0.4px);
  }
}

@keyframes tool-status-icon-write-jitter-fast {
  0%,
  100% {
    transform: translate(0, 0);
  }
  20% {
    transform: translate(0.5px, -0.7px);
  }
  40% {
    transform: translate(-0.6px, 0.4px);
  }
  60% {
    transform: translate(0.7px, 0.6px);
  }
  80% {
    transform: translate(-0.4px, -0.5px);
  }
}

@keyframes tool-request-think-sweep {
  0% {
    background-position: 120% 0;
  }
  100% {
    background-position: -120% 0;
  }
}

@keyframes tool-request-track-pulse {
  0%,
  100% {
    opacity: 0.45;
  }
  50% {
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .tool-request-status--think .tool-request-status__text {
    animation: none;
    color: #64748b;
    background: none;
    -webkit-background-clip: unset;
    background-clip: unset;
  }

  .tool-request-status__icon--write-jitter,
  .tool-request-status__icon--write-jitter-fast {
    animation: none;
  }

  .tool-request-row--pending .tool-request-track {
    animation: none;
  }
}
</style>
