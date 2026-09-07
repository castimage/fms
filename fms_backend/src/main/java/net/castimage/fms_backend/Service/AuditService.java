package net.castimage.fms_backend.Service;

import jakarta.servlet.http.HttpServletResponse;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.vo.AuditLogView;
import net.castimage.fms_backend.Model.vo.ChainCheckView;

import java.time.LocalDateTime;
import java.util.UUID;

/** 审计模块业务接口（audit）：审计日志只读查询/分页、哈希链完整性校验（调 audit.audit_log_chain_check PG 函数）、CSV 导出；
 *  RLS 仅 fms_auditor 可读（其他角色 42501 映射 40300），全部读路径 readOnly 事务 */
public interface AuditService {

    /**
     * 审计日志分页（actorId/tableName/recordId/action/时间范围可选过滤；时间参数为 Asia/Shanghai 本地时间）。
     */
    PageResult<AuditLogView> pageLogs(long page, long size, UUID actorId, String tableName,
                                      UUID recordId, String action, LocalDateTime from, LocalDateTime to);

    /**
     * 哈希链完整性校验：调 PG 函数 audit.audit_log_chain_check()（与触发器同式重算），
     * prevOk=false 时返回首断链点与详情 JSONB（修复建议：从断点行起全链重算）。
     */
    ChainCheckView chainCheck();

    /**
     * 审计日志 CSV 导出（UTF-8 BOM + Content-Disposition attachment，文件名 audit_yyyyMMdd.csv）：
     * 同分页过滤条件，分页游标循环取全量（每页行数 fms.audit.export-page-size，默认 2000）。
     */
    void exportCsv(UUID actorId, String tableName, UUID recordId, String action,
                   LocalDateTime from, LocalDateTime to, HttpServletResponse response);
}
