import { createDiscreteApi } from 'naive-ui'

/**
 * naive-ui 独立实例（message/dialog），供非组件上下文，http.ts 拦截器、store 使用，
 * 避免与组件树循环依赖，组件内仍用 useMessage/useDialog。
 */
export const { message, dialog } = createDiscreteApi(['message', 'dialog'])
