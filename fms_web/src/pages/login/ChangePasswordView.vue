<script setup lang="ts">
/**
 * 自助改密页，后端 POST /api/auth/change-password 契约，旧密码 + 新密码 + 确认，
 * 成功后服务端将当前令牌入黑名单，本地清态后跳登录页重新登录。
 * 入口，登录返回 ok-change，弱密码或 must_change_pwd 首登强制改密，与路由守卫双通道。
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import type { FormInst, FormRules } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import { useConfigStore } from '@/stores/config'

const router = useRouter()
const auth = useAuthStore()
const message = useMessage()
const configStore = useConfigStore()
void configStore.load() // 密码长度边界经 /api/config 下发，前端快速校验，后端权威兜底

const formRef = ref<FormInst | null>(null)
const form = ref({ oldPassword: '', newPassword: '', confirmPassword: '' })
const rules = computed<FormRules>(() => ({
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: ['blur', 'input'] }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: ['blur', 'input'] },
    {
      min: configStore.cfg.passwordMinLength,
      max: configStore.cfg.passwordMaxLength,
      message: `密码长度须在 ${configStore.cfg.passwordMinLength}-${configStore.cfg.passwordMaxLength} 位之间`,
      trigger: ['blur', 'input'],
    },
    {
      validator: (_rule, value: string) => value !== form.value.oldPassword,
      message: '新密码不能与旧密码相同',
      trigger: ['blur', 'input'],
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: ['blur', 'input'] },
    {
      validator: (_rule, value: string) => value === form.value.newPassword,
      message: '两次输入的新密码不一致',
      trigger: ['blur', 'input'],
    },
  ],
}))

const submitting = ref(false)

/** 提交改密，成功则清态，令牌已失效，跳登录重新登录 */
async function onSubmit() {
  if (submitting.value) return
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    await auth.changePassword(form.value.oldPassword, form.value.newPassword)
    message.success('密码修改成功，请使用新密码重新登录')
    router.replace('/login')
  } catch {
    // 旧密码错误/弱密码/长度非法等提示已由 http 拦截器统一处理
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="chpwd">
    <div class="chpwd__card">
      <header class="chpwd__head">
        <h2 class="chpwd__title">修改密码</h2>
        <p class="chpwd__desc">首次登录或检测到弱密码，请设置新密码后继续使用</p>
      </header>

      <n-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-placement="top"
        size="large"
        class="chpwd__form"
        @keydown.enter.prevent="onSubmit"
      >
        <n-form-item label="旧密码" path="oldPassword">
          <n-input
            v-model:value="form.oldPassword"
            type="password"
            show-password-on="mousedown"
            placeholder="请输入当前密码"
            :input-props="{ autocomplete: 'current-password' }"
          />
        </n-form-item>
        <n-form-item label="新密码" path="newPassword">
          <n-input
            v-model:value="form.newPassword"
            type="password"
            show-password-on="mousedown"
            placeholder="8-64 位，请勿使用常见弱密码"
            :input-props="{ autocomplete: 'new-password' }"
          />
        </n-form-item>
        <n-form-item label="确认新密码" path="confirmPassword">
          <n-input
            v-model:value="form.confirmPassword"
            type="password"
            show-password-on="mousedown"
            placeholder="请再次输入新密码"
            :input-props="{ autocomplete: 'new-password' }"
          />
        </n-form-item>
        <n-button type="primary" size="large" block :loading="submitting" @click="onSubmit">
          {{ submitting ? '提交中…' : '确认修改' }}
        </n-button>
      </n-form>
    </div>
  </div>
</template>

<style scoped>
.chpwd {
  display: flex;
  justify-content: center;
}

.chpwd__card {
  width: min(100%, 420px);
  padding: 36px 32px 32px;
  background: var(--fms-bg-card);
  border: 1px solid var(--fms-border);
  border-radius: var(--fms-radius-lg);
  box-shadow: var(--fms-shadow-card);
}

.chpwd__head {
  margin-bottom: 24px;
  text-align: center;
}

.chpwd__title {
  font-size: 22px;
  margin: 0 0 6px;
}

.chpwd__desc {
  margin: 0;
  font-size: 13px;
  color: var(--fms-text-secondary);
}

/* <576px（--fms-bp-sm，lightningcss 不支持 calc(var) 媒体查询，故用字面量） */
@media (max-width: 575px) {
  .chpwd__card {
    padding: 28px 20px 24px;
  }
}
</style>
