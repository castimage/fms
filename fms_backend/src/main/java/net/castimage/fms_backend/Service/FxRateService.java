package net.castimage.fms_backend.Service;

import net.castimage.fms_backend.Model.dto.FxRateRequest;
import net.castimage.fms_backend.Model.entity.FxRate;

import java.util.List;
import java.util.UUID;

/**
 * 期末汇率业务接口（gl.fx_rates）：列表查询 + upsert 维护 + 软删；
 * 汇率是期末调汇（gl.fn_fx_adjustment）的数据依据，无汇率记录的外币科目在调汇时跳过。
 */
public interface FxRateService {

    /** 汇率列表（companyId 必填，period/currency 可选过滤；RLS 公司范围收敛，越权返回空集） */
    List<FxRate> listFxRates(UUID companyId, String period, String currency);

    /** 汇率 upsert（同公司×币种×期间唯一：已存在则更新 rate，软删行复活） */
    FxRate saveFxRate(FxRateRequest req);

    /** 软删汇率（deleted_at=now()，统一经 auth.fn_soft_delete 闸口） */
    void deleteFxRate(UUID id);
}
