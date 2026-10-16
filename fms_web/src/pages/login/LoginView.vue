<script setup lang="ts">
/**
 * 两步登录页，后端 AuthController.login / totp/verify 契约，账号密码校验后可能回 mfaRequired
 * 平滑切第二步 6 位动态码，成功后 router.replace(redirect || '/')，
 * ≥992px 左品牌区 + 右卡片，以下仅居中卡片，深色模式自动适配。
 */
import { computed, ref, useId } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import type { FormInst, FormRules } from 'naive-ui'
import { recovery } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { useBreakpoint } from '@/utils/breakpoint'
import CodeInput from '@/components/common/CodeInput.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const message = useMessage()
const { isDesktop } = useBreakpoint()

// a11y，label for 关联，CodeInput 内部自持 useId
const loginNameId = useId()
const passwordId = useId()
const recoverLoginNameId = useId()
const recoverEmailId = useId()

const formRef = ref<FormInst | null>(null)
const form = ref({ loginName: '', password: '' })
const rules: FormRules = {
  loginName: [{ required: true, message: '请输入登录名', trigger: ['blur', 'input'] }],
  password: [{ required: true, message: '请输入密码', trigger: ['blur', 'input'] }],
}

const submitting = ref(false)
const verifying = ref(false)
const totpCode = ref('')

const isTotpStep = computed(() => auth.loginStep === 'totp')

// 密码找回，邮件闭环，登录卡片内切换，与 totp 步骤同款 collapse-transition
const showRecovery = ref(false)
const recoverFormRef = ref<FormInst | null>(null)
const recoverForm = ref({ loginName: '', email: '' })
const recoverRules: FormRules = {
  loginName: [{ required: true, message: '请输入登录名', trigger: ['blur', 'input'] }],
  email: [
    { required: true, message: '请输入注册邮箱', trigger: ['blur', 'input'] },
    { type: 'email', message: '邮箱格式不正确', trigger: ['blur', 'input'] },
  ],
}
const recovering = ref(false)

/** 提交找回，防探测口径，无论账号是否存在统一提示，服务端未命中不发邮件但响应一致 */
async function onRecover() {
  if (recovering.value) return
  try {
    await recoverFormRef.value?.validate()
  } catch {
    return
  }
  recovering.value = true
  try {
    await recovery(recoverForm.value.loginName.trim(), recoverForm.value.email.trim())
    message.success('若账号存在，重置邮件已发送，请注意查收')
    backToLogin()
  } catch {
    /* 错误提示已由 http 拦截器统一处理 */
  } finally {
    recovering.value = false
  }
}

/** 返回登录，清找回表单 */
function backToLogin() {
  showRecovery.value = false
  recoverForm.value = { loginName: '', email: '' }
}

/** redirect 白名单，仅接受站内相对路径，防开放重定向 */
function sanitizeRedirect(fallback = '/'): string {
  const raw = route.query.redirect
  if (typeof raw === 'string' && raw.startsWith('/') && !raw.startsWith('//')) return raw
  return fallback
}

/** 登录成功统一跳转 */
function goAfterLogin() {
  router.replace(sanitizeRedirect())
}

/** 第一步，账号密码登录 */
async function onSubmit() {
  if (submitting.value) return
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    const result = await auth.login(form.value.loginName.trim(), form.value.password)
    if (result === 'totp') {
      // 登录 Step 已切第二步，v-if 平滑过渡 + 自动聚焦
      return
    }
    message.success('登录成功')
    if (result === 'ok-change') {
      // 弱密码或首登 must_change_pwd，强制跳转改密页
      router.replace('/change-password')
      return
    }
    goAfterLogin()
  } catch {
    /* 错误提示已由 http 拦截器统一处理 */
  } finally {
    submitting.value = false
  }
}

/** 第二步，totp 动态码验证 */
async function onVerify() {
  if (verifying.value) return
  if (totpCode.value.length !== 6) {
    message.warning('请输入 6 位动态码')
    return
  }
  verifying.value = true
  try {
    const result = await auth.verifyTotp(totpCode.value)
    message.success('登录成功')
    if (result === 'ok-change') {
      // 弱密码或首登 must_change_pwd，强制跳转改密页
      router.replace('/change-password')
      return
    }
    goAfterLogin()
  } catch {
    // 验证码错误则清空，便于重输
    totpCode.value = ''
  } finally {
    verifying.value = false
  }
}

/** 返回，清票据回第一步 */
function backToPassword() {
  auth.backToPassword()
  totpCode.value = ''
}
</script>

