/**
 * 报表 api，/api/rpt，8 端点与 RptController 一一对应，看板聚合 + 五类报表查询 + csv 导出。
 * companyId 仅 ui 提示，rls 决定可见集合，试算平衡复用 types/gl 的 TrialBalanceView。
 */
import { request } from './http'
import type { TrialBalanceView } from '@/types/gl'
import type {
  ArAgingRow,
  BalanceSheetRow,
  CashFlowRow,
  DashboardView,
  DeptExpenseRow,
  IncomeStatementRow,
  ReportType,
} from '@/types/rpt'

/** api 前缀，与 http.ts baseURL 同源，导出端点返回 csv 附件，走原生 fetch 携带 Bearer */
const BASE = import.meta.env.VITE_API_BASE_URL ?? '/api'

/** csv 附件下载，fetch 带 Authorization 取 blob 落 a[download]，window.open 无法带请求头，网关按 Bearer 放行 */
export async function downloadCsv(path: string, params: Record<string, string | undefined>): Promise<void> {
  const token = localStorage.getItem('fms_access_token')
  const qs = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== '') qs.set(k, v)
  })
  const resp = await fetch(`${BASE}${path}${qs.size ? `?${qs.toString()}` : ''}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!resp.ok) throw new Error(`导出失败（HTTP ${resp.status}）`)
  const blob = await resp.blob()
  // 文件名取 Content-Disposition，同源可读，缺失时按当日日期兜底
  const name =
    resp.headers.get('Content-Disposition')?.match(/filename="?([^";]+)"?/)?.[1] ??
    `export_${new Date().toISOString().slice(0, 10).replaceAll('-', '')}.csv`
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  URL.revokeObjectURL(url)
}

export const rptApi = {
  /** date 缺省当日 */
  dashboard(companyId: string, date?: string): Promise<DashboardView> {
    return request<DashboardView>({ url: '/rpt/dashboard', method: 'get', params: { companyId, date } })
  },
  /** 公司+期间必填 */
  trialBalance(companyId: string, period: string): Promise<TrialBalanceView> {
    return request<TrialBalanceView>({ url: '/rpt/trial-balance', method: 'get', params: { companyId, period } })
  },
  incomeStatement(companyId?: string, period?: string): Promise<IncomeStatementRow[]> {
    return request<IncomeStatementRow[]>({ url: '/rpt/income-statement', method: 'get', params: { companyId, period } })
  },
  balanceSheet(companyId?: string, period?: string): Promise<BalanceSheetRow[]> {
    return request<BalanceSheetRow[]>({ url: '/rpt/balance-sheet', method: 'get', params: { companyId, period } })
  },
  cashFlow(companyId?: string, period?: string): Promise<CashFlowRow[]> {
    return request<CashFlowRow[]>({ url: '/rpt/cash-flow', method: 'get', params: { companyId, period } })
  },
  arAging(companyId?: string, period?: string): Promise<ArAgingRow[]> {
    return request<ArAgingRow[]>({ url: '/rpt/ar-aging', method: 'get', params: { companyId, period } })
  },
  /** 公司必填，期间/部门可选 */
  deptExpense(companyId: string, period?: string, deptId?: string): Promise<DeptExpenseRow[]> {
    return request<DeptExpenseRow[]>({ url: '/rpt/dept-expense', method: 'get', params: { companyId, period, deptId } })
  },
  /** csv 导出，report 白名单见 ReportType，文件名 report_yyyyMMdd.csv */
  exportCsv(report: ReportType, opts: { companyId?: string; period?: string; date?: string } = {}): Promise<void> {
    return downloadCsv('/rpt/export/excel', { report, ...opts })
  },
}
