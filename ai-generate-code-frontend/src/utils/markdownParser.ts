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

  // 如果没有匹配到任何代码块，返回整个文本作为普通文本
  if (segments.length === 0) {
    segments.push({
      type: 'text',
      content: text,
    })
  }

  return segments
}
