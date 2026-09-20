-- FMS 财务系统（init/notify_broadcast），通知生成源，系统公告/预算预警全量广播 + 单用户通知
-- 插入的 SECURITY DEFINER 闸口 + pgmq 消费配套。

-- notify.notifications 的 rls INSERT 策略为 WITH CHECK (user_id = auth.current_uid)，
-- 应用角色（fms_app/fms_operator）无法批量插入他人通知，广播/后台生成必须经属主
-- fms_admin 的 SECURITY DEFINER 函数绕过，与 log_access_batch 同款模式，q_notify_task /
-- q_budget_warning 两条队列由 init/queues_cron.sql 预创建，后端
-- NotifyQueueConsumer 消费后经本文件两个函数落库。

-- 幂等可重跑（CREATE OR REPLACE / DROP IF EXISTS / GRANT 幂等）。

SET ROLE fms_admin;

-- 全量广播闸口，系统公告/公司级预警，向全部 ACTIVE 用户逐行 INSERT（rls 由

-- SECURITY DEFINER 绕过，属主 fms_admin 超级用户不受 rls 限制），幂等，同
-- ref_type+ref_id+title 的通知已存在则整批跳过，防 cron/重试重复，budget-warning
-- 每 15 分钟触发一次，同一公司同一标题不得反复打扰全员。返回实际插入行数。
-- 预检之外插入带 ON CONFLICT DO NOTHING，并发两次广播同时插入同一用户时后到者静默跳过，
-- 不因唯一键 uk_notify_user_ref 冲突而整批报错；函数开头以 ref_type+ref_id+title 取事务级
-- 咨询锁把同键广播串行化，补上预检与插入之间的并发窗口，公告等 ref_id 为空的路径亦受其保护。
-- 等锁上限可配，取系统参数 notify.broadcast_lock_timeout_ms（缺省 10000 毫秒，参数页可改），
-- 函数内经 set_config(lock_timeout, is_local=true) 只施加于本锁并在取到后还原，
-- 超时抛 55P03 而不无限期排队，插入与审计触发器的等锁不受该上限约束。

-- 高权限语义，任何持 EXECUTE 的会话（已授 fms_app/fms_operator）都能向全部 ACTIVE 用户插通知，
-- 安全性不靠 rls（SECURITY DEFINER 已绕过）而靠调用面收敛，系统公告仅 ADMIN 端点可达，
-- NotifyServiceImpl.broadcast 前置 requireAdminRole 闸，普通 operator 到不了该端点，
-- 预算预警仅后台消费者（q_budget_warning）触发，无业务端点暴露，EXECUTE 授权即权限边界。
CREATE OR REPLACE FUNCTION notify.fn_broadcast_notification(
    p_title text, p_content text, p_ref_type text, p_ref_id uuid)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = notify, um, pg_catalog
AS $$
DECLARE
    v_cnt        bigint := 0;
    v_timeout_ms int := 10000;
    v_cfg        jsonb;
    v_prev       text;
