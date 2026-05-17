<script setup lang="ts">
import { computed } from 'vue'
import WorkflowIcon from '@/components/app/WorkflowIcon.vue'

const props = withDefaults(
  defineProps<{
    /** app.is_beta：1=workflow，其它=normal（AiService 链路） */
    isBeta?: number | null
    /** compact：列表卡片角标，约为 default 面积的 1/4 */
    size?: 'default' | 'compact'
  }>(),
  { size: 'default' },
)

const isWorkflow = computed(() => props.isBeta === 1)
const label = computed(() => (isWorkflow.value ? 'workflow' : 'normal'))
const iconSize = computed(() => (props.size === 'compact' ? 13 : 26))
</script>

<template>
  <div
    class="gen-pipeline-badge"
    :class="[
      isWorkflow ? 'gen-pipeline-badge--workflow' : 'gen-pipeline-badge--normal',
      size === 'compact' ? 'gen-pipeline-badge--compact' : '',
    ]"
    role="status"
    :aria-label="isWorkflow ? 'Workflow 生成链路' : 'Normal AiService 生成链路'"
  >
    <div class="gen-pipeline-badge__icon-wrap">
      <WorkflowIcon v-if="isWorkflow" :size="iconSize" class="gen-pipeline-badge__workflow-icon" />
      <svg
        v-else
        class="gen-pipeline-badge__normal-icon"
        :width="iconSize"
        :height="iconSize"
        viewBox="0 0 24 24"
        fill="none"
        xmlns="http://www.w3.org/2000/svg"
        aria-hidden="true"
      >
        <path
          d="M8 9h8M8 12.5h5.5M8 16h6"
          stroke="currentColor"
          stroke-width="1.75"
          stroke-linecap="round"
        />
        <rect
          x="4"
          y="5"
          width="16"
          height="14"
          rx="2.5"
          stroke="currentColor"
          stroke-width="1.75"
        />
      </svg>
    </div>
    <span
      class="gen-pipeline-badge__label"
      :class="{ 'gen-pipeline-badge__label--glitch': isWorkflow }"
      :data-text="label"
    >
      {{ label }}
    </span>
  </div>
</template>

<style scoped>
.gen-pipeline-badge {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-width: 72px;
  padding: 10px 12px 9px;
  border-radius: 14px;
  pointer-events: none;
  user-select: none;
  box-shadow:
    0 10px 28px rgba(0, 0, 0, 0.28),
    inset 0 1px 0 rgba(255, 255, 255, 0.06);
}

.gen-pipeline-badge--workflow {
  background: #050505;
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: #f8fafc;
}

.gen-pipeline-badge--normal {
  background: linear-gradient(145deg, #1e293b 0%, #0f172a 100%);
  border: 1px solid rgba(148, 163, 184, 0.22);
  color: #e2e8f0;
}

.gen-pipeline-badge__icon-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 28px;
}

.gen-pipeline-badge__workflow-icon {
  color: #f8fafc;
}

.gen-pipeline-badge__normal-icon {
  display: block;
  opacity: 0.92;
}

.gen-pipeline-badge__label {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', monospace;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.14em;
  line-height: 1;
  text-transform: lowercase;
  color: inherit;
}

.gen-pipeline-badge__label--glitch {
  position: relative;
  text-shadow: 0 0 12px rgba(255, 255, 255, 0.15);
}

.gen-pipeline-badge__label--glitch::before,
.gen-pipeline-badge__label--glitch::after {
  content: attr(data-text);
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  overflow: hidden;
  pointer-events: none;
}

.gen-pipeline-badge__label--glitch::before {
  color: #22d3ee;
  clip-path: inset(0 0 58% 0);
  transform: translate(-0.6px, 0);
  opacity: 0.75;
}

.gen-pipeline-badge__label--glitch::after {
  color: #fb7185;
  clip-path: inset(42% 0 0 0);
  transform: translate(0.6px, 0);
  opacity: 0.65;
}

.gen-pipeline-badge--compact {
  gap: 2px;
  min-width: 36px;
  padding: 4px 6px 3px;
  border-radius: 8px;
  box-shadow:
    0 4px 12px rgba(0, 0, 0, 0.22),
    inset 0 1px 0 rgba(255, 255, 255, 0.05);
}

.gen-pipeline-badge--compact .gen-pipeline-badge__icon-wrap {
  min-height: 14px;
}

.gen-pipeline-badge--compact .gen-pipeline-badge__label {
  font-size: 8px;
  letter-spacing: 0.1em;
}

.gen-pipeline-badge--compact .gen-pipeline-badge__label--glitch::before {
  transform: translate(-0.3px, 0);
}

.gen-pipeline-badge--compact .gen-pipeline-badge__label--glitch::after {
  transform: translate(0.3px, 0);
}

@media (prefers-reduced-motion: reduce) {
  .gen-pipeline-badge__label--glitch::before,
  .gen-pipeline-badge__label--glitch::after {
    display: none;
  }
}
</style>
