<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Button as AButton, Input as AInput, Spin as ASpin, Empty as AEmpty, Modal as AModal } from 'ant-design-vue'
import type { AppVO } from '@/api'
import { appGetVoUsingGet, appApplyUsingPost } from '@/api/appController'
import { UserLoginStore } from '@/stores/UserLogin'
import { parseMarkdownWithCode } from '@/utils/markdownParser'
import CodeBlock from '@/components/CodeBlock.vue'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8124/api'
const PREVIEW_BASE_URL =
  import.meta.env.VITE_PREVIEW_BASE_URL ?? `${API_BASE_URL.replace(/\/$/, '')}/static`

const route = useRoute()
const router = useRouter()
const userLoginStore = UserLoginStore()

// 保持 id 为字符串，避免雪花算法生成的 Long ID 精度丢失
const appId = computed(() => {
  const idStr = String(route.params.id ?? '')
  return idStr ? idStr : null
})

const appInfo = ref<AppVO | null>(null)
const loadingApp = ref(false)

// 是否为该应用创建者 / 管理员，用于控制是否允许继续对话生成
const isOwner = computed(() => {
  if (!appInfo.value || !userLoginStore.userLogin?.id) return false
  const loginId = String(userLoginStore.userLogin.id)
  const appUserId = appInfo.value.userId != null ? String(appInfo.value.userId) : ''
  return !!appUserId && appUserId === loginId
})

const isAdmin = computed(() => userLoginStore.userLogin?.userRole === 'admin')

// 普通用户访问他人应用（例如首页“精选应用”）时，只能查看预览，不能继续对话生成
const isReadOnly = computed(() => !isOwner.value && !isAdmin.value)

type ChatMessage = {
  id: number
  role: 'user' | 'assistant'
  content: string
}

const messages = ref<ChatMessage[]>([])
const inputMessage = ref('')
const sending = ref(false)
const streaming = ref(false)

let eventSource: EventSource | null = null
let nextMessageId = 1

const hasGenerated = ref(false)
const iframeKey = ref(0)
const chatMessagesRef = ref<HTMLElement | null>(null)
const shouldAutoScroll = ref(true)

const applyFeaturedModalVisible = ref(false)
const applyAppProprietyReason = ref('')

const canApplyFeatured = computed(
  () => isOwner.value && !isReadOnly.value && (!appInfo.value?.priority || appInfo.value.priority < 99),
)

function openApplyFeatured() {
  if (!canApplyFeatured.value) return
  applyFeaturedModalVisible.value = true
}

function closeApplyFeatured() {
  applyFeaturedModalVisible.value = false
}

async function handleApplyFeaturedConfirm() {
  if (!appId.value) {
    message.error('应用 ID 异常，无法提交申请')
    return
  }
  const reason = applyAppProprietyReason.value.trim() || '我想成为精选应用'
  const hide = message.loading('正在提交精选申请...', 0)
  try {
    const res = await appApplyUsingPost({
      body: {
        // 注意：这里必须保持 appId 为字符串，不能转为 Number，否则会丢失精度
        appId: appId.value as any,
        operate: 1,
        appPropriety: 99,
        applyReason: reason,
      },
    })
    hide()
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data === true) {
      message.success('申请已提交，请等待审核')
      applyFeaturedModalVisible.value = false
    } else {
      message.error(res.data.message || '申请提交失败')
    }
  } catch (e: any) {
    hide()
    message.error(e?.response?.data?.message || '申请提交失败，请稍后再试')
  }
}

const previewUrl = computed(() => {
  if (!appInfo.value || !appId.value) return ''
  const codeGenType = appInfo.value.codeGenType || 'multi_file'
  const base = PREVIEW_BASE_URL.replace(/\/$/, '')
  return `${base}/${codeGenType}_${appId.value}/`
})

function appendUserMessage(text: string) {
  messages.value.push({
    id: nextMessageId++,
    role: 'user',
    content: text,
  })
  nextTick(() => {
    if (shouldAutoScroll.value) {
      scrollToBottom()
    }
  })
}

function appendAssistantMessageChunk(chunk: string) {
  const last = messages.value[messages.value.length - 1]
  if (!last || last.role !== 'assistant') {
    messages.value.push({
      id: nextMessageId++,
      role: 'assistant',
      content: chunk,
    })
    nextTick(() => {
      if (shouldAutoScroll.value) {
        scrollToBottom()
      }
    })
    return
  }
  // 实时追加内容，这样代码块会在生成过程中就显示出来
  last.content += chunk
  // 每次追加后立即滚动到底部，让用户看到实时生成效果
  nextTick(() => {
    if (shouldAutoScroll.value) {
      scrollToBottom()
    }
  })
}

function stopStream() {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  streaming.value = false
}

