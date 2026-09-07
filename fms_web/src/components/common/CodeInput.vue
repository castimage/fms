<script setup lang="ts">
/**
 * 6 位数字验证码输入框，defineModel 双向绑定，useTemplateRef 自动聚焦。
 * 登录第 2 步与 2fa 设置向导共用，Enter 触发 submit 事件。
 */
import { onMounted, useId, useTemplateRef } from 'vue'
import { computed } from 'vue'

const { length = 6, autofocus = false } = defineProps<{
  /** 位数，默认 6 */
  length?: number
  /** 挂载后自动聚焦 */
  autofocus?: boolean
}>()

/** 双向绑定值，纯数字，截断到 length 位 */
const value = defineModel<string>({ default: '' })

const emit = defineEmits<{ submit: [] }>()

const inputId = useId()
const inputRef = useTemplateRef<HTMLInputElement>('inputRef')

/** 各格子显示的字符 */
const digits = computed(() => Array.from({ length }, (_, i) => value.value[i] ?? ''))
/** 光标位置，输入后光标恒在末尾 */
const cursorIndex = computed(() => Math.min(value.value.length, length))

/** 仅保留数字并截断 */
function onInput(e: Event) {
  const raw = (e.target as HTMLInputElement).value
  value.value = raw.replace(/\D/g, '').slice(0, length)
}

/** 回退键，删末尾一格，否则空格内退无反应 */
function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Backspace' && value.value.length > 0) {
    value.value = value.value.slice(0, -1)
  }
}

/** 粘贴，提取数字后截断 */
function onPaste(e: ClipboardEvent) {
  const text = e.clipboardData?.getData('text') ?? ''
  value.value = text.replace(/\D/g, '').slice(0, length)
  e.preventDefault()
}

onMounted(() => {
  if (autofocus) inputRef.value?.focus()
})
</script>

<template>
  <!-- 真实输入框透明覆盖在格子上，保留光标/键盘/粘贴事件 -->
  <div class="code-input">
    <input
      :id="inputId"
      ref="inputRef"
      class="code-input__real"
      type="text"
      :value="value"
      inputmode="numeric"
      autocomplete="one-time-code"
      :maxlength="length"
      @input="onInput"
      @keydown="onKeydown"
      @paste="onPaste"
      @keydown.enter="emit('submit')"
    />
    <div class="code-input__boxes" aria-hidden="true">
      <div
        v-for="(d, i) in digits"
        :key="i"
        class="code-input__box"
        :class="{ 'code-input__box--active': i === cursorIndex }"
      >
        {{ d }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.code-input {
  position: relative;
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.code-input__real {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  cursor: text;
}

.code-input__boxes {
  display: flex;
  gap: 8px;
  width: 100%;
}

.code-input__box {
  flex: 1;
  min-width: 0;
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  font-variant-numeric: tabular-nums;
  color: var(--fms-text);
  background: var(--fms-bg-card);
  border: 1px solid var(--fms-border);
  border-radius: var(--fms-radius);
  transition: border-color 0.2s, box-shadow 0.2s;
}

/* 当前位高亮，模拟光标 */
.code-input__box--active {
  border-color: var(--fms-primary);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--fms-primary) 20%, transparent);
}

/* 触控目标 ≥44px */
@media (max-width: 575px) {
  .code-input__box {
    height: 48px;
    font-size: 20px;
  }
}
</style>
