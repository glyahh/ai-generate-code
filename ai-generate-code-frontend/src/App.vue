<script setup lang="ts">
import BasicLayout from '@/layouts/BasicLayout.vue'
import { RouterView } from 'vue-router'

import { testUsingGet } from '@/api/test.ts'
import { UserLoginStore } from './stores/UserLogin'

testUsingGet({}).then((res) => {
  console.log(res)
})

const userLoginStore = UserLoginStore();
userLoginStore.fetchLoginUser();
</script>

<template>
  <BasicLayout>
    <RouterView />
  </BasicLayout>
</template>
