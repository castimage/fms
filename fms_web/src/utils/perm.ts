/**
 * 角色权限工具，写操作角色语义统一收口。
 * 写角色 = admin + operator，业务操作主力，viewer/auditor/report 均为只读角色，
 * 不得见写操作入口，数据层由后端五层模型兜底。
 *
 * 菜单（Layout.vue）与页面按钮（v-if="canWrite"）统一引用本模块，防各页面内联判定漂移，
 * 内联 '!== viewer' 会让 auditor/report 误显写按钮。
 */
import { computed } from 'vue'
import type { ComputedRef } from 'vue'
import { useAuthStore } from '@/stores/auth'

export const WRITE_ROLES = ['admin', 'operator'] as const

/** 角色是否业务写角色，auditor/report/viewer 只读 */
export function isWriteRole(role?: string | null): boolean {
  return !!role && (WRITE_ROLES as readonly string[]).includes(role)
}

/** 当前会话是否可写，页面按钮统一用 v-if="canWrite" */
export function useCanWrite(): ComputedRef<boolean> {
  const auth = useAuthStore()
  return computed(() => isWriteRole(auth.user?.role))
}
