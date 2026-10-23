/**
 * 背景配置类型，与后端 jsonb 结构前后端同构，勿改字段，
 * 顶层字段为兜底值，desktop/tablet/mobile 断点档仅覆盖给定字段，缺省继承顶层，null=完全继承。
 * 用户级图片 url 形态 /api/files/{id}/download，登录级 /api/public/files/{id} 或外链。
 */

/** 拉伸模式 */
export type Fit = 'cover' | 'contain' | 'fill'

/** 断点档名，与 utils/breakpoint.ts 三档对齐，isMobile 对应 mobile / isTablet 对应 tablet / 否则 desktop */
export type BreakpointKey = 'desktop' | 'tablet' | 'mobile'

/** 断点档覆盖，仅出现字段生效，缺省继承顶层，enabled 恒全局，不参与断点档 */
export type BreakpointOverride = Partial<Pick<BackgroundCfg, 'imageUrl' | 'fit' | 'blur' | 'darken' | 'opacity'>>

export interface BackgroundCfg {
  /** 总开关 */
  enabled: boolean
  /** 图片 url，用户级 /api/files/{id}/download，登录级 /api/public/files/{id} 或外链 */
  imageUrl: string | null
  /** 拉伸模式 */
  fit: Fit
  /** 模糊级别 px，0-20 */
  blur: number
  /** 黑暗程度，黑色覆盖层透明度 0-0.8 */
  darken: number
  /** 图片层不透明度，0-1，1 不透明，0 全透明露出底层底色 */
  opacity: number
  /** 内容区不透明度，0-1，全局属性不进断点档，<1 时组件背景半透明露出背景图 */
  contentOpacity: number
  /** 断点档覆盖，仅覆盖给定字段，null=完全继承 */
  desktop: BreakpointOverride | null
  tablet: BreakpointOverride | null
  mobile: BreakpointOverride | null
}

/** resolveFor 合并后的单档背景配置，供 BackgroundLayer 消费 */
export interface ResolvedBackground {
  enabled: boolean
  imageUrl: string | null
  fit: Fit
  blur: number
  darken: number
  opacity: number
  /** 内容区不透明度，Layout has-bg 时应用 color-mix */
  contentOpacity: number
}

/** 缺省配置，与后端 UserPrefController / init/seed_system.sql 默认值逐字一致 */
export const DEFAULT_BG: BackgroundCfg = {
  enabled: false,
  imageUrl: null,
  fit: 'cover',
  blur: 6,
  darken: 0.25,
  opacity: 1,
  contentOpacity: 1,
  desktop: null,
  tablet: null,
  mobile: null,
}
