import { onBeforeUnmount, ref, watch, type Ref } from 'vue'
import axios from 'axios'

/**
 * 认证图片 url 解析，背景图/预览共用，/api/ 同源图经 axios blob 带 token 换取
 * objectURL，原生 img 加载不带 Authorization 会 401，外链原样返回。
 * url 变化自动重新拉取，竞态安全，卸载 revoke 防泄漏。
 *
 * @param source 图片 url，null/空 = 不加载
 * @returns 可渲染 url，加载中/失败为 null
 */
export function useAuthedImageUrl(source: Ref<string | null>): Ref<string | null> {
  const resolved = ref<string | null>(null)
  let currentObjUrl: string | null = null
  let requestSeq = 0

  function revoke() {
    if (currentObjUrl) {
      URL.revokeObjectURL(currentObjUrl)
      currentObjUrl = null
    }
  }

  async function load(url: string | null) {
    const seq = ++requestSeq
    revoke()
    if (!url) {
      resolved.value = null
      return
    }
    if (!url.startsWith('/api/')) {
      resolved.value = url // 外链，浏览器直接加载，csp 允许则显示
      return
    }
    try {
      // 手动带 Authorization，http.ts 的 token 注入在其自定义 request 里，
      // 此处 axios.get 直连须显式携带，与 http.ts localStorage 键约定一致
      const token = localStorage.getItem('fms_access_token')
      const resp = await axios.get(url, {
        responseType: 'blob',
        headers: token ? { Authorization: `Bearer ${token}` } : undefined,
      })
      if (seq !== requestSeq) {
        return // 竞态，期间已发起新 url，本次结果作废
      }
      currentObjUrl = URL.createObjectURL(resp.data)
      resolved.value = currentObjUrl
    } catch {
      if (seq === requestSeq) {
        resolved.value = null // 加载失败不显示，http.ts 已统一提示
      }
    }
  }

  watch(source, (v) => void load(v), { immediate: true })
  onBeforeUnmount(() => {
    requestSeq++
    revoke()
  })
  return resolved
}
