/**
 * 格式化工具，日期/期间/金额千分位，负值红色由 .num-negative class 标红，见 styles/index.css。
 */
import dayjs from 'dayjs'

const DASH = '—'

/** 日期格式化为 YYYY-MM-DD，空值显示 — */
export function formatDate(v?: string | null): string {
  if (!v) return DASH
  const d = dayjs(v)
  return d.isValid() ? d.format('YYYY-MM-DD') : DASH
}

/** 日期时间格式化为 YYYY-MM-DD HH:mm，空值显示 — */
export function formatDateTime(v?: string | null): string {
  if (!v) return DASH
  const d = dayjs(v)
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm') : DASH
}

/** 会计期间原样展示（YYYY-MM） */
export function formatPeriod(v?: string | null): string {
  return v || DASH
}

/** 金额千分位 + 两位小数，负数前置 -，空值显示 — */
export function formatAmount(v: string | number | null | undefined): string {
  if (v === null || v === undefined || v === '') return DASH
  const n = Number(v)
  if (Number.isNaN(n)) return DASH
  const sign = n < 0 ? '-' : ''
  const abs = Math.abs(n)
  const [intPart, fracPart = ''] = abs.toFixed(2).split('.')
  const grouped = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return `${sign}${grouped}.${fracPart}`
}

/** 数字转为纯两位小数元字符串，金额输入回填用，去掉千分位 */
export function toPlainAmount(v: string | number | null | undefined): string {
  if (v === null || v === undefined || v === '') return ''
  const n = Number(v)
  return Number.isNaN(n) ? '' : n.toFixed(2)
}
