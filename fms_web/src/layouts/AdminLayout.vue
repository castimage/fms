<script setup lang="ts">
/**
 * 管理后台布局，复用主布局壳，Layout.vue 已按 isAdmin 渲染"系统管理"菜单，仅薄壳转发避免复制布局代码。
 */
import Layout from './Layout.vue'
</script>

<template>
  <Layout />
</template>
