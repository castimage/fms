<script setup lang="ts">
/**
 * 审批流程线性图，节点块加有向箭头边，按实例进度着色，
 * 定义预览不传 currentCode 与 status 时整链中性色只示编排。
 */
import { computed } from 'vue'
import { Handle, MarkerType, Position, VueFlow } from '@vue-flow/core'
import type { Edge, Node } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import type { WfGraphStep, WfInstanceStatus, WfNodeStrategy } from '@/types/wf'

const props = defineProps<{
  steps: WfGraphStep[]
  currentCode?: string
  status?: WfInstanceStatus
}>()

/** 节点进度，done 已通过 / current 当前 / rejected 已驳回 / pending 未到达 / neutral 定义预览 */
type NodeState = 'done' | 'current' | 'rejected' | 'pending' | 'neutral'

const STRATEGY_LABEL: Record<WfNodeStrategy, string> = {
  approve: '单审',
  countersign: '会签',
  orsign: '或签',
}

const states = computed<NodeState[]>(() => {
  const idx = props.currentCode ? props.steps.findIndex((s) => s.node_code === props.currentCode) : -1
  if (props.status === 'APPROVED') return props.steps.map(() => 'done' as NodeState)
  if (!props.currentCode || !props.status || idx < 0) {
    return props.steps.map(() => 'neutral' as NodeState)
  }
  if (props.status === 'REJECTED') {
    return props.steps.map((_, i) => (i < idx ? 'done' : i === idx ? 'rejected' : 'pending') as NodeState)
  }
  return props.steps.map((_, i) => (i < idx ? 'done' : i === idx ? 'current' : 'pending') as NodeState)
})

const NODE_GAP = 56
const APPROVE_WIDTH = 190
const APPROVE_HEIGHT = 60
const DOT_WIDTH = 40
const DOT_HEIGHT = 40

const nodes = computed<Node[]>(() => {
  let x = 0
  return props.steps.map((s, i) => {
    const isDot = s.type !== 'APPROVE'
    const node: Node = {
      id: `wf-${i}`,
      type: 'wfnode',
      position: { x, y: isDot ? (APPROVE_HEIGHT - DOT_HEIGHT) / 2 : 0 },
      data: { ...s, state: states.value[i] },
      draggable: false,
      selectable: false,
    }
    x += (isDot ? DOT_WIDTH : APPROVE_WIDTH) + NODE_GAP
    return node
  })
})

/** 边着色随目标节点，已走过的路径绿色，未到达灰色 */
const edges = computed<Edge[]>(() =>
  props.steps.slice(0, -1).map((_, i) => {
    const next = states.value[i + 1]
    const active = next !== 'pending' && next !== 'neutral'
    const color = active ? '#18a058' : '#c2c2c8'
    return {
      id: `wf-e-${i}`,
      source: `wf-${i}`,
      target: `wf-${i + 1}`,
      type: 'smoothstep',
      selectable: false,
      markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16, color },
      style: { stroke: color, strokeWidth: 1.5 },
    }
  }),
)

function strategyLabel(s: unknown): string {
  return STRATEGY_LABEL[s as WfNodeStrategy] ?? '单审'
}
</script>

<template>
  <div class="wf-graph">
    <VueFlow
      v-if="steps.length"
      :nodes="nodes"
      :edges="edges"
      :fit-view-on-init="true"
      :nodes-draggable="false"
      :nodes-connectable="false"
      :elements-selectable="false"
      :zoom-on-double-click="false"
      :prevent-scrolling="true"
      :min-zoom="0.4"
      :max-zoom="1.2"
    >
      <template #node-wfnode="{ data }">
        <div class="wf-node" :class="`wf-node--${String(data.type).toLowerCase()}`" :data-state="data.state">
          <Handle type="target" :position="Position.Left" class="wf-handle" />
          <template v-if="data.type === 'APPROVE'">
            <div class="wf-node-code">{{ data.node_code }}</div>
            <div class="wf-node-meta">
              <span class="wf-badge">{{ strategyLabel(data.strategy) }}</span>
              <span class="wf-handler">{{ data.handlerLabel || '未配置处理人' }}</span>
            </div>
          </template>
          <span v-else class="wf-dot-text">{{ data.type === 'START' ? '发起' : '结束' }}</span>
          <Handle type="source" :position="Position.Right" class="wf-handle" />
        </div>
      </template>
    </VueFlow>
    <div v-else class="wf-graph-empty">暂无节点编排</div>
  </div>
</template>

<style scoped>
.wf-graph {
  height: 170px;
  border: 1px solid var(--n-border-color, #efeff5);
  border-radius: 8px;
  overflow: hidden;
}
.wf-graph :deep(.vue-flow) {
  background: transparent;
}
.wf-graph :deep(.vue-flow__node) {
  cursor: default;
}
.wf-graph :deep(.vue-flow__edge-path) {
  transition: stroke 0.2s;
}
.wf-node {
  box-sizing: border-box;
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 4px;
  border: 1.5px solid var(--n-border-color, #d9d9d9);
  border-radius: 8px;
  background: var(--n-color, #fff);
  color: var(--n-text-color, #333);
}
.wf-node--approve {
  width: 190px;
  height: 60px;
  padding: 0 12px;
}
.wf-node--start,
.wf-node--end {
  width: 40px;
  height: 40px;
  align-items: center;
  border-radius: 50%;
}
.wf-node-code {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wf-node-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--n-text-color-3, #999);
  overflow: hidden;
}
.wf-badge {
  flex: none;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--n-action-color, #f0f0f0);
  font-size: 11px;
  line-height: 18px;
}
.wf-handler {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wf-dot-text {
  font-size: 12px;
  color: var(--n-text-color-3, #999);
}
.wf-handle {
  opacity: 0;
}
.wf-node[data-state='done'] {
  border-color: #18a058;
}
.wf-node[data-state='current'] {
  border-color: #2080f0;
  box-shadow: 0 0 0 3px rgba(32, 128, 240, 0.15);
}
.wf-node[data-state='rejected'] {
  border-color: #d03050;
  box-shadow: 0 0 0 3px rgba(208, 48, 80, 0.12);
}
.wf-node[data-state='pending'] {
  opacity: 0.65;
}
.wf-node[data-state='done']::after {
  content: '✓';
  position: absolute;
  top: -7px;
  right: -7px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #18a058;
  color: #fff;
  font-size: 11px;
  line-height: 16px;
  text-align: center;
}
.wf-node--start[data-state='done']::after,
.wf-node--end[data-state='done']::after {
  top: -3px;
  right: -3px;
}
.wf-graph-empty {
  padding: 24px 0;
  text-align: center;
  font-size: 13px;
  color: var(--n-text-color-3, var(--fms-text-muted, #999));
}
</style>
