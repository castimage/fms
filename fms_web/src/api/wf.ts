/**
 * 审批流 api，/api/wf，16 个端点与 WfController 一一对应，
 * 响应壳已在 http.ts 解包，此处仅暴露业务数据。
 */
import { request } from './http'
import type { PageQuery, PageResult } from '@/types/api'
import type {
  TaskActionRequest,
  WfDefinitionQuery,
  WfDefinitionRequest,
  WfDefinitionView,
  WfInstanceQuery,
  WfInstanceStatus,
  WfInstanceView,
  WfTaskEventView,
  WfTaskView,
} from '@/types/wf'

export const wfApi = {
  // 流程定义
  pageDefinitions(q: WfDefinitionQuery): Promise<PageResult<WfDefinitionView>> {
    return request<PageResult<WfDefinitionView>>({ url: '/wf/definitions', method: 'get', params: q })
  },
  getDefinition(id: string): Promise<WfDefinitionView | null> {
    return request<WfDefinitionView | null>({ url: `/wf/definitions/${id}`, method: 'get' })
  },
  createDefinition(req: WfDefinitionRequest): Promise<WfDefinitionView> {
    return request<WfDefinitionView>({ url: '/wf/definitions', method: 'post', data: req })
  },
  updateDefinition(id: string, req: WfDefinitionRequest): Promise<WfDefinitionView> {
    return request<WfDefinitionView>({ url: `/wf/definitions/${id}`, method: 'put', data: req })
  },
  deleteDefinition(id: string): Promise<void> {
    return request<void>({ url: `/wf/definitions/${id}`, method: 'delete' })
  },
  activateDefinition(id: string): Promise<WfDefinitionView> {
    return request<WfDefinitionView>({ url: `/wf/definitions/${id}/activate`, method: 'post' })
  },
  disableDefinition(id: string): Promise<WfDefinitionView> {
    return request<WfDefinitionView>({ url: `/wf/definitions/${id}/disable`, method: 'post' })
  },
  /** 回草稿 DISABLED 转 DRAFT，仅无 RUNNING 实例可回 */
  reviseDefinition(id: string): Promise<WfDefinitionView> {
    return request<WfDefinitionView>({ url: `/wf/definitions/${id}/revise`, method: 'post' })
  },

  // 流程实例
  pageInstances(q: WfInstanceQuery): Promise<PageResult<WfInstanceView>> {
    return request<PageResult<WfInstanceView>>({ url: '/wf/instances', method: 'get', params: q })
  },
  getInstance(id: string): Promise<WfInstanceView | null> {
    return request<WfInstanceView | null>({ url: `/wf/instances/${id}`, method: 'get' })
  },
  /** 本人发起实例分页，rls 限定 */
  myInstances(q: PageQuery & { status?: WfInstanceStatus }): Promise<PageResult<WfInstanceView>> {
    return request<PageResult<WfInstanceView>>({ url: '/wf/instances/my', method: 'get', params: q })
  },

  // 任务
  /** 我的待办（assignee=当前用户 AND status IN (PENDING,CLAIMED)，due_at 升序） */
  myTodos(q: PageQuery): Promise<PageResult<WfTaskView>> {
    return request<PageResult<WfTaskView>>({ url: '/wf/tasks/me', method: 'get', params: q })
  },
  claimTask(id: string): Promise<void> {
    return request<void>({ url: `/wf/tasks/${id}/claim`, method: 'post' })
  },
  /** req 可缺省，无审批意见直接通过，remark 非必填 */
  approveTask(id: string, req?: TaskActionRequest): Promise<void> {
    return request<void>({ url: `/wf/tasks/${id}/approve`, method: 'post', data: req })
  },
  /** remark 为驳回原因，必填语义在前端校验 */
  rejectTask(id: string, req?: TaskActionRequest): Promise<void> {
    return request<void>({ url: `/wf/tasks/${id}/reject`, method: 'post', data: req })
  },
  /** remark 传新处理人 uuid 文本即转办 */
  transferTask(id: string, req?: TaskActionRequest): Promise<void> {
    return request<void>({ url: `/wf/tasks/${id}/transfer`, method: 'post', data: req })
  },
  taskEvents(id: string): Promise<WfTaskEventView[]> {
    return request<WfTaskEventView[]>({ url: `/wf/tasks/${id}/events`, method: 'get' })
  },
}
