package net.castimage.fms_backend.Service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Exception.ConflictException;
import net.castimage.fms_backend.Exception.NotFoundException;
import net.castimage.fms_backend.Mapper.AuthFunctionMapper;
import net.castimage.fms_backend.Mapper.FxRateMapper;
import net.castimage.fms_backend.Model.dto.FxRateRequest;
import net.castimage.fms_backend.Model.entity.FxRate;
import net.castimage.fms_backend.Service.FxRateService;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.math.RoundingMode;

/**
 * 期末汇率业务实现：列表走 BaseMapper + Wrapper（RLS 公司范围收敛）；保存为 upsert
 * （同公司×币种×期间唯一，ON CONFLICT DO UPDATE 幂等，软删行自动复活）；删除为软删
 * （统一经 auth.fn_soft_delete 闸口——fx_rates DELETE 策略与软删口径一致）。
 */
@Service
@RequiredArgsConstructor
public class FxRateServiceImpl implements FxRateService {

    private final FxRateMapper fxRateMapper;
    private final AuthFunctionMapper authFunctionMapper;


    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<FxRate> listFxRates(UUID companyId, String period, String currency) {
        if (companyId == null) {
            throw new BadRequestException("公司必填");
        }
        return fxRateMapper.selectList(new LambdaQueryWrapper<FxRate>()
                .isNull(FxRate::getDeletedAt)
                .eq(FxRate::getCompanyId, companyId)
                .eq(period != null && !period.isBlank(), FxRate::getPeriod, period)
                .eq(currency != null && !currency.isBlank(), FxRate::getCurrency, currency)
                .orderByAsc(FxRate::getPeriod, FxRate::getCurrency));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public FxRate saveFxRate(FxRateRequest req) {
        RequestContext.requireWriteRole();
        UUID companyId = req.companyId();
        String currency = req.currency().trim().toUpperCase(Locale.ROOT);
        String period = req.period().trim();
        if (!period.matches("\\d{4}-\\d{2}")) {
            throw new BadRequestException("期间格式非法：" + period + "（须为 YYYY-MM）");
        }
        int month = Integer.parseInt(period.substring(5, 7));
        if (month < 1 || month > 12) {
            throw new BadRequestException("期间月份非法：" + period);
        }
        BigDecimal rate = req.rate().setScale(6, RoundingMode.HALF_UP);
        if (rate.signum() <= 0) {
            throw new BadRequestException("汇率必须大于 0");
        }
        fxRateMapper.upsert(UuidV7.next(), companyId, currency, period, rate, RequestContext.userId());
        // upsert 后回查返回完整行（ON CONFLICT 更新路径无 RETURNING，查全行给前端展示）
        return fxRateMapper.selectOne(new LambdaQueryWrapper<FxRate>()
                .isNull(FxRate::getDeletedAt)
                .eq(FxRate::getCompanyId, companyId)
                .eq(FxRate::getCurrency, currency)
                .eq(FxRate::getPeriod, period));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void deleteFxRate(UUID id) {
        RequestContext.requireWriteRole();
        FxRate rate = fxRateMapper.selectById(id);
        if (rate == null) {
            throw new NotFoundException("汇率记录不存在");
        }
        // 软删统一经 auth.fn_soft_delete 闸口（SECURITY DEFINER，RLS 新行可见性检查豁免）
        if (!authFunctionMapper.fnSoftDelete("gl", "fx_rates", id)) {
            throw new ConflictException("汇率删除失败，可能已不可见");
        }
    }
}
