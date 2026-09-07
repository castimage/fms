package net.castimage.fms_backend.Service;

import net.castimage.fms_backend.Model.vo.DocumentView;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * 文件存储模块业务接口（/api/files）：附件上传（multipart + 大小/类型白名单 + 字节落本地卷 +
 * 元数据入库）、业务对象附件列表、元数据详情、下载（RLS 收敛 + 磁盘双检）、软删。
 * 身份/角色由安全链路保证，company_id 恒由业务对象子查询推导（RLS 决定可见集合）。
 */
public interface FileService {

    /**
     * 上传附件：扩展名/大小白名单校验通过后把字节写本地卷（{yyyyMM}/{uuid}.{ext}，边写边算 SHA-256），随后
     * 元数据入库；company_id 由业务对象（报销明细行/凭证）经 RLS 收敛查询推导，越权 40400。
     *
     * @throws net.castimage.fms_backend.Exception.BadRequestException 文件为空/类型不支持/大小超限
     * @throws net.castimage.fms_backend.Exception.NotFoundException    业务对象不存在或越权
     */
    DocumentView upload(String businessType, UUID businessId, MultipartFile file);

    /**
     * 业务对象附件列表（RLS 收敛，越权恒空集）。
     */
    List<DocumentView> listByBusiness(String businessType, UUID businessId);

    /**
     * 附件元数据详情（RLS 收敛；越权/软删则 40400）。
     */
    DocumentView getDocument(UUID id);

    /**
     * 附件下载载荷（元数据 RLS 收敛 + 磁盘文件存在性双检；元数据可见但文件丢失则 40400）。
     */
    FilePayload download(UUID id);

    /**
     * 软删附件（仅 operator；RLS 行级收敛，越权/已删则 40400；磁盘文件同步移除）。
     */
    void deleteDocument(UUID id);

    /** 附件下载载荷（文件名/类型/大小 + 磁盘路径，Controller 组装响应） */
    record FilePayload(String fileName, String contentType, long fileSize, Path path) {
    }
}
