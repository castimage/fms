-- FMS 财务系统（init/audit_base），audit.audit_log 仅追加审计（FORCE rls + 无 INSERT 授权 + 触发器硬拒）+ sha256 哈希链（prev_hash/row_hash，篡改行即断链）+ audit.record/fn_audit_log（SECURITY DEFINER 写入）+ 链完整性校验 + um/org 审计触发器挂载

-- 执行（按实际部署主机，部署入口为 deploy/init/fms_init.sh，本文件由白名单调度），
--   docker exec fms-db psql -U postgres -d fms -v ON_ERROR_STOP=1 -f /fms-init/init/audit_base.sql

-- 职责，审计哈希链（ip/request_id 纳入公式 + occurred_at 时区无关化）+ 链校验/修复 + audit.record 最小授权

-- 并发安全设计，fn_audit_log_hash 取前驱哈希的并发竞态已根治，
-- 写路径加事务级咨询锁 pg_advisory_xact_lock，后到事务获锁后的 SELECT 在新快照下必然

-- 看到先到事务已提交的行，提交序=链序=id 序，触发器内强制 NEW.id 严格大于末行 id，
-- 不足则重生成 uuidv7，期望 ~2 次迭代，时钟回拨由循环内 pg_sleep 兜底，100 次仍失败
-- WARN 留痕，哈希公式收敛为单一实现 audit.fn_audit_row_hash，触发器/校验/修复三处同源，
-- audit.audit_log_chain_repair，从首断链点起重算重挂，带 NOTICE 留痕，init 幂等调用。


SET ROLE fms_admin;

-- audit.audit_log，仅追加
CREATE TABLE IF NOT EXISTS audit.audit_log (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    actor_id     uuid,
    action       text        NOT NULL,            -- INSERT/UPDATE/DELETE/业务动作
    table_name   text        NOT NULL,
    record_id    uuid,
    old_values   jsonb,
    new_values   jsonb,
    ip           inet,
    request_id   text,
    occurred_at  timestamptz NOT NULL DEFAULT now(),
    prev_hash    char(64),                        -- 上一行 row_hash（首行 '0'×64）
    row_hash     char(64)                         -- sha256 链哈希（见 fn_audit_log_hash）
);
COMMENT ON TABLE audit.audit_log IS
'审计日志（仅追加）：prev_hash/row_hash 构成哈希链，篡改任何一行即断链';
COMMENT ON COLUMN audit.audit_log.row_hash IS
'row_hash = sha256(prev_hash || actor_id || action || table_name || record_id || old_values || new_values || to_char(occurred_at AT TIME ZONE ''UTC'') || ip || request_id)（时区无关化，写链/校验跨会话时区一致）';

