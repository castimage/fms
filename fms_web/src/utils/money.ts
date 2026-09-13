/**
 * 金额工具，金额计算/校验走"分"整数路径杜绝浮点误差，元与分互转，入参统一 string，toFen 校验 2 位小数。
 */

const AMOUNT_RE = /^\d+(\.\d{1,2})?$/

/** 元字符串/数字转分，整数。非法格式/空值返回 null */
export function toFen(value: string | number | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null
  const s = String(value).trim()
  if (!AMOUNT_RE.test(s)) return null
  const [intPart, frac = ''] = s.split('.')
  return Number(intPart) * 100 + Number(frac.padEnd(2, '0'))
}

/** 元转分，非法/空值按 0 处理，供合计累加 */
export function toFenOrZero(value: string | number | null | undefined): number {
  return toFen(value) ?? 0
}

/** 分整数转元字符串，两位小数，负数带 - 前缀 */
export function fromFen(fen: number): string {
  const sign = fen < 0 ? '-' : ''
  const abs = Math.abs(Math.round(fen))
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`
}