<template>
  <div class="login">
    <!-- 品牌区，仅 ≥992px 显示 -->
    <aside v-if="isDesktop" class="login__brand">
      <div class="login__logo">FMS</div>
      <h1 class="login__title">FMS 财务管理系统</h1>
    </aside>

    <!-- 表单卡片 -->
    <main class="login__panel">
      <div class="login__panel-head">
        <h2 class="login__panel-title">{{ isTotpStep ? '两步验证' : showRecovery ? '密码找回' : '欢迎登录' }}</h2>
        <p class="login__panel-desc">
          {{
            isTotpStep
              ? '已开启两步验证，请输入 6 位动态码'
              : showRecovery
                ? '输入登录名与注册邮箱，重置邮件将发送至该邮箱'
                : '请输入您的账号与密码'
          }}
        </p>
      </div>

      <n-collapse-transition :show="!isTotpStep && !showRecovery">
        <n-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-placement="top"
          size="large"
          class="login__form"
          @keydown.enter.prevent="onSubmit"
        >
          <n-form-item :label="'登录名'" :for="loginNameId" path="loginName">
            <n-input
              :id="loginNameId"
              v-model:value="form.loginName"
              placeholder="请输入登录名"
              autocomplete="username"
              :input-props="{ autocomplete: 'username' }"
            />
          </n-form-item>
          <n-form-item :label="'密码'" :for="passwordId" path="password">
            <n-input
              :id="passwordId"
              v-model:value="form.password"
              type="password"
              show-password-on="mousedown"
              placeholder="请输入密码"
              :input-props="{ autocomplete: 'current-password' }"
            />
          </n-form-item>
          <n-button type="primary" size="large" block :loading="submitting" @click="onSubmit">
            {{ submitting ? '验证中…' : '登 录' }}
          </n-button>
          <div class="login__form-links">
            <n-button text type="primary" class="login__forgot" @click="showRecovery = true">
              忘记密码？
            </n-button>
          </div>
        </n-form>
      </n-collapse-transition>

      <n-collapse-transition :show="isTotpStep">
        <div v-if="isTotpStep" class="login__totp">
          <CodeInput v-model="totpCode" :autofocus="true" @submit="onVerify" />
          <n-button
            type="primary"
            size="large"
            block
            :loading="verifying"
            class="login__totp-btn"
            @click="onVerify"
          >
            验证并登录
          </n-button>
          <n-button text type="primary" class="login__totp-back" @click="backToPassword">
            ← 返回账号密码登录
          </n-button>
        </div>
      </n-collapse-transition>

      <n-collapse-transition :show="showRecovery">
        <n-form
          v-if="showRecovery"
          ref="recoverFormRef"
          :model="recoverForm"
          :rules="recoverRules"
          label-placement="top"
          size="large"
          class="login__form"
          @keydown.enter.prevent="onRecover"
        >
          <n-form-item :label="'登录名'" :for="recoverLoginNameId" path="loginName">
            <n-input
              :id="recoverLoginNameId"
              v-model:value="recoverForm.loginName"
              placeholder="请输入登录名"
              autocomplete="username"
              :input-props="{ autocomplete: 'username' }"
            />
          </n-form-item>
          <n-form-item :label="'注册邮箱'" :for="recoverEmailId" path="email">
            <n-input
              :id="recoverEmailId"
              v-model:value="recoverForm.email"
              placeholder="请输入注册邮箱"
              autocomplete="email"
              :input-props="{ autocomplete: 'email' }"
            />
          </n-form-item>
          <n-button type="primary" size="large" block :loading="recovering" @click="onRecover">
            {{ recovering ? '发送中…' : '发送重置邮件' }}
          </n-button>
          <n-button text type="primary" class="login__totp-back" @click="backToLogin">
            ← 返回登录
          </n-button>
        </n-form>
      </n-collapse-transition>
    </main>
  </div>
</template>

<style scoped>
.login {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 72px;
  width: min(100%, 1080px);
  padding: 24px 0;
}

/* 品牌区 */
.login__brand {
  flex: 1 1 380px;
  max-width: 460px;
  color: var(--fms-text);
}

.login__logo {
  width: 56px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, var(--fms-primary), color-mix(in srgb, var(--fms-primary) 60%, #000 20%));
  border-radius: 14px;
  margin-bottom: 20px;
  box-shadow: 0 8px 24px color-mix(in srgb, var(--fms-primary) 35%, transparent);
}

.login__title {
  font-size: 30px;
  margin: 0 0 12px;
  letter-spacing: 1px;
}

/* 表单卡片 */
.login__panel {
  flex: 0 1 400px;
  width: min(100%, 400px);
  padding: 36px 32px 32px;
  background: var(--fms-bg-card);
  border: 1px solid var(--fms-border);
  border-radius: var(--fms-radius-lg);
  box-shadow: var(--fms-shadow-card);
}

.login__panel-head {
  margin-bottom: 24px;
  text-align: center;
}

.login__panel-title {
  font-size: 22px;
  margin: 0 0 6px;
}

.login__panel-desc {
  margin: 0;
  font-size: 13px;
  color: var(--fms-text-secondary);
}

.login__totp {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.login__totp-btn {
  margin-top: 4px;
}

.login__totp-back {
  align-self: center;
}

/* 登录卡片底部链接行，忘记密码入口 */
.login__form-links {
  display: flex;
  justify-content: flex-end;
  margin-top: 4px;
}

.login__forgot {
  font-size: 13px;
}

/* <992px（--fms-bp-lg，lightningcss 不支持 calc(var) 媒体查询，故用字面量），仅居中卡片，品牌区隐藏 */
@media (max-width: 991px) {
  .login__brand {
    display: none;
  }
  .login {
    gap: 0;
  }
}

/* <576px（--fms-bp-sm），手机卡片占满可用宽度 */
@media (max-width: 575px) {
  .login__panel {
    padding: 28px 20px 24px;
  }
}
</style>
