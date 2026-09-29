package net.castimage.fms_backend.Service.impl;

import jakarta.servlet.http.HttpServletResponse;
import net.castimage.fms_backend.config.FmsProperties;
import net.castimage.fms_backend.Mapper.AuditLogMapper;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.vo.AuditLogView;
import net.castimage.fms_backend.Model.vo.ChainCheckView;
import net.castimage.fms_backend.Service.AuditService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 审计模块业务实现：审计日志查询/哈希链校验/导出全部为只读路径（readOnly 事务）；RLS
 * （auth.has_role('fms_auditor') OR current_user='fms_admin'）+ 授权仅 fms_auditor
 * 授 SELECT/USAGE，其他职责角色查询 42501 映射 40300；哈希链校验委托 PG 函数
 * audit.audit_log_chain_check()（STABLE + SECURITY DEFINER），Java 不做重算（口径同源）。
 */
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditServiceImpl.class);

    /** CSV 导出文件名时间戳 */
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AuditLogMapper auditLogMapper;
    private final FmsProperties fmsProperties;


    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<AuditLogView> pageLogs(long page, long size, UUID actorId, String tableName,
                                             UUID recordId, String action, LocalDateTime from, LocalDateTime to) {
        long pageNum = Math.max(page, 1);
        long sizeNum = Math.clamp(size, 1, 100);
        return PageResult.of(auditLogMapper.selectLogPage(new Page<>(pageNum, sizeNum),
                actorId, trim(tableName), recordId, trim(action), from, to));
    }

    /**
     * {@inheritDoc}
     *
     * 哈希链校验语义：PG 函数自链首（prev='0'×64）按 fn_audit_log_hash 同式逐行重算
     * （ip/request_id 入公式 + occurred_at 时区无关化 to_char AT TIME ZONE 'UTC'），与存储值
     * 比对，首处不符即停——prevOk=false + brokenAt=首断链点（从断点行起全链重算）。
     */
    @Override
    @Transactional(readOnly = true)
    public ChainCheckView chainCheck() {
        return auditLogMapper.selectChainCheck();
    }

    /**
     * {@inheritDoc}
     *
     * 导出为 UTF-8 BOM CSV（Excel 兼容）：表头 12 列（含 prev_hash/row_hash 链字段，
     * 供审计线下核验）；分页游标循环取全量（每页行数 fms.audit.export-page-size，默认 2000）；
     * 字段 RFC 4180 转义。
     */
    @Override
    @Transactional(readOnly = true)
    public void exportCsv(UUID actorId, String tableName, UUID recordId, String action,
                          LocalDateTime from, LocalDateTime to, HttpServletResponse response) {
        // 导出游标每页行数（分页循环取全量，避免单次大结果集；运维级旋钮 fms.audit.export-page-size）
        long exportPageSize = fmsProperties.getAudit().getExportPageSize();
        try {
            response.setContentType("text/csv; charset=UTF-8");
            response.setHeader("Content-Disposition",
                    "attachment; filename=audit_" + LocalDateTime.now().format(FILE_DATE) + ".csv");
            PrintWriter writer = response.getWriter();
            writer.write('\uFEFF'); // UTF-8 BOM：Excel 打开不乱码，
            CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT);
            printer.printRecord("id", "actor_id", "action", "table_name", "record_id",
                    "old_values", "new_values", "ip", "request_id", "occurred_at", "prev_hash", "row_hash");
            long pageNo = 1;
            while (true) {
                IPage<AuditLogView> pg = auditLogMapper.selectLogPage(
                        new Page<>(pageNo, exportPageSize), actorId, trim(tableName), recordId, trim(action), from, to);
                for (AuditLogView r : pg.getRecords()) {
                    printer.printRecord(r.id(), r.actorId(), r.action(), r.tableName(), r.recordId(),
                            r.oldValues(), r.newValues(), r.ip(), r.requestId(), r.occurredAt(),
                            r.prevHash(), r.rowHash());
                }
                if (pg.getRecords().size() < exportPageSize) {
                    break; // 末页退出游标循环，
                }
                pageNo++;
            }
            printer.flush();
            writer.flush();
        } catch (IOException e) {
            throw new net.castimage.fms_backend.Exception.InternalServerException("审计日志导出失败");
        }
    }

    // 私有方法

    private String trim(String s) {
        return s == null ? null : s.trim();
    }

}
