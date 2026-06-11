<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { RouterView, useRoute } from 'vue-router'
// ConfigProvider 由 Ant Design Vue 全量注册（main.ts app.use(Antd)），模板中直接使用 <a-config-provider>
import BasicLayout from '@/layouts/BasicLayout.vue'
import { UserLoginStore } from './stores/UserLogin'
import { useAppearanceStore, resolveThemeConfig } from './stores/appearance'

import { testUsingGet } from '@/api/test.ts'

// 开发环境测试调用
testUsingGet({}).then((res) => {
  console.log(res)
})

// 登录态
const userLoginStore = UserLoginStore()
userLoginStore.fetchLoginUser()

// 外观设置
const appearanceStore = useAppearanceStore()
onMounted(() => {
  appearanceStore.init()
})

// ConfigProvider 主题配置：监听 store 变化动态计算
const configProviderTheme = computed(() => {
  const settings = {
    colorMode: appearanceStore.colorMode,
    primaryColor: appearanceStore.primaryColor,
  }
  return resolveThemeConfig(settings)
})

// 路由布局
const route = useRoute()
const noLayout = computed(() => Boolean(route.meta?.noLayout))
</script>

<template>
  <a-config-provider :theme="configProviderTheme">
    <RouterView v-if="noLayout" />
    <BasicLayout v-else>
      <RouterView />
    </BasicLayout>
  </a-config-provider>
</template>
