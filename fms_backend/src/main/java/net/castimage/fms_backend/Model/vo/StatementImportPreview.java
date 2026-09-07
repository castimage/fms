package net.castimage.fms_backend.Model.vo;

import java.util.List;
import java.util.UUID;

/**
 * 对账单导入预览结果（两阶段第一步，不落库）：内存解析后的行预览 + 文件信息，
 * 前端核对后经 /statements/confirm 携带 rows 事务落库。
 */
public record StatementImportPreview(UUID bankAccountId, String filename, int totalLines,
                                     List<StatementLinePreview> rows) {
}
