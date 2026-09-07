/**
 * 纯 TS 极简 QR 码生成器，无第三方依赖。
 * 覆盖 byte 模式 + 纠错等级 M + 版本 1~9，数据容量 14~180 字节，otpauth uri 足够。
 * 输出 boolean 矩阵，true=深色模块，由 QrCode.vue 绘制到 canvas。
 */

/** 版本参数表，版本映射对齐图案位置，版本 1-9 */
const ALIGN_POS: number[][] = [
  [], // v1
  [6, 18],
  [6, 22],
  [6, 26],
  [6, 30],
  [6, 34],
  [6, 22, 38],
  [6, 24, 42],
  [6, 26, 46],
]

/**
 * 纠错等级 M 的块结构，版本映射 [总码字数, 每块纠错码字数, 组1块数, 组1数据码字数, 组2块数, 组2数据码字数]
 */
const EC_TABLE: Record<number, [number, number, number, number, number, number]> = {
  1: [26, 10, 1, 16, 0, 0],
  2: [44, 16, 1, 28, 0, 0],
  3: [70, 26, 1, 44, 0, 0],
  4: [100, 18, 2, 32, 0, 0],
  5: [134, 24, 2, 43, 0, 0],
  6: [172, 16, 4, 27, 0, 0],
  7: [196, 18, 4, 31, 0, 0],
  8: [242, 22, 2, 38, 2, 39],
  9: [292, 22, 3, 36, 2, 37],
}

/** byte 模式各版本最大数据字节数，M 级 */
const CAPACITY: number[] = [0, 14, 26, 42, 62, 84, 106, 122, 152, 180]

/** GF，256 本原多项式 0x11d 的对数/反对数表 */
const GF_EXP = new Uint8Array(512)
const GF_LOG = new Uint8Array(256)
;(() => {
  let x = 1
  for (let i = 0; i < 255; i++) {
    GF_EXP[i] = x
    GF_LOG[x] = i
    x <<= 1
    if (x & 0x100) x ^= 0x11d
  }
  for (let i = 255; i < 512; i++) GF_EXP[i] = GF_EXP[i - 255]
})()

/** 多项式乘法（GF，256） */
function gfMul(a: number, b: number): number {
  if (a === 0 || b === 0) return 0
  return GF_EXP[GF_LOG[a] + GF_LOG[b]]
}

/** Reed-Solomon 纠错，数据码字生成纠错码字，多项式除法余数 */
function rsEncode(data: number[], ecCount: number): number[] {
  // 生成多项式 g(x) = ∏(x + α^i)，i=0..ecCount-1
  const gen = [1]
  for (let i = 0; i < ecCount; i++) {
    const next = new Array<number>(gen.length + 1).fill(0)
    const root = GF_EXP[i]
    for (let j = 0; j < gen.length; j++) {
      next[j] ^= gfMul(gen[j], root) // x^j 系数 = r·gen[j]
      next[j + 1] ^= gen[j] // x^(j+1) 系数 = gen[j]
    }
    gen.splice(0, gen.length, ...next)
  }
  // 多项式除法，data·x^ecCount 对 gen 取余
  const rem = new Array<number>(ecCount).fill(0)
  for (const b of data) {
    const factor = b ^ (rem.shift() ?? 0)
    rem.push(0)
    if (factor !== 0) {
      for (let i = 0; i < ecCount; i++) {
        rem[i] ^= gfMul(gen[i + 1], factor)
      }
    }
  }
  return rem
}

