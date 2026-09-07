package net.castimage.fms_backend.Model.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 公司视图。
 */
public record CompanyView(UUID id, String code, String name, String currency,
                          LocalDate fiscalYearStart, String status, LocalDateTime createdAt) {
}
