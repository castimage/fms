package net.castimage.fms_backend.Controller;

import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Model.vo.DocumentView;
import net.castimage.fms_backend.Service.FileService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.net.URLEncoder;

/**
 * 文件存储模块 REST 接口（/api/files）：上传（multipart：file + businessType + businessId，
 * 大小/扩展名白名单 + 字节落本地卷 + 元数据入库，company 由业务对象 RLS 推导；背景图类型
 * LOGIN_BACKGROUND/USER_BACKGROUND 无业务单据，company/businessId 取当前用户）、业务对象
 * 附件列表、元数据详情、下载（元数据 RLS + 磁盘双检）、软删；身份由安全链路保证，
 * RLS 决定可见集合——越权上传业务对象 40400，越权列表恒空集。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/files")
public class FileController {

    private final FileService fileService;


    /**
     * 上传附件（multipart）：businessType 白名单（REIMBURSEMENT_LINE/VOUCHER/
     * LOGIN_BACKGROUND/USER_BACKGROUND），businessId 为业务对象主键（背景图类型
     * 语义=当前用户 id）；company_id 服务端推导，客户端不可自报。
     */
    @PostMapping("/upload")
    public ApiResponse<DocumentView> upload(@RequestParam("file") MultipartFile file,
                                            @RequestParam String businessType,
                                            @RequestParam UUID businessId) {
        return ApiResponse.ok(fileService.upload(businessType, businessId, file));
    }

    /**
     * 业务对象附件列表（RLS 收敛；越权恒空集，不泄露存在性）。
     */
    @GetMapping
    public ApiResponse<List<DocumentView>> list(@RequestParam String businessType,
                                                @RequestParam UUID businessId) {
        return ApiResponse.ok(fileService.listByBusiness(businessType, businessId));
    }

    /**
     * 附件元数据详情（RLS 收敛；越权/软删则 data:null）。
     */
    @GetMapping("/{id}")
    public ApiResponse<DocumentView> get(@PathVariable UUID id) {
        return ApiResponse.ok(fileService.getDocument(id));
    }

    /**
     * 附件下载（元数据 RLS 收敛 + 磁盘文件存在性双检；文件名 RFC 5987 编码防注入）。
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable UUID id) {
        FileService.FilePayload payload = fileService.download(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(payload.fileName()))
                .body(new FileSystemResource(payload.path()));
    }

    /**
     * 软删附件（仅 operator；RLS 行级收敛，越权/已删 40400）。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        fileService.deleteDocument(id);
        return ApiResponse.ok(null);
    }

    /** Content-Disposition（RFC 5987 UTF-8 编码，防文件名注入/中文乱码） */
    private String contentDisposition(String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename*=UTF-8''" + encoded;
    }
}
