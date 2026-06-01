import { VISUAL_EDIT_BLOCK_TITLE } from '@/utils/visualWebsiteEditor'

export type ParsedVisualEditSelection = {
  locator?: string
  tagName?: string
  id?: string
  className?: string
  name?: string
  role?: string
  type?: string
  ariaLabel?: string
  placeholder?: string
  href?: string
  src?: string
  text?: string
  cssSelector?: string
  xpath?: string
}

const FIELD_LINE_RE = /^- ([\w-]+): (.*)$/

/** formatSelectedElementForPrompt 写入的 key → ParsedVisualEditSelection 字段 */
const PROMPT_KEY_TO_FIELD: Record<string, keyof ParsedVisualEditSelection> = {
  locator: 'locator',
  tag: 'tagName',
  id: 'id',
  class: 'className',
  name: 'name',
  role: 'role',
  type: 'type',
  'aria-label': 'ariaLabel',
  placeholder: 'placeholder',
  href: 'href',
  src: 'src',
  text: 'text',
  cssSelector: 'cssSelector',
  xpath: 'xpath',
}

function parseVisualEditBlock(blockText: string): ParsedVisualEditSelection | null {
  const lines = blockText.replace(/\r\n/g, '\n').split('\n')
  if (lines.length === 0) return null
  if (lines[0]?.trim() !== VISUAL_EDIT_BLOCK_TITLE) return null

  const parsed: ParsedVisualEditSelection = {}
  let parsedLineCount = 0

  for (let i = 1; i < lines.length; i++) {
    const line = lines[i] ?? ''
    const trimmed = line.trim()
    if (!trimmed) continue
    const match = FIELD_LINE_RE.exec(trimmed)
    if (!match) return null
    const fieldKey = PROMPT_KEY_TO_FIELD[match[1] ?? '']
    if (!fieldKey) return null
    parsed[fieldKey] = match[2] ?? ''
    parsedLineCount++
  }

  if (parsedLineCount === 0) return null
  return parsed
}

/**
 * 从用户消息正文中拆出对话文字与可视化编辑元数据块（仅展示层使用）。
 * 解析失败时 fallback：整段作为 userText，不返回 visualEdit。
 */
export function splitUserMessageForDisplay(raw: string): {
  userText: string
  visualEdit: ParsedVisualEditSelection | null
} {
  const text = (raw ?? '').replace(/\r\n/g, '\n')
  const markerIndex = text.indexOf(VISUAL_EDIT_BLOCK_TITLE)
  if (markerIndex < 0) {
    return { userText: text, visualEdit: null }
  }

  const userText = text.slice(0, markerIndex).replace(/\n+$/, '').trimEnd()
  const blockText = text.slice(markerIndex)
  const visualEdit = parseVisualEditBlock(blockText)
  if (!visualEdit) {
    return { userText: text, visualEdit: null }
  }

  return { userText, visualEdit }
}

export function getVisualEditCardTitle(info: ParsedVisualEditSelection): string {
  return info.locator || info.cssSelector || info.xpath || info.tagName || '已选中元素'
}

export function getVisualEditFieldRows(
  info: ParsedVisualEditSelection,
): Array<{ label: string; value: string }> {
  const rows: Array<{ label: string; value: string }> = []
  const push = (label: string, value?: string) => {
    const v = (value ?? '').trim()
    if (v) rows.push({ label, value: v })
  }

  push('tag', info.tagName)
  push('id', info.id)
  push('class', info.className)
  push('name', info.name)
  push('role', info.role)
  push('type', info.type)
  push('aria-label', info.ariaLabel)
  push('placeholder', info.placeholder)
  push('href', info.href)
  push('src', info.src)
  push('text', info.text)
  if (info.cssSelector && info.cssSelector !== info.locator) {
    push('cssSelector', info.cssSelector)
  }
  if (info.xpath && info.xpath !== info.locator) {
    push('xpath', info.xpath)
  }
  return rows
}
