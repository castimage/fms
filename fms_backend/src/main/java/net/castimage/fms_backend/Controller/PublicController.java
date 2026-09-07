package net.castimage.fms_backend.Controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import net.castimage.fms_backend.config.FmsProperties;
import net.castimage.fms_backend.Exception.InternalServerException;
import net.castimage.fms_backend.Exception.NotFoundException;
import net.castimage.fms_backend.Mapper.DocumentMapper;
import net.castimage.fms_backend.Mapper.ParamMapper;
import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Model.vo.PublicDocumentMeta;
import net.castimage.fms_backend.Utils.SandboxRoleHint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 公开端点 REST 接口，/api/public，网关与后端双侧匿名白名单，登录页背景配置，um.params ui.login.background，经 SECURITY DEFINER 匿名读口与登录页背景图，docs.fn_public_document_meta 匿名读口 + 磁盘字节直读，无身份请求的最小暴露面，
 * 仅 LOGIN_BACKGROUND 未删文件可读，其余类型/不存在一律 40400。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/public")
public class PublicController {

    private static final Logger log = LoggerFactory.getLogger(PublicController.class);

    /** 登录页背景参数 code（与 init/seed_data.sql 种子键一致） */
    private static final String LOGIN_BACKGROUND_PARAM = "ui.login.background";

    /** 缺省配置（与 init/seed_data.sql ui.login.background 种子值逐字一致，{"v":{...}} 包装） */
    private static final String DEFAULT_LOGIN_BACKGROUND =
            "{\"v\":{\"enabled\":false,\"fit\":\"cover\",\"blur\":6,\"darken\":0.25,\"desktop\":null,\"tablet\":null,\"mobile\":null}}";

    /** 匿名背景图缓存时长，秒，公开静态资源长缓存，降低登录页重复拉取 */
    private static final long PUBLIC_FILE_CACHE_SECONDS = 3600;

    private final ParamMapper paramMapper;
    private final DocumentMapper documentMapper;
    private final FmsProperties fmsProperties;


    /** 登录页背景配置，匿名，读 um.params ui.login.background，{"v":{...}} 包装解包取 .v */
    @GetMapping("/login-background")
    public ApiResponse<JSONObject> loginBackground() {
        SandboxRoleHint.set(SandboxRoleHint.Hint.PUBLIC_READ);
        try {
            String raw = paramMapper.selectPublicParamValue(LOGIN_BACKGROUND_PARAM, DEFAULT_LOGIN_BACKGROUND);
            JSONObject parsed;
            try {
                parsed = raw == null || raw.isBlank() ? new JSONObject() : JSON.parseObject(raw);
            } catch (Exception e) {
                parsed = new JSONObject(); // 参数值非法回落缺省
            }
            if (parsed == null) {
                parsed = new JSONObject();
            }

            // 种子/写入均为 {"v":{...}} 包装，解包取 .v，无 v 键则整体即配置，结构兼容
            JSONObject config = parsed.getJSONObject("v");
            return ApiResponse.ok(config != null ? config : parsed);
        } finally {
            SandboxRoleHint.clear();
        }
    }

    /**
     * 登录页背景图，匿名，fn_public_document_meta 读元数据，仅 LOGIN_BACKGROUND 且未删，
     * SECURITY DEFINER 绕过 rls 的最小暴露面，返回磁盘字节流 + 长缓存，无结果/磁盘缺失则 40400。
     */
    @GetMapping("/files/{id}")
    public ResponseEntity<byte[]> publicFile(@PathVariable UUID id) {
        PublicDocumentMeta meta = documentMapper.selectPublicDocumentMeta(id);
        if (meta == null) {
            throw new NotFoundException("文件不存在或不可公开访问");
        }

        // 与 FileServiceImpl.resolveStored 同构，服务端拼接存储根目录（fms.file.storage-dir），防路径穿越
        Path path = fmsProperties.getFile().getStorageDirPath().resolve(meta.storagePath()).normalize();
        if (!Files.isRegularFile(path)) {
            log.warn("公开背景图磁盘文件丢失 id={} path={}", id, meta.storagePath());
            throw new NotFoundException("文件不存在或不可公开访问");
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path); // 单文件上限 8MB，可整读内存
        } catch (IOException e) {
            log.error("公开背景图读盘失败 id={} path={} msg={}", id, meta.storagePath(), e.getMessage());
            throw new InternalServerException("文件读取失败");
        }

        return ResponseEntity.ok()
                .contentType(parseMediaType(meta.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=" + PUBLIC_FILE_CACHE_SECONDS)
                .contentLength(bytes.length)
                .body(bytes);
    }

    /** 存量 mime 可能非法，解析失败回落 octet-stream，不阻断匿名读取 */
    private MediaType parseMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
