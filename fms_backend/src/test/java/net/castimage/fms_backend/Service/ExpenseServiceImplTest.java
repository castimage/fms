package net.castimage.fms_backend.Service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.castimage.fms_backend.Exception.ConflictException;
import net.castimage.fms_backend.Mapper.AuthFunctionMapper;
import net.castimage.fms_backend.Mapper.ReimbursementLineMapper;
import net.castimage.fms_backend.Mapper.ReimbursementMapper;
import net.castimage.fms_backend.Mapper.WfDefinitionMapper;
import net.castimage.fms_backend.Mapper.WfInstanceMapper;
import net.castimage.fms_backend.Model.entity.Reimbursement;
import net.castimage.fms_backend.Service.impl.ExpenseServiceImpl;
import net.castimage.fms_backend.Utils.JwtRole;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.UuidV7;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报销单过账凭证动作测试，mock Mapper 不连库：显式动作 linkReimbursementVoucher 的状态守卫
 * （仅 PAID 可生成）、幂等（voucher_id 非空直接返回既有凭证，不再触碰凭证与状态）、
 * 状态推进 CAS 影响 0 行即 40900（同事务回滚，不留孤立凭证），
 * 以及审批路径不再自动生成凭证、付款路径不触碰凭证列。
 */
class ExpenseServiceImplTest {

    private static final UUID REIMB_ID = UuidV7.next();

    private static final UUID VOUCHER_ID = UuidV7.next();

    private ReimbursementMapper reimbursementMapper;

    private VoucherLinkService voucherLinkService;

    private ExpenseServiceImpl service;

    /** LambdaUpdateWrapper 依赖 TableInfo lambda 缓存，Spring 环境由启动装配注册，单测须手动初始化 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Reimbursement.class);
    }

    @BeforeEach
    void setUp() {
        reimbursementMapper = mock(ReimbursementMapper.class);
        voucherLinkService = mock(VoucherLinkService.class);
        service = new ExpenseServiceImpl(reimbursementMapper, mock(ReimbursementLineMapper.class),
                mock(AuthFunctionMapper.class), mock(FileService.class),
                mock(WfDefinitionMapper.class), mock(WfInstanceMapper.class), voucherLinkService);
        RequestContext.set(new RequestContext(UuidV7.next(), JwtRole.OPERATOR, null, null, "t"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    /** 报销单桩，只填被测路径读取的字段（实体非 mock，避免嵌套 when 触发未完成打桩） */
    private Reimbursement reimb(String status, UUID voucherId) {
        Reimbursement r = new Reimbursement();
        r.setId(REIMB_ID);
        r.setStatus(status);
        r.setVoucherId(voucherId);
        return r;
    }

    /** PAID 且未生成凭证：生成凭证并推进 POSTED，返回联动凭证 id */
    @Test
    void linkVoucher_generatesAndPosts_whenPaidWithoutVoucher() {
        when(reimbursementMapper.selectById(REIMB_ID)).thenReturn(reimb("PAID", null));
        when(voucherLinkService.linkExpense(REIMB_ID)).thenReturn(VOUCHER_ID);
        when(reimbursementMapper.update(isNull(), any())).thenReturn(1);

        assertEquals(VOUCHER_ID, service.linkReimbursementVoucher(REIMB_ID));
        verify(voucherLinkService).linkExpense(REIMB_ID);
        verify(reimbursementMapper).update(isNull(), any());
    }

    /** 幂等：已有凭证直接返回既有 id，不再生成第二张、也不再改状态 */
    @Test
    void linkVoucher_returnsExisting_whenVoucherAlreadyLinked() {
        when(reimbursementMapper.selectById(REIMB_ID)).thenReturn(reimb("POSTED", VOUCHER_ID));

        assertEquals(VOUCHER_ID, service.linkReimbursementVoucher(REIMB_ID));
        verify(voucherLinkService, never()).linkExpense(any());
        verify(reimbursementMapper, never()).update(any(), any());
    }

    /** 未付款不得生成凭证：APPROVED/SUBMITTED/DRAFT 一律 40900，且不产生凭证副作用 */
    @Test
    void linkVoucher_rejects_whenNotPaid() {
        when(reimbursementMapper.selectById(REIMB_ID)).thenReturn(reimb("APPROVED", null));

        assertThrows(ConflictException.class, () -> service.linkReimbursementVoucher(REIMB_ID));
        verify(voucherLinkService, never()).linkExpense(any());
        verify(reimbursementMapper, never()).update(any(), any());
    }

    /** 状态推进 CAS 落空（并发已过账/不可见）：40900，事务回滚不留孤立凭证 */
    @Test
    void linkVoucher_conflicts_whenStatusAdvancedConcurrently() {
        when(reimbursementMapper.selectById(REIMB_ID)).thenReturn(reimb("PAID", null));
        when(voucherLinkService.linkExpense(REIMB_ID)).thenReturn(VOUCHER_ID);
        when(reimbursementMapper.update(isNull(), any())).thenReturn(0);

        assertThrows(ConflictException.class, () -> service.linkReimbursementVoucher(REIMB_ID));
    }

    /** 单据不存在：40400，凭证动作不触发 */
    @Test
    void linkVoucher_notFound_whenInvisible() {
        when(reimbursementMapper.selectById(REIMB_ID)).thenReturn(null);

        assertThrows(net.castimage.fms_backend.Exception.NotFoundException.class,
                () -> service.linkReimbursementVoucher(REIMB_ID));
        verify(voucherLinkService, never()).linkExpense(any());
    }

    /** 审批路径不再自动生成凭证，voucher_id 的写入时机收敛到显式过账动作 */
    @Test
    void approve_doesNotGenerateVoucher() {
        when(reimbursementMapper.selectById(REIMB_ID)).thenReturn(reimb("SUBMITTED", null));
        when(reimbursementMapper.updateById(any(Reimbursement.class))).thenReturn(1);
        when(reimbursementMapper.selectDetail(REIMB_ID)).thenReturn(null);

        service.approveReimbursement(REIMB_ID);

        verify(voucherLinkService, never()).linkExpense(any());
    }

    /** 付款路径只落状态，不触碰凭证列（凭证由显式动作在同一步内完成回填与过账） */
    @Test
    void pay_doesNotTouchVoucher() {
        when(reimbursementMapper.selectById(REIMB_ID)).thenReturn(reimb("APPROVED", null));
        when(reimbursementMapper.updateById(any(Reimbursement.class))).thenReturn(1);
        when(reimbursementMapper.selectDetail(REIMB_ID)).thenReturn(null);

        service.payReimbursement(REIMB_ID);

        verify(voucherLinkService, never()).linkExpense(any());
        verify(reimbursementMapper, never()).update(any(), any());
    }
}
