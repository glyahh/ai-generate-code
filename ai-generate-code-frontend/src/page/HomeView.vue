<script setup lang="ts">
import { ref, onMounted, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  message,
  Modal as AModal,
  Input as AInput,
  Button as AButton,
  Card as ACard,
  Pagination as APagination,
  Empty as AEmpty,
  Tag as ATag,
} from 'ant-design-vue'
import type { AppVO } from '@/api'
import {
  appAddUsingPost,
  appMyListPageVoUsingPost,
  appGoodListPageVoUsingPost,
  appDeployUsingPost,
  appUndeployUsingPost,
} from '@/api/appController'
import { UserLoginStore } from '@/stores/UserLogin'

const router = useRouter()
const userLoginStore = UserLoginStore()

const prompt = ref('')
const creating = ref(false)

const deployModalVisible = ref(false)
const deployUrl = ref('')

const quickPrompts = [
  {
    label: '博客',
    value: '帮我做一个个人博客主页，包含文章列表和关于我页面',
  },
  {
    label: '记账',
    value: '帮我做一个简单记账工具，可以按分类统计支出',
  },
  {
    label: '待办',
    value: '帮我做一个待办任务管理网站，可以添加、勾选和删除待办事项',
  },
  {
    label: '游戏',
    value: '帮我做一个简单的网页小游戏，例如点击消除类游戏',
  },
]

type AppListState = {
  records: AppVO[]
  total: number
  pageNum: number
  pageSize: number
  loading: boolean
  keyword: string
}

const myApps = ref<AppListState>({
  records: [],
  total: 0,
  pageNum: 1,
  pageSize: 12,
  loading: false,
  keyword: '',
})

const goodApps = ref<AppListState>({
  records: [],
  total: 0,
  pageNum: 1,
  pageSize: 12,
  loading: false,
  keyword: '',
})

const isLogin = computed(() => !!userLoginStore.userLogin?.id)

function formatDateTime(value?: string) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  })
}

function buildAppNameFromPrompt(text: string): string {
  const trimmed = text.trim()
  if (!trimmed) return '我的应用'
  const firstLine = (trimmed.split('\n')[0] || trimmed).trim()
  return firstLine.slice(0, 18) || '我的应用'
}

async function handleCreateApp() {
  const content = prompt.value.trim()
  if (!content) {
    message.warning('请先输入一句话描述你要生成的应用')
    return
  }

  if (!isLogin.value) {
    message.warning('请先登录后再创建应用')
    router.push({
      path: '/user/login',
      query: { redirect: '/' },
    })
    return
  }

  creating.value = true
  try {
    const res = await appAddUsingPost({
      body: {
        appName: buildAppNameFromPrompt(content),
        initPrompt: content,
        codeGenType: 'multi_file',
      },
    })
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
      const appId = res.data.data
      message.success('应用创建成功，正在进入对话页面')
      // 添加 autoSend 标识，表示从首页创建，需要自动提交初始提示词
      router.push({
        name: 'app-chat',
        params: { id: appId },
        query: { autoSend: 'true' },
      })
    } else {
      message.error(res.data.message || '创建应用失败')
    }
  } catch (e) {
    console.error(e)
    message.error('创建应用失败，请稍后重试')
  } finally {
    creating.value = false
  }
}

function handleQuickPrompt(item: { label: string; value: string }) {
  if (creating.value) return
  // 只填充文案，不直接创建应用，交由用户点击“开始生成”
  prompt.value = item.value
}

async function loadMyApps() {
  if (!isLogin.value) {
    myApps.value.records = []
    myApps.value.total = 0
    return
  }
  myApps.value.loading = true
  try {
    const keyword = myApps.value.keyword.trim()
    const isId = keyword && /^\d+$/.test(keyword)
    const res = await appMyListPageVoUsingPost({
      body: {
        pageNum: myApps.value.pageNum,
        pageSize: myApps.value.pageSize,
        id: isId ? (keyword as any) : undefined,
        appName: !isId && keyword ? keyword : undefined,
      },
    })
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
      myApps.value.records = res.data.data.records || []
      myApps.value.total = res.data.data.totalRow || 0
    }
  } finally {
    myApps.value.loading = false
  }
}

