<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  Modal as AModal,
  Input as AInput,
  Button as AButton,
  message,
} from 'ant-design-vue'
import { appAddUsingPost } from '@/api/appController'
import { UserLoginStore } from '@/stores/UserLogin'

const router = useRouter()
const userLoginStore = UserLoginStore()

const visible = ref(true)
const creating = ref(false)
const prompt = ref('')

const isLogin = computed(() => !!userLoginStore.userLogin?.id)

function buildAppNameFromPrompt(text: string): string {
  const trimmed = text.trim()
  if (!trimmed) return '我的应用'
  const firstLine = (trimmed.split('\n')[0] || trimmed).trim()
  return firstLine.slice(0, 18) || '我的应用'
}

async function handleCreate() {
  const content = prompt.value.trim()
  if (!content) {
    message.warning('请先输入一句话描述你要生成的应用')
    return
  }

  if (!isLogin.value) {
    message.warning('请先登录后再创建应用')
    const redirect = router.currentRoute.value.fullPath || '/code/generate'
    router.push({
      path: '/user/login',
      query: { redirect },
    })
    return
  }

  creating.value = true
  try {
    const res = await appAddUsingPost({
      body: {
        appName: buildAppNameFromPrompt(content),
        initPrompt: content,
        codeGenType: 'multi_file',
      },
    })
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
      const appId = res.data.data
      message.success('应用创建成功，正在进入生成页面')
      visible.value = false
      router.push({
        name: 'app-chat',
        params: { id: appId },
        query: { autoSend: 'true' },
      })
    } else {
      message.error(res.data.message || '创建应用失败')
    }
  } catch (e) {
    console.error(e)
    message.error('创建应用失败，请稍后重试')
  } finally {
    creating.value = false
  }
}

function handleCancel() {
  visible.value = false
  // 取消时回到首页，避免留在一个空白页面
  router.replace('/')
}
</script>

<template>
  <main class="code-generate-entry">
    <AModal v-model:open="visible" title="创建一个新的生成应用" :confirm-loading="creating" :mask-closable="false"
      :keyboard="false" ok-text="开始生成" cancel-text="先逛逛" @ok="handleCreate" @cancel="handleCancel">
      <div class="intro-text">
        描述你想生成的工具或网站，我们会为你创建一个应用并自动跳转到代码生成界面。
      </div>
      <AInput.TextArea v-model:value="prompt" :rows="4"
        placeholder="例如：帮我做一个简单记账工具，可以按分类统计支出" />
      <div class="hint-text">
        建议尽量具体一些，例如目标人群、主要功能、期望风格等，生成效果会更贴近你的想法。
      </div>
    </AModal>

    <!-- 占位容器：保证在布局中有合适的高度，即使弹窗关闭后也不会出现闪跳 -->
    <section class="placeholder-section">
      <div class="placeholder-card">
        <div class="placeholder-title">
          代码生成入口
        </div>
        <p class="placeholder-subtitle">
          弹窗关闭后，你可以通过顶部导航再次打开「代码生成」来创建新应用。
        </p>
        <AButton type="primary" shape="round" @click="visible = true">
          再次打开创建弹窗
        </AButton>
      </div>
    </section>
  </main>
</template>

<style scoped>
.code-generate-entry {
  min-height: calc(100vh - 64px - 48px);
  display: flex;
  align-items: stretch;
  justify-content: center;
}

.intro-text {
  margin-bottom: 12px;
  font-size: 13px;
  color: #6b7280;
}

.hint-text {
  margin-top: 8px;
  font-size: 12px;
  color: #9ca3af;
}

.placeholder-section {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 0;
}

.placeholder-card {
  max-width: 560px;
  width: 100%;
  padding: 24px 20px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.08);
  text-align: center;
}

.placeholder-title {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 6px;
}

.placeholder-subtitle {
  margin: 0 0 16px;
  font-size: 13px;
  color: #6b7280;
}

@media (max-width: 768px) {
  .code-generate-entry {
    min-height: calc(100vh - 56px - 48px);
  }

  .placeholder-card {
    margin: 0 12px;
    padding: 20px 16px;
  }
}
</style>

