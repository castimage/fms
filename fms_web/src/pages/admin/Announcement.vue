<script setup lang="ts">
/**
 * 公告广播，仅 ADMIN，路由 roles 已限定，标题 + 正文提交 POST /api/notify/announcements，
 * 后端向全部 ACTIVE 用户插 SYSTEM 通知并 ws 全量推送。响应为 Void，无条数统计，
 * 故成功提示不含 count，幂等键 = 同标题 + ref 不重复广播。
 */
import { ref } from 'vue'
import { NButton, NInput, useMessage } from 'naive-ui'
import { notifyApi } from '@/api/notify'

const message = useMessage()

const title = ref('')
const content = ref('')
const submitting = ref(false)

async function onSubmit() {
  if (!title.value.trim()) {
    message.warning('请输入公告标题')
    return
  }
  submitting.value = true
  try {
    await notifyApi.announce(title.value.trim(), content.value.trim())
    message.success('公告已广播')
    title.value = ''
    content.value = ''
  } catch {
    // 校验/权限错误已由 http 拦截器统一提示
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="announcement-page">
    <n-card title="公告广播" :bordered="false">
      <n-form label-placement="top">
        <n-form-item label="公告标题（1-128 字）">
          <n-input v-model:value="title" placeholder="请输入公告标题" :maxlength="128" show-count />
        </n-form-item>
        <n-form-item label="公告内容（≤4000 字，可空）">
          <n-input
            v-model:value="content"
            type="textarea"
            placeholder="请输入公告正文"
            :rows="8"
            :maxlength="4000"
            show-count
          />
        </n-form-item>
        <n-button type="primary" :loading="submitting" @click="onSubmit">广播公告</n-button>
      </n-form>
      <p class="hint">发送后全体 ACTIVE 用户将收到系统通知（WebSocket 实时推送）；仅 ADMIN 可操作。</p>
    </n-card>
  </div>
</template>

<style scoped>
.announcement-page {
  max-width: 720px;
}
.hint {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--fms-text-secondary);
}
</style>
