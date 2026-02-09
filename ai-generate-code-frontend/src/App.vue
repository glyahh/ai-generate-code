<script setup lang="ts">
import BasicLayout from '@/layouts/BasicLayout.vue'
import { RouterView } from 'vue-router'

import { testUsingGet } from '@/api/test.ts'

testUsingGet({}).then((res) => {
  console.log(res)
})
</script>

<template>
  <BasicLayout>
    <RouterView />
  </BasicLayout>
</template>