async function sendMessage(text?: string) {
  if (isReadOnly.value) {
    // 只读视图下静默忽略发送，不弹出任何提示
    return
  }
  const content = (text ?? inputMessage.value).trim()
  if (!content) {
    message.warning('请输入要生成的应用描述')
    return
  }
  if (!appId.value || !appId.value.trim()) {
    message.error('应用 ID 异常')
    return
  }

  if (!userLoginStore.userLogin?.id) {
    message.warning('请先登录')
    router.push({
      path: '/user/login',
      query: { redirect: route.fullPath },
    })
    return
  }

  stopStream()

  appendUserMessage(content)
  messages.value.push({
    id: nextMessageId++,
    role: 'assistant',
    content: '',
  })

  inputMessage.value = ''
  sending.value = true
  streaming.value = true

  try {
    const query = new URLSearchParams({
      appId: String(appId.value),
      message: content,
    })
    const apiBase = API_BASE_URL.replace(/\/$/, '')
    const url = `${apiBase}/chat/gen/code?${query.toString()}`

    eventSource = new EventSource(url, { withCredentials: true })

    eventSource.onmessage = (event) => {
      const data = event.data
      if (!data || data === 'null') {
        return
      }

      // 解析 JSON 格式的数据：{"d":"实际内容"}
      try {
        const jsonData = JSON.parse(data)
        if (jsonData && typeof jsonData.d === 'string') {
          appendAssistantMessageChunk(jsonData.d)
          // 自动滚动到底部
          nextTick(() => {
            scrollToBottom()
          })
        }
      } catch (e) {
        // 如果不是 JSON 格式，尝试直接使用（向后兼容）
        if (data === '[DONE]' || data === '__END__') {
          stopStream()
          hasGenerated.value = true
          iframeKey.value += 1
          return
        }
        appendAssistantMessageChunk(data)
        nextTick(() => {
          scrollToBottom()
        })
      }
    }

    // 监听自定义事件类型 "done"
    eventSource.addEventListener('done', () => {
      stopStream()
      hasGenerated.value = true
      iframeKey.value += 1
    })

    eventSource.onerror = () => {
      stopStream()
      hasGenerated.value = true
      iframeKey.value += 1
      message.error('生成过程中出现错误，已结束本次对话')
    }
  } catch (e) {
    console.error(e)
    stopStream()
    message.error('无法建立对话连接，请稍后重试')
  } finally {
    sending.value = false
  }
}

function handleManualSend() {
  if (isReadOnly.value) return
  void sendMessage()
}

function handleStop() {
  if (isReadOnly.value || !streaming.value) return
  stopStream()
  hasGenerated.value = true
  iframeKey.value += 1
}

function scrollToBottom() {
  if (chatMessagesRef.value) {
    chatMessagesRef.value.scrollTop = chatMessagesRef.value.scrollHeight
  }
}

function handleChatScroll() {
  const el = chatMessagesRef.value
  if (!el) return
  const threshold = 40
  const distanceToBottom = el.scrollHeight - (el.scrollTop + el.clientHeight)
  shouldAutoScroll.value = distanceToBottom <= threshold
}

/**
 * 解析消息内容，将代码块和普通文本分开
 */
function parseMessageContent(content: string) {
  return parseMarkdownWithCode(content)
}

async function loadAppInfo() {
  if (!appId.value || !appId.value.trim()) {
    message.error('应用 ID 异常')
    router.replace('/')
    return
  }
  loadingApp.value = true
  try {
    const res = await appGetVoUsingGet({
      params: { id: appId.value },
    })
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
      appInfo.value = res.data.data
      // 若该应用之前已生成过代码，直接展示之前的网页预览，无需用户再发提示词
      if (appInfo.value.hasGeneratedCode) {
        hasGenerated.value = true
      }

      // 检查是否从首页跳转过来（需要自动提交）
      const autoSend = route.query.autoSend === 'true'
      const hasInitPrompt = appInfo.value.initPrompt && appInfo.value.initPrompt.trim()

      if (hasInitPrompt) {
        if (autoSend) {
          // 从首页创建的应用：自动提交初始提示词
          // 清除 query 参数，避免刷新页面时重复自动发送
          router.replace({
            name: 'app-chat',
            params: { id: appId.value },
          })
          await sendMessage(appInfo.value.initPrompt)
        } else {
          // 其他情况：只预填到输入框，等待用户确认提交
          inputMessage.value = appInfo.value.initPrompt
        }
      }
    } else {
      message.error(res.data.message || '获取应用信息失败')
    }
  } catch (e) {
    console.error(e)
    message.error('获取应用信息失败，请稍后重试')
  } finally {
    loadingApp.value = false
  }
}

function goEdit() {
  if (!appId.value) return
  router.push({ name: 'app-edit', params: { id: appId.value } })
}

onMounted(() => {
  void loadAppInfo()
  if (chatMessagesRef.value) {
    chatMessagesRef.value.addEventListener('scroll', handleChatScroll)
  }
})

