/**
 * 工具执行卡片展示文案：剥离协议前缀、读目录路径友好化、底部 think 光影文案。
 */

export function stripToolCallPrefix(title: string): string {
  return title.replace(/^\[\s*工具调用\s*\]\s*/, '').trim()
}

/** 读目录：提取展示用目录名（无斜杠）；根目录返回 null */
function normalizeReadDirName(rawPath: string): string | null {
  const trimmed = rawPath.trim().replace(/\\/g, '/').replace(/\/+/g, '/').replace(/\/$/, '')
  if (!trimmed || trimmed === '.') return null
  const parts = trimmed.split('/').filter(Boolean)
  return parts[parts.length - 1] ?? null
}

/** 读目录头部手电右侧文案 */
export function formatReadDirHeaderPath(rawPath: string): string {
  return normalizeReadDirName(rawPath) ?? '整个文件'
}

/** simple 工具头部 targetPath */
export function getToolExecHeaderTargetPath(toolTitle: string, filePath: string): string {
  const action = stripToolCallPrefix(toolTitle)
  if (action === '读取目录结构') return formatReadDirHeaderPath(filePath)
  return filePath
}
