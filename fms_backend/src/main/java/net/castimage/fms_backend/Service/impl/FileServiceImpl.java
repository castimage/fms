package net.castimage.fms_backend.Service.impl;

import net.castimage.fms_backend.config.FmsProperties;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Exception.InternalServerException;
import net.castimage.fms_backend.Exception.NotFoundException;
import net.castimage.fms_backend.Mapper.DocumentMapper;
import net.castimage.fms_backend.Mapper.ReimbursementLineMapper;
import net.castimage.fms_backend.Mapper.UserMapper;
import net.castimage.fms_backend.Mapper.VoucherMapper;
import net.castimage.fms_backend.Model.entity.Document;
import net.castimage.fms_backend.Model.entity.User;
import net.castimage.fms_backend.Model.vo.DocumentView;
import net.castimage.fms_backend.Service.FileService;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.io.OutputStream;

/**
 * 文件存储模块业务实现：上传（扩展名/大小白名单预检后字节落本地卷，边写边算 SHA-256，随后
 * 元数据入库，company_id 由业务对象 RLS 收敛推导，客户端不可自报）与下载（元数据 RLS +
 * 磁盘存在性双检）；写路径先 requireWriteRole（viewer/auditor/report 拒绝），DB RLS 兜底。
 * 落盘在事务外（字节 IO 不占长事务），入库失败回滚时同步清理孤儿文件。
 */
@Service
public class FileServiceImpl implements FileService {

    private static final Logger log = LoggerFactory.getLogger(FileServiceImpl.class);

    /** 业务类型白名单（与 docs.documents CHECK 同构：单据附件 + 登录页/用户级背景图两类） */
    private static final Set<String> BUSINESS_TYPES = Set.of(
            "REIMBURSEMENT_LINE", "VOUCHER", "LOGIN_BACKGROUND", "USER_BACKGROUND");

    /** 存储日期子目录格式（{yyyyMM}） */
    private static final DateTimeFormatter MONTH_DIR = DateTimeFormatter.ofPattern("yyyyMM");

    private final FmsProperties fmsProperties;
    private final DocumentMapper documentMapper;
    private final ReimbursementLineMapper reimbursementLineMapper;
    private final VoucherMapper voucherMapper;
    private final UserMapper userMapper; // 背景图类型推导 company（um.users 本人行查询）

