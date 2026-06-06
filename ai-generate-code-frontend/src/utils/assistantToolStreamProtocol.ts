/**
 * 助手 SSE 工具协议：与后端 BaseTool.generateTool* 输出的中文标签对齐，供 AppChatView 流式分段解析。
 */

/** 与 AppChatView AssistantUiState.stage 一致 */
export type ToolExecStage =
  | 'markdown'
  | 'tool_exec_wait_fence'
  | 'tool_exec_stream'
  | 'tool_exec_modify_wait_before_fence'
  | 'tool_exec_modify_stream_before'
  | 'tool_exec_modify_wait_after_fence'
  | 'tool_exec_modify_stream_after'

/** 标签内允许额外空白，降低后端 fallback/编码导致的文案微漂移漏匹配概率 */
export const TOOL_REQUEST_ANY_RE = /\[\s*选择工具\s*\]\s*([^\r\n]+)/

export const TOOL_EXEC_HEADER_RE_WRITE_FILE =
  /\[\s*工具调用\s*\]\s*写入文件\s+([^\r\n]+)\s*(?:\r?\n|$)/
export const TOOL_EXEC_HEADER_RE_MODIFY_FILE =
  /\[\s*工具调用\s*\]\s*修改文件\s+([^\r\n]+)\s*(?:\r?\n|$)/
export const TOOL_EXEC_HEADER_RE_DELETE_FILE =
  /\[\s*工具调用\s*\]\s*删除文件\s+([^\r\n]+)\s*(?:\r?\n|$)/
export const TOOL_EXEC_HEADER_RE_READ_FILE =
  /\[\s*工具调用\s*\]\s*读取文件\s+([^\r\n]+)\s*(?:\r?\n|$)/
export const TOOL_EXEC_HEADER_RE_READ_DIR =
  /\[\s*工具调用\s*\]\s*读取目录结构\s+([^\r\n]+)\s*(?:\r?\n|$)/

/**
 * drainBufferToSegments 时是否处于「等待围栏、禁止落 markdown」阶段
 */
export function isToolExecWaitFenceStage(stage: ToolExecStage): boolean {
  return (
    stage === 'tool_exec_wait_fence' ||
    stage === 'tool_exec_modify_wait_before_fence' ||
    stage === 'tool_exec_modify_wait_after_fence'
  )
}
