import { defineStore } from 'pinia'
import { reactive, toRefs, watch } from 'vue'
import { theme } from 'ant-design-vue'

/** localStorage key */
const STORAGE_KEY = 'glyahh:appearance:v1'

/** 代码字体族预设 */
export const CODE_FONT_OPTIONS = [
  { label: '系统默认', value: 'system', css: 'Consolas, Monaco, "Courier New", monospace' },
  { label: 'JetBrains Mono', value: 'jetbrains-mono', css: '"JetBrains Mono", Consolas, monospace' },
  { label: 'Fira Code', value: 'fira-code', css: '"Fira Code", Consolas, monospace' },
  { label: 'Consolas', value: 'consolas', css: 'Consolas, Monaco, monospace' },
] as const

/** 代码块主题 */
export const CODE_THEME_OPTIONS = [
  { label: '默认', value: 'default' },
  { label: '高对比', value: 'high-contrast' },
  { label: '柔和', value: 'soft' },
] as const

/** 默认代码类型 */
export const DEFAULT_CODE_TYPE_OPTIONS = [
  { label: '自动识别', value: 'auto' },
  { label: 'HTML', value: 'html' },
  { label: '多文件', value: 'multi_file' },
  { label: 'Vue 项目', value: 'vue_project' },
] as const

/** 外观设置状态接口 */
export interface AppearanceSettings {
  // A. 基础颜色
  colorMode: 'system' | 'light' | 'dark'
  primaryColor: string

  // B. 字体
  fontSize: number
  codeFontSize: number
  codeFontFamily: string

  // C. 界面密度
  compactMode: boolean
  reducedMotion: boolean

  // D. 本项目专属
  defaultCodeType: 'auto' | 'html' | 'multi_file' | 'vue_project'
  workflowEnabled: boolean
  chatGenMode: 'legacy' | 'workflow'
  codeTheme: 'default' | 'high-contrast' | 'soft'
  toolCardCollapsed: boolean
  previewExpanded: boolean
  smoothScroll: boolean
}

/** 按分组索引的 key 列表，用于 resetSection */
export const SECTION_KEYS: Record<string, Array<keyof AppearanceSettings>> = {
  colors: ['colorMode', 'primaryColor'],
  fonts: ['fontSize', 'codeFontSize', 'codeFontFamily'],
  density: ['compactMode', 'reducedMotion'],
  preferences: ['defaultCodeType', 'workflowEnabled', 'chatGenMode', 'codeTheme', 'toolCardCollapsed', 'previewExpanded', 'smoothScroll'],
}

/** 默认值：对齐项目现有代码的初值 */
const DEFAULTS: AppearanceSettings = {
  colorMode: 'system',
  primaryColor: '#1677ff',
  fontSize: 15,
  codeFontSize: 13,
  codeFontFamily: 'system',
  compactMode: false,
  reducedMotion: false,
  defaultCodeType: 'auto',
  workflowEnabled: false,
  chatGenMode: 'legacy',
  codeTheme: 'default',
  toolCardCollapsed: true,
  previewExpanded: true,
  smoothScroll: true,
}

/** 从 localStorage 加载，缺失字段合并默认值 */
function loadFromStorage(): AppearanceSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { ...DEFAULTS }
    const parsed = JSON.parse(raw)
    // 合并确保新增字段有默认值（v1→v2 兼容）
    return { ...DEFAULTS, ...parsed }
  } catch {
    return { ...DEFAULTS }
  }
}

/** 持久化到 localStorage */
function saveToStorage(state: AppearanceSettings): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
  } catch (e) {
    console.warn('[appearance] 写入 localStorage 失败', e)
  }
}

/**
 * 将设置应用到 DOM
 * - data-theme / data-compact / data-code-theme 属性
 * - CSS 自定义属性（字号、字体族、强调色）
 * - 跟随系统时注册 matchMedia 监听
 */
let mediaQueryList: MediaQueryList | null = null
let mediaChangeHandler: (() => void) | null = null

export function applyToDocument(settings: AppearanceSettings): void {
  const root = document.documentElement

  // 1. 主题模式
  if (settings.colorMode === 'system') {
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
    root.dataset.theme = prefersDark ? 'dark' : 'light'
    if (!mediaQueryList) {
      mediaQueryList = window.matchMedia('(prefers-color-scheme: dark)')
      mediaChangeHandler = () => {
        const raw = localStorage.getItem(STORAGE_KEY)
        if (raw) {
          try {
            const saved = JSON.parse(raw)
            if (saved.colorMode === 'system') {
              root.dataset.theme = mediaQueryList!.matches ? 'dark' : 'light'
            }
          } catch { /* ignore */ }
        }
      }
      mediaQueryList.addEventListener('change', mediaChangeHandler)
    }
  } else {
    root.dataset.theme = settings.colorMode
    if (mediaQueryList && mediaChangeHandler) {
      mediaQueryList.removeEventListener('change', mediaChangeHandler)
      mediaQueryList = null
      mediaChangeHandler = null
    }
  }

  // 2. 紧凑模式
  root.dataset.compact = settings.compactMode ? 'true' : 'false'

  // 3. 代码块主题
  root.dataset.codeTheme = settings.codeTheme

  // 4. CSS 变量
  root.style.setProperty('--font-size-base', `${settings.fontSize}px`)
  root.style.setProperty('--code-font-size', `${settings.codeFontSize}px`)
  const fontCss = CODE_FONT_OPTIONS.find(f => f.value === settings.codeFontFamily)?.css ?? CODE_FONT_OPTIONS[0].css
  root.style.setProperty('--code-font-family', fontCss)
  root.style.setProperty('--color-primary', settings.primaryColor)

  // 5. 动画减弱
  if (settings.reducedMotion) {
    root.style.setProperty('--transition-duration', '0s')
  } else {
    root.style.removeProperty('--transition-duration')
  }
}

/** 根据当前设置生成 Ant Design ConfigProvider theme 对象 */
export function resolveThemeConfig(settings: { colorMode: string; primaryColor: string }): { token: Record<string, any>; algorithm: any } {
  const isDark = settings.colorMode === 'dark' ||
    (settings.colorMode === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)
  return {
    token: { colorPrimary: settings.primaryColor },
    algorithm: isDark ? theme.darkAlgorithm : theme.defaultAlgorithm,
  }
}

export const useAppearanceStore = defineStore('appearance', () => {
  const saved = loadFromStorage()

  const state = reactive<AppearanceSettings>({ ...saved })

  /** 持久化到 localStorage 并应用到 DOM */
  function persistAndApply() {
    saveToStorage(state)
    applyToDocument(state)
  }

  /** 重置某个分组的全部字段到默认值 */
  function resetSection(section: keyof typeof SECTION_KEYS) {
    const keys = SECTION_KEYS[section]
    if (!keys) return
    keys.forEach((k) => {
      ;(state as any)[k] = DEFAULTS[k]
    })
    persistAndApply()
  }

  /** 启动时加载一次 */
  function init() {
    persistAndApply()
  }

  // 自动 watch：任何 state 变化 → 持久化 + 应用
  watch(
    () => ({ ...state }),
    () => persistAndApply(),
    { deep: true },
  )

  return {
    ...toRefs(state),
    state,
    init,
    resetSection,
    DEFAULTS,
  }
})
