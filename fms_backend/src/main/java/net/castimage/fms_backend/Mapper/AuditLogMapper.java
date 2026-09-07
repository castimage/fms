package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.AuditLog;
import net.castimage.fms_backend.Model.vo.AuditLogView;
import net.castimage.fms_backend.Model.vo.ChainCheckView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 审计日志 Mapper（audit.audit_log）：BaseMapper 提供单表查询；分页（多可选过滤）与
 * 哈希链校验（audit.audit_log_chain_check PG 函数，B 类调用）手写。查询仅 fms_auditor
 * 可达（RLS 策略 + 08 授权矩阵），写入封闭（不可变触发器 + 无写策略）。
 */
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    /**
     * 审计日志分页（actorId/tableName/recordId/action/时间范围可选过滤，排序白名单 occurred_at DESC, id DESC）：
     * ip inet 列经 ::text 转字符串映射（实体组件为 String）；jsonb 快照列以文本形式映射。
     */
    @Select("""
            SELECT id, actor_id, action, table_name, record_id,
                   old_values, new_values,
                   ip::text AS ip, request_id, occurred_at, prev_hash, row_hash
              FROM audit.audit_log
             WHERE (#{actorId}::uuid IS NULL OR actor_id = #{actorId}::uuid)
               AND (#{tableName, jdbcType=VARCHAR} IS NULL OR #{tableName, jdbcType=VARCHAR} = ''
                    OR table_name = #{tableName, jdbcType=VARCHAR})
               AND (#{recordId}::uuid IS NULL OR record_id = #{recordId}::uuid)
               AND (#{action, jdbcType=VARCHAR} IS NULL OR #{action, jdbcType=VARCHAR} = ''
                    OR action = #{action, jdbcType=VARCHAR})
               AND (#{from}::timestamptz IS NULL OR occurred_at >= #{from}::timestamptz)
               AND (#{to}::timestamptz IS NULL OR occurred_at <= #{to}::timestamptz)
             ORDER BY occurred_at DESC, id DESC
            """)
    IPage<AuditLogView> selectLogPage(Page<AuditLogView> page,
                                      @Param("actorId") UUID actorId,
                                      @Param("tableName") String tableName,
                                      @Param("recordId") UUID recordId,
                                      @Param("action") String action,
                                      @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to);

    /**
     * 哈希链完整性校验（B 类调 PG 函数）：SELECT 形式调用 audit.audit_log_chain_check()
     * （STABLE + SECURITY DEFINER，fms_auditor 已授 EXECUTE）——自链首逐行重算比对，
     * 返回 {prev_ok, broken_at, detail}；broken_at NULL = 链完好，非 NULL = 首断链点。
     */
    @Select("""
            SELECT prev_ok, broken_at, detail
              FROM audit.audit_log_chain_check()
            """)
    ChainCheckView selectChainCheck(); // 篡改任何一行（含连带伪造 prev_hash）即被检出，
}
