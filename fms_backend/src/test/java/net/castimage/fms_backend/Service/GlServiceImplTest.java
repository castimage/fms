package net.castimage.fms_backend.Service;

import net.castimage.fms_backend.Utils.UuidV7;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.castimage.fms_backend.Exception.ConflictException;
import net.castimage.fms_backend.Mapper.ChartAccountMapper;
import net.castimage.fms_backend.Mapper.AuthFunctionMapper;
import net.castimage.fms_backend.Mapper.ParamMapper;
import net.castimage.fms_backend.Mapper.PeriodMapper;
import net.castimage.fms_backend.Mapper.UserMapper;
import net.castimage.fms_backend.Mapper.VoucherLineMapper;
import net.castimage.fms_backend.Mapper.VoucherMapper;
import net.castimage.fms_backend.Model.entity.Period;
import net.castimage.fms_backend.Model.entity.Voucher;
import net.castimage.fms_backend.Service.impl.GlServiceImpl;
import net.castimage.fms_backend.Utils.JwtRole;
import net.castimage.fms_backend.Utils.RequestContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 凭证状态机预检测试，mock Mapper，不连库，submit/approve/post 三步迁移的
 * Service 层预检链，状态白名单、制单人/SoD 职责分离、借贷平衡预检、期间开放校验，
 * 终审由 db 触发器/函数兜底，本测试只验证 Java 预检层。
 */
class GlServiceImplTest {

    private static final UUID VOUCHER_ID = UuidV7.next();
    private static final UUID CREATOR_ID = UuidV7.next();
    private static final UUID OTHER_ID = UuidV7.next();
    private static final UUID COMPANY_ID = UuidV7.next();

    private VoucherMapper voucherMapper;
    private VoucherLineMapper voucherLineMapper;
    private PeriodMapper periodMapper;
    private GlServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Voucher.class);
        TableInfoHelper.initTableInfo(assistant, Period.class);
        TableInfoHelper.initTableInfo(assistant, net.castimage.fms_backend.Model.entity.User.class);
        TableInfoHelper.initTableInfo(assistant, net.castimage.fms_backend.Model.entity.VoucherLine.class);
    }

    @BeforeEach
    void setUp() {
        voucherMapper = mock(VoucherMapper.class);
        voucherLineMapper = mock(VoucherLineMapper.class);
        periodMapper = mock(PeriodMapper.class);
        service = new GlServiceImpl(
                mock(ChartAccountMapper.class), voucherMapper, voucherLineMapper, periodMapper,
                mock(UserMapper.class), mock(AuthFunctionMapper.class), mock(ParamMapper.class));
        RequestContext.set(new RequestContext(CREATOR_ID, JwtRole.OPERATOR, null, null, "t"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private Voucher voucher(String status, UUID createdBy) {
        Voucher v = mock(Voucher.class);
        when(v.getId()).thenReturn(VOUCHER_ID);
        when(v.getStatus()).thenReturn(status);
        when(v.getCreatedBy()).thenReturn(createdBy);
        when(v.getCompanyId()).thenReturn(COMPANY_ID);
        when(v.getPeriod()).thenReturn("2026-08");
        return v;
    }

    private void stubOpenPeriod() {
        Period p = mock(Period.class);
        when(p.getStatus()).thenReturn("OPEN");
        when(periodMapper.selectOne(any())).thenReturn(p);
    }

    private void stubBalanced(String debit, String credit) {
        VoucherLineMapper.Sum sum = mock(VoucherLineMapper.Sum.class);
        when(sum.debit()).thenReturn(new BigDecimal(debit));
        when(sum.credit()).thenReturn(new BigDecimal(credit));
        when(voucherLineMapper.selectSum(VOUCHER_ID)).thenReturn(sum);
    }

    // ---------------- submit（DRAFT 转 SUBMITTED） ----------------

    @Test
    void submitRejectsNonDraftStatus() {
        Voucher v = voucher("SUBMITTED", CREATOR_ID);
        when(voucherMapper.selectById(VOUCHER_ID)).thenReturn(v);
        assertThrows(ConflictException.class, () -> service.submitVoucher(VOUCHER_ID));
    }

    @Test
    void submitRejectsNonCreator() {
        Voucher v = voucher("DRAFT", OTHER_ID);
        when(voucherMapper.selectById(VOUCHER_ID)).thenReturn(v);
        assertThrows(ConflictException.class, () -> service.submitVoucher(VOUCHER_ID));
    }

    @Test
    void submitRejectsUnbalancedVoucher() {
        Voucher v = voucher("DRAFT", CREATOR_ID);
        when(voucherMapper.selectById(VOUCHER_ID)).thenReturn(v);
        stubBalanced("100.00", "90.00");
        assertThrows(ConflictException.class, () -> service.submitVoucher(VOUCHER_ID));
    }

    @Test
    void submitRejectsMissingPeriod() {
        Voucher v = voucher("DRAFT", CREATOR_ID);
        when(voucherMapper.selectById(VOUCHER_ID)).thenReturn(v);
        stubBalanced("100.00", "100.00");
        when(periodMapper.selectOne(any())).thenReturn(null);
        assertThrows(ConflictException.class, () -> service.submitVoucher(VOUCHER_ID));
    }

    @Test
    void submitAdvancesToSubmitted() {
        Voucher v = voucher("DRAFT", CREATOR_ID);
        when(voucherMapper.selectById(VOUCHER_ID)).thenReturn(v);
        stubBalanced("100.00", "100.00");
        stubOpenPeriod();
        when(voucherMapper.updateById(any(Voucher.class))).thenReturn(1);

        service.submitVoucher(VOUCHER_ID);
        verify(voucherMapper).updateById(any(Voucher.class));
    }

    // ---------------- approve（SUBMITTED 转 APPROVED） ----------------

    @Test
    void approveRejectsNonSubmittedStatus() {
        Voucher v = voucher("APPROVED", CREATOR_ID);
        when(voucherMapper.selectById(VOUCHER_ID)).thenReturn(v);
        assertThrows(ConflictException.class, () -> service.approveVoucher(VOUCHER_ID));
    }

    @Test
    void approveRejectsSamePersonSoD() {
        Voucher v = voucher("SUBMITTED", CREATOR_ID);
        when(voucherMapper.selectById(VOUCHER_ID)).thenReturn(v);
        assertThrows(ConflictException.class, () -> service.approveVoucher(VOUCHER_ID));
    }

    @Test
    void approveAdvancesToApproved() {
        Voucher v = voucher("SUBMITTED", OTHER_ID);
        when(voucherMapper.selectById(VOUCHER_ID)).thenReturn(v);
        when(voucherMapper.updateById(any(Voucher.class))).thenReturn(1);

        service.approveVoucher(VOUCHER_ID);
        verify(voucherMapper).updateById(any(Voucher.class));
    }

    // ---------------- post（APPROVED 转 POSTED） ----------------

    @Test
    void postRejectsNonApprovedStatus() {
        Voucher v = voucher("DRAFT", CREATOR_ID);
        when(voucherMapper.selectById(VOUCHER_ID)).thenReturn(v);
        assertThrows(ConflictException.class, () -> service.postVoucher(VOUCHER_ID));
    }

    @Test
    void postDelegatesToDbFunction() {
        Voucher v = voucher("APPROVED", OTHER_ID);
        when(voucherMapper.selectById(VOUCHER_ID)).thenReturn(v);
        service.postVoucher(VOUCHER_ID);
        verify(voucherMapper).postVoucher(VOUCHER_ID);
    }
}
