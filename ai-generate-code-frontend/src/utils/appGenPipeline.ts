import type { AppVO } from '@/api'

/** 是否为 workflow beta 应用（与库表 app.is_beta=1 一致） */
export function isAppWorkflowBeta(isBeta?: number | null): boolean {
  return isBeta === 1
}

export function isAppWorkflowBetaFromApp(app?: Pick<AppVO, 'isBeta'> | null): boolean {
  return isAppWorkflowBeta(app?.isBeta)
}

/** 进入聊天页前同步 session 中的 genMode，与 is_beta 对齐 */
export function syncGenModeStorageForApp(appId: string | number, isBeta?: number | null): void {
  const mode = isAppWorkflowBeta(isBeta) ? 'workflow' : 'legacy'
  window.sessionStorage.setItem(`genMode:${String(appId)}`, mode)
}
