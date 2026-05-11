<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Button, Form, Input, InputNumber, message, Space, Table, Tag } from 'ant-design-vue'
import type { ColumnsType } from 'ant-design-vue/es/table'
import type { UserChatHistoryItemVO } from '@/api'
import { chatHistoryMyUsingPost } from '@/api'
import { UserLoginStore } from '@/stores/UserLogin'

const router = useRouter()
const userLoginStore = UserLoginStore()

const isLogin = computed(() => !!userLoginStore.userLogin?.id)

const loading = ref(false)
const tableData = ref<UserChatHistoryItemVO[]>([])
const pagination = ref({
  current: 1,
  pageSize: 20,
  total: 0,
  showSizeChanger: true,
  showQuickJumper: true,
  showTotal: (total: number) => `共 ${total} 条对话记录`,
})

const searchForm = ref<{
  messageType?: string
  appId?: number
}>({
  messageType: '',
  appId: undefined,
})

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

function truncateMessage(text?: string, maxLen = 80) {
  if (!text) return '-'
  return text.length > maxLen ? text.slice(0, maxLen) + '…' : text
}

async function loadData() {
  if (!isLogin.value) {
    message.warning('请先登录')
    router.push('/user/login')
    return
  }
  loading.value = true
  try {
    const res = await chatHistoryMyUsingPost({
      body: {
        pageNum: pagination.value.current,
        pageSize: pagination.value.pageSize,
        messageType: searchForm.value.messageType || undefined,
        appId: searchForm.value.appId,
      },
    })
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
      tableData.value = res.data.data.records || []
      pagination.value.total = res.data.data.totalRow || 0
    } else {
      message.error(res.data.message || '获取对话列表失败')
    }
  } catch (e) {
    console.error(e)
    message.error('获取对话列表失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function handleTableChange(pag: any) {
  pagination.value.current = pag.current
  pagination.value.pageSize = pag.pageSize
  void loadData()
}

function handleSearch() {
  pagination.value.current = 1
  void loadData()
}

function resetSearch() {
  searchForm.value = {
    messageType: '',
    appId: undefined,
  }
  pagination.value.current = 1
  void loadData()
}

function goAppChat(record: UserChatHistoryItemVO) {
  if (record.appId != null) {
    router.push({ name: 'app-chat', params: { id: String(record.appId) } })
  }
}

const columns: ColumnsType<UserChatHistoryItemVO> = [
  {
    title: '消息类型',
    dataIndex: 'messageType',
    key: 'messageType',
    width: 110,
    align: 'center',
    customRender: ({ text }: { text?: string }) => {
      const val = text || '-'
      const color = val.toLowerCase() === 'user' ? 'blue' : 'green'
      return h(Tag, { color }, () => val)
    },
  },
  {
    title: '消息内容',
    dataIndex: 'message',
    key: 'message',
    ellipsis: true,
    customRender: ({ text }: { text?: string }) => truncateMessage(text),
  },
  {
    title: '应用名称',
    dataIndex: 'appName',
    key: 'appName',
    width: 180,
    ellipsis: true,
  },
  {
    title: '应用 ID',
    dataIndex: 'appId',
    key: 'appId',
    width: 110,
    align: 'center',
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
    width: 110,
    align: 'center',
    customRender: ({ record }: { record: UserChatHistoryItemVO }) =>
      h(
        Button,
        {
          size: 'small',
          type: 'primary',
          onClick: () => goAppChat(record),
        },
        () => '查看对话',
      ),
  },
]

onMounted(async () => {
  await userLoginStore.fetchLoginUser()
  await loadData()
})
</script>

<template>
  <main class="user-chat-container">
    <div class="user-chat-bg" aria-hidden="true" />
    <div class="user-chat-wrapper">
      <div class="user-chat-header">
        <h1 class="user-chat-title">查看历史</h1>
      </div>

      <section class="search-bar">
        <Form layout="inline">
          <Form.Item label="消息类型">
            <Input v-model:value="searchForm.messageType" placeholder="user / assistant" allow-clear style="width: 140px" />
          </Form.Item>
          <Form.Item label="应用 ID">
            <InputNumber v-model:value="searchForm.appId" :min="1" style="width: 140px" />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" @click="handleSearch">查询</Button>
              <Button @click="resetSearch">重置</Button>
            </Space>
          </Form.Item>
        </Form>
      </section>

      <div class="table-wrapper">
        <Table
          :columns="columns"
          :data-source="tableData"
          :loading="loading"
          :pagination="pagination"
          :scroll="{ x: 1100 }"
          row-key="createTime"
          @change="handleTableChange"
          bordered
        />
      </div>
    </div>
  </main>
</template>

<style scoped>
.user-chat-container {
  position: relative;
  min-height: calc(100vh - 64px - 48px);
  padding: 40px 20px;
  display: flex;
  justify-content: center;
  align-items: flex-start;
}

.user-chat-bg {
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
  opacity: 0.65;
}

.user-chat-wrapper {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 1600px;
  background: rgba(255, 255, 255, 0.6);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: 32px 32px 40px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.3);
}

.user-chat-header {
  text-align: center;
  margin-bottom: 24px;
}

.user-chat-title {
  font-size: 30px;
  font-weight: 700;
  margin: 0 0 8px;
}

.user-chat-subtitle {
  margin: 0;
  color: #666;
}

.search-bar {
  margin-bottom: 16px;
}

.table-wrapper {
  overflow: hidden;
}

@media (max-width: 1200px) {
  .user-chat-wrapper {
    padding: 24px 16px 32px;
  }
}

@media (max-width: 768px) {
  .user-chat-container {
    padding: 20px 10px;
    min-height: calc(100vh - 56px - 48px);
  }

  .user-chat-bg {
    top: 56px;
  }

  .user-chat-wrapper {
    padding: 20px 12px 28px;
  }
}
</style>

