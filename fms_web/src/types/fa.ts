/**
 * 固定资产类型，与后端 Model/vo（AssetView/DepreciationRunView）及 dto（AssetRequest/DisposeRequest）逐字段一致。
 * 金额为后端 BigDecimal（json number），展示经 MoneyText / formatAmount。
 */
import type { PageQuery } from './api'

/** 资产状态，计提只纳入 ACTIVE/DEPRECIATING，处置/报废后不可变触发器终审禁改禁删 */
export type AssetStatus = 'ACTIVE' | 'DEPRECIATING' | 'DISPOSED' | 'SCRAPPED'
export type DepreciationMethod = 'STRAIGHT_LINE' | 'DOUBLE_DECLINING'
export type DepreciationRunStatus = 'DRAFT' | 'CONFIRMED'

/** 固定资产卡片视图，netValue 为后端生成列 原值-累计折旧，只读 */
export interface AssetView {
  id: string
  companyId: string
  assetCode: string
  category: string
  originalValue: number
  residualValue: number
  lifeMonths: number
  depreciationMethod: DepreciationMethod
  accumulatedDepreciation: number
  netValue: number
  startDate: string | null
  status: AssetStatus
  disposalDate: string | null
  createdAt: string
}

/** 资产创建/更新请求，更新仅非敏感字段，originalValue 开始计提后冻结 */
export interface AssetRequest {
  /** 所属公司，创建可缺省，后端从会话 scope 推导，多公司用户须显式指定 */
  companyId?: string
  assetCode: string
  category: string
  originalValue: number
  residualValue?: number
  lifeMonths: number
  depreciationMethod?: DepreciationMethod
  startDate?: string
}

/** 资产分页查询，keyword 命中 asset_code/category，rls 越权空集 */
export interface AssetQuery extends PageQuery {
  keyword?: string
  status?: AssetStatus | ''
}

/** 处置/报废请求，disposeDate 缺省当日，reason 仅日志不落库 */
export interface DisposeRequest {
  disposeDate?: string
  reason?: string
}

/** 折旧计提明细行，批次 lines jsonb 元素 */
export interface DepreciationLine {
  assetId: string
  assetCode: string
  category: string
  amount: number
  method: DepreciationMethod
}

/** 折旧计提批次视图，同公司同期间仅一条 CONFIRMED，uk_depr_run 部分唯一索引 */
export interface DepreciationRunView {
  id: string
  companyId: string
  period: string
  runBy: string
  status: DepreciationRunStatus
  lines: DepreciationLine[]
  createdAt: string
  updatedAt: string
}

/** 计提批次分页查询 */
export interface DepreciationQuery extends PageQuery {
  companyId?: string
  period?: string
}
