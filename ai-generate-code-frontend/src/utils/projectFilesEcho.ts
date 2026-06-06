import { CodeGenTypeEnum } from '@/utils/CodeGenTypeEnum'

/** 与 AppChatView.normalizeCodeGenType 一致：Vue → vue_project_{id} */
export function getStaticDeployKey(codeGenType: string, appId: string): string {
  const lower = (codeGenType || '').toLowerCase()
  if (lower === CodeGenTypeEnum.VUE_PROJECT) return `vue_project_${appId}`
  if (lower === CodeGenTypeEnum.HTML) return `html_${appId}`
  return `multi_file_${appId}`
}

export interface GeneratedFileItem {
  path: string
  language?: string
  content: string
  updatedAt: number
}

/** 后端返回的原始项目文件条目 */
interface ProjectFileRaw {
  path: string
  language?: string
  content: string
  updatedAt: number
}

export interface LoadProjectFilesEchoOpts {
  /** 应用 ID */
  appId: string
  /** API base URL（默认 /api） */
  apiBaseUrl?: string
}

/**
 * 从后端新接口获取项目文件快照，不再依赖聊天历史导出或工具输出解析。
 *
 * 调用 GET /api/app/static/project-files/{appId}，返回磁盘上当前存在的全部文本代码文件。
 * 接口失败时抛出异常，由调用方决定降级策略。
 */
export async function loadProjectFilesEchoFromDisk(
  opts: LoadProjectFilesEchoOpts,
): Promise<GeneratedFileItem[]> {
  const { appId, apiBaseUrl = '/api' } = opts

  const base = apiBaseUrl.replace(/\/$/, '')
  const url = `${base}/app/static/project-files/${encodeURIComponent(appId)}`

  const res = await fetch(url, {
    method: 'GET',
    credentials: 'include',
  })

  if (!res.ok) {
    throw new Error(`获取项目文件失败 (HTTP ${res.status})`)
  }

  const body: { code: number; data?: ProjectFileRaw[]; message?: string } = await res.json()

  if (body.code !== 0 && body.code !== 20000) {
    throw new Error(body.message || '获取项目文件失败')
  }

  const rawList: ProjectFileRaw[] = body.data ?? []

  const items: GeneratedFileItem[] = rawList.map((raw) => ({
    path: raw.path,
    language: raw.language,
    content: raw.content,
    updatedAt: raw.updatedAt,
  }))

  items.sort((a, b) => a.path.localeCompare(b.path))
  return items
}
