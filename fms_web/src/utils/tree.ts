/**
 * 树工具，科目扁平数组，path ltree 自然序建树，关键词过滤，保留祖先路径，编码规则校验。
 */
import type { AccountView } from '@/types/gl'

/** 科目树节点 = 科目视图 + children */
export interface AccountTreeNode extends AccountView {
  children?: AccountTreeNode[]
}

/** 扁平科目数组建树，按 parentId 挂接，无父节点视为根 */
export function buildAccountTree(list: AccountView[]): AccountTreeNode[] {
  const map = new Map<string, AccountTreeNode>()
  for (const item of list) map.set(item.id, { ...item, children: [] })
  const roots: AccountTreeNode[] = []
  for (const item of list) {
    const node = map.get(item.id)!
    const parent = item.parentId ? map.get(item.parentId) : undefined
    if (parent) parent.children!.push(node)
    else roots.push(node)
  }
  return roots
}

/** 过滤树，命中节点，编码/名称含关键词保留，且其祖先路径整体保留 */
export function filterTree(nodes: AccountTreeNode[], keyword: string): AccountTreeNode[] {
  const kw = keyword.trim().toLowerCase()
  if (!kw) return nodes
  const walk = (node: AccountTreeNode): AccountTreeNode | null => {
    const hitSelf = node.code.toLowerCase().includes(kw) || node.name.toLowerCase().includes(kw)
    const children = (node.children ?? []).map(walk).filter((c): c is AccountTreeNode => c !== null)
    if (hitSelf || children.length) {
      return { ...node, children: children.length ? children : node.children }
    }
    return null
  }
  return nodes.map(walk).filter((n): n is AccountTreeNode => n !== null)
}

/** 科目编码规则校验，ltree 标签，字母/数字/下划线，≤32，与后端 @Pattern 一致 */
export function isValidAccountCode(code: string): boolean {
  return /^[A-Za-z0-9_]{1,32}$/.test(code)
}
