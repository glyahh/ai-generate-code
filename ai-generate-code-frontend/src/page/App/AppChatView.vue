<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  message,
  Button as AButton,
  Input as AInput,
  Spin as ASpin,
  Empty as AEmpty,
  Modal as AModal,
  Tooltip as ATooltip,
  Alert as AAlert,
} from 'ant-design-vue'
import type { AppVO, ChatHistory } from '@/api'
import { appGetVoUsingGet, appApplyUsingPost } from '@/api/appController'
import {
  chatHistoryAppAppIdUsingGet,
  chatHistoryOpenApiExportAppIdUsingGet,
  chatHistoryRoundCountAppIdUsingGet,
} from '@/api/chatHistoryController'
import { UserLoginStore } from '@/stores/UserLogin'
import {
  parseMarkdownWithCode,
  findLineAlignedClosingFenceInBuffer,
} from '@/utils/markdownParser'
import {
  extractToolModifyFileNewContentBlocksFromText,
  extractToolWriteFileBlocksFromText,
} from '@/utils/toolOutputAdapters/toolOutputBlockParsers'
import CodeBlock from '@/components/CodeBlock.vue'
import { CodeGenTypeEnum } from '@/utils/CodeGenTypeEnum'
import {
  attachVisualWebsiteEditor,
  formatSelectedElementForPrompt,
  type VisualSelectedElementInfo,
} from '@/utils/visualWebsiteEditor'

// 与全局请求保持一致：默认走当前页面 origin 下的 `/api`
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'
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
  createTime?: string
  uiState?: AssistantUiState
}

type UiMarkdownSegment = { kind: 'markdown'; content: string }
type UiToolRequestSegment = {
  kind: 'tool_request'
  rawLabel: string
  toolName: string
}
type UiToolExecutedWriteFileSegment = {
  kind: 'tool_executed_write_file'
  filePath: string
  language: string
  content: string
  done: boolean
}

type UiToolExecutedModifyFileSegment = {
  kind: 'tool_executed_modify_file'
  filePath: string
  language: string
  beforeContent: string
  afterContent: string
  beforeDone: boolean
  afterDone: boolean
  done: boolean
}

type UiToolExecutedSimpleToolSegment = {
  kind: 'tool_executed_simple_tool'
  toolTitle: string
  filePath: string
  message: string
  done: boolean
}

type UiSegment =
  | UiMarkdownSegment
  | UiToolRequestSegment
  | UiToolExecutedWriteFileSegment
  | UiToolExecutedModifyFileSegment
  | UiToolExecutedSimpleToolSegment

type ToolExecStage =
  | 'markdown'
  | 'tool_exec_wait_fence'
  | 'tool_exec_stream'
  | 'tool_exec_modify_wait_before_fence'
  | 'tool_exec_modify_stream_before'
  | 'tool_exec_modify_wait_after_fence'
  | 'tool_exec_modify_stream_after'
type AssistantUiState = {
  segments: UiSegment[]
  buffer: string
  stage: ToolExecStage
  pendingFilePath?: string
  pendingLanguage?: string
  pendingModifyPart?: 'before' | 'after'
  /** 工具代码块起始围栏的反引号个数（与闭合行对齐规则一致） */
  pendingFenceLen?: number
  activeToolIndex?: number
  /** 折叠重复的“选择工具”提示，避免流式阶段刷屏 */
  lastToolRequestName?: string
}

/** 已加载的历史消息（来自接口） */
const loadedHistoryRecords = ref<ChatHistory[]>([])
const loadingHistory = ref(false)
const loadingMoreHistory = ref(false)
const hasMoreHistory = ref(true)
const inputMessage = ref('')
const sending = ref(false)
const streaming = ref(false)
// 首次生成时用于提示“分析代码 ing~”，避免用户干等
const analyzingHintCloser = ref<null | (() => void)>(null)

// 仅用于把用户在首页选择的“代码类型”追加到发送给 AI 的 prompt 末尾（不回显到前端对话气泡）
const codeTypePromptChoiceOnce = ref<string | null>(null)

let eventSource: EventSource | null = null
let nextMessageId = 1

const hasGenerated = ref(false)
/** 当前会话新产生的消息（不含历史） */
const sessionMessages = ref<ChatMessage[]>([])

type GeneratedFileItem = {
  path: string
  language?: string
  content: string
  updatedAt: number
}

/** 从流式工具输出中提取到的“生成文件”列表（用于 Vue 项目文件回显） */
const generatedFiles = ref<GeneratedFileItem[]>([])
const generatedFileMap = ref<Record<string, GeneratedFileItem>>({})
const toolSelectHintShown = ref(false)

const filesModalOpen = ref(false)
const activeFilePath = ref<string>('')
const activeFile = computed(() => {
  const p = activeFilePath.value
  if (!p) return null
  return generatedFileMap.value[p] ?? null
})
const lastPreviewProbing = ref<{
  active: boolean
  attempts: number
  maxAttempts: number
  timer: number | null
}>({
  active: false,
  attempts: 0,
  maxAttempts: 10,
  timer: null,
})
const refreshAppCtaVisible = ref(false)
const refreshAppCtaHint = ref('生成完成后建议刷新一次，以避免浏览器缓存 / 资源加载时序导致预览仍显示旧内容。')
const refreshingApp = ref(false)

const isVueProject = computed(() => {
  if (!appInfo.value) return false
  const codeGenType = normalizeCodeGenType(appInfo.value.codeGenType)
  return codeGenType === CodeGenTypeEnum.VUE_PROJECT
})

/** 将 ChatHistory 转为 ChatMessage */
function historyToMessage(r: ChatHistory): ChatMessage {
  const role = (r.messageType?.toLowerCase() === 'user' ? 'user' : 'assistant') as 'user' | 'assistant'
  return {
    id: r.id ?? 0,
    role,
    content: r.message ?? '',
    createTime: r.createTime,
  }
}

/** 用于展示的完整消息列表（历史 + 当前会话），按 createTime 升序，无 createTime 的当前会话消息始终排在最下面 */
const displayMessages = computed(() => {
  const historyMsgs = loadedHistoryRecords.value.map(historyToMessage)
  const session = sessionMessages.value
  const combined = [...historyMsgs, ...session]
  return combined.sort((a, b) => {
    const ta = a.createTime || ''
    const tb = b.createTime || ''
    if (ta && tb) return ta.localeCompare(tb)
    // 只有一方有 createTime 时：有时间的（历史）排前面，无时间的（当前会话）排后面
    if (ta) return -1
    if (tb) return 1
    return 0
  })
})

/** 是否应展示网站：仅在已生成过静态页面时展示，避免首次对话未生成完成就挂载 iframe 导致白屏 */
const shouldShowWebsite = computed(() => {
  // Vue 项目：以静态入口探测为准 + 显示托底刷新按钮，避免仅依赖 hasGeneratedCode 导致刷新后误判“未生成”
  if (isVueProject.value) return hasGenerated.value || !!appInfo.value?.hasGeneratedCode
  return !!appInfo.value?.hasGeneratedCode || hasGenerated.value
})
const iframeKey = ref(0)
const chatMessagesRef = ref<HTMLElement | null>(null)
const shouldAutoScroll = ref(true)
const showScrollToBottom = computed(() => !shouldAutoScroll.value && displayMessages.value.length > 0)

const previewIframeRef = ref<HTMLIFrameElement | null>(null)
const visualEditMode = ref(false)
const selectedElement = ref<VisualSelectedElementInfo | null>(null)
const selectedElementDisplay = computed(() => {
  if (!selectedElement.value) return ''
  const info = selectedElement.value
  return (
    info.cssSelector ||
    info.xpath ||
    [info.tagName, info.id ? `#${info.id}` : '', info.className ? `.${String(info.className).split(/\s+/)[0]}` : '']
      .filter(Boolean)
      .join('')
  )
})

const selectedElementTextPreview = computed(() => {
  const info = selectedElement.value
  if (!info) return ''
  const t = String(info.text ?? '').replace(/\s+/g, ' ').trim()
  if (!t) return ''
  return t.length > 120 ? `${t.slice(0, 120)}…` : t
})

let visualEditorDetach: null | (() => void) = null
let visualEditAutoRefreshTimer: number | null = null

function clearSelectedElement() {
  selectedElement.value = null
}

function exitVisualEditMode() {
  visualEditMode.value = false
  clearSelectedElement()
  if (visualEditAutoRefreshTimer != null) {
    window.clearTimeout(visualEditAutoRefreshTimer)
    visualEditAutoRefreshTimer = null
  }
  if (visualEditorDetach) {
    try {
      visualEditorDetach()
    } catch {
      // ignore
    }
    visualEditorDetach = null
  }
}

function tryAttachVisualEditor() {
  const iframe = previewIframeRef.value
  if (!iframe) return
  if (!visualEditMode.value) return

  // 先清理旧的，再挂新的（iframe 刷新/重载时需要重新 attach）
  if (visualEditorDetach) {
    try {
      visualEditorDetach()
    } catch {
      // ignore
    }
    visualEditorDetach = null
  }

  try {
    const res = attachVisualWebsiteEditor({
      iframeEl: iframe,
      onSelected: (info) => {
        selectedElement.value = info
      },
      onNavigateClear: () => {
        selectedElement.value = null
      },
      onError: (err) => {
        // 注入失败时给出明确提示（便于定位：跨域 / CSP / 预览未就绪）
        message.warning(err?.message || '可视化编辑注入失败，请刷新预览后重试')
      },
    })
    visualEditorDetach = res.detach
  } catch (e) {
    message.warning('预览尚未就绪，暂时无法进入编辑模式')
  }
}

