import writeNormalIcon from '@/assets/tool-status/write-normal.png'
import writeBoostIcon from '@/assets/tool-status/write-boost.png'
import modifyThinkingIcon from '@/assets/tool-status/modify-thinking.png'
import modifyDoneIcon from '@/assets/tool-status/modify-done.png'

export const TOOL_WAIT_STATUS_DELAY_MS = 3_000
export const TOOL_WRITE_STATUS_TIER_2_MS = 15_000
export const TOOL_MODIFY_STATUS_TIER_2_MS = 7_000

export type ToolWaitStatusMotion = 'none' | 'write-jitter' | 'write-jitter-fast'

export type ToolWaitStatusView = {
  show: boolean
  text: string
  iconSrc: string
  motion: ToolWaitStatusMotion
}

const WRITE_FILE = '写入文件'
const MODIFY_FILE = '修改文件'

export function isToolWaitStatusTool(toolName: string): boolean {
  return toolName === WRITE_FILE || toolName === MODIFY_FILE
}

export function resolveToolWaitStatus(
  toolName: string,
  elapsedMs: number,
): ToolWaitStatusView | null {
  if (!isToolWaitStatusTool(toolName)) return null
  if (elapsedMs < TOOL_WAIT_STATUS_DELAY_MS) {
    return { show: false, text: '', iconSrc: '', motion: 'none' }
  }

  if (toolName === WRITE_FILE) {
    if (elapsedMs < TOOL_WRITE_STATUS_TIER_2_MS) {
      return {
        show: true,
        text: '正在写文件中',
        iconSrc: writeNormalIcon,
        motion: 'write-jitter',
      }
    }
    return {
      show: true,
      text: '文件有点大 已增加算力',
      iconSrc: writeBoostIcon,
      motion: 'write-jitter-fast',
    }
  }

  if (elapsedMs < TOOL_MODIFY_STATUS_TIER_2_MS) {
    return {
      show: true,
      text: '正在思考修改区域',
      iconSrc: modifyThinkingIcon,
      motion: 'none',
    }
  }
  return {
    show: true,
    text: '修改即将完成',
    iconSrc: modifyDoneIcon,
    motion: 'none',
  }
}
