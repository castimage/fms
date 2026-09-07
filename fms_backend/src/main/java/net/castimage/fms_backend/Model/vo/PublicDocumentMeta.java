package net.castimage.fms_backend.Model.vo;

/**
 * 公开附件元数据投影（docs.fn_public_document_meta 结果，匿名登录页背景图）：
 * 仅承载 storage_path/file_name/content_type——storage_path 为磁盘相对路径，
 * 服务端拼接存储根目录读取，绝不直接暴露给客户端。
 */
public record PublicDocumentMeta(String storagePath, String fileName, String contentType) {
}