function toggleVisualEditMode() {
  if (isReadOnly.value) {
    message.warning('当前应用处于只读状态，无法进入编辑模式')
    return
  }
  // 如果预览入口尚未就绪，依然允许切换编辑态，并立刻触发一次与“刷新应用”相同的探测请求，
  // 等 iframe 重载后 handlePreviewIframeLoad 会重新注入。
  if (!shouldShowWebsite.value && isVueProject.value) {
    void handleManualRefreshApp()
  }
  visualEditMode.value = !visualEditMode.value
  if (!visualEditMode.value) {
    exitVisualEditMode()
    return
  }

  message.info('已进入编辑模式：悬浮高亮元素，点击选中并锁定边框')

  // 立即探测一次，确保会发出与“刷新应用”一致的 ?probe= 请求（便于确认链路）。
  void probePreviewReady().then((ready) => {
    if (ready) hasGenerated.value = true
  })

  // 经验问题：部分项目（尤其 Vue）进入编辑模式时立即注入可能打到旧文档/尚未就绪的 iframe。
  // 因此模拟“先点编辑模式，约 0.5s 后再触发一次刷新”的人工操作，提高生效率。
  if (visualEditAutoRefreshTimer != null) {
    window.clearTimeout(visualEditAutoRefreshTimer)
  }
  visualEditAutoRefreshTimer = window.setTimeout(() => {
    visualEditAutoRefreshTimer = null
    void handleManualRefreshApp()
  })

  nextTick(() => {
    tryAttachVisualEditor()
  })
}

function handlePreviewIframeLoad() {
  // 预览刷新后：若仍在编辑模式，需要重新注入监听
  if (visualEditMode.value) {
    nextTick(() => {
      tryAttachVisualEditor()
    })
  }
}

const applyFeaturedModalVisible = ref(false)
const applyAppProprietyReason = ref('')

const canApplyFeatured = computed(
  () => isOwner.value && !isReadOnly.value && (!appInfo.value?.priority || appInfo.value.priority < 99),
)

const exportingChat = ref(false)
const downloadingProject = ref(false)

/** 当前应用对话轮数（用户一问 + AI 一答为一轮），仅创建者/管理员展示 */
const roundCount = ref<number | null>(null)
const loadingRoundCount = ref(false)

async function fetchRoundCount() {
  const id = appId.value
  if (!id || !(isOwner.value || isAdmin.value)) return
  loadingRoundCount.value = true
  try {
    const res = await chatHistoryRoundCountAppIdUsingGet({
      params: { appId: id as unknown as number },
    })
    const code = res?.data?.code
    const data = res?.data?.data
    if (code === 0 || code === 20000) {
      roundCount.value = typeof data === 'number' ? data : null
    } else {
      roundCount.value = null
    }
  } catch {
    roundCount.value = null
  } finally {
    loadingRoundCount.value = false
  }
}

/** 将服务端返回的对话列表转为 Markdown 文本并触发下载 */
function buildMarkdownFromHistory(records: { messageType?: string; message?: string; createTime?: string }[]): string {
  const lines: string[] = [
    `# 对话导出 - ${appInfo.value?.appName ?? '应用'}`,
    '',
    `导出时间：${new Date().toLocaleString('zh-CN')}`,
    `共 ${records.length} 条消息`,
    '',
    '---',
    '',
  ]
  for (const r of records) {
    const role = r.messageType?.toLowerCase() === 'user' ? '用户' : '应用助手'
    const time = r.createTime ? ` (${r.createTime})` : ''
    lines.push(`## ${role}${time}`)
    lines.push('')
    lines.push(r.message ?? '')
    lines.push('')
    lines.push('')
  }
  return lines.join('\n')
}

async function handleExportChatToLocal() {
  if (!appId.value) {
    message.error('应用 ID 异常，无法导出')
    return
  }
  exportingChat.value = true
  const hide = message.loading('正在导出对话…', 0)
  try {
    const res = await chatHistoryOpenApiExportAppIdUsingGet({
      params: { appId: appId.value as unknown as number },
    })
    hide()
    const code = res?.data?.code
    const data = res?.data?.data
    if ((code === 0 || code === 20000) && Array.isArray(data)) {
      if (data.length === 0) {
        message.info('当前没有可导出的对话记录')
        return
      }
      const md = buildMarkdownFromHistory(data)
      const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `对话导出-${appInfo.value?.appName ?? appId.value}-${Date.now()}.md`
      a.click()
      URL.revokeObjectURL(url)
      message.success('对话已导出到本地')
    } else {
      message.error(res?.data?.message ?? '导出失败')
    }
  } catch (e: unknown) {
    hide()
    const err = e as { response?: { data?: { message?: string } } }
    message.error(err?.response?.data?.message ?? '导出失败，请稍后再试')
  } finally {
    exportingChat.value = false
  }
}

function getFileNameFromContentDisposition(
  headerValue: string | null,
  fallbackName: string,
): string {
  if (!headerValue) return fallbackName

  // 优先解析 filename*（RFC 5987，UTF-8 编码）
  const filenameStarMatch = headerValue.match(/filename\*\s*=\s*UTF-8''([^;]+)/i)
  if (filenameStarMatch && filenameStarMatch[1]) {
    try {
      const encoded = filenameStarMatch[1].trim().replace(/^"(.*)"$/, '$1')
      return decodeURIComponent(encoded)
    } catch {
      // ignore and try other strategies
    }
  }

  // 兼容 filename="xxx"
  const filenameMatch = headerValue.match(/filename\s*=\s*"([^"]+)"/i)
  if (filenameMatch && filenameMatch[1]) {
    return filenameMatch[1].trim()
  }

  // 兼容 filename=xxx（无引号）
  const filenameNoQuoteMatch = headerValue.match(/filename\s*=\s*([^;]+)/i)
  if (filenameNoQuoteMatch && filenameNoQuoteMatch[1]) {
    return filenameNoQuoteMatch[1].trim()
  }

  return fallbackName
}

async function handleDownloadProjectFiles() {
  if (!appId.value) {
    message.error('应用 ID 异常，无法下载项目文件')
    return
  }

  // 若还未生成代码，优先提示用户先完成一次生成
  if (!appInfo.value?.hasGeneratedCode && !hasGenerated.value) {
    message.warning('当前应用还没有可下载的项目代码，请先完成一次生成')
    return
  }

  const displayName = appInfo.value?.appName || String(appId.value)
  const fallbackFileName = `项目代码-${displayName}.zip`

  downloadingProject.value = true
  const hide = message.loading('正在打包并下载项目文件…', 0)
  try {
    const apiBase = API_BASE_URL.replace(/\/$/, '')
    const url = `${apiBase}/app/download/${encodeURIComponent(String(appId.value))}`

    const res = await fetch(url, {
      method: 'GET',
      credentials: 'include',
    })

    if (!res.ok) {
      // 尝试读取服务端返回的错误信息
      let errorText: string | null = null
      try {
        errorText = await res.text()
      } catch {
        // ignore
      }
      message.error(errorText || '下载项目文件失败，请稍后重试')
      return
    }

    const blob = await res.blob()
    const disposition =
      res.headers.get('Content-Disposition') ?? res.headers.get('content-disposition')
    const fileName = getFileNameFromContentDisposition(disposition, fallbackFileName)

    const objectUrl = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = objectUrl
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(objectUrl)

    message.success('项目文件下载成功')
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } } }
    message.error(err?.response?.data?.message ?? '下载项目文件失败，请稍后重试')
  } finally {
    hide()
    downloadingProject.value = false
  }
}

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
  const codeGenType = normalizeCodeGenType(appInfo.value.codeGenType)
  const base = PREVIEW_BASE_URL.replace(/\/$/, '')
  // 使用版本号避免浏览器缓存旧内容；同时配合 iframeKey 触发刷新
  const entry = getPreviewEntryPath(codeGenType)
  const entryPart = entry ? `/${entry}` : '/'
  return `${base}/${codeGenType}_${appId.value}${entryPart}?v=${iframeKey.value}`
})

function normalizeCodeGenType(raw: unknown): string {
  const rawType = String(raw ?? '').trim()
  const lower = rawType.toLowerCase()
  // 后端可能返回枚举名（HTML / MULTI_FILE / VUE_PROJECT），但静态目录使用 value（html / multi_file / vue_project）
  if (lower === CodeGenTypeEnum.HTML) return CodeGenTypeEnum.HTML
  if (lower === CodeGenTypeEnum.MULTI_FILE) return CodeGenTypeEnum.MULTI_FILE
  if (lower === CodeGenTypeEnum.VUE_PROJECT) return CodeGenTypeEnum.VUE_PROJECT
  const upper = rawType.toUpperCase()
  if (upper === 'HTML') return CodeGenTypeEnum.HTML
  if (upper === 'MULTI_FILE') return CodeGenTypeEnum.MULTI_FILE
  if (upper === 'VUE_PROJECT' || upper === 'VUE') return CodeGenTypeEnum.VUE_PROJECT
  return CodeGenTypeEnum.MULTI_FILE
}

function getPreviewEntryPath(codeGenType: string): string {
  if (codeGenType === CodeGenTypeEnum.VUE_PROJECT) return 'dist/index.html'
  return ''
}

function stopPreviewProbing() {
  if (lastPreviewProbing.value.timer != null) {
    window.clearTimeout(lastPreviewProbing.value.timer)
  }
  lastPreviewProbing.value.active = false
  lastPreviewProbing.value.timer = null
  lastPreviewProbing.value.attempts = 0
}

async function probePreviewReady(): Promise<boolean> {
  if (!appInfo.value || !appId.value) return false
  const codeGenType = normalizeCodeGenType(appInfo.value.codeGenType)
  const base = PREVIEW_BASE_URL.replace(/\/$/, '')
  const entry = getPreviewEntryPath(codeGenType)
  const entryPart = entry ? `/${entry}` : '/index.html'
  const url = `${base}/${codeGenType}_${appId.value}${entryPart}?probe=${Date.now()}`
  try {
    const res = await fetch(url, { method: 'GET', credentials: 'include', cache: 'no-store' as RequestCache })
    if (!res.ok) return false
    const ct = (res.headers.get('content-type') ?? '').toLowerCase()
    // 静态入口一般为 html；若是其他类型也视为可访问（某些构建产物可能返回 application/octet-stream）
    if (ct.includes('text/html')) return true
    // 内容长度不可靠（可能 gzip / chunked），这里尽量少读：只要能读取到非空就算 ready
    const text = await res.text()
    return !!text && text.trim().length > 0
  } catch {
    return false
  }
}

function showRefreshAppCta(reason?: string) {
  refreshAppCtaVisible.value = true
  if (reason && reason.trim()) {
    refreshAppCtaHint.value = reason.trim()
  }
}

