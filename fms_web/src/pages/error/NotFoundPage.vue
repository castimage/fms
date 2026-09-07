<script setup lang="ts">
/**
 * 404 页，复用 LoginLayout 全屏壳
 */
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <n-result status="404" title="页面不存在" description="您访问的页面走丢了，或已被移除">
    <template #footer>
      <n-button type="primary" @click="router.push('/')">返回首页</n-button>
    </template>
  </n-result>
</template>
