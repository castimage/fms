<script setup lang="ts">
/**
 * 2fa 设置向导，后端 totp/setup · enable · backup-codes 契约，① 扫码，后端返回 otpauthUrl，
 * 前端自研 QR 渲染 + 手动密钥，② 密码复验 + 6 位动态码启用，③ 一次性备用码展示，复制/下载，
 * 完成后跳 redirect 或首页。
 */
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { totpSetup, totpEnable, totpBackupCodes } from '@/api/auth'
import type { BackupCodesResponse, TotpSetupResponse } from '@/types/auth'
import CodeInput from '@/components/common/CodeInput.vue'
import QrCode from '@/components/common/QrCode.vue'

const route = useRoute()
const router = useRouter()
const message = useMessage()

type SetupStep = 0 | 1 | 2 // 0 扫码 / 1 验证 / 2 备用码

const step = ref<SetupStep>(0)
const loading = ref(true)
const setup = ref<TotpSetupResponse | null>(null)
const backup = ref<BackupCodesResponse | null>(null)

const password = ref('')
const code = ref('')
const enabling = ref(false)
const finishing = ref(false)

/** redirect 白名单，与登录页一致，仅站内相对路径 */
function redirectTarget(): string {
  const raw = route.query.redirect
  if (typeof raw === 'string' && raw.startsWith('/') && !raw.startsWith('//')) return raw
  return '/'
}

/** 复制文本，clipboard api 失败时降级提示 */
async function copyText(text: string, tip: string) {
  try {
    await navigator.clipboard.writeText(text)
    message.success(tip)
  } catch {
    message.warning('复制失败，请手动选择复制')
  }
}

/** 第一步，拉取 setup 密钥，密钥仅本次返回，未启用前服务端仅存 10min */
onMounted(async () => {
  try {
    setup.value = await totpSetup()
  } catch {
    /* 提示已由 http 拦截器处理 */
  } finally {
    loading.value = false
  }
})

/** 第二步，密码复验 + 动态码启用 */
async function onEnable() {
  if (enabling.value) return
  if (!password.value) {
    message.warning('请输入登录密码')
    return
  }
  if (code.value.length !== 6) {
    message.warning('请输入 6 位动态码')
    return
  }
  enabling.value = true
  try {
    await totpEnable(password.value, code.value)
    message.success('两步验证已启用')
    password.value = ''
    code.value = ''
    // 进入第三步并生成备用码，一次性返回
    step.value = 2
    try {
      backup.value = await totpBackupCodes()
    } catch {
      /* 备用码生成失败不阻塞向导收尾 */
    }
  } catch {
    code.value = ''
  } finally {
    enabling.value = false
  }
}

/** 复制全部备用码 */
function copyAllCodes() {
  if (!backup.value) return
  void copyText(backup.value.codes.join('\n'), '备用码已复制')
}

