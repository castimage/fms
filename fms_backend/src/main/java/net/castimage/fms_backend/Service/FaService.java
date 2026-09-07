package net.castimage.fms_backend.Service;

import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.AssetRequest;
import net.castimage.fms_backend.Model.dto.DisposeRequest;
import net.castimage.fms_backend.Model.vo.AssetView;
import net.castimage.fms_backend.Model.vo.DepreciationRunView;

import java.util.UUID;

/**
 * 固定资产模块业务接口（资产卡片 CRUD/处置/报废 + 折旧计提批次）：
 * 折旧计提为 Service 计算（直线/双倍余额递减、计提额封顶）+ 批量写批次
 * （DRAFT 重建、CONFIRMED 存在跳过），确认走原子 UPDATE + 部分唯一索引双闸防重复。
 */
public interface FaService {

    /** 资产分页列表（keyword 命中 asset_code/category，status 可选过滤） */
    PageResult<AssetView> pageAssets(long page, long size, String keyword, String status);

    /** 资产详情（RLS 空集返回 null） */
    AssetView getAsset(UUID id);

    /** 创建资产卡片（恒 ACTIVE；残值≤原值校验，DB CHECK 兜底） */
    AssetView createAsset(AssetRequest req);

    /** 更新资产（originalValue 仅 ACTIVE 可改；开始计提后财务参数冻结） */
    AssetView updateAsset(UUID id, AssetRequest req);

    /** 软删资产卡片（deleted_at=now()；处置/报废后不可变触发器终审） */
    void deleteAsset(UUID id);

    /** 处置（ACTIVE/DEPRECIATING 转 DISPOSED + disposal_date；不可变触发器终审） */
    void dispose(UUID id, DisposeRequest req);

    /** 报废（ACTIVE/DEPRECIATING 转 SCRAPPED + disposal_date；不可变触发器终审） */
    void scrap(UUID id, DisposeRequest req);

    /**
     * 折旧计提（Service 计算 + 批量写）：直线法 monthly=(原值-残值)/月数、
     * 双倍余额递减 monthly=净值*2/月数，计提额封顶（≤原值-残值-已计提）；
     * CONFIRMED 批次存在则跳过、DRAFT 旧批次软删重建（幂等）；计提后生成折旧凭证草稿
     * （DRAFT，幂等键 fa_depreciation:+period 与 cron PG 路径互斥）并回填批次 voucher_id。
     *
     * @return 参与计提的资产数
     */
    long runDepreciation(UUID companyId, String period);

    /** 计提批次分页列表（companyId/period 可选过滤） */
    PageResult<DepreciationRunView> pageDepreciationRuns(long page, long size, UUID companyId, String period);

    /** 批次确认（DRAFT 转 CONFIRMED）：NOT EXISTS 同公司同期间 CONFIRMED + 部分唯一索引双闸，0 行则 40900 */
    DepreciationRunView confirmDepreciation(UUID id);
}
