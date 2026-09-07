<script setup lang="ts">
/** PageCard 页面卡片容器，统一标题、extra 操作区与 footer，loading 态经 n-spin 包裹 */
import { NCard, NSpin } from 'naive-ui'

const props = defineProps<{ title?: string; loading?: boolean }>()
const { title, loading } = props
</script>

<template>
  <n-card :title="title" :bordered="false" class="fms-page-card">
    <template v-if="$slots.extra" #header-extra>
      <slot name="extra" />
    </template>
    <n-spin :show="loading">
      <slot />
    </n-spin>
    <template v-if="$slots.footer" #footer>
      <slot name="footer" />
    </template>
  </n-card>
</template>

<style scoped>
.fms-page-card {
  height: 100%;
}
</style>
