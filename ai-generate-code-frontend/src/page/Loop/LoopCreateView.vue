<template>
  <div class="loop-create">
    <a-row :gutter="24">
      <!-- 左侧：5 步流程图 + 导入区 -->
      <a-col :xs="24" :md="8" class="loop-left-col">
        <div class="flow-diagram">
          <div
            v-for="(step, idx) in templateSteps"
            :key="step.key"
            class="flow-node"
            :class="{ active: currentStep === idx }"
            @click="currentStep = idx"
          >
            <div class="flow-node-index">{{ idx + 1 }}</div>
            <div class="flow-node-label">{{ step.label }}</div>
            <div v-if="idx < templateSteps.length - 1" class="flow-connector" />
          </div>
        </div>

        <!-- 导入区 -->
        <a-divider class="import-divider">导入已有内容</a-divider>
        <div class="import-area">
          <a-textarea
            v-model:value="importRaw"
            :rows="6"
            placeholder="粘贴以下格式自动解析&#10;&#10;---&#10;name: 我的技能&#10;description: 一个示例技能&#10;visibility: public&#10;---&#10;## 角色设定&#10;你是一个XX专家&#10;&#10;## 约束与边界&#10;- 输出简洁&#10;&#10;## 执行步骤&#10;1. 分析需求&#10;2. 生成代码"
            class="import-textarea"
          />
          <a-button type="dashed" block class="import-btn" @click="parseImport">
            <template #icon>
              <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                <path d="M7 1v8M3 6l4 4 4-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
                <path d="M1 10v2a1 1 0 001 1h10a1 1 0 001-1v-2" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
              </svg>
            </template>
            解析导入
          </a-button>
        </div>
      </a-col>

      <!-- 右侧：编辑区 -->
      <a-col :xs="24" :md="16" class="loop-right-col">
        <div class="edit-header">
          <h2>{{ isEditMode ? '编辑 Loop' : '创建 Loop' }}</h2>
          <span class="edit-step-hint">第 {{ currentStep + 1 }} 步：{{ currentStepLabel }}</span>
        </div>

        <a-form layout="vertical" class="loop-form">
          <a-row :gutter="16">
            <a-col :span="12">
              <a-form-item label="Loop 名称" required>
                <a-input
                  v-model:value="form.loopName"
                  maxlength="128"
                  placeholder="给技能起个名字"
                  show-count
                />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="可见性">
                <a-radio-group v-model:value="form.visibility">
                  <a-radio value="public">公开</a-radio>
                  <a-radio value="private">私有</a-radio>
                </a-radio-group>
              </a-form-item>
            </a-col>
          </a-row>

          <a-form-item label="简介">
            <a-textarea
              v-model:value="form.description"
              :rows="2"
              maxlength="512"
              placeholder="简短的技能说明"
              show-count
            />
          </a-form-item>

          <a-divider class="step-divider" />

          <!-- 当前步骤编辑区 -->
          <div
            v-for="(step, idx) in form.steps"
            :key="step.key"
          >
            <a-form-item
              v-if="idx === currentStep"
              :label="`${step.label}`"
              class="step-content-item"
            >
              <a-textarea
                v-model:value="step.content"
                :rows="8"
                :placeholder="step.placeholder || ''"
              />
              <div class="step-tip">
                此步骤内容在编译时
                <template v-if="step.content.trim()">将</template>
                <template v-else>不</template>
                会被注入到系统提示中
              </div>
            </a-form-item>
          </div>

          <!-- 步骤预览（小提示） -->
          <div class="steps-preview">
            <span
              v-for="(step, idx) in form.steps"
              :key="step.key"
              class="step-dot"
              :class="{ filled: step.content.trim(), active: idx === currentStep }"
              :title="step.label + (step.content.trim() ? ' (已填写)' : ' (空')"
              @click="currentStep = idx"
            >
              {{ idx + 1 }}
            </span>
          </div>

          <a-form-item class="form-actions">
            <a-space>
              <a-button type="primary" @click="handleSave" :loading="saving">
                保存
              </a-button>
              <a-button @click="handleCancel">取消</a-button>
            </a-space>
          </a-form-item>
        </a-form>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { message } from 'ant-design-vue'

const props = defineProps<{
  editId?: string
}>()

const router = useRouter()
const route = useRoute()

const resolvedEditId = computed(() => props.editId ?? (route.params.id as string) ?? undefined)
const isEditMode = computed(() => !!resolvedEditId.value)

const saving = ref(false)
const currentStep = ref(0)
const importRaw = ref('')

const currentStepLabel = computed(() => templateSteps[currentStep.value]?.label ?? '')

const templateSteps = [
  { key: 'role', label: '角色设定', placeholder: '你扮演…\n例如：你是一个资深的 Vue 前端工程师' },
  { key: 'context', label: '背景上下文', placeholder: '补充背景信息…\n例如：用户需要生成一个响应式管理后台' },
  { key: 'constraints', label: '约束与边界', placeholder: '行为限制、输出规范…\n例如：- 使用 Vue 3 + TypeScript\n- 响应式布局\n- 输出简洁' },
  { key: 'workflow', label: '执行步骤', placeholder: '1. 分析需求\n2. 设计数据流\n3. 生成核心代码\n4. 优化与打包' },
  { key: 'output', label: '输出格式', placeholder: '返回 Markdown 格式结果\n或：生成可直接运行的 HTML 文件' },
]

