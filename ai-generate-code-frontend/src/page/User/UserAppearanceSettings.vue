<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ArrowLeftOutlined } from '@ant-design/icons-vue'
import { useAppearanceStore, CODE_FONT_OPTIONS, CODE_THEME_OPTIONS, DEFAULT_CODE_TYPE_OPTIONS } from '@/stores/appearance'

const router = useRouter()
const store = useAppearanceStore()

function goBack() {
  router.back()
}
</script>

<template>
  <div class="appearance-page">
    <!-- 顶部栏 -->
    <div class="page-header">
      <button class="back-btn" @click="goBack">
        <ArrowLeftOutlined />
        <span>返回</span>
      </button>
      <h1 class="page-title">外观设置</h1>
      <p class="page-desc">自定义界面颜色、字体、布局和生成偏好</p>
    </div>

    <!-- A. 基础颜色 -->
    <section class="appearance-section">
      <div class="section-header">基础颜色</div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">外观模式</span>
          <span class="row-desc">选择浅色、深色或跟随系统</span>
        </div>
        <div class="row-control">
          <a-select
            :value="store.colorMode"
            style="width: 140px"
            @change="(v: string) => store.colorMode = v as 'system' | 'light' | 'dark'"
          >
            <a-select-option value="system">跟随系统</a-select-option>
            <a-select-option value="light">浅色</a-select-option>
            <a-select-option value="dark">深色</a-select-option>
          </a-select>
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">主题强调色</span>
          <span class="row-desc">应用于按钮、链接和选中项</span>
        </div>
        <div class="row-control row-control--color">
          <a-color-picker
            :value="store.primaryColor"
            format="hex"
            :preset-colors="['#1677ff','#52c41a','#fa8c16','#eb2f96','#722ed1','#13c2c2','#f5222d','#faad14']"
            :show-text="false"
            @update:value="(v: any) => store.primaryColor = typeof v === 'string' ? v : '#1677ff'"
          />
          <a-button size="small" @click="store.primaryColor = store.DEFAULTS.primaryColor">重置</a-button>
        </div>
      </div>
    </section>

    <!-- B. 字体 -->
    <section class="appearance-section">
      <div class="section-header">字体</div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">界面字号</span>
          <span class="row-desc">{{ store.fontSize }}px — 控制全局正文字体大小</span>
        </div>
        <div class="row-control row-control--slider">
          <a-slider
            :min="12"
            :max="18"
            :step="1"
            :value="store.fontSize"
            style="width: 160px"
            @update:value="(v: number) => store.fontSize = v"
          />
          <span class="slider-value">{{ store.fontSize }}px</span>
          <a-button size="small" @click="store.fontSize = store.DEFAULTS.fontSize">重置</a-button>
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">代码字号</span>
          <span class="row-desc">{{ store.codeFontSize }}px — 代码块和终端字体大小</span>
        </div>
        <div class="row-control row-control--slider">
          <a-slider
            :min="11"
            :max="16"
            :step="1"
            :value="store.codeFontSize"
            style="width: 160px"
            @update:value="(v: number) => store.codeFontSize = v"
          />
          <span class="slider-value">{{ store.codeFontSize }}px</span>
          <a-button size="small" @click="store.codeFontSize = store.DEFAULTS.codeFontSize">重置</a-button>
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">代码字体</span>
          <span class="row-desc">代码块的等宽字体</span>
        </div>
        <div class="row-control">
          <a-select
            :value="store.codeFontFamily"
            style="width: 160px"
            @change="(v: string) => store.codeFontFamily = v"
          >
            <a-select-option
              v-for="opt in CODE_FONT_OPTIONS"
              :key="opt.value"
              :value="opt.value"
            >{{ opt.label }}</a-select-option>
          </a-select>
        </div>
      </div>
    </section>

    <!-- C. 界面密度 -->
    <section class="appearance-section">
      <div class="section-header">界面密度</div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">紧凑模式</span>
          <span class="row-desc">减小内边距和间距，显示更多内容</span>
        </div>
        <div class="row-control">
          <a-switch :checked="store.compactMode" @change="(v: boolean) => store.compactMode = v" />
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">减少动画</span>
          <span class="row-desc">关闭界面切换和内容加载的过渡动画</span>
        </div>
        <div class="row-control">
          <a-switch :checked="store.reducedMotion" @change="(v: boolean) => store.reducedMotion = v" />
        </div>
      </div>
    </section>

    <!-- D. 生成与聊天偏好 -->
    <section class="appearance-section">
      <div class="section-header">生成与聊天偏好</div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">默认代码类型</span>
          <span class="row-desc">创建新应用时的默认生成类型</span>
        </div>
        <div class="row-control">
          <a-select
            :value="store.defaultCodeType"
            style="width: 140px"
            @change="(v: string) => store.defaultCodeType = v as 'auto' | 'html' | 'multi_file' | 'vue_project'"
          >
            <a-select-option
              v-for="opt in DEFAULT_CODE_TYPE_OPTIONS"
              :key="opt.value"
              :value="opt.value"
            >{{ opt.label }}</a-select-option>
          </a-select>
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">启用工作流生成</span>
          <span class="row-desc">BETA — 逻辑更严谨、图片更贴切</span>
        </div>
        <div class="row-control">
          <a-switch :checked="store.workflowEnabled" @change="(v: boolean) => store.workflowEnabled = v" />
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">聊天默认生成模式</span>
          <span class="row-desc">新对话的生成方式</span>
        </div>
        <div class="row-control">
          <a-select
            :value="store.chatGenMode"
            style="width: 120px"
            @change="(v: string) => store.chatGenMode = v as 'legacy' | 'workflow'"
          >
            <a-select-option value="legacy">传统</a-select-option>
            <a-select-option value="workflow">工作流</a-select-option>
          </a-select>
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">代码块主题</span>
          <span class="row-desc">代码语法高亮的配色方案</span>
        </div>
        <div class="row-control">
          <a-select
            :value="store.codeTheme"
            style="width: 120px"
            @change="(v: string) => store.codeTheme = v as 'default' | 'high-contrast' | 'soft'"
          >
            <a-select-option
              v-for="opt in CODE_THEME_OPTIONS"
              :key="opt.value"
              :value="opt.value"
            >{{ opt.label }}</a-select-option>
          </a-select>
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">工具卡片默认折叠</span>
          <span class="row-desc">写入/修改文件卡片默认收起</span>
        </div>
        <div class="row-control">
          <a-switch :checked="store.toolCardCollapsed" @change="(v: boolean) => store.toolCardCollapsed = v" />
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">预览面板默认展开</span>
          <span class="row-desc">打开对话时显示 iframe 预览</span>
        </div>
        <div class="row-control">
          <a-switch :checked="store.previewExpanded" @change="(v: boolean) => store.previewExpanded = v" />
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">流式输出平滑滚动</span>
          <span class="row-desc">新内容自动滚动到底部</span>
        </div>
        <div class="row-control">
          <a-switch :checked="store.smoothScroll" @change="(v: boolean) => store.smoothScroll = v" />
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.appearance-page {
  max-width: 720px;
  margin: 0 auto;
  padding: 24px;
}

