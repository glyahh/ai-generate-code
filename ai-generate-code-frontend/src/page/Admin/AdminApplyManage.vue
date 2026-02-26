<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal as AModal } from 'ant-design-vue'
import type { ApplyVO } from '@/api'
import { appApplyListPendingUsingPost, appApplyUsingPost, appApplyAgreeUsingPost } from '@/api/appController'
import { UserLoginStore } from '@/stores/UserLogin'

const router = useRouter()
const userLoginStore = UserLoginStore()

const isAdmin = computed(() => userLoginStore.userLogin?.userRole === 'admin')

const loading = ref(false)
const applies = ref<ApplyVO[]>([])
const activeIndex = ref(0)

const activeApply = computed<ApplyVO | null>(() => {
  if (!applies.value.length) return null
  return applies.value[activeIndex.value] ?? applies.value[0] ?? null
})

const detailLoading = ref(false)

function formatOperate(apply: ApplyVO): string {
  if (apply.operate === 2) return '申请成为管理员'
  if (apply.operate === 1) return '申请应用成为精选'
  return '其他申请'
}

function formatIdLabel(apply: ApplyVO): string {
  if (apply.operate === 2) {
    return `用户 ID：${apply.userId ?? '-'}`
  }
  return `App ID：${apply.appId ?? '-'}`
}

