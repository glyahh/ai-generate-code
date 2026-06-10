import writeNormalIcon from '@/assets/tool-status/write-normal.png'
import writeBoostIcon from '@/assets/tool-status/write-boost.png'
import modifyThinkingIcon from '@/assets/tool-status/modify-thinking.png'
import modifyDoneIcon from '@/assets/tool-status/modify-done.png'

export const TOOL_WAIT_STATUS_DELAY_MS = 3_000
export const TOOL_WRITE_STATUS_TIER_2_MS = 15_000
export const TOOL_MODIFY_STATUS_TIER_2_MS = 7_000

const WRITE_FILE = '写入文件'
const MODIFY_FILE = '修改文件'

/**
 * 根据工具类型生成随机化的 tier2 阈值（毫秒），
 * 使多张工具卡片的第二段状态切换时刻错开。
 *
 * - 写入文件：基准 15000ms，±40% → [9000, 21000]
 * - 修改文件：基准 7000ms，±20%  → [5600, 8400]
 *
 * 在每张 tool_request segment 创建时调用一次，
 * 结果存入 segment，生命周期内不变。
 */
export function generateTier2Threshold(toolName: string): number {
  if (toolName === WRITE_FILE) {
    const base = TOOL_WRITE_STATUS_TIER_2_MS // 15000
    const range = base * 0.4 // 6000
    return Math.round(base - range + Math.random() * range * 2)
  }
  // MODIFY_FILE
  const base = TOOL_MODIFY_STATUS_TIER_2_MS // 7000
  const range = base * 0.2 // 1400
  return Math.round(base - range + Math.random() * range * 2)
}

export type ToolWaitStatusMotion = 'none' | 'write-jitter' | 'write-jitter-fast'

export type ToolWaitStatusView = {
  show: boolean
  text: string
  iconSrc: string
  motion: ToolWaitStatusMotion
}

export function isToolWaitStatusTool(toolName: string): boolean {
  return toolName === WRITE_FILE || toolName === MODIFY_FILE
}

/**
 * 根据耗时解析工具当前应显示的状态视图。
 *
 * @param tier2ThresholdOverride 可选覆盖 tier2 阈值（毫秒）；传入 undefined 时降级为全局默认值
 */
export function resolveToolWaitStatus(
  toolName: string,
  elapsedMs: number,
  tier2ThresholdOverride?: number,
): ToolWaitStatusView | null {
  if (!isToolWaitStatusTool(toolName)) return null
  if (elapsedMs < TOOL_WAIT_STATUS_DELAY_MS) {
    return { show: false, text: '', iconSrc: '', motion: 'none' }
  }

  if (toolName === WRITE_FILE) {
    const t2 = tier2ThresholdOverride ?? TOOL_WRITE_STATUS_TIER_2_MS
    if (elapsedMs < t2) {
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

  if (toolName === MODIFY_FILE) {
    const t2 = tier2ThresholdOverride ?? TOOL_MODIFY_STATUS_TIER_2_MS
    if (elapsedMs < t2) {
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

  return null
}
