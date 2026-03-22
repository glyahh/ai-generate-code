/**
 * Markdown 代码块解析工具
 * 用于将 AI 返回的 markdown 格式文本解析成代码块和普通文本片段
 */

export interface TextSegment {
  type: 'text' | 'code'
  content: string
  language?: string // 代码块的语言类型（如 'css', 'javascript', 'html' 等）
}

/**
 * 解析包含代码块的 markdown 文本
 * 支持格式：```语言名\n代码内容\n```
 * 支持流式输出：即使代码块未完成（只有开始标记 ```语言名\n，没有结束标记 ```），也会实时显示
 * 
 * @param text 原始文本内容
 * @returns 解析后的文本片段数组
 */
export function parseMarkdownWithCode(text: string): TextSegment[] {
  const segments: TextSegment[] = []
  // 匹配完整的代码块：```语言名\n代码内容\n```
  // 兼容不同换行符（\n 或 \r\n）
  const codeBlockRegex = /```(\w+)?\r?\n([\s\S]*?)```/g

  let lastIndex = 0
  let match: RegExpExecArray | null
  const matches: RegExpExecArray[] = []

  // 先收集所有完整的代码块匹配
  while ((match = codeBlockRegex.exec(text)) !== null) {
    matches.push(match)
  }

  // 处理所有完整的代码块
  for (const match of matches) {
    // 添加代码块之前的普通文本
    if (match.index > lastIndex) {
      const textContent = text.substring(lastIndex, match.index)
      if (textContent.trim()) {
        segments.push({
          type: 'text',
          content: textContent,
        })
      }
    }

    // 添加代码块
    const language = match[1] || 'text' // 如果没有指定语言，默认为 'text'
    const codeContent = match[2].trim() // 去除首尾空白

    segments.push({
      type: 'code',
      content: codeContent,
      language: language.toLowerCase(),
    })

    lastIndex = match.index + match[0].length
  }

  // 检查是否有未完成的代码块（流式输出中，代码块还没写完）
  const remainingText = text.substring(lastIndex)

  // 查找未完成的代码块：有 ```语言名\n 但没有对应的结束 ```，同样兼容 \n / \r\n
  const incompleteCodeBlockRegex = /```(\w+)?\r?\n([\s\S]*)$/
  const incompleteMatch = remainingText.match(incompleteCodeBlockRegex)

  if (incompleteMatch) {
    // 有未完成的代码块
    const beforeCodeBlock = remainingText.substring(0, incompleteMatch.index)
    if (beforeCodeBlock.trim()) {
      segments.push({
        type: 'text',
        content: beforeCodeBlock,
      })
    }

    // 添加未完成的代码块（实时显示正在生成的代码）
    const language = incompleteMatch[1] || 'text'
    const codeContent = incompleteMatch[2] // 不 trim，保留实时生成的内容

    segments.push({
      type: 'code',
      content: codeContent,
      language: language.toLowerCase(),
    })
  } else {
    // 没有未完成的代码块，添加剩余的普通文本
    if (remainingText.trim()) {
      segments.push({
        type: 'text',
        content: remainingText,
      })
    }
  }

  // 兜底优化：如果最后一段是短小的“代码收尾”（如 </script></body></html>），
  // 且前一段已经是代码块，则把这部分直接拼接进前一个代码块，避免掉到白底文本区域。
  if (segments.length >= 2) {
    const last = segments[segments.length - 1]
    const prev = segments[segments.length - 2]
    if (prev.type === 'code' && last.type === 'text' && isCodeTail(last.content)) {
      const tail = last.content.trimEnd()
      prev.content = `${prev.content}\n${tail}`
      segments.pop()
    }
  }

  // 如果没有匹配到任何代码块，返回整个文本作为普通文本
  if (segments.length === 0) {
    segments.push({
      type: 'text',
      content: text,
    })
  }

  return segments
}

/**
 * 判断一段文本是否更像“代码结尾”而不是普通说明文本：
 * - 行数不多（避免把整段说明都当作代码）
 * - 不包含明显中文句子
 * - 大部分非空行以 ; / } / >/标签结尾，或以 </xxx> / // 开头
 */
function isCodeTail(raw: string): boolean {
  if (!raw) return false
  const text = raw.trim()
  if (!text) return false

  const lines = text.split(/\r?\n/)
  if (lines.length > 10) return false

  // 含有较多中文字符时，倾向认为是自然语言说明
  const chineseMatch = text.match(/[\u4e00-\u9fa5]/g)
  if (chineseMatch && chineseMatch.length > 10) {
    return false
  }

  let codeLikeLines = 0
  let nonEmptyLines = 0

  for (const line of lines) {
    const trimmed = line.trim()
    if (!trimmed) continue
    nonEmptyLines += 1

    const startsWithComment = trimmed.startsWith('//') || trimmed.startsWith('/*') || trimmed.startsWith('*')
    const startsWithClosingTag = trimmed.startsWith('</')
    const endsWithCodePunct = /[;{}>\]]$/.test(trimmed)

    if (startsWithComment || startsWithClosingTag || endsWithCodePunct) {
      codeLikeLines += 1
    }
  }

  if (nonEmptyLines === 0) return false

  // 至少一半以上的非空行具备“代码样子”
  return codeLikeLines / nonEmptyLines >= 0.6
}
