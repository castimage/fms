<script setup lang="ts">
/**
 * 系统参数页，元数据驱动分组表单，um.param_meta 描述每个参数的展示方式，分组/控件/单位/
 * 范围/说明/敏感标记，前端按 controlType 渲染对应控件，开关/数字/选择/文本/json。
 *
 * 无元数据的参数 fallback 到「其他」组 json 控件，新增参数不改前端仍可编辑。
 * 敏感参数只读脱敏，后端敏感键禁写双保险，jwt./pgcrypto. 前缀或含 secret|password|token。
 */
import { computed, onMounted, ref } from 'vue'
import { NButton, NInput, NInputNumber, NSelect, NSwitch, useMessage } from 'naive-ui'
import { adminApi } from '@/api/admin'
import type { ParamControlType, ParamFieldView, ParamMetaView, ParamView } from '@/types/admin'
import { formatDateTime } from '@/utils/format'

const message = useMessage()

/** 分组展示顺序，param_meta.grp 之外归 other */
const GROUP_ORDER = ['budget', 'mail', 'cash', 'wf', 'log', 'fa', 'rpt', 'web', 'ui', 'other']
const GROUP_LABELS: Record<string, string> = {
  budget: '预算管控', mail: '邮件通知', cash: '银行对账', wf: '审批流程',
  log: '日志审计', fa: '固定资产', rpt: '报表看板', web: '界面', ui: '外观', other: '其他',
}

/** 行模型，meta，无则为 null fallback + 视图 + 编辑值 + 状态 */
interface Row {
  meta: ParamMetaView | null
  view: ParamView
  edit: string | number | boolean | null
  /** 对象参数，{"v":{...}} 且有子字段元数据的字段编辑值，null = 非对象参数 */
  obj: Record<string, string | number | boolean | object | null> | null
  dirty: boolean
  saving: boolean
}

const keyword = ref('')
const currentGroup = ref('budget')
const loading = ref(false)
const rows = ref<Row[]>([])

/** 拉取参数全量，size=100 覆盖全部业务参数 + 元数据，按 code 合并 */
async function load() {
  loading.value = true
  try {
    const [pageRes, metaList] = await Promise.all([
      adminApi.pageParams({ page: 1, size: 100 }),
      adminApi.listParamMeta().catch(() => [] as ParamMetaView[]),
    ])
    const metaMap = new Map(metaList.map((m) => [m.code, m]))
    rows.value = pageRes.items.map((v) => {
      const meta = metaMap.get(v.code) ?? null
      return {
        meta, view: v,
        edit: parseValue(v.value, meta?.controlType, !!meta),
        obj: meta && meta.controlType === 'json' && meta.fields.length ? parseObjectValue(v.value) : null,
        dirty: false, saving: false,
      }
    })
  } finally {
    loading.value = false
  }
}

/** value，jsonb 文本转控件编辑值，标量 json.parse，对象参数 {"v":...} 解包取内部值，
 * json 控件与无元数据 fallback 保持原文，fallback 参数形态未知，解包对象喂 textarea 会渲染 [object Object] */
function parseValue(value: string, ctrl: ParamControlType | undefined, hasMeta: boolean): string | number | boolean | null {
  if (!hasMeta || ctrl === 'json') return value
  try {
    const parsed = JSON.parse(value)
    // 对象参数形态，seed 中 smtp.*/fa.depr.*/ui.login.background 均 {"v":...} 包装，
    // 控件编辑的是 v 字段内部值，避免 text 控件渲染成 [object Object]
    if (parsed !== null && typeof parsed === 'object' && 'v' in parsed) {
      return (parsed as { v: unknown }).v as string | number | boolean
    }
    return parsed as string | number | boolean
  } catch {
    return value
  }
}

