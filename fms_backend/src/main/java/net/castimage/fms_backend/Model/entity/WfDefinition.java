package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import net.castimage.fms_backend.common.handler.JsonbTypeHandler;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 工作流流程定义实体（wf.definitions）：node_json 节点编排 JSONB
 * （[{node_code, type, assignee_role}]，实体映射 String + fastjson2 解析校验）；
 * 状态机经 DRAFT 启用 ACTIVE、可停用 DISABLED（激活前校验节点编排非空）；(company_id, code) 唯一
 * （uk_wf_def）——code 创建后不可变更。
 */
@TableName(schema = "wf", value = "definitions")
@Data
public class WfDefinition {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司（RLS can_access_org 行级收敛） */
    private UUID companyId;

    /** 流程编码（公司内唯一，如 REIMBURSEMENT） */
    private String code;

    /** 流程名称 */
    private String name;

    /** 流程版本 */
    private Integer version;

    /** 关联表单类型 */
    private String formType;

    /** 节点编排 JSONB（String 透传，fastjson2 解析校验；JsonbTypeHandler 防 42804 jsonb 类型不匹配） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String nodeJson;

    /** 状态：DRAFT/ACTIVE/DISABLED */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（触发器刷新） */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