async function loadGoodApps() {
  goodApps.value.loading = true
  try {
    const keyword = goodApps.value.keyword.trim()
    const isId = keyword && /^\d+$/.test(keyword)
    const res = await appGoodListPageVoUsingPost({
      body: {
        pageNum: goodApps.value.pageNum,
        pageSize: goodApps.value.pageSize,
        id: isId ? (keyword as any) : undefined,
        appName: !isId && keyword ? keyword : undefined,
      },
    })
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
      goodApps.value.records = res.data.data.records || []
      goodApps.value.total = res.data.data.totalRow || 0
    }
  } finally {
    goodApps.value.loading = false
  }
}

function handleMyPageChange(page: number, pageSize: number) {
  myApps.value.pageNum = page
  myApps.value.pageSize = pageSize
  void loadMyApps()
}

function handleGoodPageChange(page: number, pageSize: number) {
  goodApps.value.pageNum = page
  goodApps.value.pageSize = pageSize
  void loadGoodApps()
}

function goChat(app: AppVO) {
  if (!app.id) return
  router.push({ name: 'app-chat', params: { id: app.id } })
}

function goEdit(app: AppVO) {
  if (!app.id) return
  router.push({ name: 'app-edit', params: { id: app.id } })
}

async function handleDeploy(app: AppVO) {
  if (!app.id) return
  const hide = message.loading('正在部署应用...', 0)
  try {
    const res = await appDeployUsingPost({
      body: { appId: app.id },
    })
    hide()
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
      const url = res.data.data
      deployUrl.value = url
      deployModalVisible.value = true
      message.success('部署成功')
    } else {
      message.error(res.data.message || '部署失败')
    }
  } catch (e) {
    hide()
    console.error(e)
    message.error('部署失败，请稍后重试')
  }
}

async function handleUndeploy(app: AppVO) {
  if (!app.id) return
  const appName = app.appName || '该应用'
  AModal.confirm({
    title: '确认下线',
    content: `是否下线部署的应用「${appName}」？`,
    okText: '下线',
    okType: 'primary',
    cancelText: '取消',
    async onOk() {
      const hide = message.loading('正在下线应用...', 0)
      try {
        const res = await appUndeployUsingPost({
          body: { appId: app.id as any },
        })
        hide()
        if ((res.data.code === 0 || res.data.code === 20000) && res.data.data === true) {
          message.success('下线成功')
        } else {
          // 未成功下线时统一提示“好像还没有部署哦”
          message.error('好像还没有部署哦')
        }
      } catch (e) {
        hide()
        console.error(e)
        message.error('下线失败，请稍后重试')
      }
    },
  })
}

async function handleDeployCopy() {
  if (!deployUrl.value) return
  try {
    await navigator.clipboard.writeText(deployUrl.value)
    message.success('已复制访问地址到剪贴板')
  } catch (e) {
    console.error(e)
    message.error('复制失败，请手动复制')
  }
}

function handleDeployVisit() {
  if (!deployUrl.value) return
  window.open(deployUrl.value, '_blank')
}

function handleDeployModalClose() {
  deployModalVisible.value = false
}

