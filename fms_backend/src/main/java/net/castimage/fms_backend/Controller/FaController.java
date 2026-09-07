package net.castimage.fms_backend.Controller;

import jakarta.validation.Valid;
import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.AssetRequest;
import net.castimage.fms_backend.Model.dto.DisposeRequest;
import net.castimage.fms_backend.Model.vo.AssetView;
import net.castimage.fms_backend.Model.vo.DepreciationRunView;
import net.castimage.fms_backend.Service.FaService;
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
import java.util.UUID;

/**
 * 固定资产模块 REST 接口（/api/fa）：资产卡片 CRUD + 处置/报废（不可变触发器终审）、
 * 折旧计提批次（Service 计算 + 批量写；确认双闸防重复）——同公司同期间 CONFIRMED
 * 批次唯一（uk_depr_run 部分唯一索引），重复确认 40900。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/fa")
public class FaController {

    private final FaService faService;


    // 资产卡片

    /**
     * 资产分页列表（keyword 命中 asset_code/category，status 可选过滤；RLS 越权空集）。
     */
    @GetMapping("/assets")
    public ApiResponse<PageResult<AssetView>> pageAssets(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(faService.pageAssets(page, size, keyword, status));
    }

    /**
     * 资产详情（越权则 RLS 空集，回 data:null）。
     */
    @GetMapping("/assets/{id}")
    public ApiResponse<AssetView> getAsset(@PathVariable UUID id) {
        return ApiResponse.ok(faService.getAsset(id));
    }

    /**
     * 创建资产卡片（恒 ACTIVE；uk_asset 公司内编码唯一 40900）。
     */
    @PostMapping("/assets")
    public ApiResponse<AssetView> createAsset(@RequestBody @Valid AssetRequest req) {
        return ApiResponse.ok(faService.createAsset(req));
    }

    /**
     * 更新资产（原值仅 ACTIVE 且未计提可改；已处置/报废禁改）。
     */
    @PutMapping("/assets/{id}")
    public ApiResponse<AssetView> updateAsset(@PathVariable UUID id, @RequestBody @Valid AssetRequest req) {
        return ApiResponse.ok(faService.updateAsset(id, req));
    }

    /**
     * 软删资产（仅 ACTIVE；已计提 DEPRECIATING/处置/报废后冻结 40900，不可变触发器终审）。
     */
    @DeleteMapping("/assets/{id}")
    public ApiResponse<Void> deleteAsset(@PathVariable UUID id) {
        faService.deleteAsset(id);
        return ApiResponse.ok(null);
    }

    /**
     * 处置（ACTIVE/DEPRECIATING 转 DISPOSED + disposal_date；处置后禁改禁删）。
     */
    @PostMapping("/assets/{id}/dispose")
    public ApiResponse<Void> dispose(@PathVariable UUID id,
                                     @RequestBody(required = false) @Valid DisposeRequest req) {
        faService.dispose(id, req == null ? new DisposeRequest(null, null) : req);
        return ApiResponse.ok(null);
    }

    /**
     * 报废（ACTIVE/DEPRECIATING 转 SCRAPPED + disposal_date；报废后禁改禁删）。
     */
    @PostMapping("/assets/{id}/scrap")
    public ApiResponse<Void> scrap(@PathVariable UUID id,
                                   @RequestBody(required = false) @Valid DisposeRequest req) {
        faService.scrap(id, req == null ? new DisposeRequest(null, null) : req);
        return ApiResponse.ok(null);
    }

    // 折旧计提

    /**
     * 折旧计提（直线/双倍余额递减，Service 计算 + 批量写 DRAFT 批次）：
     * 同公司同期间 CONFIRMED 批次存在则跳过（幂等），返回计提资产数。
     */
    @PostMapping("/depreciation/run")
    public ApiResponse<Long> runDepreciation(@RequestParam UUID companyId,
                                             @RequestParam String period) {
        return ApiResponse.ok(faService.runDepreciation(companyId, period));
    }

    /**
     * 计提批次分页列表（companyId/period 可选过滤）。
     */
    @GetMapping("/depreciation")
    public ApiResponse<PageResult<DepreciationRunView>> pageDepreciationRuns(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) String period) {
        return ApiResponse.ok(faService.pageDepreciationRuns(page, size, companyId, period));
    }

    /**
     * 批次确认（DRAFT 转 CONFIRMED）：同公司同期间仅一条 CONFIRMED（部分唯一索引双闸），
     * 重复确认 40900。
     */
    @PostMapping("/depreciation/{id}/confirm")
    public ApiResponse<DepreciationRunView> confirmDepreciation(@PathVariable UUID id) {
        return ApiResponse.ok(faService.confirmDepreciation(id));
    }
}
