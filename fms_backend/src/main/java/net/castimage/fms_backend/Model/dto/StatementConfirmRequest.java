package net.castimage.fms_backend.Model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import net.castimage.fms_backend.Model.vo.StatementLinePreview;

import java.util.List;
import java.util.UUID;

/**
 * 对账单确认落库请求（导入两阶段第二步）：/statements/import 仅内存解析预览（不落库），
 * 前端核对后携带预览行至此确认——事务内落 bank_statements + 批量 bank_statement_lines，
 * total_lines 回填；company_id 由银行账户推导（RLS WITH CHECK 兜底）。
 */
public record StatementConfirmRequest(
        /** 银行账户 id */
        @NotNull(message = "银行账户必填")
        UUID bankAccountId,
        /** 原始文件名 */
        @NotBlank(message = "文件名必填")
        String filename,
        /** 预览行（与 import 预览口径一致，逐行校验） */
        @NotEmpty(message = "至少需要一行数据")
        List<@Valid StatementLinePreview> rows) {
}
