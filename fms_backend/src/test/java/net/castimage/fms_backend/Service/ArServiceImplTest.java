package net.castimage.fms_backend.Service;

import net.castimage.fms_backend.Utils.UuidV7;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Exception.ConflictException;
import net.castimage.fms_backend.Exception.NotFoundException;
import net.castimage.fms_backend.Mapper.ArInvoiceMapper;
import net.castimage.fms_backend.Mapper.AuthFunctionMapper;
import net.castimage.fms_backend.Mapper.BadDebtProvisionMapper;
import net.castimage.fms_backend.Mapper.CustomerMapper;
import net.castimage.fms_backend.Mapper.ReceiptAllocationMapper;
import net.castimage.fms_backend.Mapper.ReceiptMapper;
import net.castimage.fms_backend.Model.dto.AllocateRequest;
import net.castimage.fms_backend.Model.entity.ArInvoice;
import net.castimage.fms_backend.Model.entity.Receipt;
import net.castimage.fms_backend.Model.vo.AllocationView;
import net.castimage.fms_backend.Service.impl.ArServiceImpl;
import net.castimage.fms_backend.Utils.JwtRole;
import net.castimage.fms_backend.Utils.RequestContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 应收核销业务规则测试，mock Mapper，不连库，核销入口 allocate 的完整防护链，
 * 状态预检（收款单 RECEIVED/发票 ISSUED·PARTIAL）、重复核销防重，同对同金额 40900、
 * 发票/收款单双向超分拦截、乐观锁合并推进与状态迁移（PAID/ALLOCATED）。
 */
class ArServiceImplTest {

    private static final UUID RECEIPT_ID = UuidV7.next();
    private static final UUID INVOICE_ID = UuidV7.next();
    private static final UUID COMPANY_ID = UuidV7.next();

    private ReceiptMapper receiptMapper;
    private ArInvoiceMapper arInvoiceMapper;
    private ReceiptAllocationMapper receiptAllocationMapper;
    private ArServiceImpl service;

    /** LambdaUpdateWrapper 依赖 TableInfo lambda 缓存，Spring 环境由启动装配注册，单测须手动初始化 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ArInvoice.class);
        TableInfoHelper.initTableInfo(assistant, Receipt.class);
        TableInfoHelper.initTableInfo(assistant, net.castimage.fms_backend.Model.entity.ReceiptAllocation.class);
    }

    @BeforeEach
    void setUp() {
        receiptMapper = mock(ReceiptMapper.class);
        arInvoiceMapper = mock(ArInvoiceMapper.class);
        receiptAllocationMapper = mock(ReceiptAllocationMapper.class);
        service = new ArServiceImpl(
                mock(CustomerMapper.class), arInvoiceMapper, receiptMapper, receiptAllocationMapper,
                mock(BadDebtProvisionMapper.class), mock(AuthFunctionMapper.class),
                mock(VoucherLinkService.class));
        RequestContext.set(new RequestContext(UuidV7.next(), JwtRole.OPERATOR, null, null, "t"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private Receipt receipt(String status, String amount) {
        Receipt r = mock(Receipt.class);
        when(r.getStatus()).thenReturn(status);
        when(r.getAmount()).thenReturn(new BigDecimal(amount));
        return r;
    }

    private ArInvoice invoice(String status, String total) {
        ArInvoice inv = mock(ArInvoice.class);
        when(inv.getId()).thenReturn(INVOICE_ID);
        when(inv.getStatus()).thenReturn(status);
        when(inv.getTotal()).thenReturn(new BigDecimal(total));
        when(inv.getInvoiceNo()).thenReturn("AR-2026-001");
        when(inv.getInvoiceDate()).thenReturn(LocalDate.now());
        when(inv.getCompanyId()).thenReturn(COMPANY_ID);
        return inv;
    }

    private AllocateRequest req(String amount) {
        return new AllocateRequest(RECEIPT_ID, INVOICE_ID, new BigDecimal(amount));
    }

    private void stubBaseline() {
        Receipt r = receipt("RECEIVED", "1000.00");
        ArInvoice inv = invoice("ISSUED", "500.00");
        when(receiptMapper.selectByIdForUpdate(RECEIPT_ID)).thenReturn(r);
        when(arInvoiceMapper.selectById(INVOICE_ID)).thenReturn(inv);
        when(receiptAllocationMapper.selectByReceiptId(RECEIPT_ID)).thenReturn(List.of());
        when(receiptAllocationMapper.selectAllocatedSumByInvoice(INVOICE_ID)).thenReturn(BigDecimal.ZERO);
        when(receiptAllocationMapper.selectAllocatedSumByReceipt(RECEIPT_ID)).thenReturn(BigDecimal.ZERO);
        when(arInvoiceMapper.update(any(), any())).thenReturn(1);
        when(receiptMapper.update(any(), any())).thenReturn(1);
    }

    @Test
    void allocateThrows40400WhenReceiptMissing() {
        when(receiptMapper.selectByIdForUpdate(RECEIPT_ID)).thenReturn(null);
        assertThrows(NotFoundException.class, () -> service.allocate(RECEIPT_ID, req("100.00")));
    }

    @Test
    void allocateThrows40900WhenReceiptNotReceived() {
        Receipt r = receipt("ALLOCATED", "1000.00");
        ArInvoice inv = invoice("ISSUED", "500.00");
        when(receiptMapper.selectByIdForUpdate(RECEIPT_ID)).thenReturn(r);
        when(arInvoiceMapper.selectById(INVOICE_ID)).thenReturn(inv);
        assertThrows(ConflictException.class, () -> service.allocate(RECEIPT_ID, req("100.00")));
    }

    @Test
    void allocateThrows40900WhenInvoiceNotAllocable() {
        Receipt r = receipt("RECEIVED", "1000.00");
        ArInvoice inv = invoice("PAID", "500.00");
        when(receiptMapper.selectByIdForUpdate(RECEIPT_ID)).thenReturn(r);
        when(arInvoiceMapper.selectById(INVOICE_ID)).thenReturn(inv);
        assertThrows(ConflictException.class, () -> service.allocate(RECEIPT_ID, req("100.00")));
    }

    @Test
    void allocateThrows40001WhenAmountNonPositive() {
        stubBaseline();
        assertThrows(BadRequestException.class, () -> service.allocate(RECEIPT_ID, req("0.00")));
        assertThrows(BadRequestException.class, () -> service.allocate(RECEIPT_ID, req("-10.00")));
    }

    /** 同对同金额重复提交返回 40900，核销不二次生效 */
    @Test
    void allocateThrows40900OnDuplicateAllocation() {
        stubBaseline();
        when(receiptAllocationMapper.selectByReceiptId(RECEIPT_ID)).thenReturn(
                List.of(new AllocationView(UuidV7.next(), RECEIPT_ID, INVOICE_ID, "AR-2026-001",
                        LocalDate.now(), new BigDecimal("100.00"), 0, null)));
        assertThrows(ConflictException.class, () -> service.allocate(RECEIPT_ID, req("100.00")));
    }