onBeforeUnmount(() => {
  stopStream()
  if (chatMessagesRef.value) {
    chatMessagesRef.value.removeEventListener('scroll', handleChatScroll)
  }
})
</script>

<template>
  <main class="app-chat-page">
    <a-modal
      v-model:open="applyFeaturedModalVisible"
      title="申请成为精选应用"
      :confirm-loading="false"
      ok-text="申请"
      cancel-text="取消"
      @ok="handleApplyFeaturedConfirm"
      @cancel="closeApplyFeatured"
    >
      <p style="margin-bottom: 8px">你可以简单说明想成为精选应用的理由（选填）：</p>
      <a-textarea
        v-model:value="applyAppProprietyReason"
        :rows="3"
        placeholder="例如：该应用体验较好、功能完善，希望被更多用户看到"
      />
    </a-modal>

    <div class="top-bar">
      <div class="app-title-wrap">
        <div class="app-avatar">
          <span>{{ appInfo?.appName?.[0] ?? 'A' }}</span>
        </div>
        <div class="app-meta">
          <div class="app-name">
            {{ appInfo?.appName || '应用生成中' }}
          </div>
          <div class="app-subtitle">
            一句话，生成一个 Web 应用
          </div>
        </div>
      </div>
      <div class="top-actions">
        <a-button v-if="!isReadOnly" class="ghost-btn" @click="goEdit">
          编辑应用信息
        </a-button>
        <a-button v-if="canApplyFeatured" class="ghost-btn" type="primary" @click="openApplyFeatured">
          我要成为精选
        </a-button>
      </div>
    </div>

    <section class="main-content">
      <div class="chat-panel">
        <div ref="chatMessagesRef" class="chat-messages">
          <ASpin v-if="loadingApp" />
          <template v-else>
            <div v-if="messages.length === 0" class="chat-empty">
              <AEmpty description="还没有对话，先从左侧输入提示词开始吧" />
            </div>
            <div v-else v-for="m in messages" :key="m.id"
              :class="['chat-bubble', m.role === 'user' ? 'bubble-user' : 'bubble-ai']">
              <div class="bubble-inner">
                <div class="bubble-role">
                  {{ m.role === 'user' ? '我' : '应用助手' }}
                </div>
                <div class="bubble-content">
                  <template
                    v-if="m.role === 'assistant' && streaming && m.id === messages[messages.length - 1]?.id && !m.content">
                    <div class="typing-indicator">
                      <span class="typing-dot" />
                      <span class="typing-dot" />
                      <span class="typing-dot" />
                      <span class="typing-text">应用助手思考中…</span>
                    </div>
                  </template>
                  <template v-else v-for="(segment, idx) in parseMessageContent(m.content)" :key="idx">
                    <!-- 代码块：使用独立的 CodeBlock 组件 -->
                    <CodeBlock v-if="segment.type === 'code'" :code="segment.content" :language="segment.language"
                      :is-streaming="streaming && m.id === messages[messages.length - 1]?.id" />
                    <!-- 普通文本：保持原有样式 -->
                    <span v-else class="text-segment">{{ segment.content }}</span>
                  </template>
                </div>
              </div>
            </div>
          </template>
        </div>

        <div :class="['chat-input-bar', { 'chat-input-bar-readonly': isReadOnly }]">
          <AInput.TextArea v-model:value="inputMessage" placeholder="描述你想生成的网页，例如：一个用于展示我技术博客的个人站点……" :rows="3"
            :disabled="isReadOnly" :class="['chat-input', { 'chat-input-readonly': isReadOnly }]"
            @press-enter.prevent="handleManualSend" />
          <div class="chat-input-actions">
            <div class="chat-input-hint">
              支持多轮对话微调页面效果
            </div>
            <div class="chat-input-buttons">
              <AButton v-if="streaming" danger ghost :disabled="isReadOnly" @click="handleStop">
                停止生成
              </AButton>
              <AButton v-else type="primary" :loading="sending" :disabled="isReadOnly"
                :class="{ 'send-btn-readonly': isReadOnly }" @click="handleManualSend">
                发送
              </AButton>
            </div>
          </div>
        </div>
      </div>

      <div class="preview-panel">
        <div class="preview-header">
          <div class="preview-title">
            生成的网站预览
          </div>
          <div class="preview-subtitle">
            对话生成完成后自动刷新
          </div>
        </div>
        <div class="preview-body">
          <div v-if="!hasGenerated">
            <AEmpty description="等待生成结果，稍后将在此展示网页效果" />
          </div>
          <iframe v-else :key="iframeKey" class="preview-iframe" :src="previewUrl" />
        </div>
      </div>
    </section>
  </main>
</template>

<style scoped>
.app-chat-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100vh;
  overflow: hidden;
}

