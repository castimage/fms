/**
 * 总账 api，23 个端点，与 GlController/FxRateController 一一对应，
 * 封装后返回业务数据 T，不泄漏 ApiResponse 壳，错误抛 ApiError。
 */
import { request } from './http'
import { downloadCsv } from './rpt'
import type { PageResult } from '@/types/api'
import type {
  AccountRequest,
  AccountView,
  FxRateRequest,
  FxRateView,
  LedgerView,
  PeriodView,
  TrialBalanceView,
  VoucherCreateRequest,
  VoucherDetailView,
  VoucherGapView,
  VoucherListView,
  VoucherQuery,
} from '@/types/gl'

/** 凭证附件（docs.documents，businessType=VOUCHER），上传/列表/删除 */
export async function uploadVoucherFile(file: File, businessId: string): Promise<string> {
  const form = new FormData()
  form.append('file', file)
  form.append('businessType', 'VOUCHER')
  form.append('businessId', businessId)
  const res = await request<{ id: string }>({ url: '/files/upload', method: 'post', data: form })
  return res.id
}

export interface VoucherDoc {
  id: string
  fileName: string
  fileSize: number
}

export async function listVoucherFiles(businessId: string): Promise<VoucherDoc[]> {
  const res = await request<VoucherDoc[]>({
    url: '/files', method: 'get', params: { businessType: 'VOUCHER', businessId },
  })
  return res ?? []
}

export async function deleteVoucherFile(docId: string): Promise<void> {
  await request<void>({ url: '/files/' + docId, method: 'delete' })
}

/** 附件下载，fetch 带 Bearer 取 blob 落盘，同 downloadCsv 模式 */
export async function downloadVoucherFile(docId: string): Promise<void> {
  const token = localStorage.getItem('fms_access_token')
  const resp = await fetch(`${import.meta.env.VITE_API_BASE_URL ?? '/api'}/files/${docId}/download`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!resp.ok) throw new Error(`附件下载失败（HTTP ${resp.status}）`)
  const blob = await resp.blob()
  const disposition = resp.headers.get('Content-Disposition') ?? ''
  // 优先 filename*（RFC 5987），后端中文文件名用 UTF-8'' 百分号编码；退回普通 filename
  const encoded = disposition.match(/filename\*\s*=\s*UTF-8''([^;]+)/i)?.[1]
  const plain = disposition.match(/filename="?([^";]+)"?/i)?.[1]
  let name = `attach_${docId}.bin`
  if (encoded) {
    try {
      name = decodeURIComponent(encoded)
    } catch {
      name = encoded
    }
  } else if (plain) {
    name = plain
  }
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  URL.revokeObjectURL(url)
}

