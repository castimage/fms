package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 未来资金预测按日行：arDue 应收到期未核销、apDue 应付到期未核销、netDue = arDue - apDue */
public record DailyForecastRow(LocalDate date, BigDecimal arDue, BigDecimal apDue, BigDecimal netDue) {
}
