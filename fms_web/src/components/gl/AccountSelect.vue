<script setup lang="ts">
/**
 * AccountSelect 科目树选择器，凭证分录/账簿筛选共用，v-model:value 绑定 accountId，
 * display 回显"编码 名称"，弹窗内 n-tree 展示，数据经 utils/tree 建树并按公司缓存，
 * 搜索框 300ms 防抖服务端过滤，仅末级科目可选，选中 emit select(code, name)。
 */
import { computed, onMounted, ref } from 'vue'
import { NButton, NInput, NModal, NTree } from 'naive-ui'
import type { TreeOption } from 'naive-ui'
import { useDebounceFn } from '@vueuse/core'
import { glApi } from '@/api/gl'
import { buildAccountTree } from '@/utils/tree'
import type { AccountTreeNode } from '@/utils/tree'

const props = defineProps<{
  companyId?: string | null
  /** 回显文案，编辑模式由父级传入既有科目编码-名称 */
  display?: string
  disabled?: boolean
  placeholder?: string
}>()
// props 先赋值再解构是非响应式，3.5 仅 defineProps 直接解构生效，display 恒初始值导致选中后回显空，全部经 props. 读取

const value = defineModel<string | null>('value', { default: null })

const emit = defineEmits<{ select: [code: string, name: string] }>()

const showModal = ref(false)
const treeData = ref<TreeOption[]>([])
const loading = ref(false)
const keyword = ref('')
/** id 到科目映射，缓存树与搜索结果共用 */
const nodeMap = new Map<string, AccountTreeNode>()
/** 树缓存，按公司，避免重复请求 */
const cache = new Map<string, AccountTreeNode[]>()

async function loadTree(kw?: string) {
  loading.value = true
  try {
    const key = props.companyId ?? ''
    const kwTrim = (kw ?? '').trim()
    const list = kwTrim
      ? await glApi.listAccounts(props.companyId ?? undefined, kwTrim)
      : await glApi.listAccounts(props.companyId ?? undefined)
    if (!kwTrim && !cache.has(key)) cache.set(key, list)
    const src = kwTrim ? list : cache.get(key)!
    nodeMap.clear()
    for (const acc of src) nodeMap.set(acc.id, acc)
    treeData.value = toOptions(buildAccountTree(src))
  } finally {
    loading.value = false
  }
}

/** 树节点映射，末级才可选 */
function toOptions(nodes: AccountTreeNode[]): TreeOption[] {
  return nodes.map((n) => ({
    key: n.id,
    label: `${n.code} ${n.name}`,
    isLeaf: n.isLeaf,
    selectable: n.isLeaf,
    children: n.children && n.children.length ? toOptions(n.children) : undefined,
  }))
}

function open() {
  if (props.disabled) return
  keyword.value = ''
  showModal.value = true
  void loadTree()
}

const onSearch = useDebounceFn((v: string) => {
  void loadTree(v)
}, 300)

/** 选中末级科目，回填 value 并回显 */
function onSelect(keys: Array<string | number>) {
  const id = String(keys[0] ?? '')
  const acc = nodeMap.get(id)
  if (!acc) return
  value.value = acc.id
  emit('select', acc.code, acc.name)
  showModal.value = false
}

const selectedKeys = computed(() => (value.value ? [value.value] : []))

onMounted(() => {
  if (value.value) void loadTree()
})

defineExpose({ open })
</script>

<template>
  <n-input
    :value="props.display"
    :disabled="props.disabled"
    :placeholder="placeholder ?? '选择末级科目'"
    readonly
    size="small"
    @click="open"
  />
  <n-modal
    v-model:show="showModal"
    preset="card"
    title="选择科目"
    style="width: min(480px, calc(100vw - 32px))"
    :bordered="false"
  >
    <n-input
      v-model:value="keyword"
      placeholder="搜索科目编码 / 名称（仅末级可选）"
      clearable
      size="small"
      @update:value="onSearch"
    />
    <n-tree
      :data="treeData"
      :selected-keys="selectedKeys"
      block-line
      expand-on-click
      style="max-height: 48vh; overflow: auto; margin-top: 12px"
      :loading="loading"
      @update:selected-keys="onSelect"
    />
    <template #action>
      <n-button size="small" quaternary @click="showModal = false">取消</n-button>
    </template>
  </n-modal>
</template>
