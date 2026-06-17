<template>
  <a-popover v-model:open="visible" trigger="click" placement="top">
    <template #content>
      <div v-if="loopList.length === 0" class="picker-empty">
        <p>暂无 Loop，前往市场添加</p>
        <a-button type="link" @click="goToMarket">前往 Loop 市场</a-button>
      </div>
      <div v-else class="picker-list">
        <a-checkbox-group v-model:value="selectedIds">
          <div v-for="loop in loopList" :key="loop.id" class="picker-item">
            <a-checkbox :value="loop.id">{{ loop.loopName }}</a-checkbox>
            <span class="picker-desc">{{ loop.description }}</span>
          </div>
        </a-checkbox-group>
      </div>
    </template>
    <a-button class="loop-pill" :type="selectedIds.length > 0 ? 'primary' : 'default'">
      <span class="loop-pill-text">Loop{{ selectedIds.length > 0 ? ' · ' + selectedIds.length : '' }}</span>
    </a-button>
  </a-popover>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const visible = ref(false)
const loopList = ref<any[]>([])
const selectedIds = ref<number[]>([])

const emit = defineEmits<{
  (e: 'change', ids: number[]): void
}>()

watch(selectedIds, (val) => emit('change', val), { deep: true })

function goToMarket() {
  visible.value = false
  router.push('/loop')
}

onMounted(async () => {
  // TODO: openapi2ts 生成后通过 loopController.myListPage 加载
  // const res = await loopController.myListPage({ pageCurrent: 1, pageSize: 50 })
  // if (res.data.code === 0 || res.data.code === 20000) {
  //   loopList.value = res.data.data || []
  // }
})

defineExpose({ selectedIds })
</script>

<style scoped>
.picker-empty {
  text-align: center;
  padding: 16px;
  min-width: 200px;
}
.picker-list {
  max-height: 300px;
  overflow-y: auto;
  min-width: 220px;
}
.picker-item {
  padding: 6px 0;
}
.picker-desc {
  font-size: 12px;
  color: var(--text-secondary, #6b7280);
  margin-left: 24px;
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 200px;
}
.loop-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 36px;
  border-radius: 999px;
  padding: 0 12px;
  border: 1px solid rgba(15, 23, 42, 0.12);
  background: var(--bg-card, rgba(255, 255, 255, 0.68));
  color: var(--text-base, #0f172a);
  cursor: pointer;
  transition:
    transform 0.16s ease-out,
    box-shadow 0.16s ease-out,
    border-color 0.16s ease-out;
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.06);
  font-size: 12px;
  font-weight: 600;
}
.loop-pill:hover {
  transform: translateY(-1px);
  border-color: rgba(59, 130, 246, 0.35);
  box-shadow: 0 16px 34px rgba(15, 23, 42, 0.12);
}
.loop-pill-text {
  position: relative;
  z-index: 1;
}
</style>