.top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 4px 16px rgba(15, 23, 42, 0.08);
}

.app-title-wrap {
  display: flex;
  align-items: center;
  gap: 12px;
}

.app-avatar {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: linear-gradient(135deg, #34d399, #22c55e);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-weight: 600;
}

.app-meta {
  display: flex;
  flex-direction: column;
}

.app-name {
  font-size: 18px;
  font-weight: 600;
}

.app-subtitle {
  font-size: 12px;
  color: #6b7280;
}

.top-actions {
  display: flex;
  gap: 8px;
}

.ghost-btn {
  border-radius: 999px;
}

.main-content {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, 1.1fr);
  gap: 16px;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.chat-panel,
.preview-panel {
  background: rgba(255, 255, 255, 0.94);
  border-radius: 18px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.08);
  min-height: 0;
  overflow: hidden;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding-right: 4px;
  min-height: 0;
  scrollbar-width: thin;
  scrollbar-color: rgba(0, 0, 0, 0.15) transparent;
}

.chat-messages::-webkit-scrollbar {
  width: 6px;
}

.chat-messages::-webkit-scrollbar-track {
  background: transparent;
}

.chat-messages::-webkit-scrollbar-thumb {
  background: rgba(0, 0, 0, 0.12);
  border-radius: 3px;
}

.chat-messages::-webkit-scrollbar-thumb:hover {
  background: rgba(0, 0, 0, 0.2);
}

.chat-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.chat-bubble {
  display: flex;
  margin-bottom: 10px;
}

.bubble-user {
  justify-content: flex-end;
}

.bubble-ai {
  justify-content: flex-start;
}

.bubble-ai .bubble-inner {
  max-width: 100%;
  width: 100%;
}

.bubble-inner {
  max-width: 80%;
  border-radius: 18px;
  padding: 8px 12px;
  background: #f3f4f6;
}

.bubble-user .bubble-inner {
  background: #2563eb;
  color: white;
}

.bubble-role {
  font-size: 11px;
  opacity: 0.7;
  margin-bottom: 4px;
}

.bubble-content {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.text-segment {
  display: block;
  line-height: 1.6;
}

.typing-indicator {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.04);
}

.typing-dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.35);
  animation: typingDot 1.4s infinite ease-in-out;
}

.typing-dot:nth-child(2) {
  animation-delay: 0.15s;
}

.typing-dot:nth-child(3) {
  animation-delay: 0.3s;
}

.typing-text {
  font-size: 12px;
  color: #6b7280;
}

@keyframes typingDot {

  0%,
  60%,
  100% {
    transform: translateY(0);
    opacity: 0.35;
  }

  30% {
    transform: translateY(-3px);
    opacity: 0.8;
  }
}

/* 代码块在对话气泡中的样式：占满宽度，内嵌淡色滚动条 */
.bubble-content :deep(.code-block-container) {
  max-width: 100%;
  width: 100%;
  margin: 0;
  border-radius: 6px;
  flex: 1;
  min-height: 200px;
}

.bubble-content :deep(.code-block-container .code-block) {
  max-height: none;
  flex: 1;
  min-height: 180px;
}

.bubble-user .bubble-content :deep(.code-block-container) {
  background: rgba(0, 0, 0, 0.2);
  border-color: rgba(255, 255, 255, 0.2);
}

.bubble-user .bubble-content :deep(.code-block-header) {
  background: rgba(0, 0, 0, 0.3);
  border-bottom-color: rgba(255, 255, 255, 0.1);
}

.bubble-user .bubble-content :deep(.code-block) {
  background: rgba(0, 0, 0, 0.2);
  color: rgba(255, 255, 255, 0.9);
}

.chat-input-bar {
  margin-top: 8px;
  border-radius: 16px;
  background: #f9fafb;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.chat-input-bar-readonly {
  opacity: 0.7;
}

.chat-input :deep(.ant-input) {
  border-radius: 12px;
  resize: none;
}

.chat-input-readonly :deep(.ant-input) {
  cursor: not-allowed;
  background-color: #f3f4f6;
}

.chat-input-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: #6b7280;
}

.chat-input-buttons {
  display: flex;
  gap: 8px;
}

.send-btn-readonly {
  cursor: not-allowed;
}

.preview-header {
  margin-bottom: 8px;
}

.preview-title {
  font-size: 15px;
  font-weight: 600;
}

.preview-subtitle {
  font-size: 12px;
  color: #6b7280;
}

.preview-body {
  flex: 1;
  border-radius: 12px;
  background: #f3f4f6;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

.preview-iframe {
  width: 100%;
  height: 100%;
  border: none;
  background: white;
}

@media (max-width: 992px) {
  .main-content {
    grid-template-columns: minmax(0, 1fr);
    flex: 1;
    min-height: 0;
    overflow: hidden;
  }
}
</style>
