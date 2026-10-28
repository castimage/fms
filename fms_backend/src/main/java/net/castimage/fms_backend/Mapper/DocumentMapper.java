package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.Document;
import net.castimage.fms_backend.Model.vo.PublicDocumentMeta;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * 附件元数据 Mapper（docs.documents）：单表软删经 BaseMapper + Wrapper；按业务对象
 * 列表/主键查询/软删手写——RLS 公司范围收敛（越权恒空集，不泄露存在性）。
 */
public interface DocumentMapper extends BaseMapper<Document> {

    /** 按业务对象列附件（创建时间倒序；RLS 收敛，越权恒空集） */
    @Select("""
            SELECT id, company_id, business_type, business_id, file_name, file_size,
                   content_type, storage_path, sha256, uploader_id, created_at, updated_at, deleted_at
              FROM docs.documents
             WHERE business_type = #{businessType} AND business_id = #{businessId}
               AND deleted_at IS NULL
             ORDER BY created_at DESC
            """)
    List<Document> selectByBusiness(@Param("businessType") String businessType,
                                    @Param("businessId") UUID businessId);

    /** 附件主键查询（RLS 收敛；越权/软删则 null，由 Service 映射 40400） */
    @Select("""
            SELECT id, company_id, business_type, business_id, file_name, file_size,
                   content_type, storage_path, sha256, uploader_id, created_at, updated_at, deleted_at
              FROM docs.documents
             WHERE id = #{id} AND deleted_at IS NULL
            """)
    Document selectActiveById(@Param("id") UUID id);

    /** 软删附件（RLS UPDATE 策略行级收敛；越权影响 0 行） */
    @Select("SELECT docs.fn_soft_delete_document(#{id})")
    boolean softDelete(@Param("id") UUID id);

    /**
     * 公开附件元数据（匿名登录页背景图）：docs.fn_public_document_meta SECURITY DEFINER
     * 读口，仅放行 LOGIN_BACKGROUND 且未删（其他类型/已删/不存在则空行），绕过 RLS
     * 的最小暴露面；字节读取由后端按 storage_path 直读磁盘，不经本查询路径。
     */
    @Select("""
            SELECT storage_path, file_name, content_type
              FROM docs.fn_public_document_meta(#{id})
            """)
    PublicDocumentMeta selectPublicDocumentMeta(@Param("id") UUID id);
}
