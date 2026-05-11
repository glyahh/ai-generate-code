<script setup lang="ts">
import BasicLayout from '@/layouts/BasicLayout.vue'
import { RouterView } from 'vue-router'
import { computed } from 'vue'
import { useRoute } from 'vue-router'

import { testUsingGet } from '@/api/test.ts'
import { UserLoginStore } from './stores/UserLogin'

testUsingGet({}).then((res) => {
  console.log(res)
})

const userLoginStore = UserLoginStore();
userLoginStore.fetchLoginUser();

const route = useRoute()
const noLayout = computed(() => Boolean(route.meta?.noLayout))
</script>

<template>
  <RouterView v-if="noLayout" />
  <BasicLayout v-else>
    <RouterView />
  </BasicLayout>
</template>
