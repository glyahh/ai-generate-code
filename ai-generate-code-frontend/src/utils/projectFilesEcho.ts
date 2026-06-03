import { chatHistoryOpenApiExportAppIdUsingGet } from '@/api/chatHistoryController'
import type { ChatHistoryVO } from '@/api'
import {
  extractToolDeleteFilePaths,
  extractToolModifyFileNewContentBlocksFromText,
  extractToolWriteFileBlocksFromText,
} from '@/utils/toolOutputAdapters/toolOutputBlockParsers'
import { CodeGenTypeEnum } from '@/utils/CodeGenTypeEnum'

// 对齐后端 ConversationMemoryConstant.SNAPSHOT_IGNORE_DIRS
const SNAPSHOT_IGNORE_DIRS: ReadonlySet<string> = new Set([
  'node_modules',
  '.git',
  'dist',
  'target',
  'temp',
  'build',
  'coverage',
  '.idea',
  '.vscode',
])

// 对齐后端 ConversationMemoryConstant.TEXT_FILE_EXTS
const TEXT_FILE_EXTS: ReadonlySet<string> = new Set([
  'java', 'kt', 'js', 'ts', 'tsx', 'jsx', 'vue', 'html', 'htm',
  'css', 'scss', 'less', 'json', 'yaml', 'yml', 'xml', 'md',
  'txt', 'properties', 'sql', 'sh', 'bat', 'ps1',
])

