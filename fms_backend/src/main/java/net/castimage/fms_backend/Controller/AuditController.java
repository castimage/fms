package net.castimage.fms_backend.Controller;

import jakarta.servlet.http.HttpServletResponse;
import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.vo.AuditLogView;
import net.castimage.fms_backend.Model.vo.ChainCheckView;
import net.castimage.fms_backend.Service.AuditService;
import net.castimage.fms_backend.Utils.RequestContext;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 审计模块 REST 接口（/api/audit）：审计日志查询（分页/多过滤）、哈希链完整性校验、
 * CSV 导出。API 层 requireAuditRole（auditor/admin）闸口 + PG 层授权（audit schema
 * 仅 fms_auditor/fms_operator·is_admin 收敛）双层防御，全部只读路径。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;


    /**
     * 审计日志分页（actorId/tableName/recordId/action/时间范围可选；from/to 为 ISO 日期时间）。
     */
    @GetMapping("/logs")
    public ApiResponse<PageResult<AuditLogView>> pageLogs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String tableName,
            @RequestParam(required = false) UUID recordId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        RequestContext.requireAuditRole();
        return ApiResponse.ok(auditService.pageLogs(page, size, actorId, tableName, recordId, action, from, to));
    }

    /**
     * 哈希链完整性校验：调 audit.audit_log_chain_check() PG 函数（与写入触发器同式重算），
     * prevOk=false 时返回首断链点 brokenAt 与断链详情 detail。
     */
    @GetMapping("/chain-check")
    public ApiResponse<ChainCheckView> chainCheck() {
        RequestContext.requireAuditRole();
        return ApiResponse.ok(auditService.chainCheck());
    }

    /**
     * 审计日志 CSV 导出（同分页过滤条件全量；UTF-8 BOM + attachment，文件名 audit_yyyyMMdd.csv）。
     */
    @GetMapping("/export/csv")
    public void exportCsv(@RequestParam(required = false) UUID actorId,
                          @RequestParam(required = false) String tableName,
                          @RequestParam(required = false) UUID recordId,
                          @RequestParam(required = false) String action,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                          HttpServletResponse response) {
        RequestContext.requireAuditRole();
        auditService.exportCsv(actorId, tableName, recordId, action, from, to, response);
    }
}