export const glApi = {
  // 科目
  listAccounts(companyId?: string, keyword?: string): Promise<AccountView[]> {
    return request<AccountView[]>({ url: '/gl/accounts', method: 'get', params: { companyId, keyword } })
  },
  getAccount(id: string): Promise<AccountView | null> {
    return request<AccountView | null>({ url: `/gl/accounts/${id}`, method: 'get' })
  },
  createAccount(req: AccountRequest): Promise<AccountView> {
    return request<AccountView>({ url: '/gl/accounts', method: 'post', data: req })
  },
  updateAccount(id: string, req: AccountRequest): Promise<AccountView> {
    return request<AccountView>({ url: `/gl/accounts/${id}`, method: 'put', data: req })
  },
  deleteAccount(id: string): Promise<void> {
    return request<void>({ url: `/gl/accounts/${id}`, method: 'delete' })
  },

  // 凭证
  pageVouchers(q: VoucherQuery): Promise<PageResult<VoucherListView>> {
    return request<PageResult<VoucherListView>>({ url: '/gl/vouchers', method: 'get', params: q })
  },
  getVoucher(id: string): Promise<VoucherDetailView | null> {
    return request<VoucherDetailView | null>({ url: `/gl/vouchers/${id}`, method: 'get' })
  },
  createVoucher(req: VoucherCreateRequest): Promise<VoucherDetailView> {
    return request<VoucherDetailView>({ url: '/gl/vouchers', method: 'post', data: req })
  },
  updateVoucher(id: string, req: VoucherCreateRequest): Promise<VoucherDetailView> {
    return request<VoucherDetailView>({ url: `/gl/vouchers/${id}`, method: 'put', data: req })
  },
  deleteVoucher(id: string): Promise<void> {
    return request<void>({ url: `/gl/vouchers/${id}`, method: 'delete' })
  },
  submitVoucher(id: string): Promise<VoucherDetailView> {
    return request<VoucherDetailView>({ url: `/gl/vouchers/${id}/submit`, method: 'post' })
  },
  approveVoucher(id: string): Promise<VoucherDetailView> {
    return request<VoucherDetailView>({ url: `/gl/vouchers/${id}/approve`, method: 'post' })
  },
  postVoucher(id: string): Promise<VoucherDetailView> {
    return request<VoucherDetailView>({ url: `/gl/vouchers/${id}/post`, method: 'post' })
  },
  rejectVoucher(id: string, reason: string): Promise<VoucherDetailView> {
    return request<VoucherDetailView>({ url: `/gl/vouchers/${id}/reject`, method: 'post', data: { reason } })
  },
  reverseVoucher(id: string, reason?: string): Promise<VoucherDetailView> {
    return request<VoucherDetailView>({ url: `/gl/vouchers/${id}/reverse`, method: 'post', data: { reason } })
  },

  // 账簿
  ledger(companyId: string, period: string, accountId?: string): Promise<LedgerView[]> {
    return request<LedgerView[]>({
      url: '/gl/ledger',
      method: 'get',
      params: { companyId, period, accountId },
    })
  },
  /** 总账 csv 导出，镜像 rpt 导出，带 Bearer 下载，文件名 ledger_yyyyMMdd.csv */
  exportLedger(companyId: string, period: string, accountId?: string): Promise<void> {
    return downloadCsv('/gl/ledger/export', { companyId, period, accountId })
  },
  trialBalance(companyId: string, period: string): Promise<TrialBalanceView> {
    return request<TrialBalanceView>({
      url: '/gl/trial-balance',
      method: 'get',
      params: { companyId, period },
    })
  },

  // 期间开关 / 期末调汇 / 断号

  /**
   * 凭证断号清单，GET /gl/voucher-gaps，与 cron 断号检查同口径，仅 POSTED/REVERSED
   * 已取号凭证，空数组 = 无断号。rls 收敛，越权空集。
   */
  voucherGaps(companyId: string, period: string): Promise<VoucherGapView[]> {
    return request<VoucherGapView[]>({
      url: '/gl/voucher-gaps',
      method: 'get',
      params: { companyId, period },
    })
  },

  /** 期间状态清单，按公司返回已存在的期间行与 OPEN/CLOSED 状态，供期间管理页回显 */
  listPeriods(companyId: string): Promise<PeriodView[]> {
    return request<PeriodView[]>({
      url: '/gl/periods',
      method: 'get',
      params: { companyId },
    })
  },

  /** 期间结账（OPEN 转 CLOSED），fn_close_period 完整性预检，未完成凭证/断号 40900，reason 仅扩展占位 */
  closePeriod(companyId: string, period: string, reason?: string): Promise<void> {
    return request<void>({
      url: `/gl/periods/${companyId}/${period}/close`,
      method: 'post',
      data: reason ? { reason } : undefined,
    })
  },

  /** 期间反结账，CLOSED 转 OPEN，末位期间，reason 必填，审计留痕 + fms.force_reopen 开关未开 40900 */
  reopenPeriod(companyId: string, period: string, reason: string): Promise<void> {
    return request<void>({
      url: `/gl/periods/${companyId}/${period}/reopen`,
      method: 'post',
      data: { reason },
    })
  },

  /** 期末调汇，手动触发，返回生成的调汇凭证数，幂等防重复，无外币科目/无汇率则 0 */
  runFxAdjustment(companyId: string, period: string): Promise<number> {
    return request<number>({
      url: '/gl/fx-adjustment',
      method: 'post',
      params: { companyId, period },
    })
  },

  // 期末汇率维护

  /** 汇率列表，companyId 必填，period/currency 可选过滤 */
  listFxRates(companyId: string, period?: string, currency?: string): Promise<FxRateView[]> {
    return request<FxRateView[]>({
      url: '/gl/fx-rates',
      method: 'get',
      params: { companyId, period, currency },
    })
  },

  /** 汇率 upsert，同公司×币种×期间已存在则更新，软删行复活，重复提交 = 更新而非冲突 */
  saveFxRate(req: FxRateRequest): Promise<FxRateView> {
    return request<FxRateView>({ url: '/gl/fx-rates', method: 'put', data: req })
  },

  /** 软删汇率，调汇查询按 deleted_at IS NULL 自动排除 */
  deleteFxRate(id: string): Promise<void> {
    return request<void>({ url: `/gl/fx-rates/${id}`, method: 'delete' })
  },
}