/** 规范化路径：统一 /、去 leading ./、去首尾空白 */
function normalizePath(p: string): string {
  return (p || '').trim().replace(/^\.\//, '').replace(/\\/g, '/')
}

/** 与 AppChatView.normalizeCodeGenType 一致：Vue → vue_project_{id} */
export function getStaticDeployKey(codeGenType: string, appId: string): string {
  const lower = (codeGenType || '').toLowerCase()
  if (lower === CodeGenTypeEnum.VUE_PROJECT) return `vue_project_${appId}`
  if (lower === CodeGenTypeEnum.HTML) return `html_${appId}`
  return `multi_file_${appId}`
}

/**
 * 根据后端忽略目录 + 文本扩展名白名单判断是否应纳入回显。
 * 路径若包含任一忽略目录段 → 排除；扩展名不在白名单 → 排除。
 */
export function shouldIncludeEchoPath(path: string): boolean {
  const segments = normalizePath(path).split('/')
  for (const seg of segments) {
    if (!seg) continue
    if (SNAPSHOT_IGNORE_DIRS.has(seg.toLowerCase())) return false
  }
  const lastSlash = path.lastIndexOf('/')
  const filename = lastSlash >= 0 ? path.slice(lastSlash + 1) : path
  const dotIdx = filename.lastIndexOf('.')
  if (dotIdx < 0 || dotIdx === filename.length - 1) return false
  const ext = filename.slice(dotIdx + 1).toLowerCase()
  return TEXT_FILE_EXTS.has(ext)
}

/**
 * 从全量导出历史消息中收集所有文件路径（写入/修改 → 入集；删除 → 出集）。
 */
export function collectFilePathsFromHistoryMessages(
  msgs: ChatHistoryVO[],
): Set<string> {
  const paths = new Set<string>()

  for (const r of msgs) {
    const msg = r.message ?? ''

    const deletes = extractToolDeleteFilePaths(msg)
    for (const p of deletes) {
      const np = normalizePath(p)
      if (np) paths.delete(np)
    }

    const writes = extractToolWriteFileBlocksFromText(msg)
    for (const w of writes) {
      const p = normalizePath(w.filePath)
      if (p) paths.add(p)
    }

    const modifies = extractToolModifyFileNewContentBlocksFromText(msg)
    for (const mf of modifies) {
      const p = normalizePath(mf.filePath)
      if (p) paths.add(p)
    }
  }

  return paths
}

export interface GeneratedFileItem {
  path: string
  language?: string
  content: string
  updatedAt: number
}

/**
 * 从静态资源接口拉取单个文件内容。404 返回 null（文件可能已被删除或未构建）。
 */
async function fetchProjectFileFromStatic(
  baseUrl: string,
  deployKey: string,
  path: string,
): Promise<string | null> {
  try {
    const url = `${baseUrl.replace(/\/$/, '')}/${deployKey}/${path}`
    const res = await fetch(url, { method: 'GET', credentials: 'include' })
    if (!res.ok) return null
    return await res.text()
  } catch {
    return null
  }
}

function inferLanguageFromPath(p: string): string {
  const lower = (p || '').toLowerCase()
  if (lower.endsWith('.vue')) return 'vue'
  if (lower.endsWith('.ts') || lower.endsWith('.tsx')) return 'typescript'
  if (lower.endsWith('.js') || lower.endsWith('.jsx')) return 'javascript'
  if (lower.endsWith('.css') || lower.endsWith('.scss') || lower.endsWith('.less')) return 'css'
  if (lower.endsWith('.html')) return 'html'
  return 'text'
}

export interface LoadProjectFilesEchoOpts {
  /** 应用 ID */
  appId: string
  /** 代码生成类型（如 vue_project / html / multi_file） */
  codeGenType: string
  /** 静态资源 base URL（默认 /api/static） */
  previewBaseUrl?: string
  /** 并发数上限（默认 8） */
  concurrency?: number
  /** 当前流式阶段已有的 generatedFileMap，用于合并（生成中尚未落盘时兜底） */
  existingMap?: Record<string, GeneratedFileItem>
}

/**
 * 全量刷新项目文件回显：导出历史 → 收集路径 → 并发拉取静态资源 → GeneratedFileItem[]。
 *
 * 流程：
 * 1. 调用 `GET /chatHistory/export/{appId}` 获取全量历史消息
 * 2. 解析所有写入/修改/删除工具调用，收集最终路径集合
 * 3. 合并 existingMap（兜底流式阶段尚未落盘的文件）
 * 4. 有限并发从静态资源接口拉取文件内容
 * 5. 过滤掉拉取失败（404 等）的路径
 */
export async function loadProjectFilesEchoFromDisk(
  opts: LoadProjectFilesEchoOpts,
): Promise<GeneratedFileItem[]> {
  const {
    appId,
    codeGenType,
    previewBaseUrl = '/api/static',
    concurrency = 8,
    existingMap = {},
  } = opts

  const deployKey = getStaticDeployKey(codeGenType, appId)
  const now = Date.now()

  // 1. 全量导出历史
  let historyPaths: Set<string> = new Set()
  try {
    const res = await chatHistoryOpenApiExportAppIdUsingGet({
      params: { appId: appId as unknown as number },
    })
    const data = res.data?.data as ChatHistoryVO[] | undefined
    if (data && data.length > 0) {
      historyPaths = collectFilePathsFromHistoryMessages(data)
    }
  } catch {
    // 导出失败时退化为仅使用 existingMap 中的路径
  }

  // 2. 合并 existingMap 中的路径（兜底流式阶段尚未落盘）
  for (const p of Object.keys(existingMap)) {
    const np = normalizePath(p)
    if (np) historyPaths.add(np)
  }

  // 3. 过滤忽略路径
  const filteredPaths = Array.from(historyPaths).filter(shouldIncludeEchoPath)

  if (filteredPaths.length === 0) {
    // 无有效路径时回退到 existingMap 的值（首轮生成中）
    return Object.values(existingMap).sort((a, b) => a.path.localeCompare(b.path))
  }

  // 4. 有限并发拉取静态内容
  const results: GeneratedFileItem[] = []
  const base = previewBaseUrl.replace(/\/$/, '')

  for (let i = 0; i < filteredPaths.length; i += concurrency) {
    const batch = filteredPaths.slice(i, i + concurrency)
    const batchResults = await Promise.all(
      batch.map(async (filePath): Promise<GeneratedFileItem | null> => {
        const content = await fetchProjectFileFromStatic(base, deployKey, filePath)
        if (content === null) {
          // 静态资源不存在：使用 existingMap 的值兜底
          const es = existingMap[filePath]
          if (es) {
            return { ...es, updatedAt: now }
          }
          return null
        }
        return {
          path: filePath,
          language: inferLanguageFromPath(filePath),
          content,
          updatedAt: now,
        }
      }),
    )
    for (const r of batchResults) {
      if (r) results.push(r)
    }
  }

  results.sort((a, b) => a.path.localeCompare(b.path))
  return results
}
