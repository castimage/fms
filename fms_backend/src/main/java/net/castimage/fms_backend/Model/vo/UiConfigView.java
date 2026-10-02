package net.castimage.fms_backend.Model.vo;

import java.util.List;

/**
 * 前端 UI 配置下发视图（GET /api/config，任意已认证角色可读）：收敛前端硬编码值——
 * 密码长度边界（fms.security.password-min/max-length，后端权威兜底、前端仅快速校验）、
 * 附件扩展名白名单（fms.file.allowed-extensions，对账单导入 accept 同源）、分页每页条数选项
 * （fms.ui.page-sizes）、报表导出白名单（fms.report.enabled-types，未知值 40001 前端防漂移）、
 * 参考数据下拉拉取上限（refdataPageSize，运行期读 um.params web.refdata.page.size，缺省 200）。
 * 非敏感运维值，不含密钥/令牌等安全参数（安全边界常量仍由后端硬编码持有，铁律）。
 */
public record UiConfigView(int passwordMinLength, int passwordMaxLength,
                           List<String> allowedExtensions,
                           List<Integer> pageSizes,
                           List<String> enabledReportTypes,
                           int refdataPageSize) {
}