/* 顶部栏 */
.page-header {
  margin-bottom: 24px;
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 0;
  border: none;
  background: none;
  color: var(--color-primary, #1677ff);
  cursor: pointer;
  font-size: 14px;
  margin-bottom: 12px;
}

.back-btn:hover {
  opacity: 0.8;
}

.page-title {
  font-size: 24px;
  font-weight: 700;
  margin: 0 0 4px;
  color: var(--text-base, #1f1f1f);
}

.page-desc {
  margin: 0;
  color: var(--text-secondary, #666);
  font-size: 14px;
}

/* 分组卡片 */
.appearance-section {
  background: var(--bg-card, #fff);
  border-radius: var(--radius-lg, 12px);
  box-shadow: var(--shadow-card, 0 2px 8px rgba(0,0,0,0.06));
  overflow: hidden;
  margin-bottom: 16px;
}

.section-header {
  padding: 10px 16px;
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--text-secondary, #666);
  background: var(--bg-section-header, #f0f5ff);
  border-bottom: 1px solid var(--border-color, #e8e8e8);
}

/* 设置行 */
.appearance-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-color, #e8e8e8);
}

.appearance-row:last-child {
  border-bottom: none;
}

.row-label {
  flex: 1;
  min-width: 0;
  padding-right: 16px;
}

.row-title {
  display: block;
  font-size: 14px;
  font-weight: 500;
  color: var(--text-base, #1f1f1f);
  margin-bottom: 2px;
}

.row-desc {
  display: block;
  font-size: 12px;
  color: var(--text-secondary, #666);
  line-height: 1.4;
}

.row-control {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.row-control--slider {
  min-width: 220px;
}

.row-control--color {
  gap: 6px;
}

.slider-value {
  min-width: 32px;
  font-size: 13px;
  color: var(--text-secondary, #666);
  text-align: center;
}

/* 响应式 */
@media (max-width: 576px) {
  .appearance-page {
    padding: 16px;
  }

  .appearance-row {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }

  .row-label {
    padding-right: 0;
  }

  .row-control {
    width: 100%;
  }

  .row-control--slider {
    min-width: unset;
    width: 100%;
  }
}
</style>
