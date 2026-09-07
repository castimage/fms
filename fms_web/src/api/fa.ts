/**
 * 固定资产 api，/fa，10 端点与 FaController 一一对应，资产卡片 CRUD + 处置/报废 + 折旧计提批次。
 */
import { request } from './http'
import type { PageResult } from '@/types/api'
import type {
  AssetQuery,
  AssetRequest,
  AssetView,
  DepreciationQuery,
  DepreciationRunView,
  DisposeRequest,
} from '@/types/fa'

export const faApi = {
  // 资产卡片
  pageAssets(q: AssetQuery): Promise<PageResult<AssetView>> {
    return request<PageResult<AssetView>>({ url: '/fa/assets', method: 'get', params: q })
  },
  getAsset(id: string): Promise<AssetView | null> {
    return request<AssetView | null>({ url: `/fa/assets/${id}`, method: 'get' })
  },
  createAsset(req: AssetRequest): Promise<AssetView> {
    return request<AssetView>({ url: '/fa/assets', method: 'post', data: req })
  },
  updateAsset(id: string, req: AssetRequest): Promise<AssetView> {
    return request<AssetView>({ url: `/fa/assets/${id}`, method: 'put', data: req })
  },
  deleteAsset(id: string): Promise<void> {
    return request<void>({ url: `/fa/assets/${id}`, method: 'delete' })
  },
  disposeAsset(id: string, req?: DisposeRequest): Promise<void> {
    return request<void>({ url: `/fa/assets/${id}/dispose`, method: 'post', data: req })
  },
  scrapAsset(id: string, req?: DisposeRequest): Promise<void> {
    return request<void>({ url: `/fa/assets/${id}/scrap`, method: 'post', data: req })
  },

  // 折旧计提，同公司同期间 CONFIRMED 批次唯一，重复确认 40900
  runDepreciation(companyId: string, period: string): Promise<number> {
    return request<number>({ url: '/fa/depreciation/run', method: 'post', params: { companyId, period } })
  },
  pageDepreciationRuns(q: DepreciationQuery): Promise<PageResult<DepreciationRunView>> {
    return request<PageResult<DepreciationRunView>>({ url: '/fa/depreciation', method: 'get', params: q })
  },
  confirmDepreciation(id: string): Promise<DepreciationRunView> {
    return request<DepreciationRunView>({ url: `/fa/depreciation/${id}/confirm`, method: 'post' })
  },
}