async function handleManualRefreshApp() {
  if (!appInfo.value || !appId.value) return
  refreshingApp.value = true
  try {
    // 强制刷新 iframe（key 变化会重新挂载）
    iframeKey.value += 1
    // 刷新后立即探测一次，若已 ready 则认为可预览（即使后端 hasGeneratedCode 未同步）
    const ready = await probePreviewReady()
    if (ready) {
      hasGenerated.value = true
    } else {
      // 未 ready 也保留按钮，避免用户无从操作
      showRefreshAppCta('当前预览入口暂未就绪（可能仍在写入/构建）。建议稍等片刻后再次点击“刷新应用”。')
    }
  } finally {
    refreshingApp.value = false
  }
}

async function refreshPreviewWithRetry() {
  stopPreviewProbing()
  lastPreviewProbing.value.active = true
  lastPreviewProbing.value.attempts = 0
  refreshAppCtaVisible.value = false

  const loop = async () => {
    if (!lastPreviewProbing.value.active) return
    lastPreviewProbing.value.attempts += 1
    const ready = await probePreviewReady()
    if (ready) {
      hasGenerated.value = true
      iframeKey.value += 1
      stopPreviewProbing()
      // 生成完成后仍提供托底刷新按钮（避免缓存/资源时序导致的“看不到”）
      if (isVueProject.value) {
        showRefreshAppCta(
          'Vue 项目构建产物可能受浏览器缓存或资源加载时序影响。若预览仍异常，点一次“刷新应用”可强制重载 iframe。',
        )
      }
      return
    }
    if (lastPreviewProbing.value.attempts >= lastPreviewProbing.value.maxAttempts) {
      // 最后兜底刷新一次（哪怕还没探测到），避免用户一直看旧内容
      iframeKey.value += 1
      stopPreviewProbing()
      if (isVueProject.value) {
        showRefreshAppCta(
          '已完成多次探测但入口仍可能未就绪/被缓存。你可以点击“刷新应用”强制重载预览（必要时多点几次）。',
        )
      }
      return
    }
    const delay = Math.min(2500, 500 + lastPreviewProbing.value.attempts * 250)
    lastPreviewProbing.value.timer = window.setTimeout(() => {
      void loop()
    }, delay)
  }

  void loop()
}