/** 下载备用码为 txt 文件 */
function downloadCodes() {
  if (!backup.value) return
  const blob = new Blob([backup.value.codes.join('\n')], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'fms-backup-codes.txt'
  a.click()
  URL.revokeObjectURL(url)
}

/** 完成向导 */
function onDone() {
  if (finishing.value) return
  finishing.value = true
  router.replace(redirectTarget())
}
</script>

<template>
  <div class="totp">
    <div class="totp__card">
      <header class="totp__head">
        <h2 class="totp__title">两步验证设置</h2>
        <p class="totp__desc">通过认证器应用为您的账户增加一层安全保护</p>
      </header>

      <n-steps :current="step" size="small" class="totp__steps">
        <n-step title="扫码绑定" description="认证器扫码" />
        <n-step title="验证启用" description="密码 + 动态码" />
        <n-step title="保存备用码" description="一次性恢复码" />
      </n-steps>

      <!-- 步骤 1，扫码 / 手动录入密钥 -->
      <div v-if="step === 0" class="totp__body">
        <n-spin :show="loading" class="totp__spin">
          <template v-if="setup">
            <QrCode :text="setup.otpauthUrl" :size="200" class="totp__qr" />
            <p class="totp__tip">
              使用 Google Authenticator / Microsoft Authenticator 等应用扫码，<br />
              或手动录入下方密钥
            </p>
            <div class="totp__secret">
              <n-input :value="setup.secretBase32" readonly class="totp__secret-input" />
              <n-button @click="copyText(setup.secretBase32, '密钥已复制')">复制</n-button>
            </div>
            <p class="totp__warn">密钥仅本次显示，且未启用前仅保留 10 分钟，请尽快完成绑定</p>
            <n-button type="primary" size="large" block class="totp__primary" @click="step = 1">
              我已完成扫码，下一步
            </n-button>
          </template>
        </n-spin>
      </div>

      <!-- 步骤 2，密码复验 + 动态码 -->
      <div v-else-if="step === 1" class="totp__body">
        <n-form label-placement="top" size="large" @keydown.enter.prevent="onEnable">
          <n-form-item label="登录密码">
            <n-input
              v-model:value="password"
              type="password"
              show-password-on="mousedown"
              placeholder="输入登录密码以确认身份"
              :input-props="{ autocomplete: 'current-password' }"
            />
          </n-form-item>
          <n-form-item label="动态码">
            <CodeInput v-model="code" :autofocus="true" @submit="onEnable" />
          </n-form-item>
          <n-button type="primary" size="large" block :loading="enabling" @click="onEnable">
            启用两步验证
          </n-button>
          <n-button text type="primary" class="totp__back" @click="step = 0">
            ← 上一步
          </n-button>
        </n-form>
      </div>

      <!-- 步骤 3，一次性备用码 -->
      <div v-else class="totp__body">
        <n-alert type="warning" title="请立即保存备用码">
          备用码仅本次显示，用于丢失认证器时恢复登录；每码仅可使用一次，请妥善保管
        </n-alert>
        <div v-if="backup" class="totp__codes">
          <div v-for="c in backup.codes" :key="c" class="totp__code">{{ c }}</div>
        </div>
        <div class="totp__actions">
          <n-button @click="copyAllCodes">复制全部</n-button>
          <n-button @click="downloadCodes">下载 txt</n-button>
        </div>
        <n-button type="primary" size="large" block :loading="finishing" @click="onDone">
          完成
        </n-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.totp {
  display: flex;
  justify-content: center;
}

.totp__card {
  width: min(100%, 460px);
  padding: 32px 28px;
  background: var(--fms-bg-card);
  border: 1px solid var(--fms-border);
  border-radius: var(--fms-radius-lg);
  box-shadow: var(--fms-shadow-card);
}

.totp__head {
  text-align: center;
  margin-bottom: 20px;
}

.totp__title {
  margin: 0 0 6px;
  font-size: 22px;
}

.totp__desc {
  margin: 0;
  font-size: 13px;
  color: var(--fms-text-secondary);
}

.totp__steps {
  margin-bottom: 24px;
}

.totp__body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.totp__spin {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}

.totp__qr {
  margin-top: 4px;
}

.totp__tip {
  margin: 0;
  text-align: center;
  font-size: 13px;
  line-height: 1.8;
  color: var(--fms-text-secondary);
}

.totp__secret {
  display: flex;
  gap: 8px;
  width: 100%;
}

.totp__secret-input {
  flex: 1;
  min-width: 0;
  letter-spacing: 2px;
}

.totp__warn {
  margin: 0;
  text-align: center;
  font-size: 12px;
  color: var(--fms-warning);
}

.totp__primary {
  margin-top: 4px;
}

.totp__back {
  align-self: center;
}

.totp__codes {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.totp__code {
  padding: 8px 4px;
  text-align: center;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 14px;
  letter-spacing: 1px;
  background: var(--fms-bg);
  border: 1px dashed var(--fms-border);
  border-radius: var(--fms-radius);
}

.totp__actions {
  display: flex;
  justify-content: center;
  gap: 12px;
}

/* <576px（--fms-bp-sm，lightningcss 不支持 calc(var) 媒体查询，故用字面量） */
@media (max-width: 575px) {
  .totp__card {
    padding: 24px 16px;
  }
  .totp__codes {
    grid-template-columns: 1fr;
  }
}
</style>
