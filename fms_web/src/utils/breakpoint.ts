/**
 * 断点工具，JS 断点常量与 styles/variables.css 的 --fms-bp-* 一一对应（xs=0 / sm=576 / md=768 / lg=992 / xl=1200）。
 */
import { useBreakpoints } from '@vueuse/core'
import type { ComputedRef } from 'vue'

export const BREAKPOINTS = {
  xs: 0,
  sm: 576,
  md: 768,
  lg: 992,
  xl: 1200,
} as const

export interface BreakpointState {
  /** <768px 手机，底部导航 + 全单列 */
  isMobile: ComputedRef<boolean>
  /** 768–991px 平板，侧边栏图标折叠态 */
  isTablet: ComputedRef<boolean>
  /** ≥992px 桌面，侧边栏全展开 */
  isDesktop: ComputedRef<boolean>
}

/** 响应式三档 */
export function useBreakpoint(): BreakpointState {
  const bp = useBreakpoints(BREAKPOINTS)
  return {
    isMobile: bp.smaller('md'),
    isTablet: bp.between('md', 'lg'),
    isDesktop: bp.greaterOrEqual('lg'),
  }
}
