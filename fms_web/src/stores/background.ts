/**
 * 背景 store，用户背景，登录后加载 + 登录页背景，匿名读口，
 * 拉取失败回落 DEFAULT_BG 不阻断页面渲染，resolveFor 按断点档合并单档配置。
 */
import { ref } from 'vue'
import { defineStore } from 'pinia'
import * as backgroundApi from '@/api/background'
import { DEFAULT_BG, type BackgroundCfg, type BreakpointKey, type ResolvedBackground } from '@/types/background'

export const useBackgroundStore = defineStore('background', () => {
  /** 当前用户背景，未加载/失败为缺省关闭态 */
  const userCfg = ref<BackgroundCfg>({ ...DEFAULT_BG })
  /** 登录页背景，管理员全局配置，全用户一致 */
  const loginCfg = ref<BackgroundCfg>({ ...DEFAULT_BG })
  /** 是否已加载过一轮，供调试/埋点，ui 不依赖 */
  const loaded = ref(false)

  /** 登录后拉取用户背景，失败回落默认，http.ts 已统一提示 */
  async function loadUser(): Promise<void> {
    try {
      userCfg.value = await backgroundApi.getUserBackground()
    } catch {
      userCfg.value = { ...DEFAULT_BG }
    }
    loaded.value = true
  }

  /** 登录页挂载时拉取登录背景，匿名，失败回落默认不阻断 */
  async function loadLogin(): Promise<void> {
    try {
      loginCfg.value = await backgroundApi.getLoginBackground()
    } catch {
      loginCfg.value = { ...DEFAULT_BG }
    }
    loaded.value = true
  }

  /** 保存用户背景，写后端成功后刷新本地显示 */
  async function saveUser(cfg: BackgroundCfg): Promise<void> {
    await backgroundApi.putUserBackground(cfg)
    userCfg.value = { ...cfg }
  }

  /** 保存登录页背景，管理员，写系统参数，成功后刷新本地显示 */
  async function saveLogin(cfg: BackgroundCfg): Promise<void> {
    await backgroundApi.saveLoginBackground(cfg)
    loginCfg.value = { ...cfg }
  }

  /**
   * 纯函数，断点档仅覆盖给定字段，缺省继承顶层，
   * 顶层 enabled/darken/blur/fit/opacity 恒作兜底，断点档为 null 时完全继承顶层。
   */
  function resolveFor(cfg: BackgroundCfg, bp: BreakpointKey): ResolvedBackground {
    const ov = cfg[bp] ?? null
    return {
      enabled: cfg.enabled,
      imageUrl: ov?.imageUrl !== undefined ? ov.imageUrl : cfg.imageUrl,
      fit: ov?.fit ?? cfg.fit,
      blur: ov?.blur ?? cfg.blur,
      darken: ov?.darken ?? cfg.darken,
      opacity: ov?.opacity ?? cfg.opacity,
      contentOpacity: cfg.contentOpacity ?? 1, // 全局属性，不进断点档
    }
  }

  return { userCfg, loginCfg, loaded, loadUser, loadLogin, saveUser, saveLogin, resolveFor }
})
