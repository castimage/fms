/**
 * 前端 ui 配置类型，GET /api/config 下发，与后端 Model/vo/UiConfigView 逐字段一致，
 * 密码长度边界/附件扩展名白名单/分页每页条数选项/报表导出白名单，
 * 后端 yaml（fms.*）为单一事实源，前端仅做快速校验与选项渲染，权威裁决恒在后端。
 */
export interface UiConfig {
  /** 密码最小长度（fms.security.password-min-length，后端 Service max(配置,8) 权威兜底） */
  passwordMinLength: number
  /** 密码最大长度，fms.security.password-max-length，与 dto @Size 对齐 */
  passwordMaxLength: number
  /** 附件扩展名白名单，fms.file.allowed-extensions，小写不含点，对账单导入 accept 同源 */
  allowedExtensions: string[]
  /** 列表分页每页条数选项（fms.ui.page-sizes） */
  pageSizes: number[]
  /** 报表导出白名单，fms.report.enabled-types，后端未知值 40001 */
  enabledReportTypes: string[]
  /** 参考数据下拉拉取上限，um.params web.refdata.page.size 可配，缺省 200，公司/供应商/客户/部门/银行账户等下拉统一消费 */
  refdataPageSize: number
}
