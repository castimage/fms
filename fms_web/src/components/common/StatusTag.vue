<script setup lang="ts">
/** 全系统状态标签映射，凭证/科目/应收/应付/现金/固定资产/费用报销，未知值兜底原样显示 */
import { computed } from 'vue'
import { NTag } from 'naive-ui'
import type { TagProps } from 'naive-ui'

const props = defineProps<{ status: string }>()
const { status } = props

const STATUS_MAP: Record<string, { label: string; type: TagProps['type'] }> = {
  DRAFT: { label: '草稿', type: 'default' },
  SUBMITTED: { label: '已提交', type: 'info' },
  APPROVED: { label: '已审批', type: 'primary' },
  POSTED: { label: '已过账', type: 'success' },
  REVERSED: { label: '已冲销', type: 'error' },
  ACTIVE: { label: '启用', type: 'success' },
  DISABLED: { label: '停用', type: 'default' },
  // 固定资产，DEPRECIATING/DISPOSED/SCRAPPED、折旧批次 CONFIRMED
  DEPRECIATING: { label: '计提中', type: 'warning' },
  DISPOSED: { label: '已处置', type: 'default' },
  SCRAPPED: { label: '已报废', type: 'error' },
  CONFIRMED: { label: '已确认', type: 'success' },
  // 费用报销，REJECTED 退回可改再提交
  REJECTED: { label: '已驳回', type: 'error' },
  // 应收/应付共用，发票 ISSUED/PARTIAL/PAID/VOID、收付款 ALLOCATED/RECEIVED、往来单位 INACTIVE/BLOCKED
  ISSUED: { label: '已开具', type: 'primary' },
  PARTIAL: { label: '部分核销', type: 'warning' },
  PAID: { label: '已结清', type: 'success' },
  VOID: { label: '已作废', type: 'error' },
  ALLOCATED: { label: '已核销', type: 'success' },
  RECEIVED: { label: '已登记', type: 'info' },
  INACTIVE: { label: '停用', type: 'default' },
  BLOCKED: { label: '冻结', type: 'error' },
  // 现金银行，对账匹配状态、账户状态
  UNMATCHED: { label: '未匹配', type: 'default' },
  MATCHED: { label: '已匹配', type: 'success' },
  FROZEN: { label: '冻结', type: 'warning' },
  CLOSED: { label: '已销户', type: 'default' },
}

const item = computed(() => STATUS_MAP[status] ?? { label: status, type: 'default' as TagProps['type'] })
</script>

<template>
  <n-tag :type="item.type" size="small" :bordered="false">{{ item.label }}</n-tag>
</template>
