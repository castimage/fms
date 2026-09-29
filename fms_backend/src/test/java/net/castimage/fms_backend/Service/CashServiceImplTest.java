package net.castimage.fms_backend.Service;

import net.castimage.fms_backend.Utils.UuidV7;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Mapper.AuthFunctionMapper;
import net.castimage.fms_backend.Mapper.BankAccountMapper;
import net.castimage.fms_backend.Mapper.BankStatementLineMapper;
import net.castimage.fms_backend.Mapper.BankStatementMapper;
import net.castimage.fms_backend.Mapper.CashTransactionMapper;
import net.castimage.fms_backend.Mapper.ReconcileRunMapper;
import net.castimage.fms_backend.Model.entity.BankAccount;
import net.castimage.fms_backend.Model.vo.StatementImportPreview;
import net.castimage.fms_backend.Model.vo.StatementLinePreview;
import net.castimage.fms_backend.Service.impl.CashServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 对账单导入解析测试，mock Mapper，不连库，csv/TSV 分隔符识别、表头跳过、
 * 日期双格式（yyyy-MM-dd / yyyy/M/d）、金额/方向校验、坏行 40001 带行号快速失败。
 */
class CashServiceImplTest {

    private static final UUID ACCOUNT_ID = UuidV7.next();

    private BankAccountMapper bankAccountMapper;
    private CashServiceImpl service;

    @BeforeEach
    void setUp() {
        bankAccountMapper = mock(BankAccountMapper.class);
        service = new CashServiceImpl(
                bankAccountMapper, mock(CashTransactionMapper.class),
                mock(BankStatementMapper.class), mock(BankStatementLineMapper.class),
                mock(ReconcileRunMapper.class), mock(AuthFunctionMapper.class),
                mock(VoucherLinkService.class));
        BankAccount acc = mock(BankAccount.class);
        when(acc.getCompanyId()).thenReturn(UuidV7.next());
        when(bankAccountMapper.selectById(ACCOUNT_ID)).thenReturn(acc);
    }

    private MockMultipartFile file(String content) {
        return new MockMultipartFile("file", "stmt.csv", "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void emptyFileThrows40001() {
        assertThrows(BadRequestException.class, () ->
                service.importStatement(new MockMultipartFile("f", new byte[0]), ACCOUNT_ID));
    }

    @Test
    void csvParsesRowFields() {
        MockMultipartFile f = file("2026-08-01,100.00,工资\n2026-08-02,50.50,餐费,OUT\n");
        StatementImportPreview preview = service.importStatement(f, ACCOUNT_ID);
        assertEquals(2, preview.totalLines());
        StatementLinePreview first = preview.rows().getFirst();
        assertEquals(LocalDate.of(2026, 8, 1), first.trxDate());
        assertEquals(new BigDecimal("100.00"), first.amount());
        assertEquals("工资", first.summary());
        assertNull(first.direction());
        assertEquals("OUT", preview.rows().get(1).direction());
    }

    @Test
    void headerLineSkipped() {
        MockMultipartFile f = file("trx_date,amount,summary,direction\n2026-08-01,100.00,工资\n");
        StatementImportPreview preview = service.importStatement(f, ACCOUNT_ID);
        assertEquals(1, preview.totalLines());
    }

    @Test
    void tsvDelimiterDetected() {
        MockMultipartFile f = file("2026-08-01\t100.00\t工资\n");
        StatementImportPreview preview = service.importStatement(f, ACCOUNT_ID);
        assertEquals(1, preview.totalLines());
        assertEquals("工资", preview.rows().getFirst().summary());
    }

    @Test
    void singleZeroDateCompatible() {
        MockMultipartFile f = file("2026/8/1,100.00,工资\n");
        StatementImportPreview preview = service.importStatement(f, ACCOUNT_ID);
        assertEquals(LocalDate.of(2026, 8, 1), preview.rows().getFirst().trxDate());
    }

    @Test
    void badLineFailsFastWithLineNumber() {
        MockMultipartFile f = file("2026-08-01,100.00,工资\nbad-date,50.00,餐费\n");
        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.importStatement(f, ACCOUNT_ID));
        assertEquals(true, ex.getMessage().contains("第 2 行"), ex.getMessage());
    }

    @Test
    void negativeAmountRejected() {
        MockMultipartFile f = file("2026-08-01,-100.00,工资\n");
        assertThrows(BadRequestException.class, () -> service.importStatement(f, ACCOUNT_ID));
    }

    @Test
    void invalidDirectionRejected() {
        MockMultipartFile f = file("2026-08-01,100.00,工资,UP\n");
        assertThrows(BadRequestException.class, () -> service.importStatement(f, ACCOUNT_ID));
    }

    @Test
    void insufficientColumnsRejected() {
        MockMultipartFile f = file("2026-08-01,100.00\n");
        assertThrows(BadRequestException.class, () -> service.importStatement(f, ACCOUNT_ID));
    }
}
