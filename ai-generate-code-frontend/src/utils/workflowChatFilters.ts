/** 从 SSE 片段中解析出的工作流步骤（与后端 CodeGenWorkflow 文案一致） */
export type WorkflowStepRow = { step: number; label: string }
export type WorkflowStageKey = 'initializing' | 'image_collecting' | 'code_generating' | 'code_checking' | 'ready'

const WORKFLOW_STEP_LINE_RE = /^\[workflow\]\s*第\s*(\d+)\s*步完成[：:]\s*(.+)\s*$/
const WORKFLOW_STEP_GLOBAL_RE = /\[workflow\]\s*第\s*(\d+)\s*步完成[：:]\s*([^[]+)/g
const WORKFLOW_DONE_LINE_RE = /^\[workflow\]\s*(代码生成完成|工作流结束|生成完成)\s*。?\s*$/
const WORKFLOW_NOTICE_MERMAID_ERROR_RE = /^\[workflow_notice\]\s*mermaid_error\s*$/
const WORKFLOW_STAGE_ORDER: WorkflowStageKey[] = [
  'initializing',
  'image_collecting',
  'code_generating',
  'code_checking',
  'ready',
]
const WORKFLOW_STAGE_BASE_LABEL: Record<WorkflowStageKey, string> = {
  initializing: '初始化',
  image_collecting: '图片收集',
  code_generating: '代码生成',
  code_checking: '代码检查',
  ready: '就绪',
}

function getWorkflowStageLabel(stage: WorkflowStageKey, done: boolean): string {
  if (done) {
    if (stage === 'ready') return '就绪！'
    return `${WORKFLOW_STAGE_BASE_LABEL[stage]}已完成！`
  }
  return `${WORKFLOW_STAGE_BASE_LABEL[stage]}中...`
}

function classifyWorkflowStage(rawLabel: string): WorkflowStageKey | null {
  const t = (rawLabel ?? '').trim()
  if (!t) return null
  if (/初始化|提示词增强|智能路由|开始|准备/.test(t)) return 'initializing'
  if (/就绪|完成|结束/.test(t)) return 'ready'
  if (/图片|收集|图像|插画|logo|架构图/i.test(t)) return 'image_collecting'
  if (/代码质量检查|质量检查|质检|检查/.test(t)) return 'code_checking'
  if (/代码生成|项目构建|生成代码|生成/.test(t)) return 'code_generating'
  return null
}

function hasOnlyInitializingPlaceholder(rows: WorkflowStepRow[] | undefined): boolean {
  if (!rows || rows.length !== 1) return false
  return classifyWorkflowStage(rows[0]?.label ?? '') === 'initializing'
}

function hasNonInitializingStage(rows: WorkflowStepRow[]): boolean {
  return rows.some((row) => {
    const stage = classifyWorkflowStage(row.label)
    return stage != null && stage !== 'initializing'
  })
}

export function resolveWorkflowStepsFromMessageContent(
  currentSteps: WorkflowStepRow[] | undefined,
  rawContent: string,
): WorkflowStepRow[] {
  const parsed = parseWorkflowStepsFromText(rawContent)
  if (!currentSteps || currentSteps.length === 0) return parsed
  if (hasOnlyInitializingPlaceholder(currentSteps) && hasNonInitializingStage(parsed)) {
    return parsed
  }
  return currentSteps
}

export function normalizeWorkflowStepsForUi(
  rows: WorkflowStepRow[] | undefined,
  opts?: { historyMode?: boolean; streaming?: boolean },
): WorkflowStepRow[] {
  if (!rows || rows.length === 0) return []
  const stageSet = new Set<WorkflowStageKey>()
  // 只要存在工作流卡片，就固定在最前面展示“初始化”
  stageSet.add('initializing')
  for (const row of rows) {
    const stage = classifyWorkflowStage(row.label)
    if (stage) {
      stageSet.add(stage)
    }
  }
  const isHistoryMode = opts?.historyMode === true
  const isStreaming = opts?.streaming === true
  const orderedStages = WORKFLOW_STAGE_ORDER.filter((s) => stageSet.has(s))
  const displayStages = [...orderedStages]

  // 仅在流式阶段前移一节：后端到达 X 时，前端展示到 X+1。
  if (isStreaming && !isHistoryMode && displayStages.length > 0) {
    const lastStage = displayStages[displayStages.length - 1]
    const lastIdx = lastStage ? WORKFLOW_STAGE_ORDER.indexOf(lastStage) : -1
    if (lastIdx >= 0 && lastIdx < WORKFLOW_STAGE_ORDER.length - 1) {
      const nextStage = WORKFLOW_STAGE_ORDER[lastIdx + 1]
      if (nextStage && !stageSet.has(nextStage)) {
        displayStages.push(nextStage)
      }
    }
  }
  const latestIdx = displayStages.length - 1

  return displayStages.map((stage, index) => {
    const done = isHistoryMode || (!isStreaming || index < latestIdx)
    return {
      step: index + 1,
      label: getWorkflowStageLabel(stage, done),
    }
  })
}

/** 非流式全文：提取步骤并去重（按 step 保留最后一条 label） */
export function parseWorkflowStepsFromText(raw: string): WorkflowStepRow[] {
  if (!raw?.trim()) return []
  const byStep = new Map<number, string>()
  for (const line of raw.split(/\r?\n/)) {
    const t = line.trim()
    const m = t.match(WORKFLOW_STEP_LINE_RE)
    if (m) {
      const step = Number(m[1])
      if (Number.isFinite(step)) {
        byStep.set(step, (m[2] ?? '').trim())
      }
      continue
    }
    if (WORKFLOW_DONE_LINE_RE.test(t)) {
      const nextStep = byStep.size > 0 ? Math.max(...byStep.keys()) + 1 : 1
      byStep.set(nextStep, '代码生成完成')
      continue
    }
    // 兼容历史脏数据：多个 [workflow] 粘在同一行
    for (const item of extractWorkflowStepsFromText(t)) {
      byStep.set(item.step, item.label)
    }
  }
  return [...byStep.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([step, label]) => ({ step, label }))
}

function extractWorkflowStepsFromText(text: string): WorkflowStepRow[] {
  if (!text) return []
  const out: WorkflowStepRow[] = []
  for (const m of text.matchAll(WORKFLOW_STEP_GLOBAL_RE)) {
    const step = Number(m[1] ?? '')
    if (!Number.isFinite(step)) continue
    const label = (m[2] ?? '').trim()
    if (!label) continue
    out.push({ step, label })
  }
  return out
}

const INTERNAL_DIR_LINE_RE = /^(生成目录|构建目录)\s*[:：].+$/

export type SseLineAccumulator = { carry: string }
export type WorkflowSseFilterResult = {
  uiText: string
  newSteps: WorkflowStepRow[]
  mermaidErrorNotice: boolean
}

function maybeAppendDoneStep(source: string, steps: WorkflowStepRow[]) {
  if (!WORKFLOW_DONE_LINE_RE.test(source.trim())) return
  const maxStep = steps.length > 0 ? Math.max(...steps.map((s) => s.step)) : 0
  steps.push({ step: maxStep + 1, label: '代码生成完成' })
}

function normalizeUiText(text: string): string {
  if (!text) return ''
  return text
    .replace(/\r\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
}

/**
 * 将 SSE chunk 与上行残留拼接，按行过滤噪声；未完成行留在 carry。
 * 返回供 `processAssistantChunkIntoUiState` 使用的正文片段（可能为空字符串）。
 */
export function filterAssistantSseChunkForUi(
  acc: SseLineAccumulator,
  chunk: string,
): WorkflowSseFilterResult {
  const s = acc.carry + (chunk ?? '')
  const newSteps: WorkflowStepRow[] = []
  let mermaidErrorNotice = false
  if (!s) {
    return { uiText: '', newSteps, mermaidErrorNotice }
  }

  const endsWithBreak = /\r?\n$/.test(s)
  const allParts = s.split(/\r?\n/)

  // 单行且无换行结尾：整段就是一条 [workflow] 时立即消费（避免步骤卡片延迟到下一包）
  if (!endsWithBreak && allParts.length === 1) {
    const singleLine = allParts[0] ?? ''
    if (WORKFLOW_NOTICE_MERMAID_ERROR_RE.test(singleLine.trim())) {
      acc.carry = ''
      return { uiText: '', newSteps, mermaidErrorNotice: true }
    }
    maybeAppendDoneStep(singleLine, newSteps)
    if (newSteps.length > 0 && singleLine.replace(WORKFLOW_DONE_LINE_RE, '').trim().length === 0) {
      acc.carry = ''
      return { uiText: '', newSteps, mermaidErrorNotice }
    }
    const immediate = extractWorkflowStepsFromText(singleLine)
    if (immediate.length > 0 && singleLine.replace(WORKFLOW_STEP_GLOBAL_RE, '').trim().length === 0) {
      newSteps.push(...immediate)
      acc.carry = ''
      return { uiText: '', newSteps, mermaidErrorNotice }
    }
  }

  const completeLines = endsWithBreak ? allParts : allParts.slice(0, -1)
  acc.carry = endsWithBreak ? '' : (allParts[allParts.length - 1] ?? '')

  const kept: string[] = []
  for (const line of completeLines) {
    const trimmed = line.trim()
    if (trimmed) {
      const wm = trimmed.match(WORKFLOW_STEP_LINE_RE)
      if (wm) {
        const step = Number(wm[1] ?? '')
        if (Number.isFinite(step)) {
          newSteps.push({ step, label: (wm[2] ?? '').trim() })
        }
        continue
      }
      if (WORKFLOW_DONE_LINE_RE.test(trimmed)) {
        maybeAppendDoneStep(trimmed, newSteps)
        continue
      }
      if (WORKFLOW_NOTICE_MERMAID_ERROR_RE.test(trimmed)) {
        mermaidErrorNotice = true
        continue
      }
      if (INTERNAL_DIR_LINE_RE.test(trimmed)) continue
    }
    kept.push(line)
  }

  const uiText = normalizeUiText(kept.join('\n') + (endsWithBreak && completeLines.length > 0 ? '\n' : ''))
  return { uiText, newSteps, mermaidErrorNotice }
}

/** 流结束后：把 carry 中剩余半行也过滤一遍（若无换行结尾） */
export function flushAssistantSseCarry(acc: SseLineAccumulator): WorkflowSseFilterResult {
  const tail = acc.carry
  acc.carry = ''
  if (!tail) return { uiText: '', newSteps: [], mermaidErrorNotice: false }
  const trimmed = tail.trim()
  const newSteps: WorkflowStepRow[] = []
  const mermaidErrorNotice = false
  if (trimmed) {
    if (WORKFLOW_NOTICE_MERMAID_ERROR_RE.test(trimmed)) {
      return { uiText: '', newSteps, mermaidErrorNotice: true }
    }
    maybeAppendDoneStep(trimmed, newSteps)
    if (newSteps.length > 0 && trimmed.replace(WORKFLOW_DONE_LINE_RE, '').trim().length === 0) {
      return { uiText: '', newSteps, mermaidErrorNotice }
    }
    const immediate = extractWorkflowStepsFromText(trimmed)
    if (immediate.length > 0 && trimmed.replace(WORKFLOW_STEP_GLOBAL_RE, '').trim().length === 0) {
      newSteps.push(...immediate)
      return { uiText: '', newSteps, mermaidErrorNotice }
    }
    if (INTERNAL_DIR_LINE_RE.test(trimmed)) {
      return { uiText: '', newSteps, mermaidErrorNotice }
    }
  }
  return { uiText: normalizeUiText(tail), newSteps, mermaidErrorNotice }
}

export function resetSseLineAccumulator(acc: SseLineAccumulator) {
  acc.carry = ''
}

/** 历史回放 / 全文解析：去掉不应展示给用户的行 */
export function stripAssistantNoiseLines(text: string): string {
  if (!text) return ''
  return text
    .split(/\r?\n/)
    .filter((line) => {
      const t = line.trim()
      if (!t) return true
      if (WORKFLOW_STEP_LINE_RE.test(t)) return false
      if (WORKFLOW_DONE_LINE_RE.test(t)) return false
      if (WORKFLOW_NOTICE_MERMAID_ERROR_RE.test(t)) return false
      if (INTERNAL_DIR_LINE_RE.test(t)) return false
      return true
    })
    .join('\n')
}

export function mergeWorkflowSteps(
  existing: WorkflowStepRow[] | undefined,
  delta: WorkflowStepRow[],
): WorkflowStepRow[] {
  const map = new Map<number, string>()
  for (const r of existing ?? []) {
    map.set(r.step, r.label)
  }
  for (const r of delta) {
    map.set(r.step, r.label)
  }
  return [...map.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([step, label]) => ({ step, label }))
}
