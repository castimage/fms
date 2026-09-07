package net.castimage.fms_backend.Controller;

import jakarta.validation.Valid;
import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Model.dto.FxRateRequest;
import net.castimage.fms_backend.Model.entity.FxRate;
import net.castimage.fms_backend.Service.FxRateService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.UUID;

/**
 * 期末汇率维护接口（/api/gl/fx-rates）：列表查询 + PUT/POST upsert（同公司×币种×期间
 * 唯一，重复提交为更新语义）+ 软删；汇率是期末调汇（POST /api/gl/fx-adjustment 与 cron
 * 月度任务）的数据依据——维护后调汇才会计入对应外币科目。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/gl/fx-rates")
public class FxRateController {

    private final FxRateService fxRateService;


    /**
     * 汇率列表（companyId 必填，period/currency 可选过滤；RLS 越权空集）。
     */
    @GetMapping
    public ApiResponse<List<FxRate>> listFxRates(@RequestParam UUID companyId,
                                                 @RequestParam(required = false) String period,
                                                 @RequestParam(required = false) String currency) {
        return ApiResponse.ok(fxRateService.listFxRates(companyId, period, currency));
    }

    /**
     * 汇率 upsert（同公司×币种×期间已存在则更新 rate，软删行复活；冲突语义 = 更新而非 40900）。
     */
    @PutMapping
    public ApiResponse<FxRate> saveFxRate(@RequestBody @Valid FxRateRequest req) {
        return ApiResponse.ok(fxRateService.saveFxRate(req));
    }

    /**
     * 汇率 upsert（POST 与 PUT 同语义，均幂等；前端表单提交便捷入口）。
     */
    @PostMapping
    public ApiResponse<FxRate> createFxRate(@RequestBody @Valid FxRateRequest req) {
        return ApiResponse.ok(fxRateService.saveFxRate(req));
    }

    /**
     * 软删汇率（deleted_at=now()；调汇查询按 deleted_at IS NULL 自动排除）。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteFxRate(@PathVariable UUID id) {
        fxRateService.deleteFxRate(id);
        return ApiResponse.ok(null);
    }
}
