-- FMS 财务系统（init/docs_base，独立小模块，文件存储），docs 1 表（documents 附件元数据）+ rls + 授权，全部 DDL 幂等

-- 存储形态，文件字节落本地卷（fms-backend 容器 /app/data/files，compose fms-files 命名卷持久化），db 仅存元数据
-- （storage_path 相对路径 + sha256 完整性哈希），挂接业务（business_type + business_id 泛化关联，权限经 rls 公司范围收敛）

-- 业务类型白名单，REIMBURSEMENT_LINE（报销明细发票附件，接入 expense.reimbursement_lines.invoice_file_id 写入链路）/
-- VOUCHER，凭证附件，已接通

-- 安全模型，company_id 恒由业务对象子查询推导，rls 收敛，客户端不可自报，上传越权业务对象即空集 40400，
-- fms_app 沙箱空壳无 docs USAGE（fail-closed），无 DELETE 策略，软删（deleted_at）为唯一删除通道

SET ROLE fms_admin;

CREATE SCHEMA IF NOT EXISTS docs AUTHORIZATION fms_admin;

-- docs.documents 附件元数据表

CREATE TABLE IF NOT EXISTS docs.documents (
    id            uuid         PRIMARY KEY DEFAULT uuidv7(),
    company_id    uuid         NOT NULL,                -- 冗余公司范围，rls 用，上传时由业务对象推导
    business_type TEXT         NOT NULL
                   CHECK (business_type IN ('REIMBURSEMENT_LINE','VOUCHER')),  -- 业务类型白名单，与 Java 侧同构
    business_id   uuid         NOT NULL,                -- 业务单据主键，报销明细行 id / 凭证 id
    file_name     TEXT         NOT NULL,                -- 原始文件名，展示用，含扩展名，存储名恒为服务端生成
    file_size     BIGINT       NOT NULL CHECK (file_size > 0 AND file_size <= 8388608),  -- 单文件上限 8MB（与后端 yaml fms.file.max-size-bytes=8388608 同值对齐，CHECK 恒随该键，低于网关 10MB 请求体上限留 multipart 开销余量，网关侧不重复定义文件上限）
    content_type  TEXT         NOT NULL,                -- 客户端申报 mime，仅记录元数据，白名单裁决以扩展名为准
    storage_path  TEXT         NOT NULL,                -- 相对存储路径（{yyyyMM}/{uuid}.{ext}，服务端生成防穿越）
    sha256        TEXT         NOT NULL CHECK (length(sha256) = 64),  -- 上传期 SHA-256，完整性校验/去重预留
    uploader_id   uuid         NOT NULL REFERENCES um.users (id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ
);
COMMENT ON TABLE  docs.documents IS '附件元数据（文件字节落本地卷，DB 仅存元数据+完整性哈希；RLS 公司范围）';
COMMENT ON COLUMN docs.documents.business_type IS '业务类型（REIMBURSEMENT_LINE/VOUCHER，Java 白名单同构）';
COMMENT ON COLUMN docs.documents.business_id IS '业务单据主键（报销明细行 id / 凭证 id）';
COMMENT ON COLUMN docs.documents.storage_path IS '相对存储路径（服务端生成 {yyyyMM}/{uuid}.{ext}，防穿越）';
COMMENT ON COLUMN docs.documents.sha256 IS '文件 SHA-256（64 位 hex，上传期计算，下载复验预留）';

CREATE INDEX IF NOT EXISTS idx_docs_doc_biz     ON docs.documents (business_type, business_id);
CREATE INDEX IF NOT EXISTS idx_docs_doc_company ON docs.documents (company_id, created_at);

-- rls，公司范围，与 expense.reimbursement_lines 同族，company 经业务对象推导，客户端不可自报

ALTER TABLE docs.documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE docs.documents FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS docs_documents_select ON docs.documents;
CREATE POLICY docs_documents_select ON docs.documents FOR SELECT
    USING (auth.can_access_org(company_id) AND deleted_at IS NULL);
DROP POLICY IF EXISTS docs_documents_insert ON docs.documents;
CREATE POLICY docs_documents_insert ON docs.documents FOR INSERT
    WITH CHECK (auth.can_access_org(company_id));
DROP POLICY IF EXISTS docs_documents_update ON docs.documents;
CREATE POLICY docs_documents_update ON docs.documents FOR UPDATE
    USING (auth.can_access_org(company_id) AND deleted_at IS NULL)
    WITH CHECK (auth.can_access_org(company_id));
-- 无 DELETE 策略，软删（deleted_at=now）为唯一删除通道，物理删除仅经管理通道，与 fa/expense 同族约定

-- 授权（与 grants_base 中央闸同构，operator W / viewer·auditor·report R，fms_app 沙箱不授 USAGE）

GRANT USAGE ON SCHEMA docs TO fms_operator, fms_viewer, fms_auditor, fms_report;
REVOKE USAGE ON SCHEMA docs FROM fms_app;   -- 沙箱最小化（同 fa/expense/budget/wf/rpt）
GRANT SELECT, INSERT, UPDATE ON docs.documents TO fms_operator;
GRANT SELECT ON docs.documents TO fms_viewer, fms_auditor, fms_report;

-- 文件末尾自查（配合 psql -v ON_ERROR_STOP=1）

SELECT 'docs_base' AS file_name,
       (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'docs' AND c.relkind = 'r') AS docs_tbl_cnt,
       (SELECT count(*) FROM pg_policies WHERE schemaname = 'docs') AS docs_policy_cnt;

-- 软删门面（SECURITY DEFINER），rls UPDATE 对 deleted_at 列更新被拒（PG18 rls 新行
-- WITH CHECK 校验 company_id 时 deleted_at=now 触发策略异常，updated_at 正常），
-- 按项目门面模式（同 auth.fn_soft_delete / log.fn_send_access_batch 的属主绕过 + 显式校验模式）采用属主绕过 + 函数内显式公司校验，软删语义不变（deleted_at=now 唯一通道）
CREATE OR REPLACE FUNCTION docs.fn_soft_delete_document(p_id uuid)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = docs, pg_temp
AS $$
DECLARE
    v_cid uuid;
BEGIN
    SELECT company_id INTO v_cid FROM docs.documents
     WHERE id = p_id AND deleted_at IS NULL;
    RAISE NOTICE 'fn_soft_delete: user_id=% org_ids=% cid=%', current_setting('fms.user_id', true), current_setting('fms.org_ids', true), v_cid;
    IF v_cid IS NULL THEN
        RETURN false;                          -- 不存在/已删/越权（rls 补偿，显式公司校验）
    END IF;
    IF NOT auth.can_access_org(v_cid) THEN
        RETURN false;
    END IF;
    UPDATE docs.documents SET deleted_at = now()
     WHERE id = p_id AND deleted_at IS NULL;
    RETURN FOUND;
END $$;
REVOKE ALL ON FUNCTION docs.fn_soft_delete_document(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION docs.fn_soft_delete_document(uuid) TO fms_operator;