const form = reactive<{
  loopName: string
  description: string
  visibility: string
  steps: { key: string; label: string; content: string; placeholder: string }[]
}>({
  loopName: '',
  description: '',
  visibility: 'private',
  steps: templateSteps.map((s) => ({
    key: s.key,
    label: s.label,
    content: '',
    placeholder: s.placeholder,
  })),
})

// 解析导入：frontmatter → 名称/简介/可见性，body 按 ## 标题拆分映射到 steps
const parseImport = () => {
  const raw = importRaw.value.trim()
  if (!raw) {
    message.warning('请先粘贴要导入的内容')
    return
  }
  try {
    let name = '未命名技能'
    let desc = ''
    let vis = 'private'
    let body = raw

    if (raw.startsWith('---')) {
      const endIdx = raw.indexOf('---', 3)
      if (endIdx > 0) {
        const front = raw.substring(3, endIdx).trim()
        front.split('\n').forEach((line) => {
          const trimmed = line.trim()
          if (trimmed.startsWith('name:')) name = trimmed.substring(5).trim()
          else if (trimmed.startsWith('description:')) desc = trimmed.substring(12).trim()
          else if (trimmed.startsWith('visibility:')) vis = trimmed.substring(11).trim()
        })
        body = raw.substring(endIdx + 3).trim()
      }
    }

    form.loopName = name
    form.description = desc
    form.visibility = vis

    // 按 ## 标题拆分 body 填入对应步骤
    const lines = body.split('\n')
    let currentKey = ''
    const contentMap: Record<string, string> = {}
    for (const line of lines) {
      const h2Match = line.match(/^##\s+(.+)/)
      if (h2Match) {
        const title = h2Match[1]?.trim() ?? ''
        currentKey = mapLabelToKey(title)
        if (!contentMap[currentKey]) contentMap[currentKey] = ''
      } else if (currentKey) {
        contentMap[currentKey] = (contentMap[currentKey] || '') + line + '\n'
      }
    }

    form.steps.forEach((s) => {
      const stepContent = contentMap[s.key]
      if (stepContent) s.content = stepContent.trim()
    })

    message.success('解析成功，请确认并保存')
  } catch (e) {
    console.error('解析失败', e)
    message.error('解析失败，请检查格式是否正确')
  }
}

// 将 ## 标题标签映射到 step key
const mapLabelToKey = (label: string): string => {
  if (label.includes('角色')) return 'role'
  if (label.includes('背景') || label.includes('上下文')) return 'context'
  if (label.includes('约束') || label.includes('边界')) return 'constraints'
  if (label.includes('步骤') || label.includes('执行') || label.includes('工作流')) return 'workflow'
  if (label.includes('输出') || label.includes('格式')) return 'output'
  return 'role'
}

// 编译 workflowJson（仅非空步骤）
const buildWorkflowJson = () => {
  const steps = form.steps
    .filter((s) => s.content.trim())
    .map((s) => ({
      key: s.key,
      label: s.label,
      content: s.content.trim(),
    }))
  return {
    templateId: 'standard_v1',
    steps,
  }
}

// 保存
const handleSave = async () => {
  if (!form.loopName.trim()) {
    message.warning('请输入 Loop 名称')
    return
  }

  saving.value = true
  try {
    const payload = {
      loopName: form.loopName.trim(),
      description: form.description.trim(),
      visibility: form.visibility,
      workflowJson: JSON.stringify(buildWorkflowJson()),
    }

    // TODO: openapi2ts 生成后替换为实际 API 调用
    if (isEditMode.value) {
      // const res = await loopController.updateUsingPost({
      //   id: resolvedEditId.value,
      //   ...payload,
      // })
      console.log('更新 Loop', resolvedEditId.value, payload)
      message.success('更新成功')
    } else {
      // const res = await loopController.addUsingPost(payload)
      console.log('创建 Loop', payload)
      message.success('创建成功')
    }
    router.push('/user/loops')
  } catch (e) {
    console.error('保存失败', e)
    message.error('保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

// 取消
const handleCancel = () => {
  router.push('/user/loops')
}

// 编辑模式：加载已有 Loop 数据
onMounted(async () => {
  if (resolvedEditId.value) {
    try {
      // TODO: openapi2ts 生成后替换为实际 API 调用
      // const res = await loopController.getVoUsingGet({ id: resolvedEditId.value })
      // if (res.data?.code === 0 && res.data?.data) {
      //   const data = res.data.data
      //   form.loopName = data.loopName || ''
      //   form.description = data.description || ''
      //   form.visibility = data.visibility || 'private'
      //
      //   // 解析 workflowJson 回填 steps
      //   if (data.workflowJson) {
      //     try {
      //       const parsed = JSON.parse(data.workflowJson)
      //       if (parsed.steps && Array.isArray(parsed.steps)) {
      //         parsed.steps.forEach((step: any) => {
      //           const match = form.steps.find((s) => s.key === step.key)
      //           if (match) match.content = step.content || ''
      //         })
      //       }
      //     } catch (e) {
      //       console.warn('workflowJson 解析失败', e)
      //     }
      //   }
      // }
      console.log('编辑模式加载 ID:', resolvedEditId.value)
    } catch (e) {
      console.error('加载 Loop 失败', e)
      message.error('加载失败，请稍后重试')
    }
  }
})
</script>

<style scoped>
.loop-create {
  padding: 24px;
  max-width: 1200px;
  margin: 0 auto;
  min-height: calc(100vh - 140px);
}

/* ===== 左侧：流程图 ===== */
.loop-left-col {
  position: sticky;
  top: 80px;
  align-self: flex-start;
}

.flow-diagram {
  padding: 20px 16px;
  background: var(--bg-card, #ffffff);
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 12px;
}

.flow-node {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  cursor: pointer;
  border-radius: 8px;
  position: relative;
  transition: background var(--transition-duration, 0.2s) ease,
              border-color var(--transition-duration, 0.2s) ease;
  border: 1px solid transparent;
}

.flow-node:hover {
  background: var(--color-primary-light, #e6f4ff);
}

.flow-node.active {
  background: var(--color-primary-bg, #f0f5ff);
  border-color: var(--color-primary, #1677ff);
}

.flow-node-index {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--color-primary, #1677ff);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  font-size: 13px;
  flex-shrink: 0;
  transition: background var(--transition-duration, 0.2s) ease;
}

.flow-node.active .flow-node-index {
  background: var(--color-primary);
  box-shadow: 0 0 0 3px rgba(22, 119, 255, 0.2);
}

.flow-node-label {
  font-size: 14px;
  color: var(--text-base, #1f1f1f);
  font-weight: 500;
}

.flow-connector {
  width: 2px;
  height: 20px;
  background: var(--border-color, #e8e8e8);
  margin-left: 13px;
  flex-shrink: 0;
}

/* ===== 导入区 ===== */
.import-divider {
  margin: 20px 0 12px;
  font-size: 12px;
  color: var(--text-secondary, #666);
}

.import-area {
  margin-bottom: 8px;
}

.import-textarea {
  font-size: 12px;
  font-family: var(--font-family-base, inherit);
}

.import-btn {
  margin-top: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

/* ===== 右侧：编辑区 ===== */
.loop-right-col {
  min-height: 400px;
}

.edit-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border-color, #e8e8e8);
}

.edit-header h2 {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-base, #1f1f1f);
  margin: 0;
}

.edit-step-hint {
  font-size: 13px;
  color: var(--text-secondary, #666);
  background: var(--color-primary-bg, #f0f5ff);
  padding: 4px 12px;
  border-radius: 12px;
}

.loop-form {
  background: var(--bg-card, #ffffff);
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 12px;
  padding: 24px;
}

.step-divider {
  margin: 16px 0;
}

.step-content-item {
  margin-bottom: 12px;
}

.step-tip {
  font-size: 12px;
  color: var(--text-muted, #999);
  margin-top: 4px;
  line-height: 1.5;
}

/* 步骤指示点 */
.steps-preview {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  padding: 8px 0;
  justify-content: center;
}

.step-dot {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 500;
  cursor: pointer;
  background: var(--bg-mute, #f0f0f0);
  color: var(--text-secondary, #666);
  border: 1px solid var(--border-color, #e8e8e8);
  transition: all var(--transition-duration, 0.2s) ease;
}

.step-dot.filled {
  background: var(--color-primary, #1677ff);
  color: #fff;
  border-color: var(--color-primary, #1677ff);
}

.step-dot.active {
  box-shadow: 0 0 0 2px rgba(22, 119, 255, 0.3);
}

.step-dot:hover {
  transform: scale(1.15);
}

.form-actions {
  margin-bottom: 0;
  padding-top: 8px;
  border-top: 1px solid var(--border-color, #e8e8e8);
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .loop-create {
    padding: 16px;
  }

  .loop-left-col {
    position: static;
    margin-bottom: 16px;
  }

  .flow-diagram {
    display: flex;
    flex-wrap: wrap;
    gap: 0;
    padding: 16px;
    justify-content: center;
  }

  .flow-node {
    flex-direction: column;
    gap: 4px;
    padding: 8px;
    min-width: 60px;
    align-items: center;
  }

  .flow-connector {
    display: none;
  }

  .flow-node-label {
    font-size: 11px;
    text-align: center;
  }

  .loop-form {
    padding: 16px;
  }

  .edit-header {
    flex-direction: column;
    gap: 8px;
    align-items: flex-start;
  }
}

@media (max-width: 375px) {
  .loop-create {
    padding: 12px;
  }

  .flow-diagram {
    padding: 12px 8px;
  }

  .flow-node {
    min-width: 50px;
    padding: 6px 4px;
  }

  .flow-node-index {
    width: 24px;
    height: 24px;
    font-size: 11px;
  }

  .loop-form {
    padding: 12px;
  }
}
</style>
