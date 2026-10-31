package net.castimage.fms_backend.Service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.castimage.fms_backend.Exception.ConflictException;
import net.castimage.fms_backend.Exception.ForbiddenException;
import net.castimage.fms_backend.Mapper.AuthFunctionMapper;
import net.castimage.fms_backend.Mapper.ReimbursementLineMapper;
import net.castimage.fms_backend.Mapper.ReimbursementMapper;
import net.castimage.fms_backend.Mapper.WfDefinitionMapper;
import net.castimage.fms_backend.Mapper.WfInstanceMapper;
import net.castimage.fms_backend.Model.dto.ReimbursementRequest;
import net.castimage.fms_backend.Model.entity.Reimbursement;
import net.castimage.fms_backend.Model.entity.ReimbursementLine;
import net.castimage.fms_backend.Service.impl.ExpenseServiceImpl;
import net.castimage.fms_backend.Utils.JwtRole;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.UuidV7;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
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
 * 报销单业务动作测试，mock Mapper 不连库
 *
 * 覆盖显式动作 linkReimbursementVoucher 的状态守卫，仅 PAID 可生成，voucher_id 非空即幂等返回
 * 既有凭证且不再触碰凭证与状态，状态推进 CAS 影响 0 行即 40900 并回滚不留孤立凭证，
 * 另覆盖审批路径不生成凭证、付款路径不触碰凭证列，以及修改与删除仅限申请人本人的 40300 校验
 */
class ExpenseServiceImplTest {

    private static final UUID REIMB_ID = UuidV7.next();

    private static final UUID LINE_ID = UuidV7.next();

    private static final UUID VOUCHER_ID = UuidV7.next();

    private static final UUID CURRENT_USER = UuidV7.next();

    private ReimbursementMapper reimbursementMapper;

    private ReimbursementLineMapper lineMapper;