CREATE INDEX IF NOT EXISTS idx_audit_time  ON audit.audit_log (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_table ON audit.audit_log (table_name, record_id);

-- 触发器函数
-- 仅追加硬拒（BEFORE UPDATE OR DELETE，防超级用户直连绕过 rls）
CREATE OR REPLACE FUNCTION audit.fn_audit_log_immutable()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = audit, public
AS $$
BEGIN
    RAISE EXCEPTION 'audit_log 仅追加：禁止 UPDATE/DELETE（表 audit.audit_log）';
END $$;

-- 哈希链（BEFORE INSERT，SECURITY DEFINER 属主 fms_admin），actor_id/record_id 以 ::text 化后参与 concat，uuid 无 = 0 比较，ip/request_id 纳入哈希输入，与 chain_check 公式同源（统一 audit.fn_audit_row_hash），写路径篡改可被发现
CREATE OR REPLACE FUNCTION audit.fn_audit_log_hash()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = audit, pg_catalog, public
AS $$
DECLARE
    v_prev   char(64);
    v_max_id uuid;
BEGIN
    -- 哈希链 append 串行化，并发竞态根治之一，prev_hash 必须取自"已提交的末行"。并发 INSERT 时
    -- 两个事务可能基于同一快照读到同一个末行（SELECT ... FOR UPDATE 只锁该行、不重算结果集），
    -- 于是两条记录 prev_hash 相同则后一条断链，重生成/重放窗口内多进程并发 DML 实测触发。
    -- pg_advisory_xact_lock 持有至事务结束，后到事务在锁上等待，获锁后的 SELECT（READ COMMITTED
    -- 新快照）必然看到先到事务已提交的行，提交序 = 链序 = id 序，三者一致。

    -- 锁序说明，捕获触发器在业务 INSERT 之后才取本锁，与业务行锁不构成常见锁环，极端交叉持锁
    -- 由 pg 死锁检测裁决（40P01 中止一方，业务层重试即可）。同事务内多次 INSERT 锁可重入。
    PERFORM pg_advisory_xact_lock(hashtextextended('fms.audit_log.chain', 0));
    SELECT row_hash, id INTO v_prev, v_max_id
      FROM audit.audit_log
     ORDER BY id DESC
     LIMIT 1
       FOR UPDATE;
    IF v_prev IS NULL THEN
        v_prev := repeat('0', 64);                -- 链首
    END IF;
    -- id 有序性强制，并发竞态根治之二，uuidv7 仅 48 位毫秒时间戳有序，同毫秒内的 12 位随机位
    -- 无序，多会话并发 INSERT 落在同一毫秒时，重生成/压测窗口实测，id 序会与写入序错位，
    -- 而链校验按 id 序逐行比对则误判断链。持链锁期间末行 id 稳定，重生成 uuidv7 直至
    -- NEW.id 严格大于 v_max_id，同毫秒下期望 ~2 次迭代，时钟回拨时循环内 pg_sleep 等毫秒刻度
    -- 推进。100 次仍失败，>100ms 级时钟回拨，则 WARN 留痕并接受，链校验/修复兜底发现。
    IF v_max_id IS NOT NULL AND NEW.id <= v_max_id THEN
        FOR i IN 1..100 LOOP
            NEW.id := uuidv7();
            EXIT WHEN NEW.id > v_max_id;
            IF i % 10 = 0 THEN
                PERFORM pg_sleep(0.001);
            END IF;
        END LOOP;
        IF NEW.id <= v_max_id THEN
            RAISE WARNING 'audit.fn_audit_log_hash: 100 次重试仍未生成大于末行 id 的 uuidv7（时钟回拨？），链序可能错乱';
        END IF;
    END IF;
    NEW.prev_hash := v_prev;
    -- occurred_at 以 UTC 规范化参与哈希，timestamptz::text 渲染依赖会话 TimeZone guc，应用
    -- Asia/Shanghai 与 cron/DBA UTC 会话会算出不同字符串致链误报断裂，公式统一走
    -- audit.fn_audit_row_hash，与 audit_log_chain_check/audit_log_chain_repair 同源
    NEW.row_hash  := audit.fn_audit_row_hash(
        v_prev, NEW.actor_id, NEW.action, NEW.table_name, NEW.record_id,
        NEW.old_values, NEW.new_values, NEW.occurred_at, NEW.ip, NEW.request_id);
    RETURN NEW;
END $$;

-- 显式记录函数，SECURITY DEFINER，应用角色无 audit_log 表权限，审计写入统一经此函数与触发器完成

-- 参数，p_action 动作类型（INSERT/UPDATE/DELETE 或业务动作如 voucher_post/period_close），p_table_name 目标表，p_record_id 目标记录 id，p_old_values/p_new_values 变更前后快照 jsonb，p_ip/p_request_id 来源信息（空串则 NULL 入库）
-- 行为，actor_id 从 fms.user_id guc 取（未注入则 NULL），INSERT 由 trg_audit_log_hash 自动补 prev_hash/row_hash 构成哈希链，返回新行 id

-- 表级无 INSERT 授权 + FORCE rls 无写策略 + 不可变触发器硬拒 UPDATE/DELETE，写入仅经本函数与捕获触发器可达，应用角色不可直插伪造审计条目，本文件末与 grants_base.sql 均已 REVOKE PUBLIC EXECUTE，仅 fms_admin 与内部触发器（SECURITY DEFINER 自动提升）可调

-- 调用约定，业务函数（fn_post_voucher/fn_reverse_voucher/fn_close_period 等）在事务内显式调用本函数留痕业务动作，挂载了捕获触发器（fn_audit_log）的表在业务 DML 时自动经 record 写 INSERT/UPDATE/DELETE 行，双通道写入均走同一 INSERT 路径，哈希链在触发器层统一补链，无旁路
CREATE OR REPLACE FUNCTION audit.record(
    p_action     text,
    p_table_name text,
    p_record_id  uuid,
    p_old_values jsonb,
    p_new_values jsonb,
    p_ip         text,
    p_request_id text)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = audit, public
AS $$
DECLARE
    v_id uuid;
BEGIN
    INSERT INTO audit.audit_log
        (actor_id, action, table_name, record_id, old_values, new_values, ip, request_id)
    VALUES
        (NULLIF(current_setting('fms.user_id', true), '')::uuid,
         p_action, p_table_name, p_record_id, p_old_values, p_new_values,
         NULLIF(p_ip, '')::inet, NULLIF(p_request_id, ''))
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;

-- 通用审计捕获触发器函数（业务表 AFTER INSERT/UPDATE/DELETE 挂载，SECURITY DEFINER 以 fms_admin 身份写审计，不受业务表 rls 限制）
-- 行为，按 TG_OP 取变更快照（UPDATE/DELETE 取 OLD，INSERT/UPDATE 取 NEW），记录 id 取 OLD.id（DELETE）或 NEW.id，经 audit.record 写入，捕获路径 ip/request_id 传 NULL，返回 COALESCE(NEW, OLD) 不阻断业务 DML
-- SECURITY DEFINER 使审计写入不受业务表 rls 约束，任何业务 DML（含软删 UPDATE deleted_at=now）自动留痕，触发器函数不对外授权，仅触发器以内置身份调用
CREATE OR REPLACE FUNCTION audit.fn_audit_log()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = audit, public
AS $$
DECLARE
    v_old jsonb;
    v_new jsonb;
    v_id  uuid;
BEGIN
    IF TG_OP IN ('UPDATE','DELETE') THEN
        v_old := to_jsonb(OLD);
    END IF;
    IF TG_OP IN ('INSERT','UPDATE') THEN
        v_new := to_jsonb(NEW);
    END IF;
    IF TG_OP = 'DELETE' THEN
        v_id := OLD.id;
    ELSE
        v_id := NEW.id;
    END IF;
    PERFORM audit.record(TG_OP, TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME,
                         v_id, v_old, v_new, NULL, NULL);
    RETURN COALESCE(NEW, OLD);
END $$;

-- audit_log 触发器实例
DROP TRIGGER IF EXISTS trg_audit_log_immutable ON audit.audit_log;
CREATE TRIGGER trg_audit_log_immutable
    BEFORE UPDATE OR DELETE ON audit.audit_log
    FOR EACH ROW EXECUTE FUNCTION audit.fn_audit_log_immutable();

DROP TRIGGER IF EXISTS trg_audit_log_hash ON audit.audit_log;
CREATE TRIGGER trg_audit_log_hash
    BEFORE INSERT ON audit.audit_log
    FOR EACH ROW EXECUTE FUNCTION audit.fn_audit_log_hash();

-- 哈希链完整性校验（返回首行断链点，NULL = 链完好），verify/system_verify.sql 场景 2.8 初始 NULL，篡改后非 NULL，恢复后 NULL，queues_cron.sql 包装函数 log.fn_cron_audit_chain_check 读 prev_ok/broken_at/detail

-- 返回，prev_ok 链是否完好，broken_at 首行断链点记录 id（NULL=链完好），detail 断链详情 jsonb（断链行 id/动作/期望与存储 row_hash、期望与存储 prev_hash）

-- 行为，自链首（prev='0'×64）按 fn_audit_log_hash 同式逐行重算（ip/request_id 入公式 + occurred_at 时区无关化 to_char AT TIME ZONE 'UTC'），与存储值比对，首处不符即停，首断链点即返回

-- STABLE + SECURITY DEFINER，fms_auditor 与 queues_cron.sql 中的 cron 包装函数（读 prev_ok/broken_at/detail）调用，篡改任何一行（含连带伪造 prev_hash）即被检出

-- 校验语义，逐行顺序依赖 id 与写入序一致，由 fn_audit_log_hash 在链锁内强制
-- NEW.id 严格递增保证，uuidv7 同毫秒随机位无序，未经强制会在并发同毫秒 INSERT 时错位，
-- 断链判定同时比对 row_hash 与 prev_hash，伪造者改行内容后重算 row_hash 还需伪造前一行的
-- prev_hash 引用，逐行前向依赖使篡改必然留下断点，返回首断链点即停止，修复建议为从断点行起
-- 全链重算（audit.audit_log_chain_repair）
CREATE OR REPLACE FUNCTION audit.audit_log_chain_check()
RETURNS TABLE (prev_ok boolean, broken_at uuid, detail jsonb)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = audit, public
AS $$
DECLARE
    v_prev      char(64) := repeat('0', 64);
    v_exp_hash  char(64);
    v_ok        boolean  := true;
    v_broken    uuid     := NULL;
    v_detail    jsonb;
    v_row       record;
BEGIN
    FOR v_row IN
        SELECT id, actor_id, action, table_name, record_id,
               old_values, new_values, occurred_at,
               ip, request_id,
               prev_hash, row_hash
          FROM audit.audit_log
         ORDER BY id
    LOOP
        -- 与 fn_audit_log_hash 同源（audit.fn_audit_row_hash），ip/request_id 入公式 + occurred_at 时区无关化
        v_exp_hash := audit.fn_audit_row_hash(
            v_prev, v_row.actor_id, v_row.action, v_row.table_name, v_row.record_id,
            v_row.old_values, v_row.new_values, v_row.occurred_at, v_row.ip, v_row.request_id);
        IF v_exp_hash <> COALESCE(v_row.row_hash, '') OR v_prev <> COALESCE(v_row.prev_hash, '') THEN
            v_ok     := false;
            v_broken := v_row.id;
            v_detail := jsonb_build_object(
                'broken_row_id', v_row.id,
                'action', v_row.action,
                'table_name', v_row.table_name,
                'expected_hash', v_exp_hash,
                'stored_hash', v_row.row_hash,
                'expected_prev', v_prev,
                'stored_prev', v_row.prev_hash);
            EXIT;                                  -- 首断链点即返回
        END IF;
        v_prev := v_row.row_hash;
    END LOOP;
    prev_ok   := v_ok;
    broken_at := v_broken;
    detail    := v_detail;
    RETURN NEXT;
END $$;

-- 双命名探测兼容（chain_check(date) 形态委托链校验函数）
CREATE OR REPLACE FUNCTION audit.chain_check(p_date date)
RETURNS TABLE (prev_ok boolean, broken_at uuid, detail jsonb)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = audit, public
AS $$
BEGIN
    RETURN QUERY SELECT * FROM audit.audit_log_chain_check();
END $$;

-- audit_log rls，FORCE，超级用户以外无任何 INSERT/UPDATE/DELETE 能力，verify/system_verify.sql 场景 2.6，fms_operator 直插被拒
ALTER TABLE audit.audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.audit_log FORCE ROW LEVEL SECURITY;
-- 策略须含 is_admin 分支（管理员会话的库侧身份是 fms_operator，缺此支则查得空集）；
-- 本策略在 init 中共三处定义（本文件、grants_base.sql、fill_missing.sql），三处必须同文，
-- 否则单独重放任一文件都会把策略改回自己那一版
DROP POLICY IF EXISTS audit_log_select ON audit.audit_log;
CREATE POLICY audit_log_select ON audit.audit_log
    FOR SELECT USING (auth.has_role('fms_auditor')
                      OR current_user = 'fms_admin'
                      OR auth.is_admin());
-- 无 INSERT/UPDATE/DELETE 策略 + 无表级授权则仅审计函数（SECURITY DEFINER）可写

-- 审计捕获触发器挂载（um/org 表，um_org_base.sql 已建）
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'um.params','um.users','um.roles','um.permissions',
        'um.user_role','um.role_perm','um.user_org_scope','um.user_dept_scope',
        'org.companies','org.departments']
    LOOP
        IF to_regclass(t) IS NOT NULL THEN
            EXECUTE format('DROP TRIGGER IF EXISTS trg_%s_audit ON %s',
                           split_part(t, '.', 2), t);
            EXECUTE format('CREATE TRIGGER trg_%s_audit AFTER INSERT OR UPDATE OR DELETE ON %s '
                           || 'FOR EACH ROW EXECUTE FUNCTION audit.fn_audit_log()',
                           split_part(t, '.', 2), t);
        END IF;
    END LOOP;
