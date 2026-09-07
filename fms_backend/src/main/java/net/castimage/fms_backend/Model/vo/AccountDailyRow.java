package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.util.UUID;

/** 资金日报分账户行（fn_daily_report jsonb.by_account 元素） */
public record AccountDailyRow(UUID bankAccountId, BigDecimal income, BigDecimal expense) {
}