    private AuthFunctionMapper authFunctionMapper;

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
        lineMapper = mock(ReimbursementLineMapper.class);
        authFunctionMapper = mock(AuthFunctionMapper.class);
        voucherLinkService = mock(VoucherLinkService.class);
        service = new ExpenseServiceImpl(reimbursementMapper, lineMapper,
                authFunctionMapper, mock(FileService.class),
                mock(WfDefinitionMapper.class), mock(WfInstanceMapper.class), voucherLinkService);
        RequestContext.set(new RequestContext(CURRENT_USER, JwtRole.OPERATOR, null, null, "t"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    /** 报销单桩，只填被测路径读取的字段（实体非 mock，避免嵌套 when 触发未完成打桩） */
    private Reimbursement reimb(String status, UUID voucherId) {
        Reimbursement r = new Reimbursement();
        r.setId(REIMB_ID);
        r.setApplicantId(CURRENT_USER);
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

    /** 冲销前置满足：单据已过账且其凭证已冲销，置为已冲销 */
    @Test
    void reverse_setsReversed_whenPostedAndVoucherReversed() {
        when(reimbursementMapper.selectById(REIMB_ID)).thenReturn(reimb("POSTED", VOUCHER_ID));
        when(voucherLinkService.voucherStatus(VOUCHER_ID)).thenReturn("REVERSED");
        when(reimbursementMapper.update(isNull(), any())).thenReturn(1);

        service.reverseReimbursement(REIMB_ID, "重复报销，冲回");

        verify(reimbursementMapper).update(isNull(), any());
    }

    /** 账先冲、单后标：凭证已可见且未冲销即拒绝，且不触碰状态 */
    @Test
    void reverse_rejects_whenVoucherNotReversed() {
        when(reimbursementMapper.selectById(REIMB_ID)).thenReturn(reimb("POSTED", VOUCHER_ID));
        when(voucherLinkService.voucherStatus(VOUCHER_ID)).thenReturn("POSTED");

        assertThrows(ConflictException.class,
                () -> service.reverseReimbursement(REIMB_ID, "重复报销，冲回"));
        verify(reimbursementMapper, never()).update(any(), any());
    }

    /** 凭证查询受行级策略约束可能返回空：此时不预判，交由数据库状态机的权威前置终审 */
    @Test
    void reverse_defersToDatabase_whenVoucherInvisible() {
        when(reimbursementMapper.selectById(REIMB_ID)).thenReturn(reimb("POSTED", VOUCHER_ID));
        when(voucherLinkService.voucherStatus(VOUCHER_ID)).thenReturn(null);
        when(reimbursementMapper.update(isNull(), any())).thenReturn(0);   // 触发器拒绝则更新 0 行

        assertThrows(ConflictException.class,
                () -> service.reverseReimbursement(REIMB_ID, "重复报销，冲回"));
        verify(reimbursementMapper).update(isNull(), any());
    }

    /** 仅已过账可冲销：其余状态（含已冲销终态）一律 40900，且不查询凭证 */
    @Test
    void reverse_rejects_whenNotPosted() {
        when(reimbursementMapper.selectById(REIMB_ID)).thenReturn(reimb("PAID", VOUCHER_ID));

        assertThrows(ConflictException.class,
                () -> service.reverseReimbursement(REIMB_ID, "重复报销，冲回"));
        verify(voucherLinkService, never()).voucherStatus(any());
        verify(reimbursementMapper, never()).update(any(), any());
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

    /** 修改仅限申请人本人，非本人草稿直接 40300，不进入明细重写 */
    @Test
    void update_rejects_whenNotApplicant() {
        Reimbursement r = reimb("DRAFT", null);
        r.setApplicantId(UuidV7.next());
        when(reimbursementMapper.selectById(REIMB_ID)).thenReturn(r);

        assertThrows(ForbiddenException.class,
                () -> service.updateReimbursement(REIMB_ID, request()));
        verify(reimbursementMapper, never()).updateById(any(Reimbursement.class));
    }

    /** 删除仅限申请人本人，非本人草稿直接 40300，不触碰软删闸口 */
    @Test
    void delete_rejects_whenNotApplicant() {
        Reimbursement r = reimb("DRAFT", null);
        r.setApplicantId(UuidV7.next());
        when(reimbursementMapper.selectById(REIMB_ID)).thenReturn(r);

        assertThrows(ForbiddenException.class, () -> service.deleteReimbursement(REIMB_ID));
        verify(authFunctionMapper, never()).fnSoftDelete(any(), any(), any());
    }

    /** 申请人本人删除草稿正常放行 */
    @Test
    void delete_allowsApplicant() {
        when(reimbursementMapper.selectById(REIMB_ID)).thenReturn(reimb("DRAFT", null));
        when(lineMapper.selectList(any())).thenReturn(List.of());
        when(authFunctionMapper.fnSoftDelete("expense", "reimbursements", REIMB_ID)).thenReturn(true);

        service.deleteReimbursement(REIMB_ID);

        verify(authFunctionMapper).fnSoftDelete("expense", "reimbursements", REIMB_ID);
    }

    /** 绑定附件仅限申请人本人的草稿/驳回单，非本人直接 40300 */
    @Test
    void bindInvoiceFile_rejects_whenNotApplicant() {
        Reimbursement r = reimb("DRAFT", null);
        r.setApplicantId(UuidV7.next());
        when(reimbursementMapper.selectById(REIMB_ID)).thenReturn(r);
        when(lineMapper.selectLineById(LINE_ID)).thenReturn(line());

        assertThrows(ForbiddenException.class,
                () -> service.bindInvoiceFile(LINE_ID, UuidV7.next()));
        verify(lineMapper, never()).updateInvoiceFileId(any(), any());
    }

    /** 绑定附件仅限 DRAFT/REJECTED，已付款/已过账单据拒绝 */
    @Test
    void bindInvoiceFile_rejects_whenParentNotEditable() {
        when(reimbursementMapper.selectById(REIMB_ID)).thenReturn(reimb("PAID", null));
        when(lineMapper.selectLineById(LINE_ID)).thenReturn(line());

        assertThrows(ConflictException.class,
                () -> service.bindInvoiceFile(LINE_ID, UuidV7.next()));
        verify(lineMapper, never()).updateInvoiceFileId(any(), any());
    }

    private ReimbursementLine line() {
        ReimbursementLine line = new ReimbursementLine();
        line.setId(LINE_ID);
        line.setReimbursementId(REIMB_ID);
        return line;
    }

    private ReimbursementRequest request() {
        return new ReimbursementRequest(UuidV7.next(), "t", BigDecimal.TEN, "CNY",
                UuidV7.next(), List.of());
    }
}