/** 数据码字组装全部码字，数据 + 纠错，块间交错排列 */
function interleave(dataCodewords: number[], version: number): number[] {
  const [, ecCount, g1Blocks, g1Data, g2Blocks, g2Data] = EC_TABLE[version]
  const blocks: { data: number[]; ec: number[] }[] = []
  let offset = 0
  for (let i = 0; i < g1Blocks; i++) {
    const d = dataCodewords.slice(offset, offset + g1Data)
    offset += g1Data
    blocks.push({ data: d, ec: rsEncode(d, ecCount) })
  }
  for (let i = 0; i < g2Blocks; i++) {
    const d = dataCodewords.slice(offset, offset + g2Data)
    offset += g2Data
    blocks.push({ data: d, ec: rsEncode(d, ecCount) })
  }
  const result: number[] = []
  const maxData = Math.max(...blocks.map((b) => b.data.length))
  for (let i = 0; i < maxData; i++) {
    for (const b of blocks) if (i < b.data.length) result.push(b.data[i])
  }
  for (let i = 0; i < ecCount; i++) {
    for (const b of blocks) result.push(b.ec[i])
  }
  return result
}

/** 取数值第 i 位，LSB 为 0 */
function getBit(x: number, i: number): boolean {
  return ((x >>> i) & 1) === 1
}

/**
 * BCH(15,5) 格式信息，纠错等级 M，=00 + 掩码号，生成多项式 0x537，异或 0x5412 掩码。
 * 已知常量，M0=0x5412，自校验基准
 */
function formatBits(mask: number): number {
  const data = (0b00 << 3) | mask
  let rem = data
  for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537)
  return ((data << 10) | rem) ^ 0x5412
}

/** BCH(18,6) 版本信息，版本 ≥ 7 使用，多项式 0x1F25，v7 基准值 0x07C94 */
function versionBits(version: number): number {
  let rem = version << 12
  for (let i = 17; i >= 12; i--) {
    if (rem & (1 << i)) rem ^= 0x1f25 << (i - 12)
  }
  return (version << 12) | rem
}

/** 掩码 0-7 */
function maskBit(mask: number, x: number, y: number): boolean {
  switch (mask) {
    case 0:
      return (x + y) % 2 === 0
    case 1:
      return y % 2 === 0
    case 2:
      return x % 3 === 0
    case 3:
      return (x + y) % 3 === 0
    case 4:
      return (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0
    case 5:
      return ((x * y) % 2) + ((x * y) % 3) === 0
    case 6:
      return (((x * y) % 2) + ((x * y) % 3)) % 2 === 0
    default:
      return (((x + y) % 2) + ((x * y) % 3)) % 2 === 0
  }
}

/** 掩码评价，4 条规则，仅影响观感不影响解码 */
function penaltyScore(m: boolean[][]): number {
  const size = m.length
  let score = 0
  // 规则 1，连续同色 ≥5 的线段
  for (let y = 0; y < size; y++) {
    let run = 1
    for (let x = 1; x <= size; x++) {
      if (x < size && m[y][x] === m[y][x - 1]) run++
      else {
        if (run >= 5) score += 3 + (run - 5)
        run = 1
      }
    }
  }
  for (let x = 0; x < size; x++) {
    let run = 1
    for (let y = 1; y <= size; y++) {
      if (y < size && m[y][x] === m[y - 1][x]) run++
      else {
        if (run >= 5) score += 3 + (run - 5)
        run = 1
      }
    }
  }
  // 规则 2，2x2 同色块
  for (let y = 0; y < size - 1; y++) {
    for (let x = 0; x < size - 1; x++) {
      const c = m[y][x]
      if (m[y][x + 1] === c && m[y + 1][x] === c && m[y + 1][x + 1] === c) score += 3
    }
  }
  // 规则 3，1011101 / 0101110 模式
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size - 6; x++) {
      const pat = m[y].slice(x, x + 7).map((v) => (v ? '1' : '0')).join('')
      if (pat === '1011101' || pat === '0101110') score += 40
    }
  }
  for (let x = 0; x < size; x++) {
    for (let y = 0; y < size - 6; y++) {
      let pat = ''
      for (let i = 0; i < 7; i++) pat += m[y + i][x] ? '1' : '0'
      if (pat === '1011101' || pat === '0101110') score += 40
    }
  }
  // 规则 4，深色比例偏差
  let dark = 0
  for (const row of m) for (const c of row) if (c) dark++
  const percent = (dark * 100) / (size * size)
  score += Math.floor(Math.abs(percent - 50) / 5) * 10
  return score
}