    @Test
    void allocateThrows40900OnInvoiceOverAllocation() {
        stubBaseline();
        when(receiptAllocationMapper.selectAllocatedSumByInvoice(INVOICE_ID)).thenReturn(new BigDecimal("450.00"));
        assertThrows(ConflictException.class, () -> service.allocate(RECEIPT_ID, req("100.00")));
    }

    @Test
    void allocateThrows40900OnReceiptOverAllocation() {
        stubBaseline();
        when(receiptAllocationMapper.selectAllocatedSumByReceipt(RECEIPT_ID)).thenReturn(new BigDecimal("950.00"));
        assertThrows(ConflictException.class, () -> service.allocate(RECEIPT_ID, req("100.00")));
    }

    /** 首次核销全额，INSERT 新行、发票 ISSUED 转 PAID、收款单 RECEIVED 转 ALLOCATED */
    @Test
    void firstAllocationFullAmountAdvancesStatus() {
        stubBaseline();
        AllocationView view = service.allocate(RECEIPT_ID, req("500.00"));

        assertEquals(RECEIPT_ID, view.receiptId());
        assertEquals(INVOICE_ID, view.invoiceId());
        assertEquals(0, view.version());
        verify(receiptAllocationMapper).insert(any(net.castimage.fms_backend.Model.entity.ReceiptAllocation.class));
        assertUpdateValue(arInvoiceMapper, "PAID");
        assertUpdateValue(receiptMapper, "ALLOCATED");
    }

    /** 部分核销，发票置 PARTIAL、收款单不推进（fullyPaid=false） */
    @Test
    void partialAllocationSetsInvoicePartialOnly() {
        stubBaseline();
        service.allocate(RECEIPT_ID, req("100.00"));

        assertUpdateValue(arInvoiceMapper, "PARTIAL");
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(receiptMapper, never()).update(any(), captor.capture());
    }

    /** 捕获 update wrapper 的 set 值，MyBatis-Plus 值在 paramNameValuePairs，sqlSet 仅占位符 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void assertUpdateValue(com.baomidou.mybatisplus.core.mapper.BaseMapper mapper, String expectedValue) {
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        com.baomidou.mybatisplus.core.conditions.AbstractWrapper wrapper =
                (com.baomidou.mybatisplus.core.conditions.AbstractWrapper) captor.getValue();
        assertTrue(wrapper.getParamNameValuePairs().containsValue(expectedValue),
                "update set 值应含 " + expectedValue);
    }

    /** 同对不同金额合并，乐观锁 updateWithVersion 推进，不 INSERT 新行 */
    @Test
    void samePairDifferentAmountMerges() {
        stubBaseline();
        when(receiptAllocationMapper.selectByReceiptId(RECEIPT_ID)).thenReturn(
                List.of(new AllocationView(UuidV7.next(), RECEIPT_ID, INVOICE_ID, "AR-2026-001",
                        LocalDate.now(), new BigDecimal("100.00"), 0, null)));
        when(receiptAllocationMapper.updateWithVersion(any(), eq(0), any())).thenReturn(1);

        AllocationView view = service.allocate(RECEIPT_ID, req("50.00"));

        assertEquals(new BigDecimal("150.00"), view.amount());
        assertEquals(1, view.version());
        verify(receiptAllocationMapper, never()).insert(any(net.castimage.fms_backend.Model.entity.ReceiptAllocation.class));
    }

    @Test
    void optimisticLockConflictThrows40900() {
        stubBaseline();
        when(receiptAllocationMapper.selectByReceiptId(RECEIPT_ID)).thenReturn(
                List.of(new AllocationView(UuidV7.next(), RECEIPT_ID, INVOICE_ID, "AR-2026-001",
                        LocalDate.now(), new BigDecimal("100.00"), 0, null)));
        when(receiptAllocationMapper.updateWithVersion(any(), eq(0), any())).thenReturn(0);

        assertThrows(ConflictException.class, () -> service.allocate(RECEIPT_ID, req("50.00")));
    }
}