END $$;

-- 授权，audit schema 仅 fms_auditor 可读，写入仅经 SECURITY DEFINER 函数
GRANT USAGE ON SCHEMA audit TO fms_auditor;
GRANT SELECT ON audit.audit_log TO fms_auditor;
-- audit.record 收回 PUBLIC EXECUTE，默认 proacl 含 =X/，任何持 fms_app 凭据者可伪造审计条目，仅 fms_admin 与内部触发器，SECURITY DEFINER 自动提升，可调用，审计写入路径收敛为捕获触发器 + fms_admin 显式调用
REVOKE EXECUTE ON FUNCTION audit.record(text, text, uuid, jsonb, jsonb, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit.record(text, text, uuid, jsonb, jsonb, text, text)
    TO fms_admin;

-- 审计哈希链（ip/request_id 纳入公式）+ 存量链重算，覆盖上方同名函数，最终生效定义
CREATE OR REPLACE FUNCTION audit.fn_audit_log_hash()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = audit, pg_catalog, public
AS $$
DECLARE
    v_prev   char(64);
    v_max_id uuid;
BEGIN
    -- 哈希链 append 串行化，并发竞态根治之一，并发 INSERT 两事务可能基于同一快照
    -- 读到同一个"末行"（FOR UPDATE 只锁行不重算结果集）则两条记录 prev_hash 相同，断链。
    -- 事务级咨询锁持有至事务结束，获锁后的 SELECT 在新快照下必然看到先到事务已提交的行，
    -- 提交序 = 链序 = id 序，三者一致。同事务内多次 INSERT 锁可重入。
    PERFORM pg_advisory_xact_lock(hashtextextended('fms.audit_log.chain', 0));
    SELECT row_hash, id INTO v_prev, v_max_id
      FROM audit.audit_log
     ORDER BY id DESC
     LIMIT 1
       FOR UPDATE;
    IF v_prev IS NULL THEN
        v_prev := repeat('0', 64);
    END IF;
    -- id 有序性强制，并发竞态根治之二，uuidv7 同毫秒内随机位无序，多会话并发
    -- 同毫秒 INSERT 时 id 序与写入序错位则按 id 校验的链误判断裂。持链锁期间重生成 uuidv7
    -- 直至 NEW.id 严格大于末行 id，同毫秒期望 ~2 次迭代，时钟回拨时 pg_sleep 等毫秒刻度推进，
    -- 100 次仍失败则 WARN 留痕并接受，链校验/修复兜底。
    IF v_max_id IS NOT NULL AND NEW.id <= v_max_id THEN
        FOR i IN 1..100 LOOP
            NEW.id := uuidv7();
            EXIT WHEN NEW.id > v_max_id;
            IF i % 10 = 0 THEN
                PERFORM pg_sleep(0.001);
            END IF;
        END LOOP;
        IF NEW.id <= v_max_id THEN
            RAISE WARNING 'audit.fn_audit_log_hash: 100 次重试仍未生成大于末行 id 的 uuidv7（时钟回拨？），链序可能错乱';
        END IF;
    END IF;
    NEW.prev_hash := v_prev;
    -- occurred_at 以会话时区无关形式参与哈希，timestamptz::text 按会话 TimeZone 渲染，写链/校验
    -- 会话时区不一致会误报断链，公式统一走 audit.fn_audit_row_hash，与校验/修复同源
    NEW.row_hash  := audit.fn_audit_row_hash(
        v_prev, NEW.actor_id, NEW.action, NEW.table_name, NEW.record_id,
        NEW.old_values, NEW.new_values, NEW.occurred_at, NEW.ip, NEW.request_id);
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION audit.audit_log_chain_check()
RETURNS TABLE (prev_ok boolean, broken_at uuid, detail jsonb)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = audit, pg_catalog, public
AS $$
DECLARE
    v_prev      char(64) := repeat('0', 64);
    v_exp_hash  char(64);
    v_ok        boolean  := true;
    v_broken    uuid     := NULL;
    v_detail    jsonb;
    v_row       record;
BEGIN
    FOR v_row IN
        SELECT id, actor_id, action, table_name, record_id,
               old_values, new_values, occurred_at,
               ip, request_id,
               prev_hash, row_hash
          FROM audit.audit_log
         ORDER BY id
    LOOP
        -- 与 fn_audit_log_hash 同源（audit.fn_audit_row_hash）时区无关化
        v_exp_hash := audit.fn_audit_row_hash(
            v_prev, v_row.actor_id, v_row.action, v_row.table_name, v_row.record_id,
            v_row.old_values, v_row.new_values, v_row.occurred_at, v_row.ip, v_row.request_id);
        IF v_exp_hash <> COALESCE(v_row.row_hash, '') OR v_prev <> COALESCE(v_row.prev_hash, '') THEN
            v_ok     := false;
            v_broken := v_row.id;
            v_detail := jsonb_build_object(
                'broken_row_id', v_row.id,
                'action', v_row.action,
                'table_name', v_row.table_name,
                'expected_hash', v_exp_hash,
                'stored_hash', v_row.row_hash,
                'expected_prev', v_prev,
                'stored_prev', v_row.prev_hash);
            EXIT;
        END IF;
        v_prev := v_row.row_hash;
    END LOOP;
    prev_ok   := v_ok;
    broken_at := v_broken;
    detail    := v_detail;
    RETURN NEXT;
END $$;

-- 哈希公式唯一实现（fn_audit_log_hash / audit_log_chain_check / audit_log_chain_repair 三处同源，
-- 杜绝公式口径漂移），sha256(prev || actor_id || action || table_name || record_id ||
-- old_values || new_values || to_char(occurred_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US')
-- || ip || request_id)，NULL 由 concat 自动省略（等价 COALESCE ''），occurred_at 时区无关化，
-- timestamptz::text 按会话 TimeZone 渲染，跨时区会话会算出不同字符串致链误报断裂
CREATE OR REPLACE FUNCTION audit.fn_audit_row_hash(
    p_prev        char(64),
    p_actor_id    uuid,
    p_action      text,
    p_table_name  text,
    p_record_id   uuid,
    p_old_values  jsonb,
    p_new_values  jsonb,
    p_occurred_at timestamptz,
    p_ip          inet,
    p_request_id  text)
RETURNS char(64)
LANGUAGE sql
IMMUTABLE
SET search_path = audit, pg_catalog, public
AS $$
    SELECT encode(digest(
        concat(p_prev,
               COALESCE(p_actor_id::text, ''),
               COALESCE(p_action, ''),
               COALESCE(p_table_name, ''),
               COALESCE(p_record_id::text, ''),
               COALESCE(p_old_values::text, ''),
               COALESCE(p_new_values::text, ''),
               COALESCE(to_char(p_occurred_at AT TIME ZONE 'UTC',
                                'YYYY-MM-DD"T"HH24:MI:SS.US'), ''),
               COALESCE(p_ip::text, ''),
               COALESCE(p_request_id, '')),
        'sha256'), 'hex')
$$;

-- 链修复，幂等，链完好则 no-op，断链则从首断链点起逐行重算重挂，断链点之前的行校验完好保持
-- 原样。覆盖两类断链，并发 INSERT 竞态，fn_audit_log_hash 链锁外的断链在此修复，
-- 绕过触发器写入，session_replication_role=replica 直插 audit_log 产生的 NULL 哈希行。


-- 修复动作与断链点 RAISE NOTICE 留痕，部署/修复日志可审计，与写入触发器共用同一把咨询锁，
-- 修复期间并发写入串行化，不会在修复窗口内插入新的断链行。返回修复后链校验结果。

-- 授权，仅 fms_admin，SECURITY DEFINER 属主，超级用户可绕过 FORCE rls 重写审计行。
CREATE OR REPLACE FUNCTION audit.audit_log_chain_repair()
RETURNS TABLE (prev_ok boolean, broken_at uuid, detail jsonb)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = audit, pg_catalog, public
AS $$
DECLARE
    v_prev  char(64) := repeat('0', 64);
    v_h     char(64);
    v_res   record;
    v_row   record;
    v_cnt   int := 0;
    v_total bigint;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('fms.audit_log.chain', 0));
    SELECT * INTO v_res FROM audit.audit_log_chain_check();
    IF v_res.prev_ok THEN
        prev_ok := true;
        broken_at := NULL;
        detail := NULL;
        RETURN NEXT;
        RETURN;
    END IF;
    SELECT count(*) INTO v_total FROM audit.audit_log;
    ALTER TABLE audit.audit_log DISABLE TRIGGER trg_audit_log_immutable;
    ALTER TABLE audit.audit_log DISABLE TRIGGER trg_audit_log_hash;
    FOR v_row IN
        SELECT id, actor_id, action, table_name, record_id,
               old_values, new_values, occurred_at,
               ip, request_id,
               prev_hash, row_hash
          FROM audit.audit_log
         ORDER BY id
    LOOP
        v_h := audit.fn_audit_row_hash(
            v_prev, v_row.actor_id, v_row.action, v_row.table_name, v_row.record_id,
            v_row.old_values, v_row.new_values, v_row.occurred_at, v_row.ip, v_row.request_id);
        IF v_row.prev_hash IS DISTINCT FROM v_prev OR v_row.row_hash IS DISTINCT FROM v_h THEN
            UPDATE audit.audit_log SET prev_hash = v_prev, row_hash = v_h WHERE id = v_row.id;
            v_cnt := v_cnt + 1;
        END IF;
        v_prev := v_h;
    END LOOP;
    ALTER TABLE audit.audit_log ENABLE TRIGGER trg_audit_log_immutable;
    ALTER TABLE audit.audit_log ENABLE TRIGGER trg_audit_log_hash;
    RAISE NOTICE 'audit.audit_log_chain_repair: 首断链点 % 起修复 %/% 行（断链前 % 行完好）',
        v_res.broken_at, v_cnt, v_total, v_total - v_cnt;
    RETURN QUERY SELECT * FROM audit.audit_log_chain_check();
END $$;

REVOKE EXECUTE ON FUNCTION audit.audit_log_chain_repair() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit.audit_log_chain_repair() TO fms_admin;

-- 存量链修复，幂等，先校验，仅当断链时从首断链点起重算重挂。

-- 断链根因两类，并发 INSERT 竞态，见 fn_audit_log_hash 注释，已加链锁根治，
-- 哈希公式差异/绕过触发器写入（replica 直插），修复动作与断链点均 RAISE NOTICE 留痕。


-- 链完好时零写入，不产生 WAL/掩蔽风险，重复执行结果不变。
DO $$
DECLARE
    v_res record;
    v_total bigint;
BEGIN
    SELECT * INTO v_res FROM audit.audit_log_chain_repair();
    SELECT count(*) INTO v_total FROM audit.audit_log;
    IF v_res.prev_ok THEN
        RAISE NOTICE 'audit_base: 审计哈希链完好（% 行，无需修复）', v_total;
    END IF;
END $$;

-- audit.record 最小授权，与上文 边界说明一致
REVOKE EXECUTE ON FUNCTION audit.record(text, text, uuid, jsonb, jsonb, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit.record(text, text, uuid, jsonb, jsonb, text, text) TO fms_admin;

-- 文件末尾自查
SELECT 'audit_base' AS file_name,
       (SELECT count(*) FROM pg_tables
         WHERE schemaname = 'audit' AND tablename = 'audit_log')              AS table_cnt,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'audit')                                           AS audit_fn_cnt,
       (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
         JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname IN ('um','org') AND NOT t.tgisinternal)               AS capture_trg_cnt;