async function loadPending() {
  if (!isAdmin.value) {
    message.error('您没有权限访问用户请求页')
    router.push('/')
    return
  }
  loading.value = true
  try {
    const res = await appApplyListPendingUsingPost({})
    if ((res.data.code === 0 || res.data.code === 20000) && Array.isArray(res.data.data)) {
      applies.value = res.data.data
      activeIndex.value = 0
    } else {
      message.error(res.data.message || '获取申请列表失败')
    }
  } catch (e) {
    console.error(e)
    message.error('获取申请列表失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function selectApply(index: number) {
  activeIndex.value = index
}

async function handleDecision(type: 'approve' | 'reject') {
  const apply = activeApply.value
  if (!apply) return

  const title = type === 'approve' ? '确认同意该请求？' : '确认拒绝该请求？'
  const content =
    type === 'approve'
      ? '该操作可能会影响权限或应用展示位，请谨慎确认。'
      : '拒绝后用户需要重新提交申请才能再次审核，是否继续？'

  AModal.confirm({
    title,
    content,
    okText: type === 'approve' ? '确认同意' : '确认拒绝',
    okType: type === 'approve' ? 'primary' : 'danger',
    cancelText: '再想想',
    async onOk() {
      detailLoading.value = true
      const operate =
        type === 'approve'
          ? apply.operate === 2
            ? 3 // 同意管理员申请
            : 5 // 同意精选申请
          : apply.operate === 2
            ? 4 // 拒绝管理员申请
            : 6 // 拒绝精选申请

      try {
        const res =
          type === 'approve'
            ? await appApplyAgreeUsingPost({ body: { applyId: apply.applyId } })
            : await appApplyUsingPost({
                body: {
                  appId: apply.appId as any,
                  operate,
                  appPropriety: apply.appId && apply.operate === 1 ? 99 : undefined,
                  applyReason: apply.reason,
                },
              })
        if ((res.data.code === 0 || res.data.code === 20000) && res.data.data === true) {
          message.success(type === 'approve' ? '已同意该请求' : '已拒绝该请求')
          await loadPending()
        } else {
          message.error(res.data.message || '操作失败')
        }
      } catch (e) {
        console.error(e)
        message.error('操作失败，请稍后重试')
      } finally {
        detailLoading.value = false
      }
    },
  })
}

function handleQuickLocate() {
  const apply = activeApply.value
  if (!apply) return

  if (apply.operate === 2 && apply.userId != null) {
    // 管理员申请：跳到用户管理并按 userId 搜索
    router.push({
      path: '/admin/users',
      query: { userId: String(apply.userId), fromApply: '1' },
    })
  } else if (apply.operate === 1 && apply.appId != null) {
    // 精选申请：跳到该应用的代码生成（对话）页面
    router.push({
      name: 'app-chat',
      params: { id: String(apply.appId) },
    })
  }
}

onMounted(() => {
  void loadPending()
})
</script>

<template>
  <div class="apply-manage-page">
    <section class="left-column">
      <h2 class="column-title">待处理请求</h2>
      <p class="column-subtitle">
        点击左侧列表中的用户或应用，右侧将展示详细信息。
      </p>
      <div class="apply-list" v-loading="loading">
        <div
          v-if="!applies.length"
          class="empty-tip"
        >
          暂无待处理的用户请求。
        </div>
        <button
          v-for="(item, index) in applies"
          v-else
          :key="`${item.userId}-${item.appId}-${index}`"
          type="button"
          class="apply-item"
          :class="{ active: index === activeIndex }"
          @click="selectApply(index)"
        >
          <div class="avatar-circle">
            <span>{{ (item.userId ?? 'U').toString().slice(-2) }}</span>
          </div>
          <div class="apply-meta">
            <div class="apply-id">
              {{ formatIdLabel(item) }}
            </div>
            <div class="apply-type">
              {{ formatOperate(item) }}
            </div>
          </div>
        </button>
      </div>
    </section>

    <section class="right-column" v-loading="detailLoading">
      <div v-if="activeApply" class="detail-card">
        <header class="detail-header">
          <div class="detail-title-block">
            <div class="detail-tag">
              {{ formatOperate(activeApply) }}
            </div>
            <div class="detail-ids">
              <span>用户 ID：{{ activeApply.userId ?? '-' }}</span>
              <span v-if="activeApply.appId">App ID：{{ activeApply.appId }}</span>
            </div>
          </div>
        </header>

        <main class="detail-body">
          <div class="detail-section">
            <h3 class="section-title">申请理由</h3>
            <div class="reason-box">
              {{ activeApply.reason || '用户未填写具体理由。' }}
            </div>
          </div>
        </main>

        <footer class="detail-footer">
          <span class="footer-hint">请谨慎处理用户权限与应用展示相关的请求。</span>
          <div class="footer-actions">
            <a-button @click="handleQuickLocate">
              一键查找
            </a-button>
            <a-button danger @click="handleDecision('reject')">
              拒绝
            </a-button>
            <a-button type="primary" @click="handleDecision('approve')">
              同意
            </a-button>
          </div>
        </footer>
      </div>
      <div v-else class="detail-empty">
        请选择左侧列表中的一条请求查看详情。
      </div>
    </section>
  </div>
</template>

<style scoped>
.apply-manage-page {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 20px;
  height: calc(100vh - 64px - 80px);
  min-height: 560px;
}

.left-column,
.right-column {
  background: #ffffff;
  border-radius: 18px;
  box-shadow: 0 10px 30px rgba(15, 23, 42, 0.06);
  padding: 18px 18px 16px;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.column-title {
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 6px;
}

.column-subtitle {
  margin: 0 0 10px;
  font-size: 12px;
  color: #6b7280;
}

.apply-list {
  flex: 1;
  overflow-y: auto;
  padding-right: 4px;
}

.apply-item {
  width: 100%;
  border: none;
  background: transparent;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 6px;
  border-radius: 12px;
  cursor: pointer;
  transition: background-color 0.15s ease, transform 0.1s ease;
}

.apply-item:hover {
  background-color: rgba(148, 163, 184, 0.18);
  transform: translateY(-1px);
}

.apply-item.active {
  background-color: rgba(59, 130, 246, 0.1);
}

.avatar-circle {
  width: 32px;
  height: 32px;
  border-radius: 999px;
  background: linear-gradient(135deg, #4f46e5, #22c55e);
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
}

.apply-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.apply-id {
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
}

.apply-type {
  font-size: 12px;
  color: #6b7280;
}

.empty-tip {
  font-size: 13px;
  color: #6b7280;
  padding: 16px 4px;
}

.detail-card {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.detail-header {
  padding-bottom: 10px;
  border-bottom: 1px solid #e5e7eb;
}

.detail-title-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.detail-tag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  background: rgba(59, 130, 246, 0.08);
  color: #1d4ed8;
  font-weight: 500;
}

.detail-ids {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 13px;
  color: #4b5563;
}

.detail-body {
  flex: 1;
  padding: 14px 0 10px;
  overflow-y: auto;
}

.detail-section + .detail-section {
  margin-top: 12px;
}

.section-title {
  margin: 0 0 6px;
  font-size: 13px;
  font-weight: 600;
  color: #111827;
}

.reason-box {
  border-radius: 10px;
  padding: 10px 12px;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  font-size: 13px;
  color: #374151;
  min-height: 72px;
  white-space: pre-wrap;
}

.detail-footer {
  padding-top: 10px;
  border-top: 1px solid #e5e7eb;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.footer-hint {
  font-size: 12px;
  color: #6b7280;
}

.footer-actions {
  display: flex;
  gap: 8px;
}

.detail-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  font-size: 13px;
  color: #6b7280;
}

@media (max-width: 960px) {
  .apply-manage-page {
    grid-template-columns: minmax(0, 1fr);
  }

  .left-column {
    order: 1;
  }

  .right-column {
    order: 2;
  }
}
</style>