async function handleDeleteApp(app: AppVO) {
  if (!app.id) return
  const appName = app.appName || '该应用'
  AModal.confirm({
    title: '确认删除',
    content: `确定要删除「${appName}」吗？删除后将无法恢复。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      try {
        const { appOpenApiDeleteUsingPost } = await import('@/api/appController')
        const res = await appOpenApiDeleteUsingPost({
          body: { id: app.id as any },
        })
        if (res.data.code === 0 || res.data.code === 20000) {
          message.success('删除成功')
          await loadMyApps()
        } else {
          message.error(res.data.message || '删除失败')
        }
      } catch (e) {
        console.error(e)
        message.error('删除失败，请稍后重试')
      }
    },
  })
}

// 已登录用户首屏自动加载“我的应用”，登录状态变化时自动刷新
watch(
  () => userLoginStore.userLogin?.id,
  (id) => {
    if (id) {
      myApps.value.pageNum = 1
      void loadMyApps()
    } else {
      myApps.value.records = []
      myApps.value.total = 0
    }
  },
  { immediate: true },
)

onMounted(() => {
  void loadGoodApps()
})
</script>

<template>
  <main class="home-page">
    <AModal v-model:open="deployModalVisible" title="部署成功" :footer="null" centered @cancel="handleDeployModalClose">
      <div class="deploy-modal">
        <p class="deploy-modal-text">
          应用已成功部署，访问地址如下：
        </p>
        <AInput readonly :value="deployUrl" class="deploy-modal-input" />
        <div class="deploy-modal-actions">
          <AButton type="primary" @click="handleDeployVisit">
            一键访问
          </AButton>
          <AButton @click="handleDeployCopy">
            复制地址
          </AButton>
        </div>
      </div>
    </AModal>
    <section class="hero-section">
      <div class="hero-bg" />
      <div class="hero-content">
        <div class="hero-title">
          <div class="hero-main-text">
            <span class="hero-main-text-inner">Start Vibe Coding~</span>
          </div>
        </div>

        <div class="prompt-card-border">
          <div class="prompt-card">
            <AInput.TextArea v-model:value="prompt" class="prompt-input" :rows="3"
              placeholder="使用 glyahh-generate-code 创建一个高效的小工具，例如：帮我做一个旅行计划记录网站……" />
            <div class="prompt-footer">
              <div class="prompt-hint">
                支持自然语言描述，越具体效果越好
              </div>
              <div class="prompt-actions">
                <AButton type="primary" shape="round" :loading="creating" @click="handleCreateApp">
                  开始生成
                </AButton>
              </div>
            </div>
          </div>
        </div>
        <div class="prompt-quick-row">
          <AButton v-for="item in quickPrompts" :key="item.label" size="large" shape="round"
            class="prompt-quick-btn" @click="handleQuickPrompt(item)">
            {{ item.label }}
          </AButton>
        </div>
      </div>
    </section>

    <section class="apps-section">
      <div class="section-header">
        <h2>精选应用</h2>
        <span class="section-subtitle">由管理员挑选的优质案例</span>
      </div>

      <div class="list-toolbar">
        <div class="list-toolbar-search">
          <AInput
            v-model:value="goodApps.keyword"
            placeholder="按名称或 App ID 搜索精选应用"
            allow-clear
            @press-enter="loadGoodApps"
          />
          <AButton type="primary" ghost @click="loadGoodApps">
            搜索
          </AButton>
        </div>
      </div>

      <div class="card-grid" v-loading="goodApps.loading">
        <template v-if="goodApps.records.length">
          <ACard
            v-for="app in goodApps.records"
            :key="app.id"
            class="app-card good-app-card"
            hoverable
            @click="goChat(app)"
          >
            <div class="app-cover good-cover">
              <span>{{ app.appName?.[0] ?? 'A' }}</span>
            </div>
            <div class="app-info">
              <div class="app-name-line">
                <span class="app-name">{{ app.appName || '未命名应用' }}</span>
                <ATag color="gold" size="small">精选</ATag>
              </div>
              <div class="app-meta">
                <span>{{ app.userVO?.userName || app.userVO?.userAccount || '匿名用户' }}</span>
              </div>
            </div>
          </ACard>
        </template>
        <template v-else>
          <div class="empty-wrap">
            <AEmpty description="暂时还没有精选应用" />
          </div>
        </template>
      </div>

      <div v-if="goodApps.total > 0" class="pagination-wrap">
        <APagination :current="goodApps.pageNum" :page-size="goodApps.pageSize" :total="goodApps.total"
          :show-size-changer="true" :page-size-options="['8', '12', '20']" @change="handleGoodPageChange"
          @show-size-change="handleGoodPageChange" />
      </div>
    </section>

    <section class="apps-section">
      <div class="section-header">
        <h2>我的应用</h2>
        <span class="section-subtitle">最多每页展示 20 个，可根据名称搜索</span>
      </div>

      <div v-if="!isLogin" class="section-login-tip">
        <AEmpty description="登录后即可查看你创建的所有应用" />
      </div>
      <div v-else>
        <div class="list-toolbar">
          <div class="list-toolbar-search">
            <AInput
              v-model:value="myApps.keyword"
              placeholder="按名称或 App ID 搜索我的应用"
              allow-clear
              @press-enter="loadMyApps"
            />
            <AButton type="primary" ghost @click="loadMyApps">
              搜索
            </AButton>
          </div>
        </div>
        <div class="card-grid" v-loading="myApps.loading">
          <template v-if="myApps.records.length">
            <ACard
              v-for="app in myApps.records"
              :key="app.id"
              class="app-card my-app-card"
              hoverable
              @click="goChat(app)"
            >
              <div class="app-hover-meta">
                <div v-if="app.createTime" class="app-hover-meta-row">
                  <span class="meta-label">创建时间</span>
                  <span class="meta-value">{{ formatDateTime(app.createTime) }}</span>
                </div>
                <div v-if="app.updateTime" class="app-hover-meta-row">
                  <span class="meta-label">修改时间</span>
                  <span class="meta-value">{{ formatDateTime(app.updateTime) }}</span>
                </div>
              </div>
              <div class="app-cover">
                <span>{{ app.appName?.[0] ?? 'A' }}</span>
              </div>
              <div class="app-info">
                <div class="app-name-line">
                  <span class="app-name">{{ app.appName || '未命名应用' }}</span>
                  <ATag v-if="app.priority && app.priority >= 99" color="gold" size="small">
                    精选
                  </ATag>
                </div>
                <div class="app-meta">
                  <div class="app-meta-type">
                    <span class="app-meta-type-label">生成类型</span>
                    <span class="app-meta-type-tag">
                      {{ app.codeGenType || 'multi_file' }}
                    </span>
                  </div>
                </div>
              </div>
              <div class="app-actions" @click.stop>
                <AButton size="small" type="link" danger @click="handleDeleteApp(app)">
                  删除
                </AButton>
                <AButton size="small" type="link" @click="goEdit(app)">
                  编辑
                </AButton>
                <AButton size="small" type="link" @click="handleDeploy(app)">
                  部署
                </AButton>
                <AButton size="small" type="link" @click="handleUndeploy(app)">
                  下线
                </AButton>
              </div>
            </ACard>
          </template>
          <template v-else>
            <div class="empty-wrap">
              <AEmpty description="还没有创建应用，先在上方输入一句话试试吧" />
            </div>
          </template>
        </div>
        <div v-if="myApps.total > 0" class="pagination-wrap">
          <APagination :current="myApps.pageNum" :page-size="myApps.pageSize" :total="myApps.total"
            :show-size-changer="true" :page-size-options="['8', '12', '20']" @change="handleMyPageChange"
            @show-size-change="handleMyPageChange" />
        </div>
      </div>
    </section>
  </main>
</template>

<style scoped>
.home-page {
  display: flex;
  flex-direction: column;
  gap: 32px;
  min-height: 100vh;
  width: 100%;
  padding: 24px 0 40px;
}

.hero-section {
  position: relative;
  border-radius: 24px;
  overflow: hidden;
  padding: 40px 32px 32px;
  min-height: 60vh;
  display: flex;
  align-items: center;
  justify-content: center;
}

.hero-bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
  opacity: 0.28;
  background:
    radial-gradient(circle at 0 0, rgba(129, 140, 248, 0.4) 0, transparent 55%),
    radial-gradient(circle at 100% 0, rgba(56, 189, 248, 0.4) 0, transparent 55%);
}

.hero-content {
  position: relative;
  z-index: 1;
  max-width: 960px;
  width: 100%;
  margin: 0 auto;
  text-align: center;
  padding-inline: 24px;
}

.hero-title {
  margin-bottom: 24px;
}

.hero-main-text {
  font-size: 40px;
  font-weight: 700;
  letter-spacing: 0.04em;
  margin-bottom: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #0f172a;
}

.hero-main-text-inner {
  display: inline-block;
  padding-inline: 4px;
  text-shadow: 0 8px 16px rgba(148, 163, 184, 0.35);
  animation: heroFloat 3s ease-in-out infinite;
}

.prompt-card-border {
  margin-top: 8px;
  padding: 1px;
  border-radius: 22px;
  max-width: 780px;
  margin-left: auto;
  margin-right: auto;
  background:
    linear-gradient(120deg,
      rgba(59, 130, 246, 0.25),
      rgba(45, 212, 191, 0.25),
      rgba(244, 114, 182, 0.25),
      rgba(129, 140, 248, 0.25),
      rgba(59, 130, 246, 0.25));
  background-size: 300% 300%;
  animation: borderFlow 12s linear infinite;
}

.prompt-card {
  padding: 16px 16px 12px;
  border-radius: 21px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.08);
}

.prompt-input :deep(.ant-input) {
  border-radius: 14px;
  border: none;
  background: transparent;
  resize: none;
  box-shadow: none;
}

@keyframes borderFlow {
  0% {
    background-position: 0% 50%;
  }
  100% {
    background-position: 100% 50%;
  }
}

.prompt-quick-row {
  margin-top: 16px;
  display: flex;
  justify-content: center;
  gap: 16px;
  flex-wrap: wrap;
}

.prompt-quick-btn {
  min-width: 72px;
}

@keyframes heroFloat {
  0% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-4px);
  }
  100% {
    transform: translateY(0);
  }
}

.prompt-footer {
  margin-top: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: #6b7280;
}

.prompt-actions {
  display: flex;
  gap: 8px;
}

.apps-section {
  background: #ffffff;
  border-radius: 20px;
  padding: 20px 20px 16px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.06);
  color: #0f172a;
}
.section-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 16px;
}

.section-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.section-subtitle {
  font-size: 12px;
  color: #6b7280;
}

.section-login-tip {
  padding: 40px 0;
}

.list-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.list-toolbar-search {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
}

.app-card {
  cursor: pointer;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 260px;
  position: relative;
  overflow: hidden;
  border-radius: 16px;
  border: 1px solid transparent !important;
  background: rgba(255, 255, 255, 0.86);
  box-shadow: 0 10px 26px rgba(15, 23, 42, 0.08);
  backdrop-filter: blur(14px) saturate(130%);
  -webkit-backdrop-filter: blur(14px) saturate(130%);
  padding-top: 32px;
  transition:
    border-color 0.16s ease-out,
    box-shadow 0.16s ease-out,
    transform 0.16s ease-out;
}

.app-card:hover {
  border-color: rgba(59, 130, 246, 0.55) !important;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.14);
  transform: translateY(-2px);
}

.app-cover {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  margin: 0 auto 10px;
  background: linear-gradient(135deg, #1d4ed8, #22c55e);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #e5e7eb;
  font-size: 22px;
  font-weight: 600;
}

/* 我的应用 hover 时封面仅极轻微变淡，几乎看不出 */
.my-app-card:hover .app-cover {
  background: linear-gradient(135deg, #2a5dd4, #2dc968);
}

.good-cover {
  background: linear-gradient(135deg, #facc15, #f97316);
}

.app-info {
  flex: 1;
}

.good-app-card .app-info {
  margin-top: 10px;
}

.app-name-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
  /* 预留出最多两行标题的高度，保证不同长度标题下方按钮对齐 */
  min-height: 3.2em;
}

.app-name {
  font-weight: 600;
  font-size: 14px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.app-meta {
  margin-top: 4px;
  display: flex;
  justify-content: flex-end;
  font-size: 12px;
  color: #6b7280;
}

.app-meta-type {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 8px;
  border-radius: 999px;
  background:
    radial-gradient(circle at 0 0, rgba(148, 163, 184, 0.38), transparent 60%),
    rgba(248, 250, 252, 0.95);
  box-shadow:
    0 6px 14px rgba(15, 23, 42, 0.12),
    0 0 0 1px rgba(148, 163, 184, 0.4);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}

.app-meta-type-label {
  font-size: 11px;
  color: #9ca3af;
}

.app-meta-type-tag {
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: #0f172a;
  background: linear-gradient(135deg, #dbeafe, #bbf7d0);
  box-shadow: 0 0 0 1px rgba(59, 130, 246, 0.45);
}

.app-actions {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  margin-top: 16px;
  padding-bottom: 4px;
}

.my-app-card .app-hover-meta {
  position: absolute;
  inset: 0;
  padding: 10px 12px;
  background: linear-gradient(
    to bottom,
    rgba(15, 23, 42, 0.18),
    rgba(15, 23, 42, 0.25),
    transparent 65%
  );
  opacity: 0;
  pointer-events: none;
  display: flex;
  flex-direction: column;
  gap: 4px;
  justify-content: flex-start;
  align-items: center;
  text-align: center;
  transition: opacity 0.18s ease-out;
}

.my-app-card:hover .app-hover-meta {
  opacity: 1;
}

.app-hover-meta-row {
  display: flex;
  gap: 6px;
  font-size: 13px;
  color: #4b5563;
  justify-content: center;
  width: 100%;
  text-align: center;
}

.meta-label {
  opacity: 0.7;
}

.meta-value {
  font-variant-numeric: tabular-nums;
}

.empty-wrap {
  grid-column: 1 / -1;
  padding: 24px 0;
}

.pagination-wrap {
  margin-top: 12px;
  display: flex;
  justify-content: center;
}

.deploy-modal-text {
  margin-bottom: 8px;
  font-size: 13px;
  color: #4b5563;
}

.deploy-modal-input {
  margin-bottom: 12px;
}

.deploy-modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

@keyframes homeGradientShift {
  0% {
    background-position: 0% 50%;
  }
  50% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0% 50%;
  }
}

@media (max-width: 768px) {
  .hero-section {
    padding: 28px 12px 20px;
  }

  .hero-main-text {
    font-size: 30px;
  }

  .hero-highlight {
    font-size: 20px;
  }

  .apps-section {
    padding: 16px 12px 12px;
  }
}
</style>
