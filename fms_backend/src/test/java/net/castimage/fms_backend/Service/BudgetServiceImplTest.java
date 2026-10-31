package net.castimage.fms_backend.Service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.castimage.fms_backend.Exception.ForbiddenException;
import net.castimage.fms_backend.Mapper.AuthFunctionMapper;
import net.castimage.fms_backend.Mapper.BudgetAdjustmentMapper;
import net.castimage.fms_backend.Mapper.BudgetPlanMapper;
import net.castimage.fms_backend.Mapper.ChartAccountMapper;
import net.castimage.fms_backend.Model.entity.BudgetPlan;
import net.castimage.fms_backend.Service.impl.BudgetServiceImpl;
import net.castimage.fms_backend.Utils.JwtRole;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.UuidV7;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预算计划对象级授权测试，修改/删除仅创建人本人，审批人不得审批本人创建的计划。
 */
class BudgetServiceImplTest {

    private static final UUID PLAN_ID = UuidV7.next();

    private static final UUID CURRENT_USER = UuidV7.next();

    private BudgetPlanMapper planMapper;

    private BudgetServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, BudgetPlan.class);
    }

    @BeforeEach
    void setUp() {
        planMapper = mock(BudgetPlanMapper.class);
        service = new BudgetServiceImpl(planMapper, mock(BudgetAdjustmentMapper.class),
                mock(ChartAccountMapper.class), mock(AuthFunctionMapper.class));
        RequestContext.set(new RequestContext(CURRENT_USER, JwtRole.OPERATOR, null, null, "t"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private BudgetPlan plan(String status, UUID createdBy) {
        BudgetPlan p = new BudgetPlan();
        p.setId(PLAN_ID);
        p.setStatus(status);
        p.setCreatedBy(createdBy);
        return p;
    }

    @Test
    void update_rejects_whenNotCreator() {
        when(planMapper.selectById(PLAN_ID)).thenReturn(plan("DRAFT", UuidV7.next()));

        assertThrows(ForbiddenException.class, () -> service.updatePlan(PLAN_ID, null));
        verify(planMapper, never()).updateById(any(BudgetPlan.class));
    }

    @Test
    void delete_rejects_whenNotCreator() {
        when(planMapper.selectById(PLAN_ID)).thenReturn(plan("DRAFT", UuidV7.next()));

        assertThrows(ForbiddenException.class, () -> service.deletePlan(PLAN_ID));
        verify(planMapper, never()).updateById(any(BudgetPlan.class));
    }

    @Test
    void approve_rejects_whenCreatorIsApprover() {
        when(planMapper.selectById(PLAN_ID)).thenReturn(plan("SUBMITTED", CURRENT_USER));

        assertThrows(ForbiddenException.class, () -> service.approvePlan(PLAN_ID));
        verify(planMapper, never()).updateById(any(BudgetPlan.class));
    }
}