/** 对象参数（{"v":{...}}）转字段编辑对象，解包 v 字段，缺失字段补 null 由控件空态显示 */
function parseObjectValue(value: string): Record<string, string | number | boolean | object | null> {
  try {
    const parsed = JSON.parse(value) as { v?: Record<string, unknown> }
    const obj = (parsed && typeof parsed === 'object' && parsed.v && typeof parsed.v === 'object'
      ? parsed.v
      : {}) as Record<string, unknown>
    const out: Record<string, string | number | boolean | object | null> = {}
    for (const [k, v] of Object.entries(obj)) {
      out[k] = (v ?? null) as string | number | boolean | null
    }
    return out
  } catch {
    return {}
  }
}

/** 控件编辑值转提交 value，jsonb 文本，子表单对象参数重组 {"v":{...}}，其余对象回包 {"v":值}，json 控件原文 */
function toSubmitValue(r: Row): string {
  if (r.meta?.controlType === 'json') {
    if (r.obj !== null) {
      // 子表单对象参数，仅提交已设置字段，null 字段剔除，与缺省继承语义一致
      const out: Record<string, unknown> = {}
      for (const [k, v] of Object.entries(r.obj)) {
        if (v !== null && v !== '') out[k] = v
      }
      return JSON.stringify({ v: out })
    }
    return String(r.edit ?? '')
  }
  // 无元数据 fallback，edit 为 value 原文，原文保存，标量/对象形态均不二次包装
  if (!r.meta) return String(r.edit ?? '')
  const raw = JSON.stringify(r.edit)
  try {
    const parsed = JSON.parse(r.view.value)
    if (parsed !== null && typeof parsed === 'object' && 'v' in parsed) {
      return JSON.stringify({ v: r.edit })
    }
  } catch {
    // 原值非法 json，异常态，按标量保存
  }
  return raw
}

async function save(r: Row) {
  if (!r.dirty) return
  // 子表单对象参数各字段已按控件约束，整段 json 兜底控件仍需合法 json 校验
  if (r.meta?.controlType === 'json' && r.obj === null && !isValidJson(String(r.edit ?? ''))) {
    message.warning('JSON 参数值须为合法 JSON（对象如 {"v":{...}}）')
    return
  }
  r.saving = true
  try {
    await adminApi.setParam(r.view.code, toSubmitValue(r))
    r.dirty = false
    r.view.value = toSubmitValue(r)
    message.success(`${r.meta?.label ?? r.view.code} 已保存`)
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    r.saving = false
  }
}

function isValidJson(s: string): boolean {
  try {
    JSON.parse(s)
    return true
  } catch {
    return false
  }
}

/** 关键字过滤，code/label 匹配 */
const filteredRows = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return rows.value
  return rows.value.filter(
    (r) => r.view.code.toLowerCase().includes(kw) || (r.meta?.label ?? '').toLowerCase().includes(kw),
  )
})

/** 当前分组行，按 sortNo/code 排序 */
const groupRows = computed(() =>
  filteredRows.value
    .filter((r) => (r.meta?.grp ?? 'other') === currentGroup.value)
    .sort((a, b) => (a.meta?.sortNo ?? 0) - (b.meta?.sortNo ?? 0) || a.view.code.localeCompare(b.view.code)),
)

/** 有行数据的分组列表，空组不显示 */
const activeGroups = computed(() =>
  GROUP_ORDER.filter((g) => rows.value.some((r) => (r.meta?.grp ?? 'other') === g)),
)