/**
 * 绘制定位图案（中心 (row+3, col+3) 的 7x7 finder + 1 圈浅色分隔带），
 * 环距相对中心，ring∈{0,1,3} 深、{2,4} 浅
 */
function drawFinder(m: boolean[][], row: number, col: number) {
  for (let dy = -1; dy <= 7; dy++) {
    for (let dx = -1; dx <= 7; dx++) {
      const y = row + dy
      const x = col + dx
      if (y < 0 || y >= m.length || x < 0 || x >= m[0].length) continue
      const ring = Math.max(Math.abs(dy - 3), Math.abs(dx - 3))
      m[y][x] = ring === 3 || ring <= 1
    }
  }
}

/** 功能模块判定，定位/定时/对齐/格式/版本/暗模块 */
function isFunctionModule(x: number, y: number, size: number): boolean {
  if (y < 9 && x < 9) return true // 左上定位 + 格式副本
  if (y < 9 && x >= size - 8) return true // 右上定位 + 格式横排
  if (y >= size - 8 && x < 9) return true // 左下定位 + 格式竖排 + 暗模块
  if (x === 6 || y === 6) return true // 定时图案
  const version = (size - 17) / 4
  if (version >= 7 && ((y < 6 && x >= size - 11) || (x < 6 && y >= size - 11))) return true // 版本信息
  const positions = ALIGN_POS[version - 1]
  for (const cy of positions) {
    for (const cx of positions) {
      if ((cy === 6 && cx === 6) || (cy === 6 && cx === size - 7) || (cy === size - 7 && cx === 6)) continue
      if (Math.abs(y - cy) <= 2 && Math.abs(x - cx) <= 2) return true
    }
  }
  return false
}

/**
 * 绘制格式信息两副本，15bit，bit 位序 LSB=0，与 ISO 规格一致，
 * 副本1 左上（col8 行0-5 对应 bit0-5，bit6@(7,8)，bit7@(8,8)，bit8@(8,7)，row8 col5-0 对应 bit9-14）
 * 副本2，row8 col size-1..size-8 对应 bit0-7，col8 行 size-7..size-1 对应 bit8-14，暗模块恒深
 */
function drawFormatInfo(m: boolean[][], bits: number, size: number) {
  for (let i = 0; i <= 5; i++) m[i][8] = getBit(bits, i)
  m[7][8] = getBit(bits, 6)
  m[8][8] = getBit(bits, 7)
  m[8][7] = getBit(bits, 8)
  for (let i = 9; i < 15; i++) m[8][14 - i] = getBit(bits, i)
  for (let i = 0; i < 8; i++) m[8][size - 1 - i] = getBit(bits, i)
  for (let i = 8; i < 15; i++) m[size - 15 + i][8] = getBit(bits, i)
  m[size - 8][8] = true // 暗模块
}

/** 绘制版本信息，版本 ≥ 7，两处镜像 */
function drawVersionInfo(m: boolean[][], version: number, size: number) {
  const vb = versionBits(version)
  for (let i = 0; i < 18; i++) {
    const bit = getBit(vb, i)
    const a = size - 11 + (i % 3)
    const b = Math.floor(i / 3)
    m[b][a] = bit
    m[a][b] = bit
  }
}

