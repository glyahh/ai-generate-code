<script setup lang="ts">
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'

/** 菜单项配置：通过此数组配置导航菜单 */
const menuItems = [
  { key: 'home', path: '/', label: '首页' },
  { key: 'about', path: '/about', label: '关于' },
]

const menuItemsForMenu = computed(() =>
  menuItems.map((m) => ({ key: m.key, label: m.label }))
)

const router = useRouter()
const route = useRoute()

const selectedKeys = computed(() => {
  const path = route.path
  const item = menuItems.find((m) => m.path === path)
  return item ? [item.key] : []
})

function onMenuSelect({ key }: { key: string }) {
  const item = menuItems.find((m) => m.key === key)
  if (item) router.push(item.path)
}
</script>

<template>
  <div class="global-header">
    <div class="header-left">
      <router-link to="/" class="logo-wrap">
        <span class="site-title">Ai-generate-code</span>
      </router-link>
      <a-menu mode="horizontal" :selected-keys="selectedKeys" :items="menuItemsForMenu" class="header-menu"
        @select="onMenuSelect" />
    </div>
    <div class="header-right">
      <a-button type="primary">登录</a-button>
    </div>
  </div>
</template>

<style scoped>
.global-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 100%;
  padding: 0 24px;
  gap: 24px;
}

.header-left {
  display: flex;
  align-items: center;
  flex: 1;
  min-width: 0;
}

.logo-wrap {
  display: flex;
  align-items: center;
  text-decoration: none;
  color: inherit;
  margin-right: 32px;
  flex-shrink: 0;
}

.site-title {
  font-size: 18px;
  font-weight: 600;
  white-space: nowrap;
}

.header-menu {
  flex: 1;
  min-width: 0;
  border-bottom: none;
  line-height: 64px;
}

.header-right {
  flex-shrink: 0;
}

@media (max-width: 768px) {
  .global-header {
    padding: 0 16px;
    gap: 12px;
  }

  .logo-wrap {
    margin-right: 16px;
  }

  .site-title {
    font-size: 16px;
  }

  .header-menu {
    flex: none;
  }
}
</style>
