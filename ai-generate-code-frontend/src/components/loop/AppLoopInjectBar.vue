<template>
  <div v-if="loopList.length > 0" class="loop-inject-bar">
    <div v-if="loopList.length > 20" class="inject-search">
      <a-input
        v-model:value="searchText"
        placeholder="搜索 Loop…"
        size="small"
        allow-clear
      />
    </div>
    <div class="inject-radio-scroll">
      <a-radio-group v-model:value="selectedLoopId" class="inject-radio-group">
        <a-radio-button :value="0">无</a-radio-button>
        <a-radio-button
          v-for="loop in filteredList"
          :key="loop.loopId"
          :value="loop.loopId"
        >
          {{ loop.loopName }}
        </a-radio-button>
      </a-radio-group>
    </div>
    <a-button type="link" size="small" class="inject-add-btn" @click="goToMarket">
      + 添加
    </a-button>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { appLoopListVoUsingPost } from '@/api/appLoopController'

const router = useRouter()

const props = defineProps<{
  appId: number
}>()

const emit = defineEmits<{
  (e: 'select', loopId: number): void
}>()

const loopList = ref<any[]>([])
const selectedLoopId = ref(0)
const searchText = ref('')

const filteredList = computed(() => {
  if (!searchText.value) return loopList.value
  const q = searchText.value.toLowerCase()
  return loopList.value.filter((l: any) => l.loopName?.toLowerCase().includes(q))
})

watch(selectedLoopId, (val) => emit('select', val))

function goToMarket() {
  router.push('/loop')
}

onMounted(async () => {
  try {
    const res = await appLoopListVoUsingPost({ params: { appId: props.appId } })
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
      loopList.value = res.data.data
    }
  } catch (e) {
    console.error('加载应用 Loop 列表失败', e)
  }
})
</script>

<style scoped>
.loop-inject-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-top: 1px solid var(--border-color, rgba(15, 23, 42, 0.08));
  flex-wrap: wrap;
}
.inject-search {
  width: 160px;
  flex-shrink: 0;
}
.inject-radio-scroll {
  flex: 1;
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: thin;
}
.inject-radio-group {
  display: inline-flex;
  gap: 4px;
  white-space: nowrap;
}
.inject-add-btn {
  flex-shrink: 0;
}
</style>