/** 生成 QR 码矩阵，byte 模式 / 等级 M / 版本自动 1-9 */
export function generateQrMatrix(text: string): boolean[][] {
  // 字节编码（utf-8）
  const bytes = new TextEncoder().encode(text)
  let version = 1
  while (version < 9 && bytes.length > CAPACITY[version]) version++
  if (bytes.length > CAPACITY[version]) {
    throw new Error(`内容过长（${bytes.length} 字节，QR M 级上限 180 字节）`)
  }

  // 数据码字，模式，0100 + 8bit 长度 + 数据 + 终止符 + 补齐字节 0xEC/0x11
  const totalCodewords = EC_TABLE[version][0]
  const dataBits: number[] = [0, 1, 0, 0]
  for (let i = 7; i >= 0; i--) dataBits.push((bytes.length >> i) & 1)
  for (const b of bytes) for (let i = 7; i >= 0; i--) dataBits.push((b >> i) & 1)
  const capacityBits = totalCodewords * 8
  if (dataBits.length < capacityBits - 4) dataBits.push(0, 0, 0, 0)
  while (dataBits.length % 8 !== 0) dataBits.push(0)
  while (dataBits.length < capacityBits) {
    const pad = (dataBits.length / 8) % 2 === 0 ? 0xec : 0x11
    for (let i = 7; i >= 0; i--) dataBits.push((pad >> i) & 1)
  }
  const dataCodewords: number[] = []
  for (let i = 0; i < dataBits.length; i += 8) {
    let v = 0
    for (let j = 0; j < 8; j++) v = (v << 1) | dataBits[i + j]
    dataCodewords.push(v)
  }
  const allCodewords = interleave(dataCodewords, version)

  // 矩阵骨架，定位图案 + 定时 + 暗模块 + 对齐图案 + 版本信息
  const size = 17 + version * 4
  const m: boolean[][] = Array.from({ length: size }, () => new Array<boolean>(size).fill(false))
  drawFinder(m, 0, 0)
  drawFinder(m, 0, size - 7)
  drawFinder(m, size - 7, 0)
  for (let i = 8; i < size - 8; i++) {
    m[6][i] = i % 2 === 0
    m[i][6] = i % 2 === 0
  }
  m[size - 8][8] = true
  const positions = ALIGN_POS[version - 1]
  for (const cy of positions) {
    for (const cx of positions) {
      if ((cy === 6 && cx === 6) || (cy === 6 && cx === size - 7) || (cy === size - 7 && cx === 6)) continue
      for (let dy = -2; dy <= 2; dy++) {
        for (let dx = -2; dx <= 2; dx++) {
          const ring = Math.max(Math.abs(dy), Math.abs(dx))
          m[cy + dy][cx + dx] = ring !== 1 && ring !== 2 // 5x5 深 / 3x3 浅 / 中心深
        }
      }
    }
  }
  if (version >= 7) drawVersionInfo(m, version, size)

  // 数据填充，右下向上、双列对，跳功能模块
  const bitStream: number[] = []
  for (const cw of allCodewords) for (let i = 7; i >= 0; i--) bitStream.push((cw >> i) & 1)
  let bitIdx = 0
  let dir = -1 // 自底向上
  for (let col = size - 1; col > 0; col -= 2) {
    if (col === 6) col-- // 跳过垂直定时列
    const xRight = col
    const xLeft = col - 1
    for (let row = dir === -1 ? size - 1 : 0; row >= 0 && row < size; row += dir) {
      for (const x of [xRight, xLeft]) {
        if (isFunctionModule(x, row, size)) continue
        m[row][x] = bitIdx < bitStream.length ? bitStream[bitIdx] === 1 : false
        bitIdx++
      }
    }
    dir = -dir
  }

  // 8 种掩码评分选最优，应用掩码 + 格式信息
  let bestMask = 0
  let bestScore = Infinity
  for (let mask = 0; mask < 8; mask++) {
    const copy = m.map((row) => row.slice())
    for (let y = 0; y < size; y++) {
      for (let x = 0; x < size; x++) {
        if (!isFunctionModule(x, y, size) && maskBit(mask, x, y)) copy[y][x] = !copy[y][x]
      }
    }
    drawFormatInfo(copy, formatBits(mask), size)
    if (version >= 7) drawVersionInfo(copy, version, size)
    const score = penaltyScore(copy)
    if (score < bestScore) {
      bestScore = score
      bestMask = mask
    }
  }
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      if (!isFunctionModule(x, y, size) && maskBit(bestMask, x, y)) m[y][x] = !m[y][x]
    }
  }
  drawFormatInfo(m, formatBits(bestMask), size)
  if (version >= 7) drawVersionInfo(m, version, size)
  return m
}