function appendUserMessage(text: string) {
  sessionMessages.value.push({
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

function createAssistantUiState(): AssistantUiState {
  return {
    segments: [],
    buffer: '',
    stage: 'markdown',
    lastToolRequestName: undefined,
  }
}

function getOrCreateLastMarkdownSegment(state: AssistantUiState): UiMarkdownSegment {
  const last = state.segments[state.segments.length - 1]
  if (last && last.kind === 'markdown') return last
  const seg: UiMarkdownSegment = { kind: 'markdown', content: '' }
  state.segments.push(seg)
  return seg
}

function appendMarkdown(state: AssistantUiState, text: string) {
  if (!text) return
  const seg = getOrCreateLastMarkdownSegment(state)
  seg.content += text
}

function ensureToolExecutedSegment(
  state: AssistantUiState,
  filePath: string,
  language: string,
): UiToolExecutedWriteFileSegment {
  const seg: UiToolExecutedWriteFileSegment = {
    kind: 'tool_executed_write_file',
    filePath,
    language: language || 'text',
    content: '',
    done: false,
  }
  state.activeToolIndex = state.segments.length
  state.segments.push(seg)
  return seg
}

function getActiveToolExecutedSegment(state: AssistantUiState): UiToolExecutedWriteFileSegment | null {
  const idx = state.activeToolIndex
  if (idx == null) return null
  const seg = state.segments[idx]
  if (seg && seg.kind === 'tool_executed_write_file') return seg
  return null
}

function ensureToolExecutedModifyFileSegment(
  state: AssistantUiState,
  filePath: string,
  language: string,
): UiToolExecutedModifyFileSegment {
  const seg: UiToolExecutedModifyFileSegment = {
    kind: 'tool_executed_modify_file',
    filePath,
    language: language || 'text',
    beforeContent: '',
    afterContent: '',
    beforeDone: false,
    afterDone: false,
    done: false,
  }
  state.activeToolIndex = state.segments.length
  state.segments.push(seg)
  return seg
}

function getActiveToolExecutedModifyFileSegment(
  state: AssistantUiState,
): UiToolExecutedModifyFileSegment | null {
  const idx = state.activeToolIndex
  if (idx == null) return null
  const seg = state.segments[idx]
  if (seg && seg.kind === 'tool_executed_modify_file') return seg
  return null
}

function trimTrailingNewlinesInLastMarkdown(state: AssistantUiState, maxTrim = 2) {
  if (maxTrim <= 0) return
  const last = state.segments[state.segments.length - 1]
  if (!last || last.kind !== 'markdown') return
  let s = last.content ?? ''
  let trimmed = 0
  while (trimmed < maxTrim) {
    if (s.endsWith('\r\n')) {
      s = s.slice(0, -2)
      trimmed += 1
      continue
    }
    if (s.endsWith('\n')) {
      s = s.slice(0, -1)
      trimmed += 1
      continue
    }
    break
  }
  last.content = s
}

function consumeLeadingNewlines(text: string, maxConsume = 2): string {
  let s = text ?? ''
  let consumed = 0
  while (consumed < maxConsume) {
    if (s.startsWith('\r\n')) {
      s = s.slice(2)
      consumed += 1
      continue
    }
    if (s.startsWith('\n')) {
      s = s.slice(1)
      consumed += 1
      continue
    }
    break
  }
  return s
}

function inferLanguageFromFilePath(p: string): string {
  const lower = (p || '').toLowerCase()
  if (lower.endsWith('.vue')) return 'vue'
  if (lower.endsWith('.ts') || lower.endsWith('.tsx')) return 'typescript'
  if (lower.endsWith('.js') || lower.endsWith('.jsx')) return 'javascript'
  if (lower.endsWith('.css') || lower.endsWith('.scss') || lower.endsWith('.less')) return 'css'
  if (lower.endsWith('.html')) return 'html'
  return 'text'
}

function flushSafeMarkdown(state: AssistantUiState, keepTail = 80) {
  if (!state.buffer) return
  if (state.buffer.length <= keepTail) return
  const flushLen = state.buffer.length - keepTail
  const part = state.buffer.slice(0, flushLen)
  state.buffer = state.buffer.slice(flushLen)
  appendMarkdown(state, part)
}

function processAssistantChunkIntoUiState(state: AssistantUiState, chunk: string) {
  state.buffer += chunk ?? ''

  const TOOL_REQUEST_ANY_RE = /\[选择工具\]\s*([^\r\n]+)\s*\r?\n/

  const TOOL_EXEC_HEADER_RE_WRITE_FILE = /\[工具调用\]\s*写入文件\s+([^\r\n]+)\s*\r?\n/
  const TOOL_EXEC_HEADER_RE_MODIFY_FILE = /\[工具调用\]\s*修改文件\s+([^\r\n]+)\s*\r?\n/
  const TOOL_EXEC_HEADER_RE_DELETE_FILE = /\[工具调用\]\s*删除文件\s+([^\r\n]+)\s*\r?\n/
  const TOOL_EXEC_HEADER_RE_READ_FILE = /\[工具调用\]\s*读取文件\s+([^\r\n]+)\s*\r?\n/
  const TOOL_EXEC_HEADER_RE_READ_DIR = /\[工具调用\]\s*读取目录结构\s+([^\r\n]+)\s*\r?\n/

  const loopGuardMax = 2000
  let loopGuard = 0

  while (state.buffer && loopGuard++ < loopGuardMax) {
    if (state.stage === 'markdown') {
      const mReqAny = TOOL_REQUEST_ANY_RE.exec(state.buffer)
      const idxRequestAny = mReqAny ? (mReqAny.index ?? -1) : -1

      const mExecWrite = state.buffer.match(TOOL_EXEC_HEADER_RE_WRITE_FILE)
      const idxExecWrite = mExecWrite ? (mExecWrite.index ?? -1) : -1
      const mExecModify = state.buffer.match(TOOL_EXEC_HEADER_RE_MODIFY_FILE)
      const idxExecModify = mExecModify ? (mExecModify.index ?? -1) : -1
      const mExecDelete = state.buffer.match(TOOL_EXEC_HEADER_RE_DELETE_FILE)
      const idxExecDelete = mExecDelete ? (mExecDelete.index ?? -1) : -1
      const mExecRead = state.buffer.match(TOOL_EXEC_HEADER_RE_READ_FILE)
      const idxExecRead = mExecRead ? (mExecRead.index ?? -1) : -1
      const mExecDir = state.buffer.match(TOOL_EXEC_HEADER_RE_READ_DIR)
      const idxExecDir = mExecDir ? (mExecDir.index ?? -1) : -1

      // 没有任何工具标记：尽量流式把 buffer 大部分吐到 markdown
      if (
        idxRequestAny < 0 &&
        idxExecWrite < 0 &&
        idxExecModify < 0 &&
        idxExecDelete < 0 &&
        idxExecRead < 0 &&
        idxExecDir < 0
      ) {
        flushSafeMarkdown(state, 80)
        break
      }

      // 找到最靠前的事件
      const candidates: Array<{
        idx: number
        type:
          | 'tool_request_any'
          | 'tool_exec_write'
          | 'tool_exec_modify'
          | 'tool_exec_delete'
          | 'tool_exec_read'
          | 'tool_exec_dir'
        toolName?: string
      }> = []

      if (idxRequestAny >= 0) {
        const toolName = (mReqAny?.[1] ?? '').trim()
        candidates.push({ idx: idxRequestAny, type: 'tool_request_any', toolName })
      }
      if (idxExecWrite >= 0) {
        candidates.push({ idx: idxExecWrite, type: 'tool_exec_write' })
      }
      if (idxExecModify >= 0) {
        candidates.push({ idx: idxExecModify, type: 'tool_exec_modify' })
      }
      if (idxExecDelete >= 0) {
        candidates.push({ idx: idxExecDelete, type: 'tool_exec_delete' })
      }
      if (idxExecRead >= 0) {
        candidates.push({ idx: idxExecRead, type: 'tool_exec_read' })
      }
      if (idxExecDir >= 0) {
        candidates.push({ idx: idxExecDir, type: 'tool_exec_dir' })
      }

      candidates.sort((a, b) => a.idx - b.idx)
      const next = candidates[0]
      if (!next) {
        flushSafeMarkdown(state, 80)
        break
      }

      // 先把事件前面的普通文本作为 markdown 吐出
      const before = state.buffer.slice(0, next.idx)
      if (before) appendMarkdown(state, before)
      state.buffer = state.buffer.slice(next.idx)

      if (next.type === 'tool_request_any') {
        // 证据：流式阶段会连续出现大量 `[选择工具] 写入文件`，导致 UI 被胶囊刷屏。
        // 修复：同名工具请求仅展示一次，直到后续出现其它工具或实际工具执行头。
        trimTrailingNewlinesInLastMarkdown(state, 2)

        const mReqNow = TOOL_REQUEST_ANY_RE.exec(state.buffer)
        if (!mReqNow) {
          flushSafeMarkdown(state, 40)
          break
        }

        const raw = (mReqNow[0] ?? '').trim()
        const toolName = (mReqNow[1] ?? next.toolName ?? '').trim() || '未知工具'

        state.buffer = state.buffer.slice(mReqNow[0].length)
        state.buffer = consumeLeadingNewlines(state.buffer, 2)
        if (state.lastToolRequestName !== toolName) {
          state.segments.push({
            kind: 'tool_request',
            rawLabel: raw,
            toolName,
          })
          state.lastToolRequestName = toolName
        }
        toolSelectHintShown.value = true
        continue
      }

      if (next.type === 'tool_exec_write') {
        // TOOL_EXEC(writeFile)：消费 header 行，并进入等待 fence
        const m = state.buffer.match(TOOL_EXEC_HEADER_RE_WRITE_FILE)
        if (!m) {
          // header 可能被 chunk 截断
          flushSafeMarkdown(state, 120)
          break
        }
        const filePath = (m[1] ?? '').trim()
        const headerLen = m[0].length
        state.buffer = state.buffer.slice(headerLen)
        state.pendingFilePath = filePath
        // 进入真实工具执行后，重置“选择工具”去重状态，允许下一轮提示正常出现。
        state.lastToolRequestName = undefined
        state.stage = 'tool_exec_wait_fence'
        continue
      }

      if (next.type === 'tool_exec_modify') {
        // TOOL_EXEC(modifyFile)：创建卡片并进入解析“替换前/替换后”围栏
        const m = state.buffer.match(TOOL_EXEC_HEADER_RE_MODIFY_FILE)
        if (!m) {
          flushSafeMarkdown(state, 120)
          break
        }
        const filePath = (m[1] ?? '').trim()
        const headerLen = m[0].length
        state.buffer = state.buffer.slice(headerLen)
        // 与 writeFile 一致：执行开始后清空去重状态。
        state.lastToolRequestName = undefined

        const lang = inferLanguageFromFilePath(filePath)
        ensureToolExecutedModifyFileSegment(state, filePath, lang)
        state.pendingFilePath = filePath
        state.pendingLanguage = lang
        state.pendingModifyPart = 'before'
        state.stage = 'tool_exec_modify_wait_before_fence'
        continue
      }

      if (next.type === 'tool_exec_delete') {
        const m = state.buffer.match(TOOL_EXEC_HEADER_RE_DELETE_FILE)
        if (!m) {
          flushSafeMarkdown(state, 120)
          break
        }
        const filePath = (m[1] ?? '').trim()
        const headerLen = m[0].length
        state.buffer = state.buffer.slice(headerLen)
        state.lastToolRequestName = undefined

        removeGeneratedFile(filePath)

        state.segments.push({
          kind: 'tool_executed_simple_tool',
          toolTitle: '[工具调用] 删除文件',
          filePath,
          message: `删除文件：${filePath}`,
          done: true,
        })
        continue
      }

      if (next.type === 'tool_exec_read') {
        const m = state.buffer.match(TOOL_EXEC_HEADER_RE_READ_FILE)
        if (!m) {
          flushSafeMarkdown(state, 120)
          break
        }
        const filePath = (m[1] ?? '').trim()
        const headerLen = m[0].length
        state.buffer = state.buffer.slice(headerLen)
        state.lastToolRequestName = undefined

        state.segments.push({
          kind: 'tool_executed_simple_tool',
          toolTitle: '[工具调用] 读取文件',
          filePath,
          message: `读取文件：${filePath}`,
          done: true,
        })
        continue
      }

      if (next.type === 'tool_exec_dir') {
        const m = state.buffer.match(TOOL_EXEC_HEADER_RE_READ_DIR)
        if (!m) {
          flushSafeMarkdown(state, 120)
          break
        }
        const filePath = (m[1] ?? '').trim()
        const headerLen = m[0].length
        state.buffer = state.buffer.slice(headerLen)
        state.lastToolRequestName = undefined

        state.segments.push({
          kind: 'tool_executed_simple_tool',
          toolTitle: '[工具调用] 读取目录结构',
          filePath,
          message: `目录结构：${filePath}`,
          done: true,
        })
        continue
      }
    }

    if (state.stage === 'tool_exec_wait_fence') {
      // 后端 FileWriteTool 的工具执行结果格式为：
      // [工具调用] 写入文件 xxx\n
      // 文件内容:\n
      // ```lang\n
      // ...
      // 因为 streaming chunk 可能被截断，所以这里允许 header 后的：
      // 1) 空行/换行
      // 2) 可选“文件内容:”行
      // 3) 围栏前最多 0-3 空格缩进
      state.buffer = consumeLeadingNewlines(state.buffer, 3)
      state.buffer = state.buffer.replace(/^\s*文件内容:\s*\r?\n/, '')
      state.buffer = consumeLeadingNewlines(state.buffer, 2)

      // 期望：``` 或更多反引号 + 可选语言/信息 + 换行（与 markdown 围栏一致）
      const fenceRe = /^ {0,3}(`{3,})([^\r\n]*)\r?\n/
      const mFence = state.buffer.match(fenceRe)
      if (!mFence) {
        // 证据：这里如果把等待中的片段误吐到 markdown，会打断「文件内容 + 围栏」序列，
        // 导致后续即使收齐围栏也无法进入代码流式卡片（用户只看到工具气泡）。
        // 修复：等待围栏阶段只缓存，不向 markdown 外吐。
        break
      }
      const fenceRunLen = (mFence[1] ?? '```').length
      const info = (mFence[2] ?? '').trim()
      const lang = info.split(/\s+/)[0] || 'text'
      state.pendingLanguage = lang
      state.pendingFenceLen = fenceRunLen
      state.buffer = state.buffer.slice(mFence[0].length)
      ensureToolExecutedSegment(state, state.pendingFilePath ?? '', lang)
      state.stage = 'tool_exec_stream'
      continue
    }

    if (state.stage === 'tool_exec_stream') {
      // tool_exec_stream：流式追加到卡片内部代码区，直到遇到 closing fence
      const toolSeg = getActiveToolExecutedSegment(state)
      if (!toolSeg) {
        // 防御：状态异常则退回 markdown
        state.stage = 'markdown'
        continue
      }

      const minRun = state.pendingFenceLen ?? 3
      const closeRange = findLineAlignedClosingFenceInBuffer(state.buffer, minRun)

      if (closeRange) {
        const codePart = state.buffer.slice(0, closeRange.codeEnd)
        toolSeg.content += codePart
        toolSeg.done = true
        if (toolSeg.filePath) {
          recordGeneratedFile(toolSeg.filePath, toolSeg.language, toolSeg.content)
        }
        state.buffer = state.buffer.slice(closeRange.consumeEnd)

        state.pendingFilePath = undefined
        state.pendingLanguage = undefined
        state.pendingFenceLen = undefined
        state.activeToolIndex = undefined
        state.stage = 'markdown'
        continue
      }

      // 没有结束 fence：尽量把 buffer 大部分作为代码流式追加（保留少量 tail 防止 fence 被截断）
      if (state.buffer.length > 120) {
        const flushLen = state.buffer.length - 40
        toolSeg.content += state.buffer.slice(0, flushLen)
        state.buffer = state.buffer.slice(flushLen)
        break
      }
      break
    }

    if (state.stage === 'tool_exec_modify_wait_before_fence') {
      // 期望 token 序列：
      // 替换前:\n
      // ```(\n|lang...\n)
      state.buffer = consumeLeadingNewlines(state.buffer, 3)
      state.buffer = state.buffer.replace(/^\s*替换前:\s*\r?\n/, '')
      state.buffer = consumeLeadingNewlines(state.buffer, 2)

      const fenceRe = /^ {0,3}(`{3,})([^\r\n]*)\r?\n/
      const mFence = state.buffer.match(fenceRe)
      if (!mFence) {
        // 同上：等待 `替换前` 围栏时仅缓存，避免破坏后续代码块解析。
        break
      }

      const fenceRunLen = (mFence[1] ?? '```').length
      const info = (mFence[2] ?? '').trim()
      const langFromFence = info.split(/\s+/)[0] || ''

      const seg = getActiveToolExecutedModifyFileSegment(state)
      if (seg && langFromFence) {
        seg.language = langFromFence
      }

      state.pendingFenceLen = fenceRunLen
      state.buffer = state.buffer.slice(mFence[0].length)
      state.pendingModifyPart = 'before'
      state.stage = 'tool_exec_modify_stream_before'
      continue
    }

    if (state.stage === 'tool_exec_modify_stream_before') {
      const seg = getActiveToolExecutedModifyFileSegment(state)
      if (!seg) {
        state.stage = 'markdown'
        continue
      }

      const minRun = state.pendingFenceLen ?? 3
      const closeRange = findLineAlignedClosingFenceInBuffer(state.buffer, minRun)
      if (closeRange) {
        const codePart = state.buffer.slice(0, closeRange.codeEnd)
        seg.beforeContent += codePart
        seg.beforeDone = true
        state.buffer = state.buffer.slice(closeRange.consumeEnd)
        state.stage = 'tool_exec_modify_wait_after_fence'
        continue
      }

      if (state.buffer.length > 120) {
        const flushLen = state.buffer.length - 40
        seg.beforeContent += state.buffer.slice(0, flushLen)
        state.buffer = state.buffer.slice(flushLen)
        break
      }
      break
    }

    if (state.stage === 'tool_exec_modify_wait_after_fence') {
      state.buffer = consumeLeadingNewlines(state.buffer, 3)
      state.buffer = state.buffer.replace(/^\s*替换后:\s*\r?\n/, '')
      state.buffer = consumeLeadingNewlines(state.buffer, 2)

      const fenceRe = /^ {0,3}(`{3,})([^\r\n]*)\r?\n/
      const mFence = state.buffer.match(fenceRe)
      if (!mFence) {
        // 同上：等待 `替换后` 围栏时仅缓存，避免“只显示工具调用，不显示代码块”。
        break
      }

      const fenceRunLen = (mFence[1] ?? '```').length
      const info = (mFence[2] ?? '').trim()
      const langFromFence = info.split(/\s+/)[0] || ''

      const seg = getActiveToolExecutedModifyFileSegment(state)
      if (seg && langFromFence) {
        seg.language = langFromFence
      }

      state.pendingFenceLen = fenceRunLen
      state.buffer = state.buffer.slice(mFence[0].length)
      state.pendingModifyPart = 'after'
      state.stage = 'tool_exec_modify_stream_after'
      continue
    }

    if (state.stage === 'tool_exec_modify_stream_after') {
      const seg = getActiveToolExecutedModifyFileSegment(state)
      if (!seg) {
        state.stage = 'markdown'
        continue
      }

      const minRun = state.pendingFenceLen ?? 3
      const closeRange = findLineAlignedClosingFenceInBuffer(state.buffer, minRun)
      if (closeRange) {
        const codePart = state.buffer.slice(0, closeRange.codeEnd)
        seg.afterContent += codePart
        seg.afterDone = true
        seg.done = true

        if (seg.filePath) {
          recordGeneratedFile(seg.filePath, seg.language, seg.afterContent)
        }

        state.buffer = state.buffer.slice(closeRange.consumeEnd)
        state.pendingFilePath = undefined
        state.pendingLanguage = undefined
        state.pendingFenceLen = undefined
        state.pendingModifyPart = undefined
        state.activeToolIndex = undefined
        state.stage = 'markdown'
        continue
      }

      if (state.buffer.length > 120) {
        const flushLen = state.buffer.length - 40
        seg.afterContent += state.buffer.slice(0, flushLen)
        state.buffer = state.buffer.slice(flushLen)
        break
      }
      break
    }

    break
  }
}

function appendAssistantMessageChunk(chunk: string) {
  const last = sessionMessages.value[sessionMessages.value.length - 1]
  if (!last || last.role !== 'assistant') {
    sessionMessages.value.push({
      id: nextMessageId++,
      role: 'assistant',
      content: chunk,
      uiState: createAssistantUiState(),
    })
    const created = sessionMessages.value[sessionMessages.value.length - 1]
    if (created?.uiState) {
      processAssistantChunkIntoUiState(created.uiState, chunk)
    }
    nextTick(() => {
      if (shouldAutoScroll.value) scrollToBottom()
    })
    return
  }
  last.content += chunk
  if (!last.uiState) last.uiState = createAssistantUiState()
  processAssistantChunkIntoUiState(last.uiState, chunk)
  nextTick(() => {
    if (shouldAutoScroll.value) scrollToBottom()
  })
}

function showGeneratedFilesModal() {
  filesModalOpen.value = true
  const list = generatedFiles.value
  const last = list.length > 0 ? (list[list.length - 1] as GeneratedFileItem | undefined) : undefined
  if (last) activeFilePath.value = last.path
}

function parseToolWriteFileBlock(chunk: string) {
  const list = extractToolWriteFileBlocksFromText(chunk ?? '')
  const first = list[0]
  if (!first) return null
  return { filePath: first.filePath, lang: first.lang ?? '', content: first.content }
}

function extractToolWriteFileBlocks(text: string): Array<{ filePath: string; lang?: string; content: string }> {
  return extractToolWriteFileBlocksFromText(text ?? '')
}

function recordGeneratedFile(filePath: string, language: string | undefined, content: string) {
  const now = Date.now()
  const existing = generatedFileMap.value[filePath]
  const item: GeneratedFileItem = {
    path: filePath,
    language: language || existing?.language,
    content,
    updatedAt: now,
  }
  generatedFileMap.value = {
    ...generatedFileMap.value,
    [filePath]: item,
  }
  if (!existing) {
    generatedFiles.value = [...generatedFiles.value, item]
  } else {
    generatedFiles.value = generatedFiles.value.map((it) => (it.path === filePath ? item : it))
  }
}

function removeGeneratedFile(filePath: string) {
  if (!filePath) return
  const existing = generatedFileMap.value[filePath]
  if (!existing) return

  const nextMap = { ...generatedFileMap.value }
  delete nextMap[filePath]
  generatedFileMap.value = nextMap

  generatedFiles.value = generatedFiles.value.filter((it) => it.path !== filePath)
}

function getFileNameColorClass(path: string): string {
  const lower = path.toLowerCase()
  if (lower.endsWith('.vue')) return 'file-path--vue'
  if (lower.endsWith('.ts') || lower.endsWith('.tsx') || lower.endsWith('.js') || lower.endsWith('.jsx'))
    return 'file-path--script'
  if (lower.endsWith('.css') || lower.endsWith('.scss') || lower.endsWith('.less')) return 'file-path--style'
  if (lower.endsWith('.html')) return 'file-path--html'
  if (
    lower.endsWith('package.json') ||
    lower.endsWith('vite.config.js') ||
    lower.endsWith('tsconfig.json') ||
    lower.endsWith('eslint.config.js') ||
    lower.endsWith('postcss.config.js')
  ) {
    return 'file-path--config'
  }
  if (/\.(png|jpg|jpeg|gif|svg|webp)$/.test(lower)) return 'file-path--asset'
  return 'file-path--default'
}

function isCoreFile(path: string): boolean {
  const lower = path.toLowerCase()
  if (lower.includes('/src/main.') || lower.includes('\\src\\main.')) return true
  if (lower.endsWith('/src/app.vue') || lower.endsWith('\\src\\app.vue')) return true
  if (lower.endsWith('/src/router/index.js') || lower.endsWith('\\src\\router\\index.js')) return true
  if (lower.endsWith('/src/router/index.ts') || lower.endsWith('\\src\\router\\index.ts')) return true
  if (lower.includes('/src/pages/') || lower.includes('\\src\\pages\\')) return true
  if (lower.endsWith('/src/styles/global.css') || lower.endsWith('\\src\\styles\\global.css')) return true
  if (lower.endsWith('/index.html') && !lower.includes('node_modules')) return true
  if (lower.endsWith('/vite.config.js') || lower.endsWith('\\vite.config.js')) return true
  if (lower.endsWith('/package.json') || lower.endsWith('\\package.json')) return true
  return false
}

function maybeInjectToolSelectHint(chunk: string) {
  // 仅在后端单独返回 "[选择工具] 写入文件" 这句话时，标记已经展示过提示；
  // 不再自动插入额外文本，避免与真正的 "[工具调用] 写入文件 xxx + 代码块" 混在一起。
  if (toolSelectHintShown.value) return
  const text = (chunk || '').trim()
  if (text === '[选择工具] 写入文件') {
    toolSelectHintShown.value = true
  }
}

function buildUiSegmentsFromFullText(fullText: string): UiSegment[] {
  const state = createAssistantUiState()
  processAssistantChunkIntoUiState(state, fullText ?? '')
  // 最后把残留 buffer 全部作为 markdown 吐出
  if (state.buffer) {
    appendMarkdown(state, state.buffer)
    state.buffer = ''
  }
  // 若异常情况下仍在 tool_exec_stream，则也视为未完成（允许历史里展示“未闭合”的工具卡片）
  return state.segments.filter((s) => {
    if (s.kind !== 'markdown') return true
    return !!s.content && s.content.trim().length > 0
  })
}

/** 仅附加在发给模型的 prompt 末尾，不应在用户气泡中展示 */
const VISUAL_EDIT_MODEL_ONLY_HINT =
  '请根据以上元素信息，对该元素及其相关区域进行定向修改/优化,注意只能修改用户选中的区域,其他区域不可动,并不要再下面的对话中提到这句话'

function stripVisualEditModelOnlyHint(text: string): string {
  const t = text ?? ''
  if (!t.includes(VISUAL_EDIT_MODEL_ONLY_HINT)) return t
  const withBreak = `\n\n${VISUAL_EDIT_MODEL_ONLY_HINT}`
  if (t.endsWith(withBreak)) {
    return t.slice(0, -withBreak.length).trimEnd()
  }
  if (t.endsWith(VISUAL_EDIT_MODEL_ONLY_HINT)) {
    return t.slice(0, -VISUAL_EDIT_MODEL_ONLY_HINT.length).replace(/\n+$/, '').trimEnd()
  }
  return t
}

function getMessageUiSegments(m: ChatMessage): UiSegment[] {
  if (m.role === 'user') {
    return [{ kind: 'markdown', content: stripVisualEditModelOnlyHint(m.content ?? '') }]
  }
  if (m.role !== 'assistant') {
    return [{ kind: 'markdown', content: m.content ?? '' }]
  }
  if (m.uiState) {
    // 对流式消息：把残留 buffer 也当作 markdown（只做展示，不清空 buffer，避免影响后续解析）
    const segs = m.uiState.segments.slice()
    if (m.uiState.buffer && m.uiState.buffer.trim()) {
      segs.push({ kind: 'markdown', content: m.uiState.buffer })
    }
    return segs
  }
  return buildUiSegmentsFromFullText(m.content ?? '')
}

function rebuildGeneratedFilesFromHistory() {
  generatedFiles.value = []
  generatedFileMap.value = {}
  toolSelectHintShown.value = false

  const inferLanguageFromPath = (p: string): string => {
    const lower = (p || '').toLowerCase()
    if (lower.endsWith('.vue')) return 'vue'
    if (lower.endsWith('.ts') || lower.endsWith('.tsx')) return 'typescript'
    if (lower.endsWith('.js') || lower.endsWith('.jsx')) return 'javascript'
    if (lower.endsWith('.css') || lower.endsWith('.scss') || lower.endsWith('.less')) return 'css'
    if (lower.endsWith('.html')) return 'html'
    return 'text'
  }

  for (const r of loadedHistoryRecords.value) {
    const msg = r.message ?? ''
    if (msg.includes('[选择工具] 写入文件') || msg.includes('[选择工具] 修改文件')) {
      toolSelectHintShown.value = true
    }
    const blocks = extractToolWriteFileBlocks(msg)
    for (const b of blocks) {
      recordGeneratedFile(b.filePath, b.lang, b.content)
    }

    // 兼容后端使用 modifyFile 做局部替换：在历史回放时也更新“生成文件”回显
    const modifyBlocks = extractToolModifyFileNewContentBlocksFromText(msg)
    for (const b of modifyBlocks) {
      recordGeneratedFile(b.filePath, inferLanguageFromPath(b.filePath), b.content)
    }
  }
}

async function copyToClipboard(text: string) {
  try {
    await window.navigator.clipboard.writeText(text)
    message.success('已复制到剪贴板')
  } catch {
    message.error('复制失败，请检查浏览器权限')
  }
}

function stopStream() {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  streaming.value = false
  // 结束流式时关闭“分析代码 ing~”提示
  if (analyzingHintCloser.value) {
    try {
      analyzingHintCloser.value()
    } catch {
      // ignore
    }
    analyzingHintCloser.value = null
  }
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

  const selectedSnapshot = selectedElement.value
  // 不再在前端拼接“代码类型提示词”；让后端按 appInfo.codeGenType 决定输出格式
  // （仍然清空一次性变量，避免未来误复用产生重复约束）
  codeTypePromptChoiceOnce.value = null

  const promptBase =
    selectedSnapshot && visualEditMode.value
      ? `${content}\n\n${formatSelectedElementForPrompt(selectedSnapshot)}\n\n${VISUAL_EDIT_MODEL_ONLY_HINT}`
      : content

  const prompt = promptBase

  // 用户在可视化编辑模式下选择了元素时：
  // - 发给 AI 的 prompt 会附带完整元素信息（如上）
  // - 同时对话区里用户这条消息也展示一份元素信息，避免“只在记忆里存在，用户看不到选择了什么”
  const visibleUserContent =
    selectedSnapshot && visualEditMode.value
      ? `${content}\n\n${formatSelectedElementForPrompt(selectedSnapshot)}`
      : content

  // 发送后：清空选中并退出编辑模式（保持其余流程不变）
  if (visualEditMode.value) {
    exitVisualEditMode()
  }

  stopStream()

  // 对话区展示用户输入 + 选中元素信息；模型专用引导句仅在 prompt 中，展示时由 getMessageUiSegments 剥离
  appendUserMessage(visibleUserContent)
  sessionMessages.value.push({
    id: nextMessageId++,
    role: 'assistant',
    content: '',
    uiState: createAssistantUiState(),
  })

  inputMessage.value = ''
  sending.value = true
  streaming.value = true

   // 首次生成时给出“分析代码 ing~”提示，告诉用户正在分析并决定生成格式
  if (!hasGenerated.value && !appInfo.value?.hasGeneratedCode && !analyzingHintCloser.value) {
    try {
      analyzingHintCloser.value = message.loading('分析代码ing~ 请不要退出界面以及其他操作', 0)
    } catch {
      analyzingHintCloser.value = null
    }
  }

  try {
    const query = new URLSearchParams({
      appId: String(appId.value),
      message: prompt,
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
          const chunk = jsonData.d

          const trimmed = (chunk ?? '').trim()
          if (trimmed === '[DONE]' || trimmed === '__END__') {
            stopStream()
            hasGenerated.value = true
            void refreshPreviewWithRetry()
            void fetchRoundCount()
            return
          }

          maybeInjectToolSelectHint(chunk)

          const parsed = parseToolWriteFileBlock(chunk)
          if (parsed) {
            recordGeneratedFile(parsed.filePath, parsed.lang, parsed.content)
          }
          appendAssistantMessageChunk(chunk)
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
          void refreshPreviewWithRetry()
          void fetchRoundCount()
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
      void refreshPreviewWithRetry()
      void fetchRoundCount()
    })

    eventSource.onerror = () => {
      stopStream()
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
}

function scrollToBottom(options: { smooth?: boolean } = {}) {
  const el = chatMessagesRef.value
  if (!el) return
  if (options.smooth && 'scrollTo' in el) {
    el.scrollTo({
      top: el.scrollHeight,
      behavior: 'smooth',
    } as ScrollToOptions)
  } else {
    el.scrollTop = el.scrollHeight
  }
}

function handleScrollToBottomClick() {
  shouldAutoScroll.value = true
  scrollToBottom({ smooth: true })
}

function handleChatScroll() {
  const el = chatMessagesRef.value
  if (!el) return
  const threshold = 40
  const distanceToBottom = el.scrollHeight - (el.scrollTop + el.clientHeight)
  shouldAutoScroll.value = distanceToBottom <= threshold
}

/** 加载对话历史（游标分页） */
async function loadChatHistory(lastCreateTime?: string) {
  if (!appId.value || !appId.value.trim()) return
  const isLoadMore = !!lastCreateTime
  if (isLoadMore) {
    loadingMoreHistory.value = true
  } else {
    loadingHistory.value = true
  }
  try {
    const res = await chatHistoryAppAppIdUsingGet({
      params: {
        // 这里保持 appId 为字符串，避免长整型精度丢失，只在类型层面断言为 number
        appId: appId.value as unknown as number,
        size: 10,
        ...(lastCreateTime ? { lastCreateTime } : {}),
      },
    })
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
      const page = res.data.data
      const records = page?.records ?? []
      if (isLoadMore) {
        loadedHistoryRecords.value = [...records, ...loadedHistoryRecords.value]
      } else {
        loadedHistoryRecords.value = records
      }
      // 历史回放需要重建工具输出（否则“查看项目回显/架构”按钮会丢失）
      rebuildGeneratedFilesFromHistory()
      const totalRow = page?.totalRow ?? 0
      const loaded = loadedHistoryRecords.value.length
      hasMoreHistory.value = loaded < totalRow
    }
  } catch (e) {
    console.error(e)
    if (!isLoadMore) {
      message.error('加载对话历史失败')
    } else {
      message.error('加载更多历史失败')
    }
  } finally {
    loadingHistory.value = false
    loadingMoreHistory.value = false
  }
}

function loadMoreHistory() {
  const records = loadedHistoryRecords.value
  if (records.length === 0 || !hasMoreHistory.value || loadingMoreHistory.value) return
  const oldest = records.reduce((a, b) => {
    const ta = a.createTime ?? ''
    const tb = b.createTime ?? ''
    return ta && tb && ta < tb ? a : b
  })
  if (oldest.createTime) {
    void loadChatHistory(oldest.createTime)
  }
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
      if (appInfo.value.hasGeneratedCode) {
        hasGenerated.value = true
      }
      generatedFiles.value = []
      generatedFileMap.value = {}
      toolSelectHintShown.value = false
      filesModalOpen.value = false
      activeFilePath.value = ''
      await loadChatHistory()
      // loadChatHistory 内已重建；这里再兜底一次，防止未来 loadChatHistory 被改动
      rebuildGeneratedFilesFromHistory()
      void fetchRoundCount()
      // 从首页进入 Vue 项目页面：先探测一次静态入口，并显示托底“刷新应用”按钮（不强依赖 hasGeneratedCode 字段）
      if (isVueProject.value) {
        showRefreshAppCta(
          '已为你加载 Vue 项目。由于浏览器缓存/资源加载顺序，预览可能不会立刻更新；需要时点击“刷新应用”强制重载。',
        )
        // 触发一次入口探测：若 ready 则确保 shouldShowWebsite 生效（即使 hasGeneratedCode 未正确同步）
        const ready = await probePreviewReady()
        if (ready) {
          hasGenerated.value = true
        }
      }

      // 检查是否从首页跳转过来（需要自动提交）
      const autoSend = route.query.autoSend === 'true'
      const hasInitPrompt = appInfo.value.initPrompt && appInfo.value.initPrompt.trim()

      if (hasInitPrompt) {
        if (autoSend) {
          // 从首页创建的应用：自动提交初始提示词
          // 兼容读取 codeTypeChoice 参数（当前前端不再拼接提示词，约束由后端按 appInfo.codeGenType 控制）
          const codeTypeChoice =
            typeof route.query.codeTypeChoice === 'string' && route.query.codeTypeChoice.trim()
              ? route.query.codeTypeChoice.trim()
              : null
          codeTypePromptChoiceOnce.value = codeTypeChoice

          // 清除 query 参数，避免刷新页面时重复自动发送
          router.replace({
            name: 'app-chat',
            params: { id: appId.value },
          })
          await sendMessage(appInfo.value.initPrompt)
        } else {
          // 其他情况：只预填到输入框，等待用户确认提交
          inputMessage.value = appInfo.value.initPrompt ?? ''
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
  stopPreviewProbing()
  exitVisualEditMode()
  if (chatMessagesRef.value) {
    chatMessagesRef.value.removeEventListener('scroll', handleChatScroll)
  }
})
</script>

<template>
  <main class="app-chat-page">
    <a-modal v-model:open="filesModalOpen" title="生成文件回显" :footer="null" width="1180px">
      <div class="files-modal">
        <div class="files-list">
          <div class="files-list-title">文件（{{ generatedFiles.length }}）</div>
          <div v-if="generatedFiles.length === 0" class="files-empty">
            <AEmpty description="暂无可回显的文件（等待工具写入文件输出）" />
          </div>
          <button
            v-for="f in generatedFiles"
            :key="f.path"
            type="button"
            :class="['file-item', { active: f.path === activeFilePath }]"
            @click="activeFilePath = f.path"
          >
            <div class="file-path-row">
              <span class="file-path" :class="getFileNameColorClass(f.path)">{{ f.path }}</span>
              <span v-if="isCoreFile(f.path)" class="file-core-badge">核心代码</span>
            </div>
            <span class="file-meta">{{ new Date(f.updatedAt).toLocaleTimeString('zh-CN') }}</span>
          </button>
        </div>
        <div class="files-viewer">
          <div v-if="!activeFile" class="files-viewer-empty">
            <AEmpty description="请选择左侧文件查看内容" />
          </div>
          <div v-else class="files-viewer-inner">
            <div class="files-viewer-header">
              <div class="files-viewer-path">{{ activeFile.path }}</div>
              <div class="files-viewer-actions">
                <a-button
                  size="small"
                  class="ghost-btn"
                  @click="copyToClipboard(activeFile.content)"
                >
                  复制内容
                </a-button>
              </div>
            </div>
            <div class="files-viewer-code">
              <CodeBlock :code="activeFile.content" :language="activeFile.language || 'txt'" :is-streaming="false" />
            </div>
          </div>
        </div>
      </div>
    </a-modal>

    <a-modal v-model:open="applyFeaturedModalVisible" title="申请成为精选应用" :confirm-loading="false" ok-text="申请"
      cancel-text="取消" @ok="handleApplyFeaturedConfirm" @cancel="closeApplyFeatured">
      <p style="margin-bottom: 8px">你可以简单说明想成为精选应用的理由（选填）：</p>
      <a-textarea v-model:value="applyAppProprietyReason" :rows="3" placeholder="例如：该应用体验较好、功能完善，希望被更多用户看到" />
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
        <div v-if="(isOwner || isAdmin) && (roundCount !== null || loadingRoundCount)" class="round-count-wrap">
          <span class="round-count-line" aria-hidden="true" />
          <span class="round-count-text">
            <template v-if="loadingRoundCount">…</template>
            <template v-else>共 {{ roundCount }} 轮对话</template>
          </span>
        </div>
      </div>
      <div class="top-actions">
        <a-button
          v-if="!isReadOnly"
          class="ghost-btn"
          :loading="exportingChat"
          @click="handleExportChatToLocal"
        >
          导出对话
        </a-button>
        <a-button
          v-if="!isReadOnly"
          class="ghost-btn"
          :loading="downloadingProject"
          @click="handleDownloadProjectFiles"
        >
          下载项目
        </a-button>
        <a-button
          v-if="!isReadOnly"
          class="ghost-btn"
          @click="goEdit"
        >
          编辑应用
        </a-button>
        <a-button
          v-if="!isReadOnly && isVueProject && generatedFiles.length > 0"
          class="ghost-btn"
          @click="showGeneratedFilesModal"
        >
          查看项目回显
        </a-button>
        <a-button v-if="canApplyFeatured" class="ghost-btn" type="primary" @click="openApplyFeatured">
          我要成为精选
        </a-button>
      </div>
    </div>

    <section class="main-content">
      <div class="chat-panel">
        <div v-if="generatedFiles.length > 0" class="generated-files-bar">
          <div class="generated-files-left">
            <span class="generated-files-dot" aria-hidden="true" />
            <span class="generated-files-text">已回显 {{ generatedFiles.length }} 个文件（Vue 项目）</span>
          </div>
          <a-button size="small" class="ghost-btn" @click="showGeneratedFilesModal">查看回显</a-button>
        </div>
        <div ref="chatMessagesRef" class="chat-messages">
          <ASpin v-if="loadingApp" />
          <template v-else>
            <div v-if="displayMessages.length === 0" class="chat-empty">
              <AEmpty description="还没有对话，先从左侧输入提示词开始吧" />
            </div>
            <template v-else>
              <div v-if="hasMoreHistory && !loadingHistory" class="load-more-wrap">
                <a-button type="link" size="small" :loading="loadingMoreHistory" @click="loadMoreHistory">
                  加载更多历史消息
                </a-button>
              </div>
              <div v-for="(m, midx) in displayMessages" :key="m.createTime ? `h-${m.id}` : `s-${m.id}-${midx}`"
                :class="['chat-bubble', m.role === 'user' ? 'bubble-user' : 'bubble-ai']">
                <div class="bubble-inner">
                  <div class="bubble-role">
                    {{ m.role === 'user' ? '我' : '应用助手' }}
                  </div>
                  <div class="bubble-content">
                    <template
                      v-if="m.role === 'assistant' && streaming && m.id === sessionMessages[sessionMessages.length - 1]?.id && !m.content">
                      <div class="typing-indicator">
                        <span class="typing-dot" />
                        <span class="typing-dot" />
                        <span class="typing-dot" />
                        <span class="typing-text">应用助手思考中…</span>
                      </div>
                    </template>
                    <template v-else v-for="(segment, idx) in getMessageUiSegments(m)" :key="idx">
                      <!-- TOOL_REQUEST：选择工具卡片（一次性） -->
                      <div v-if="segment.kind === 'tool_request'" class="tool-hint-pill">
                        <span class="tool-hint-label">选择工具</span>
                        <span class="tool-hint-main">{{ segment.toolName }}</span>
                      </div>

                      <!-- TOOL_EXECUTED：写入文件卡片（可流式追加） -->
                      <div v-else-if="segment.kind === 'tool_executed_write_file'" class="tool-exec-card">
                        <div class="tool-exec-header">
                          <div class="tool-call-badge">使用工具</div>
                          <div class="tool-exec-meta">
                            <div class="tool-call-title">[工具调用] 写入文件</div>
                            <div class="tool-call-path">{{ segment.filePath }}</div>
                          </div>
                          <div class="tool-exec-actions">
                            <a-button size="small" class="ghost-btn" @click="copyToClipboard(segment.content)">
                              复制内容
                            </a-button>
                          </div>
                        </div>
                        <CodeBlock
                          :code="segment.content"
                          :language="segment.language"
                          :is-streaming="streaming && m.id === sessionMessages[sessionMessages.length - 1]?.id && !segment.done"
                        />
                      </div>

                      <!-- TOOL_EXECUTED：修改文件卡片（替换前/替换后） -->
                      <div v-else-if="segment.kind === 'tool_executed_modify_file'" class="tool-exec-card">
                        <div class="tool-exec-header">
                          <div class="tool-call-badge">使用工具</div>
                          <div class="tool-exec-meta">
                            <div class="tool-call-title">[工具调用] 修改文件</div>
                            <div class="tool-call-path">{{ segment.filePath }}</div>
                          </div>
                          <div class="tool-exec-actions">
                            <a-button
                              size="small"
                              class="ghost-btn"
                              @click="copyToClipboard(segment.afterContent)"
                            >
                              复制替换后
                            </a-button>
                          </div>
                        </div>

                        <div style="display: flex; flex-direction: column; gap: 8px">
                          <div class="tool-call-title" style="font-size: 12px; font-weight: 600; color: #111827">
                            替换前
                          </div>
                          <CodeBlock
                            :code="segment.beforeContent"
                            :language="segment.language"
                            :is-streaming="streaming && m.id === sessionMessages[sessionMessages.length - 1]?.id && !segment.beforeDone"
                          />

                          <div class="tool-call-title" style="font-size: 12px; font-weight: 600; color: #111827">
                            替换后
                          </div>
                          <CodeBlock
                            :code="segment.afterContent"
                            :language="segment.language"
                            :is-streaming="streaming && m.id === sessionMessages[sessionMessages.length - 1]?.id && !segment.afterDone"
                          />
                        </div>
                      </div>

                      <!-- TOOL_EXECUTED：其它工具简单输出卡片 -->
                      <div v-else-if="segment.kind === 'tool_executed_simple_tool'" class="tool-exec-card">
                        <div class="tool-exec-header">
                          <div class="tool-call-badge">使用工具</div>
                          <div class="tool-exec-meta">
                            <div class="tool-call-title">{{ segment.toolTitle }}</div>
                            <div class="tool-call-path">{{ segment.filePath }}</div>
                          </div>
                        </div>
                        <div
                          style="
                            margin-top: 2px;
                            font-size: 12px;
                            color: #4b5563;
                            white-space: pre-wrap;
                            line-height: 1.5;
                          "
                        >
                          {{ segment.message }}
                        </div>
                      </div>

                      <!-- 普通 markdown：仍保持流式解析与代码块渲染 -->
                      <template v-else>
                        <template v-for="(mdSeg, mdIdx) in parseMarkdownWithCode(segment.content)" :key="`md-${idx}-${mdIdx}`">
                          <CodeBlock
                            v-if="mdSeg.type === 'code'"
                            :code="mdSeg.content"
                            :language="mdSeg.language"
                            :is-streaming="streaming && m.id === sessionMessages[sessionMessages.length - 1]?.id"
                          />
                          <!-- 证据：隐藏流式 markdown 会导致“生成计划/生成完毕”等说明文本在生成时不可见。
                               修复：流式阶段也展示文本片段，保证与历史回显一致。 -->
                          <span v-else class="text-segment">
                            {{ mdSeg.content }}
                          </span>
                        </template>
                      </template>
                    </template>
                  </div>
                </div>
              </div>
            </template>
          </template>
        </div>

        <div :class="['chat-input-bar', { 'chat-input-bar-readonly': isReadOnly }]">
          <div v-if="selectedElement" class="selected-element-alert">
            <AAlert
              type="info"
              show-icon
              closable
              :message="`已选中元素：${selectedElementDisplay}`"
              @close="clearSelectedElement"
            >
              <template #description>
                <div style="white-space: pre-wrap;">
                  {{
                    selectedElementTextPreview
                      ? `内容预览：${selectedElementTextPreview}\n\n发送消息时会把该元素信息附加到提示词中；你也可以手动移除选中。`
                      : '发送消息时会把该元素信息附加到提示词中；你也可以手动移除选中。'
                  }}
                </div>
              </template>
            </AAlert>
          </div>
          <AInput.TextArea v-model:value="inputMessage" placeholder="描述你想生成的网页，例如：一个用于展示我技术博客的个人站点……" :rows="3"
            :disabled="isReadOnly" :class="['chat-input', { 'chat-input-readonly': isReadOnly }]"
            @press-enter.prevent="handleManualSend" />
          <div class="chat-input-actions">
            <div class="chat-input-hint">
              支持多轮对话微调页面效果
            </div>
            <div class="chat-input-buttons">
              <button v-if="showScrollToBottom" type="button" class="scroll-bottom-btn"
                @click="handleScrollToBottomClick">
                <span class="scroll-bottom-icon">↓</span>
              </button>
              <AButton
                v-if="!streaming"
                class="ghost-btn"
                :type="visualEditMode ? 'primary' : 'default'"
                @click="toggleVisualEditMode"
              >
                {{ visualEditMode ? '退出编辑' : '编辑模式' }}
              </AButton>
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
          <div class="preview-header-left">
            <div class="preview-title">
              生成的网站预览
            </div>
            <div class="preview-subtitle">
              对话生成完成后自动刷新
            </div>
          </div>
          <div class="preview-header-right">
            <ATooltip
              v-if="shouldShowWebsite"
              :title="refreshAppCtaVisible ? refreshAppCtaHint : '强制重新加载预览 iframe，避免浏览器缓存导致仍显示旧页面'"
            >
              <a-button
                size="small"
                class="ghost-btn preview-refresh-btn"
                :loading="refreshingApp"
                @click="handleManualRefreshApp"
              >
                刷新应用
              </a-button>
            </ATooltip>
          </div>
        </div>

        <div class="preview-body">
          <div v-if="!shouldShowWebsite" class="preview-empty">
            <AEmpty description="AI 未生成内容，请先完成至少一轮对话（用户提问 + AI 回复）" />
          </div>
          <iframe
            v-else
            ref="previewIframeRef"
            :key="iframeKey"
            class="preview-iframe"
            :src="previewUrl"
            @load="handlePreviewIframeLoad"
          />
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

/* 应用标题右侧：纤细竖线 + 对话轮数，仅创建者/管理员可见 */
.round-count-wrap {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 8px;
  min-height: 24px;
}

.round-count-line {
  width: 1px;
  height: 20px;
  background: linear-gradient(to bottom, transparent, rgba(15, 23, 42, 0.12), transparent);
  flex-shrink: 0;
}

.round-count-text {
  font-size: 12px;
  color: #64748b;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.02em;
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
  position: relative;
  background: rgba(255, 255, 255, 0.94);
  border-radius: 18px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.08);
  min-height: 0;
  overflow: hidden;
}

.generated-files-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  margin-bottom: 10px;
  border-radius: 14px;
  background: radial-gradient(circle at 20% 0%, rgba(34, 197, 94, 0.18), rgba(14, 165, 233, 0.12) 55%, rgba(15, 23, 42, 0.02));
  box-shadow: inset 0 0 0 1px rgba(15, 23, 42, 0.05);
}

.generated-files-left {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.generated-files-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: linear-gradient(135deg, #22c55e, #0ea5e9);
  box-shadow: 0 0 0 4px rgba(34, 197, 94, 0.12);
  flex: 0 0 auto;
}

.generated-files-text {
  font-size: 12px;
  color: #0f172a;
  opacity: 0.86;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.files-modal {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 12px;
  min-height: 640px;
}

.files-list {
  border-radius: 14px;
  background: rgba(249, 250, 251, 1);
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
}

.files-list-title {
  font-size: 12px;
  color: #334155;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.file-path-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.files-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.file-item {
  width: 100%;
  text-align: left;
  border: none;
  border-radius: 12px;
  padding: 10px 10px;
  cursor: pointer;
  background: rgba(255, 255, 255, 0.85);
  box-shadow: inset 0 0 0 1px rgba(15, 23, 42, 0.06);
  display: flex;
  flex-direction: column;
  gap: 4px;
  transition: transform 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;
}

.file-item:hover {
  transform: translateY(-1px);
  background: rgba(255, 255, 255, 1);
  box-shadow:
    0 8px 18px rgba(15, 23, 42, 0.08),
    inset 0 0 0 1px rgba(15, 23, 42, 0.06);
}

.file-item.active {
  box-shadow:
    0 10px 22px rgba(15, 23, 42, 0.1),
    inset 0 0 0 1px rgba(14, 165, 233, 0.45);
}

.file-path {
  font-size: 12px;
  color: #0f172a;
  word-break: break-all;
}

.file-path--vue {
  color: #2563eb;
  font-weight: 600;
}

.file-path--script {
  color: #16a34a;
  font-weight: 500;
}

.file-path--style {
  color: #db2777;
}

.file-path--html {
  color: #ea580c;
}

.file-path--config {
  color: #7c3aed;
}

.file-path--asset {
  color: #0ea5e9;
}

.file-path--default {
  color: #0f172a;
}

.file-core-badge {
  flex: 0 0 auto;
  padding: 1px 6px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 600;
  color: #065f46;
  background: linear-gradient(135deg, #bbf7d0, #6ee7b7);
  box-shadow: 0 0 0 1px rgba(22, 163, 74, 0.35);
}

.file-meta {
  font-size: 11px;
  color: #64748b;
  font-variant-numeric: tabular-nums;
}

.files-viewer {
  border-radius: 14px;
  background: rgba(249, 250, 251, 1);
  padding: 10px;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.files-viewer-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.files-viewer-inner {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.files-viewer-code {
  flex: 1;
  min-height: 0;
  display: flex;
}

.files-viewer-code :deep(.code-block-container) {
  margin: 0;
  width: 100%;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.files-viewer-code :deep(.code-block-container .code-block) {
  flex: 1 1 auto;
  height: 0;
  min-height: 0;
  max-height: none !important;
}

.files-viewer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.files-viewer-path {
  font-size: 12px;
  font-weight: 600;
  color: #0f172a;
  word-break: break-all;
}

.files-viewer-actions {
  flex: 0 0 auto;
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

.load-more-wrap {
  text-align: center;
  padding: 8px 0;
  margin-bottom: 8px;
}

.chat-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.preview-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.chat-bubble {
  display: flex;
  margin-bottom: 10px;
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
  gap: 6px;
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

.selected-element-alert :deep(.ant-alert) {
  border-radius: 14px;
  border: 1px solid rgba(14, 165, 233, 0.25);
  background:
    radial-gradient(circle at 0 0, rgba(14, 165, 233, 0.18), transparent 55%),
    rgba(255, 255, 255, 0.75);
  box-shadow:
    inset 0 0 0 1px rgba(15, 23, 42, 0.03),
    0 10px 22px rgba(15, 23, 42, 0.06);
}

.selected-element-alert :deep(.ant-alert-message) {
  font-weight: 600;
}

.selected-element-alert :deep(.ant-alert-description) {
  color: rgba(15, 23, 42, 0.72);
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
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.preview-header-left {
  min-width: 0;
}

.preview-header-right {
  flex: 0 0 auto;
  padding-top: 2px;
}

.preview-title {
  font-size: 15px;
  font-weight: 600;
}

.preview-subtitle {
  font-size: 12px;
  color: #6b7280;
}

.preview-refresh-btn {
  border-radius: 999px;
  box-shadow:
    0 10px 22px rgba(15, 23, 42, 0.1),
    inset 0 0 0 1px rgba(14, 165, 233, 0.35);
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

.scroll-bottom-btn {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  border: none;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  background: radial-gradient(circle at 30% 0%, #bbf7d0 0, rgba(34, 197, 94, 0.85) 40%, #0ea5e9 100%);
  box-shadow:
    0 10px 25px rgba(15, 23, 42, 0.25),
    0 0 0 1px rgba(15, 23, 42, 0.04);
  color: #f9fafb;
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  transition:
    transform 0.18s ease-out,
    box-shadow 0.18s ease-out,
    opacity 0.18s ease-out,
    background 0.25s ease-out;
  opacity: 0.98;
}

.scroll-bottom-btn:hover {
  transform: translateY(-2px) scale(1.03);
  box-shadow:
    0 14px 30px rgba(15, 23, 42, 0.3),
    0 0 0 1px rgba(15, 23, 42, 0.06);
}

.scroll-bottom-btn:active {
  transform: translateY(0) scale(0.98);
  box-shadow:
    0 6px 15px rgba(15, 23, 42, 0.25),
    0 0 0 1px rgba(15, 23, 42, 0.06);
}

.scroll-bottom-icon {
  font-size: 18px;
  line-height: 1;
  transform: translateY(1px);
  text-shadow: 0 1px 2px rgba(15, 23, 42, 0.35);
}

.tool-hint-pill {
  align-self: flex-start;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid #d4d4d8;
  background:
    radial-gradient(circle at 0 0, rgba(148, 163, 184, 0.2), transparent 55%),
    #f9fafb;
  box-shadow:
    0 0 0 1px rgba(15, 23, 42, 0.02),
    0 6px 14px rgba(15, 23, 42, 0.08);
  font-size: 12px;
  color: #111827;
}

.tool-hint-label {
  padding: 1px 6px;
  border-radius: 999px;
  background: #e5e7eb;
  color: #4b5563;
  font-size: 11px;
}

.tool-hint-main {
  font-weight: 600;
  letter-spacing: 0.02em;
}

.tool-call-card {
  align-self: stretch;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 14px;
  border: 1px solid rgba(148, 163, 184, 0.8);
  background:
    radial-gradient(circle at 0 0, rgba(226, 232, 240, 0.9), rgba(148, 163, 184, 0.2) 55%),
    #f9fafb;
  box-shadow:
    0 8px 18px rgba(15, 23, 42, 0.12),
    0 0 0 1px rgba(15, 23, 42, 0.02);
}

.tool-exec-card {
  align-self: stretch;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 10px 12px;
  border-radius: 14px;
  border: 1px solid rgba(148, 163, 184, 0.8);
  background:
    radial-gradient(circle at 0 0, rgba(226, 232, 240, 0.9), rgba(148, 163, 184, 0.2) 55%),
    #f9fafb;
  box-shadow:
    0 8px 18px rgba(15, 23, 42, 0.12),
    0 0 0 1px rgba(15, 23, 42, 0.02);
}

.tool-exec-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.tool-exec-meta {
  min-width: 0;
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.tool-exec-actions {
  flex: 0 0 auto;
}

.tool-call-badge {
  flex: 0 0 auto;
  padding: 4px 8px;
  border-radius: 999px;
  background: #0f172a;
  color: #f9fafb;
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.tool-call-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.tool-call-title {
  font-size: 13px;
  font-weight: 600;
  color: #111827;
}

.tool-call-path {
  font-size: 11px;
  color: #4b5563;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono",
    "Courier New", monospace;
  word-break: break-all;
}

.bubble-content :deep(.code-block-container) {
  max-width: 100%;
  width: 100%;
  margin: 2px 0 0;
  border-radius: 6px;
  flex: 1;
  min-height: 200px;
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
