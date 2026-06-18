<script setup lang="ts">
import { ref, onMounted, computed, h } from 'vue'
import { useRouter } from 'vue-router'
import { Table, Button, Modal, Tag, Space, message } from 'ant-design-vue'
import type { ColumnsType } from 'ant-design-vue/es/table'
import { UserLoginStore } from '@/stores/UserLogin'
import {
  loopAdminListPageVoUsingPost,
  loopAdminUpdateUsingPost,
  loopAdminDeleteUsingPost,
} from '@/api/loopController'

const router = useRouter()
const userLoginStore = UserLoginStore()

const isAdmin = computed(() => userLoginStore.userLogin?.userRole === 'admin')

const loading = ref(false)
const tableData = ref<any[]>([])
const pagination = ref({
  current: 1,
  pageSize: 20,
  total: 0,
  showSizeChanger: true,
  showQuickJumper: true,
  showTotal: (total: number) => `共 ${total} 条`,
})

// 删除确认
const deleteModalVisible = ref(false)
const deletingId = ref<number | null>(null)

function formatDateTime(value?: string) {
  if (!value) return '-'
  try {
    const date = new Date(value)
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(
      date.getDate(),
    ).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(
      date.getMinutes(),
    ).padStart(2, '0')}`
  } catch {
    return value
  }
}

async function loadData() {
  if (!isAdmin.value) {
    message.error('您没有权限访问此页面')
    router.push('/')
    return
  }
  loading.value = true
  try {
    const res = await loopAdminListPageVoUsingPost({
      body: { pageNum: pagination.value.current, pageSize: pagination.value.pageSize },
    })
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
      tableData.value = res.data.data
    } else {
      message.error(res.data.message || '获取 Loop 列表失败')
    }
  } catch (e) {
    console.error(e)
    message.error('获取 Loop 列表失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function handleTableChange(pag: any) {
  pagination.value.current = pag.current
  pagination.value.pageSize = pag.pageSize
  void loadData()
}

/** 设为精选（priority=99） */
async function handleSetGood(record: any) {
  if (!record.id) return
  loading.value = true
  try {
    const res = await loopAdminUpdateUsingPost({
      body: { id: record.id, priority: 99 },
    })
    if (res.data.code === 0 || res.data.code === 20000) {
      message.success('已通过，Loop 已上架精选')
      await loadData()
    } else {
      message.error(res.data.message || '操作失败')
    }
  } catch (e) {
    console.error(e)
    message.error('操作失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function openDeleteModal(record: any) {
  deletingId.value = record.id ?? null
  deleteModalVisible.value = true
}

function closeDeleteModal() {
  deleteModalVisible.value = false
  deletingId.value = null
}

async function handleDeleteConfirm() {
  if (!deletingId.value) return
  loading.value = true
  try {
    const res = await loopAdminDeleteUsingPost({ params: { id: deletingId.value } })
    if (res.data.code === 0 || res.data.code === 20000) {
      message.success('删除成功')
      closeDeleteModal()
      await loadData()
    } else {
      message.error(res.data.message || '删除失败')
    }
  } catch (e) {
    console.error(e)
    message.error('删除失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

const columns: ColumnsType<any> = [
  {
    title: 'ID',
    dataIndex: 'id',
    key: 'id',
    width: 80,
    align: 'center',
  },
  {
    title: '名称',
    dataIndex: 'loopName',
    key: 'loopName',
    width: 180,
    ellipsis: true,
  },
  {
    title: '用户',
    dataIndex: 'userId',
    key: 'userId',
    width: 100,
    align: 'center',
  },
  {
    title: '优先级',
    dataIndex: 'priority',
    key: 'priority',
    width: 100,
    align: 'center',
    customRender: ({ text }: { text?: number }) => {
      const val = text ?? 0
      if (val >= 99) {
        return h(Tag, { color: 'gold' }, () => `精选 (${val})`)
      }
      return h('span', val)
    },
  },
  {
    title: '可见性',
    dataIndex: 'visibility',
    key: 'visibility',
    width: 90,
    align: 'center',
    customRender: ({ text }: { text?: string }) => {
      if (text === 'public') {
        return h(Tag, { color: 'green' }, () => '公开')
      }
      return h(Tag, () => '私有')
    },
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    key: 'createTime',
    width: 170,
    customRender: ({ text }: { text?: string }) => formatDateTime(text),
  },
  {
    title: '操作',
    key: 'action',
    fixed: 'right',
    width: 200,
    align: 'center',
    customRender: ({ record }: { record: any }) =>
      h(Space, { size: 'small' }, () => [
        h(
          Button,
          {
            size: 'small',
            type: 'primary',
            disabled: (record.priority ?? 0) >= 99,
            onClick: () => handleSetGood(record),
          },
          () => '设精选',
        ),
        h(
          Button,
          {
            size: 'small',
            onClick: () => openDeleteModal(record),
            danger: true,
          },
          () => '删除',
        ),
      ]),
  },
]

onMounted(async () => {
  await userLoginStore.fetchLoginUser()
  await loadData()
})
</script>

<template>
  <main class="admin-home-container">
    <div class="admin-home-bg" aria-hidden="true" />
    <div class="admin-content-wrapper">
      <div class="admin-header">
        <h1 class="admin-title">Loop 管理</h1>
        <p class="admin-subtitle">管理 Loop 技能的上架与下架</p>
      </div>

      <div class="table-wrapper">
        <Table
          :columns="columns"
          :data-source="tableData"
          :loading="loading"
          :pagination="pagination"
          :scroll="{ x: 1000 }"
          row-key="id"
          @change="handleTableChange"
          bordered
        />
      </div>
    </div>

    <Modal
      v-model:open="deleteModalVisible"
      title="确认删除"
      ok-text="确认删除"
      cancel-text="取消"
      ok-type="danger"
      :confirm-loading="loading"
      @ok="handleDeleteConfirm"
      @cancel="closeDeleteModal"
    >
      <p>确定要删除该 Loop 吗？此操作不可恢复。</p>
    </Modal>
  </main>
</template>

<style scoped>
.admin-home-container {
  position: relative;
  min-height: calc(100vh - 64px - 48px);
  padding: 40px 20px;
  display: flex;
  justify-content: center;
  align-items: flex-start;
}

.admin-home-bg {
  position: fixed;
  top: 64px;
  left: 0;
  right: 0;
  bottom: 48px;
  z-index: 0;
  background-image: url('@/picture/admin/image.png');
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  opacity: 0.8;
}

.admin-content-wrapper {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 1600px;
  background: var(--bg-card, rgba(255, 255, 255, 0.6));
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: 32px 32px 40px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
  border: 1px solid var(--border-color, rgba(255, 255, 255, 0.3));
}

.admin-header {
  text-align: center;
  margin-bottom: 24px;
}

.admin-title {
  font-size: 30px;
  font-weight: 700;
  margin: 0 0 8px;
}

.admin-subtitle {
  margin: 0;
  color: var(--text-secondary, #666);
}

.table-wrapper {
  overflow: hidden;
}

@media (max-width: 1200px) {
  .admin-content-wrapper {
    padding: 24px 16px 32px;
  }
}

@media (max-width: 768px) {
  .admin-home-container {
    padding: 20px 10px;
    min-height: calc(100vh - 56px - 48px);
  }

  .admin-home-bg {
    top: 56px;
  }

  .admin-content-wrapper {
    padding: 20px 12px 28px;
  }
}
</style>