    public FileServiceImpl(FmsProperties fmsProperties, DocumentMapper documentMapper,
                           ReimbursementLineMapper reimbursementLineMapper, VoucherMapper voucherMapper,
                           UserMapper userMapper) {
        this.fmsProperties = fmsProperties;
        this.documentMapper = documentMapper;
        this.reimbursementLineMapper = reimbursementLineMapper;
        this.voucherMapper = voucherMapper;
        this.userMapper = userMapper;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public DocumentView upload(String businessType, UUID businessId, MultipartFile file) {
        RequestContext.requireWriteRole();
        if (businessType == null || !BUSINESS_TYPES.contains(businessType)) {
            throw new BadRequestException("业务类型不支持");
        }
        if (businessId == null) {
            throw new BadRequestException("业务对象 id 必填");
        }
        // 背景图类型无业务单据：businessId 语义 = 当前用户 id（客户端不可指定他人）
        if ("LOGIN_BACKGROUND".equals(businessType) || "USER_BACKGROUND".equals(businessType)) {
            businessId = RequestContext.requireUserId();
        }
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("上传文件为空");
        }
        String originalName = safeFileName(file.getOriginalFilename());
        String ext = extensionOf(originalName);
        if (!fmsProperties.getFile().getAllowedExtensions().contains(ext)) {
            throw new BadRequestException("文件类型不支持（允许: " + String.join(", ", fmsProperties.getFile().getAllowedExtensions()) + "）");
        }
        long maxSize = fmsProperties.getFile().getMaxSizeBytes();
        if (file.getSize() > maxSize) {
            throw new BadRequestException("文件大小超限（上限 " + (maxSize / 1024 / 1024) + "MB）");
        }

        // company_id 恒由业务对象经 RLS 收敛查询推导——越权业务对象即空集 40400（不泄露存在性）
        UUID companyId = resolveCompanyId(businessType, businessId);
        if (companyId == null) {
            throw new NotFoundException("业务对象不存在或无权访问");
        }

        UUID id = UuidV7.next();
        String storagePath = LocalDateTime.now().format(MONTH_DIR) + "/" + id + "." + ext;
        String sha256;
        try {
            sha256 = storeToDisk(file, storagePath);
        } catch (IOException e) {
            log.error("附件写盘失败 path={} msg={}", storagePath, e.getMessage());
            throw new net.castimage.fms_backend.Exception.InternalServerException("文件存储失败");
        }

        Document doc = new Document();
        doc.setId(id);
        doc.setCompanyId(companyId);
        doc.setBusinessType(businessType);
        doc.setBusinessId(businessId);
        doc.setFileName(originalName);
        doc.setFileSize(file.getSize());
        doc.setContentType(file.getContentType() == null ? "application/octet-stream" : file.getContentType());
        doc.setStoragePath(storagePath);
        doc.setSha256(sha256);
        doc.setUploaderId(RequestContext.requireUserId());
        try {
            documentMapper.insert(doc);
        } catch (RuntimeException e) {
            // 入库失败（RLS/约束等）则移除已落盘孤儿文件后重抛，防磁盘垃圾累积
            deleteQuietly(storagePath);
            throw e;
        }
        log.info("附件上传成功 id={} company={} biz={}/{} name={} size={} sha256={}",
                id, companyId, businessType, businessId, originalName, file.getSize(), sha256);
        return toView(doc);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<DocumentView> listByBusiness(String businessType, UUID businessId) {
        if (businessType == null || !BUSINESS_TYPES.contains(businessType) || businessId == null) {
            return List.of(); // 非法业务类型/缺 id 则空集（列表语义，不报错）
        }
        return documentMapper.selectByBusiness(businessType, businessId).stream().map(this::toView).toList();
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public DocumentView getDocument(UUID id) {
        Document doc = requireActive(id);
        return toView(doc);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public FilePayload download(UUID id) {
        Document doc = requireActive(id);
        Path path = resolveStored(doc.getStoragePath());
        if (!Files.isRegularFile(path)) {
            log.warn("附件磁盘文件丢失 id={} path={}", id, doc.getStoragePath());
            throw new NotFoundException("附件文件已丢失");
        }
        // 下载完整性复验：上传期 sha256 已落库，下载重算比对（防磁盘静默损坏/外部篡改）。
        // 不一致 fail-closed 拒绝下载（损坏文件交付出去比拒绝更危险——审计对象完整性）
        if (!sha256Matches(path, doc.getSha256())) {
            log.warn("附件完整性复验失败 id={} path={}（sha256 不一致，拒绝下载）", id, doc.getStoragePath());
            throw new InternalServerException("附件文件已损坏，请联系管理员");
        }
        return new FilePayload(doc.getFileName(), doc.getContentType(), doc.getFileSize(), path);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void deleteDocument(UUID id) {
        RequestContext.requireWriteRole();
        Document doc = requireActive(id);
        if (!documentMapper.softDelete(id)) {
            throw new NotFoundException("附件不存在或无权访问");
        }
        deleteQuietly(doc.getStoragePath()); // 软删元数据后同步移除磁盘文件（无恢复通道，防孤儿累积）
        log.info("附件软删 id={} path={} by={}", id, doc.getStoragePath(), RequestContext.userId());
    }

    // 私有辅助

    /** 业务对象求所属公司（RLS 收敛查询；越权/软删则 null；背景图类型取当前用户所属公司） */
    private UUID resolveCompanyId(String businessType, UUID businessId) {
        return switch (businessType) {
            case "REIMBURSEMENT_LINE" -> reimbursementLineMapper.selectLineCompanyId(businessId);
            case "VOUCHER" -> voucherMapper.selectVoucherCompanyId(businessId);
            // 背景图类型无业务单据：company 取当前用户所属公司（um.users RLS 本人行查询，查无则 40001）
            case "LOGIN_BACKGROUND", "USER_BACKGROUND" -> {
                User user = userMapper.selectById(RequestContext.requireUserId());
                if (user == null || user.getCompanyId() == null) {
                    throw new BadRequestException("用户不存在或未分配公司");
                }
                yield user.getCompanyId();
            }
            default -> null; // BUSINESS_TYPES 已白名单过滤，不可达
        };
    }

    /** 主键查询 + RLS/软删兜底（不可见则 40400） */
    private Document requireActive(UUID id) {
        if (id == null) {
            throw new BadRequestException("附件 id 必填");
        }
        Document doc = documentMapper.selectActiveById(id);
        if (doc == null) {
            throw new NotFoundException("附件不存在或无权访问");
        }
        return doc;
    }

    /** 下载侧完整性复验：重算文件 SHA-256 与落库值比对（false=损坏/被篡改；读流失败也按不匹配处理，fail-closed） */
    private boolean sha256Matches(Path path, String expectedHex) {
        if (expectedHex == null || expectedHex.length() != 64) {
            return false; // 落库值缺失/异常即拒绝（宁严勿松，无哈希的文档按损坏处理）
        }
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest digest = sha256Digest();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expectedHex);
        } catch (IOException e) {
            log.warn("附件复验读取失败 path={} err={}", path, e.getMessage());
            return false;
        }
    }

    /** 字节写本地卷（{yyyyMM} 子目录自动创建），单遍拷贝边写边算 SHA-256，返回 64 位 hex */
    private String storeToDisk(MultipartFile file, String storagePath) throws IOException {
        Path target = resolveStored(storagePath);
        Files.createDirectories(target.getParent());
        MessageDigest digest = sha256Digest();
        try (InputStream in = file.getInputStream();
             OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            deleteQuietly(storagePath); // 写盘中断则清理半成品文件
            throw e;
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e); // JDK 必备算法，理论不可达
        }
    }

    /** 存储根目录（fms.file.storage-dir）解析相对路径，恒服务端生成防穿越 */
    private Path resolveStored(String storagePath) {
        return fmsProperties.getFile().getStorageDirPath().resolve(storagePath).normalize();
    }

    /** 孤儿文件清理（best-effort，失败仅告警不阻断主流程） */
    private void deleteQuietly(String storagePath) {
        try {
            Files.deleteIfExists(resolveStored(storagePath));
        } catch (IOException e) {
            log.warn("附件磁盘清理失败 path={} msg={}", storagePath, e.getMessage());
        }
    }

    /** 原始文件名净化：剥离路径成分（防展示注入/误导），空值兜底 "file" */
    private String safeFileName(String original) {
        if (original == null || original.isBlank()) {
            return "file";
        }
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    /** 扩展名（小写，不含点；无扩展名则返回 ""，白名单外拒绝） */
    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 实体转对外视图（不暴露 storage_path） */
    private DocumentView toView(Document doc) {
        return new DocumentView(doc.getId(), doc.getCompanyId(), doc.getBusinessType(), doc.getBusinessId(),
                doc.getFileName(), doc.getFileSize(), doc.getContentType(), doc.getSha256(),
                doc.getUploaderId(), doc.getCreatedAt());
    }
}