BEGIN
    -- 等锁上限取系统参数 notify.broadcast_lock_timeout_ms，缺省 10000 毫秒，
    -- 值形态兼容裸数字与 {"v":数字}，空值与非正数回落缺省，超 60000 截断；
    -- 空值必须显式判，NULL 参与 <= 比较得 NULL 而非真，漏判即把上限重置为不设限
    BEGIN
        v_cfg := um.fn_get_param_value('notify.broadcast_lock_timeout_ms', to_jsonb(v_timeout_ms));
        v_timeout_ms := CASE WHEN jsonb_typeof(v_cfg) = 'object'
                             THEN (v_cfg ->> 'v')::int
                             ELSE (v_cfg #>> '{}')::int END;
        IF v_timeout_ms IS NULL OR v_timeout_ms <= 0 THEN
            v_timeout_ms := 10000;
        ELSIF v_timeout_ms > 60000 THEN
            v_timeout_ms := 60000;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        v_timeout_ms := 10000;
    END;

    -- 同键广播串行化，预检与插入非原子，并发两次相同广播会双双通过预检而重复插给全员；
    -- 公告路径 ref_id 恒为 NULL，唯一索引对该路径不生效，只能靠本锁收口。
    -- 键与预检同口径即 ref_type+ref_id+title，事务级锁随提交或回滚自动释放。
    -- 上限只包住本锁，取到即还原，插入与审计触发器的等锁维持与系统其他写入一致的既有行为，
    -- 否则无关的长事务持有审计链锁时会把一次键不冲突的正常广播也打断；
    -- 等锁超时报错而非无限期排队，公告由管理员重试，预算预警消息留队回投
    v_prev := current_setting('lock_timeout');
    PERFORM set_config('lock_timeout', v_timeout_ms::text || 'ms', true);
    PERFORM pg_advisory_xact_lock(hashtext(
        COALESCE(p_ref_type, '') || ':' || COALESCE(p_ref_id::text, '') || ':' || COALESCE(p_title, '')));
    PERFORM set_config('lock_timeout', v_prev, true);

    -- 幂等预检，同 ref_type+ref_id+title 已存在，含软删历史，防重跑恢复，即整批跳过
    IF EXISTS (
        SELECT 1 FROM notify.notifications
         WHERE ref_type IS NOT DISTINCT FROM p_ref_type
           AND ref_id   IS NOT DISTINCT FROM p_ref_id
           AND title    = p_title
    ) THEN
        RETURN 0;
    END IF;

    INSERT INTO notify.notifications (user_id, title, content, type, ref_type, ref_id)
    SELECT u.id, p_title, COALESCE(p_content, ''), 'SYSTEM', p_ref_type, p_ref_id
      FROM um.users u
     WHERE u.status = 'ACTIVE'
       AND u.deleted_at IS NULL
    ON CONFLICT (user_id, ref_type, ref_id) WHERE deleted_at IS NULL DO NOTHING;
    GET DIAGNOSTICS v_cnt = ROW_COUNT;
    RETURN v_cnt;
END;
$$;
COMMENT ON FUNCTION notify.fn_broadcast_notification(text,text,text,uuid)
    IS '系统公告/公司级预警全量广播：向全部 ACTIVE 用户逐行 INSERT（SECURITY DEFINER 绕过 RLS）；幂等（同 ref_type+ref_id+title 已存在整批跳过，插入冲突按 uk_notify_user_ref 静默跳过），同键并发由事务级咨询锁串行化，等锁上限取参数 notify.broadcast_lock_timeout_ms，返回插入行数';

-- 单用户通知插入闸口，pgmq 消费落库，q_notify_task 任务完成/超时催办按 assignee_id 落库，

-- rls 由 SECURITY DEFINER 绕过，消费者线程无会话身份，auth.current_uid 恒 NULL。
CREATE OR REPLACE FUNCTION notify.fn_insert_notification(
    p_user_id uuid, p_title text, p_content text,
    p_type text DEFAULT 'SYSTEM', p_ref_type text DEFAULT NULL, p_ref_id uuid DEFAULT NULL)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = notify, um, pg_catalog
AS $$
BEGIN
    IF p_user_id IS NULL THEN
        RAISE EXCEPTION '通知收件人不能为空';
    END IF;
    -- 收件人必须是现存 ACTIVE 用户，消息可能滞后于用户停用/删除，落库前校验防脏数据
    IF NOT EXISTS (SELECT 1 FROM um.users
                    WHERE id = p_user_id AND status = 'ACTIVE' AND deleted_at IS NULL) THEN
        RAISE EXCEPTION '通知收件人不存在或已停用';
    END IF;
    -- 同用户同 ref_type 同 ref_id 已存在即静默跳过，消息重投与消费者并发重放不重复落库，
    -- ref 为空的通知不受唯一键约束，可重复插入
    INSERT INTO notify.notifications (user_id, title, content, type, ref_type, ref_id)
    VALUES (p_user_id, p_title, COALESCE(p_content, ''), COALESCE(p_type, 'SYSTEM'),
            p_ref_type, p_ref_id)
    ON CONFLICT (user_id, ref_type, ref_id) WHERE deleted_at IS NULL DO NOTHING;
END;
$$;
COMMENT ON FUNCTION notify.fn_insert_notification(uuid,text,text,text,text,uuid)
    IS '单用户通知插入（PGMQ 消费落库）：SECURITY DEFINER 绕过 RLS，收件人须为现存 ACTIVE 用户；同用户同 ref_type 同 ref_id 幂等，冲突静默跳过';

-- 授权，应用角色仅持 EXECUTE，函数体以 fms_admin 身份执行，不授予表级写权限，

-- 与 log_access_batch 同款最小授权模式
-- PUBLIC 须显式收回，CREATE FUNCTION 默认带 PUBLIC EXECUTE，通知域不在 grants_base 的批量清扫范围内
REVOKE ALL ON FUNCTION notify.fn_broadcast_notification(text,text,text,uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION notify.fn_insert_notification(uuid,text,text,text,text,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notify.fn_broadcast_notification(text,text,text,uuid)
    TO fms_app, fms_operator;
GRANT EXECUTE ON FUNCTION notify.fn_insert_notification(uuid,text,text,text,text,uuid)
    TO fms_app, fms_operator;

-- 广播等锁上限的系统参数种子，缺省 10 秒，运维在 Web 系统参数页（元数据见 um_param_meta.sql）
-- 或经 um.fn_upsert_param 调整，改后下一次广播即生效，无需重跑 init。
-- ON CONFLICT DO NOTHING 保留运维改过的值，重跑本文件不覆盖。

INSERT INTO um.params (code, value, updated_by)
VALUES ('notify.broadcast_lock_timeout_ms', '10000'::jsonb,
        '00000000-0000-7000-0000-000000000001')
ON CONFLICT (code) DO NOTHING;

-- 安全告警检测函数族，AlertService 定时调度，以 fms_app 直连调用，无会话身份，

-- 全部 SECURITY DEFINER 只读/通知类，无提权面，暴力破解聚合、审计链状态、admin 站内信
CREATE OR REPLACE FUNCTION notify.fn_alert_login_failures(
    p_threshold int DEFAULT 20, p_window_min int DEFAULT 15)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = notify, um, pg_catalog
AS $$
DECLARE
    v_rows jsonb;
BEGIN
    -- 窗口内同 ip 登录失败聚合，暴力破解模式检测，超阈值返回 [{ip, count}]，空数组 = 无异常
    SELECT COALESCE(jsonb_agg(jsonb_build_object('ip', ip, 'count', cnt) ORDER BY cnt DESC), '[]'::jsonb)
      INTO v_rows
      FROM (SELECT ip, count(*) AS cnt
              FROM um.login_log
             WHERE success = false
               AND occurred_at > now() - make_interval(mins => p_window_min)
               AND ip IS NOT NULL
             GROUP BY ip
            HAVING count(*) >= p_threshold) t;
    RETURN v_rows;
END $$;
COMMENT ON FUNCTION notify.fn_alert_login_failures(int, int)
    IS '暴力破解检测：窗口内同 IP 登录失败聚合（阈值默认 20 次/15 分钟），返回 [{ip,count}] 超阈值列表';

CREATE OR REPLACE FUNCTION notify.fn_alert_audit_chain()
RETURNS uuid
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = notify, audit, pg_catalog
AS $$
DECLARE
    v_broken uuid;
BEGIN
    -- 审计哈希链完整性，委托 audit.audit_log_chain_check，属主 fms_admin 超户直调，
    -- 返回首断链点（NULL = 链完好），篡改检测告警依据
    SELECT broken_at INTO v_broken FROM audit.audit_log_chain_check();
    RETURN v_broken;
END $$;
COMMENT ON FUNCTION notify.fn_alert_audit_chain()
    IS '审计哈希链状态：返回首断链点（NULL=完好），供篡改告警';

CREATE OR REPLACE FUNCTION notify.fn_alert_notify_admin(p_title text, p_content text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = notify, um, pg_catalog
AS $$
DECLARE
    v_admin uuid;
BEGIN
    -- 告警站内信发 admin，首个 ACTIVE admin 用户，无 admin 时静默跳过，防空转异常
    SELECT id INTO v_admin FROM um.users
     WHERE login_name = 'admin' AND status = 'ACTIVE' AND deleted_at IS NULL
     ORDER BY created_at LIMIT 1;
    IF v_admin IS NOT NULL THEN
        PERFORM notify.fn_insert_notification(v_admin, p_title, COALESCE(p_content, ''), 'SECURITY');
    END IF;
END $$;
COMMENT ON FUNCTION notify.fn_alert_notify_admin(text, text)
    IS '安全告警站内信：发 admin（SECURITY 类型），无 admin 用户时静默跳过';

-- 告警函数族授权，AlertService 定时以 fms_app 直连调用（无会话身份），
-- PUBLIC 收回后按最小授权重授，与 fn_alert_replica_lag 同款
REVOKE ALL ON FUNCTION notify.fn_alert_login_failures(int,int) FROM PUBLIC;
REVOKE ALL ON FUNCTION notify.fn_alert_audit_chain() FROM PUBLIC;
REVOKE ALL ON FUNCTION notify.fn_alert_notify_admin(text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notify.fn_alert_login_failures(int,int) TO fms_app;
GRANT EXECUTE ON FUNCTION notify.fn_alert_audit_chain() TO fms_app;
GRANT EXECUTE ON FUNCTION notify.fn_alert_notify_admin(text,text) TO fms_app;

-- 主从复制延迟检测（AlertService 定时调度），pg_stat_replication 仅 superuser/

-- pg_monitor 可见，fms_app 无权限，经 SECURITY DEFINER 属主（fms_admin）读取后
-- 返回聚合 jsonb {replicas, max_lag_ms, degraded}，replicas=walsender 总数，含
-- 非 streaming 状态，max_lag_ms=streaming 中最大 replay 延迟，毫秒，无则 0，
-- degraded=存在非 streaming 状态的 walsender（catchup/startup 等），从库完全
-- 断开时 walsender 消失，replicas 计数下降，由 Java 侧"期望从库数"配置判定缺失。
CREATE OR REPLACE FUNCTION notify.fn_alert_replica_lag()
RETURNS jsonb
LANGUAGE sql
SECURITY DEFINER
SET search_path = notify, pg_catalog
AS $$
    SELECT jsonb_build_object(
        'replicas', (SELECT count(*) FROM pg_stat_replication),
        'max_lag_ms', COALESCE((SELECT max(extract(epoch FROM replay_lag) * 1000)::bigint
                                 FROM pg_stat_replication WHERE state = 'streaming'), 0),
        'degraded', (SELECT count(*) FROM pg_stat_replication
                      WHERE state IS DISTINCT FROM 'streaming') > 0)
$$;
COMMENT ON FUNCTION notify.fn_alert_replica_lag()
    IS '主从复制状态聚合（SECURITY DEFINER，pg_stat_replication 仅属主可见）：{replicas, max_lag_ms, degraded}';
REVOKE ALL ON FUNCTION notify.fn_alert_replica_lag() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notify.fn_alert_replica_lag() TO fms_app;

-- 文件末尾自查（配合 psql -v ON_ERROR_STOP=1）
SELECT 'notify_broadcast' AS file_name,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'notify' AND p.proname IN
               ('fn_broadcast_notification','fn_insert_notification')) AS gateway_fn_cnt,
       (SELECT count(*) FROM pg_policies WHERE schemaname = 'notify') AS policy_cnt;