/** select 选项解析，options json 文本转 naive options */
function selectOptions(r: Row): { label: string; value: string }[] {
  try {
    const parsed = JSON.parse(r.meta?.options ?? '[]') as { label: string; value: string }[]
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

/** 子字段 select 选项解析 */
function fieldOptions(f: ParamFieldView): { label: string; value: string }[] {
  try {
    const parsed = JSON.parse(f.options ?? '[]') as { label: string; value: string }[]
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

/** 子字段 json 值转编辑文本，对象转 json 文本，null 显示空，历史字符串形态原样兼容 */
function fieldJsonText(r: Row, field: string): string {
  const v = r.obj?.[field]
  if (v === null || v === undefined) return ''
  if (typeof v === 'string') return v
  return JSON.stringify(v)
}

/** 子字段 json 编辑文本转存储值，空串/非法/非对象则 null=继承，合法对象 json 则解析后的对象
 * 与 types/background.ts 契约一致，desktop/tablet/mobile 为 BreakpointOverride 对象或 null，
 * 若以字符串持久化，消费端 resolveFor 取字段得 undefined 导致覆盖静默不生效，验证代理实测发现 */
function fieldJsonParse(text: string): object | null {
  const t = text.trim()
  if (!t) return null
  try {
    const parsed = JSON.parse(t)
    return parsed !== null && typeof parsed === 'object' ? parsed : null
  } catch {
    return null
  }
}

/** 敏感参数只读掩码展示 */
function displayValue(r: Row): string {
  if (r.meta?.sensitive) return '••••••（敏感参数，仅可经运维通道修改）'
  return String(r.edit ?? '')
}

onMounted(() => void load())
</script>

<template>
  <div class="param-list">
    <div class="filter-grid">
      <n-input v-model:value="keyword" placeholder="搜索参数名称 / 键" clearable size="small" style="max-width: 320px" />
    </div>

    <n-tabs v-model:value="currentGroup" type="line" size="small" animated>
      <n-tab-pane v-for="g in activeGroups" :key="g" :name="g" :tab="GROUP_LABELS[g] ?? g" />
    </n-tabs>

    <div v-if="loading" class="param-empty">加载中…</div>
    <div v-else-if="!groupRows.length" class="param-empty">该分组暂无参数</div>
    <div v-else class="param-group">
      <div v-for="r in groupRows" :key="r.view.code" class="param-row" :class="{ 'is-dirty': r.dirty }">
        <div class="param-row__label">
          <span class="param-row__name">{{ r.meta?.label ?? r.view.code }}</span>
          <n-tag v-if="r.meta?.sensitive" size="tiny" type="warning">敏感</n-tag>
          <span v-if="r.meta?.hint" class="param-row__hint">{{ r.meta.hint }}</span>
          <span v-else class="param-row__hint param-row__code">{{ r.view.code }}</span>
        </div>

        <div class="param-row__control">
          <!-- 敏感参数，只读掩码 -->
          <span v-if="r.meta?.sensitive" class="param-row__masked">{{ displayValue(r) }}</span>
          <!-- 布尔用开关 -->
          <n-switch v-else-if="r.meta?.controlType === 'boolean'" v-model:value="r.edit as boolean" size="small" @update:value="r.dirty = true">
            <template #checked>开</template>
            <template #unchecked>关</template>
          </n-switch>
          <!-- 数字用数字输入，单位/范围/步长 -->
          <n-input-number
            v-else-if="r.meta?.controlType === 'number'"
            v-model:value="r.edit as number | null"
            :min="r.meta?.minVal ?? undefined"
            :max="r.meta?.maxVal ?? undefined"
            :step="r.meta?.step ?? 1"
            size="small"
            style="width: 180px"
            @update:value="r.dirty = true"
          >
            <template v-if="r.meta?.unit" #suffix>{{ r.meta.unit }}</template>
          </n-input-number>
          <!-- 选择用下拉 -->
          <n-select
            v-else-if="r.meta?.controlType === 'select'"
            v-model:value="r.edit as string"
            :options="selectOptions(r)"
            size="small"
            style="width: 220px"
            @update:value="r.dirty = true"
          />
          <!-- 文本用单行输入 -->
          <n-input
            v-else-if="r.meta?.controlType === 'text'"
            v-model:value="r.edit as string"
            size="small"
            style="width: 280px"
            @update:value="r.dirty = true"
          />
          <!-- 对象参数子表单，{"v":{...}} 且有子字段元数据，按字段渲染独立控件 -->
          <div v-else-if="r.meta?.controlType === 'json' && r.obj !== null" class="param-subform">
            <div v-for="f in r.meta.fields" :key="f.field" class="param-subform__row">
              <span class="param-subform__label">{{ f.label }}</span>
              <!-- 布尔字段用开关 -->
              <n-switch
                v-if="f.controlType === 'boolean'"
                :value="Boolean(r.obj[f.field])"
                size="small"
                @update:value="(v: boolean) => { r.obj![f.field] = v; r.dirty = true }"
              />
              <!-- 数字字段用数字输入，单位/范围/步长 -->
              <n-input-number
                v-else-if="f.controlType === 'number'"
                :value="(r.obj[f.field] as number | null) ?? null"
                :min="f.minVal ?? undefined"
                :max="f.maxVal ?? undefined"
                :step="f.step ?? 1"
                size="small"
                style="width: 140px"
                @update:value="(v: number | null) => { r.obj![f.field] = v; r.dirty = true }"
              >
                <template v-if="f.unit" #suffix>{{ f.unit }}</template>
              </n-input-number>
              <!-- 枚举字段用下拉 -->
              <n-select
                v-else-if="f.controlType === 'select'"
                :value="(r.obj[f.field] as string | null) ?? null"
                :options="fieldOptions(f)"
                size="small"
                style="width: 160px"
                @update:value="(v: string) => { r.obj![f.field] = v; r.dirty = true }"
              />
              <!-- json 字段，分设备覆盖等嵌套对象用多行编辑 -->
              <n-input
                v-else-if="f.controlType === 'json'"
                :value="fieldJsonText(r, f.field)"
                type="textarea"
                :rows="2"
                size="small"
                style="width: 240px"
                placeholder="JSON（null/空 = 继承顶层）"
                @update:value="(v: string) => { r.obj![f.field] = fieldJsonParse(v); r.dirty = true }"
              />
              <!-- 文本字段用单行输入 -->
              <n-input
                v-else
                :value="(r.obj[f.field] as string | null) ?? ''"
                size="small"
                style="width: 220px"
                @update:value="(v: string) => { r.obj![f.field] = v; r.dirty = true }"
              />
              <span v-if="f.hint" class="param-subform__hint">{{ f.hint }}</span>
            </div>
          </div>
          <!-- json/无元数据 fallback 用多行 json 编辑 -->
          <n-input
            v-else
            v-model:value="r.edit as string"
            type="textarea"
            :rows="3"
            size="small"
            style="width: 380px"
            placeholder="JSON 文本（对象如 {&quot;v&quot;:{...}}）"
            @update:value="r.dirty = true"
          />
        </div>

        <div class="param-row__meta">
          <span>{{ formatDateTime(r.view.updatedAt) }}</span>
        </div>
        <div class="param-row__action">
          <n-button size="tiny" type="primary" :disabled="!r.dirty" :loading="r.saving" @click="save(r)">
            保存
          </n-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.param-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.filter-grid {
  background: var(--n-color, #fff);
  border-radius: 8px;
  padding: 12px 16px;
}
html.dark .filter-grid {
  background: var(--n-color, #1e1e1e);
}
.param-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.param-row {
  display: flex;
  align-items: center;
  gap: 16px;
  background: var(--n-color, #fff);
  border-radius: 8px;
  padding: 12px 16px;
  border: 1px solid transparent;
  transition: border-color 0.2s;
}
html.dark .param-row {
  background: var(--n-color, #1e1e1e);
}
.param-row.is-dirty {
  border-color: var(--fms-primary);
}
.param-row__label {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}
.param-row__name {
  font-weight: 600;
  white-space: nowrap;
}
.param-row__hint {
  color: var(--fms-text-muted);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.param-row__code {
  font-family: monospace;
}
.param-row__control {
  flex-shrink: 0;
}
.param-row__masked {
  color: var(--fms-text-muted);
  font-size: 13px;
}
.param-row__meta {
  color: var(--fms-text-muted);
  font-size: 12px;
  white-space: nowrap;
}
.param-row__action {
  flex-shrink: 0;
}
.param-empty {
  padding: 32px;
  text-align: center;
  color: var(--fms-text-muted);
}
/* 对象参数子表单，嵌套行，左对齐 label + 控件 + 说明，轻微缩进区分主参数行 */
.param-subform {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 0 8px 12px;
  border-left: 2px solid var(--fms-border);
}
.param-subform__row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.param-subform__label {
  width: 84px;
  flex-shrink: 0;
  font-size: 13px;
  color: var(--fms-text-secondary);
}
.param-subform__hint {
  color: var(--fms-text-muted);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
