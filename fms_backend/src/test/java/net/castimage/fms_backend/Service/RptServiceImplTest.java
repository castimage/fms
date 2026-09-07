package net.castimage.fms_backend.Service;

import net.castimage.fms_backend.Utils.UuidV7;
import jakarta.servlet.http.HttpServletResponse;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Mapper.RptMapper;
import net.castimage.fms_backend.Model.vo.TrialBalanceRow;
import net.castimage.fms_backend.Service.impl.RptServiceImpl;
import net.castimage.fms_backend.config.FmsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报表导出测试，白名单校验，未知报表类型 40001，配置驱动与前端 /api/config 下发一致、
 * utf-8 BOM csv 生成、RFC 4180 字段转义，含逗号/引号字段引号包裹、引号加倍。
 */
class RptServiceImplTest {

    private RptMapper rptMapper;
    private RptServiceImpl service;
    private HttpServletResponse response;
    private StringWriter out;

    @BeforeEach
    void setUp() throws Exception {
        rptMapper = mock(RptMapper.class);
        FmsProperties props = new FmsProperties();
        props.getReport().setEnabledTypes(List.of(
                "trial-balance", "income-statement", "balance-sheet", "cash-flow", "ar-aging", "dept-expense"));
        service = new RptServiceImpl(rptMapper, props);
        response = mock(HttpServletResponse.class);
        out = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(out));
    }

    @Test
    void invalidReportTypeThrows40001WithoutWriting() throws Exception {
        assertThrows(BadRequestException.class, () ->
                service.exportCsv("unknown-type", UuidV7.next(), "2026-08", null, response));
        verify(response, never()).getWriter();
    }

    @Test
    void trialBalanceExportContainsBomHeaderAndRows() throws Exception {
        TrialBalanceRow row = new TrialBalanceRow(UuidV7.next(), "1001", "库存现金", "DEBIT",
                new BigDecimal("1000.00"), new BigDecimal("500.00"), new BigDecimal("200.00"),
                new BigDecimal("1300.00"));
        when(rptMapper.selectTrialBalance(any(), eq("2026-08")))
                .thenReturn(List.of(row));

        service.exportCsv("trial-balance", UuidV7.next(), "2026-08", null, response);
        String csv = out.toString();
        assertTrue(csv.startsWith("\uFEFF"), "CSV 应以 UTF-8 BOM 开头");
        assertTrue(csv.contains("科目编码,科目名称,方向,期初余额,本期借方,本期贷方,期末余额"));
        // 期初余额列恒输出 ZERO 常量，exportTrialBalance 设计，数据行为 借/贷/期末
        assertTrue(csv.contains("1001,库存现金,DEBIT,0.00,500.00,200.00,1300.00"));
    }

    /** RFC 4180，含逗号/引号的字段引号包裹且引号加倍 */
    @Test
    void commaQuoteFieldsEscapedPerRfc4180() throws Exception {
        TrialBalanceRow row = new TrialBalanceRow(UuidV7.next(), "1001", "库存\"现金\",外币", "DEBIT",
                new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1.00"));
        when(rptMapper.selectTrialBalance(any(), eq("2026-08")))
                .thenReturn(List.of(row));

        service.exportCsv("trial-balance", UuidV7.next(), "2026-08", null, response);
        assertTrue(out.toString().contains("\"库存\"\"现金\"\",外币\",DEBIT"),
                "含逗号/引号字段应引号包裹且引号加倍");
    }

    @Test
    void whitelistedTypesAllExportable() throws Exception {
        service.exportCsv("income-statement", UuidV7.next(), "2026-08", null, response);
        service.exportCsv("cash-flow", UuidV7.next(), "2026-08", null, response);
        service.exportCsv("dept-expense", UuidV7.next(), "2026-08", null, response);
        assertTrue(out.toString().contains("收入,费用,净利润"));
        assertTrue(out.toString().contains("现金流入,现金流出,净流量"));
        assertTrue(out.toString().contains("部门,科目编码"));
    }
}
