-- FMS 财务系统（init/log_access_batch，执行库，fms），访问日志批量投递函数 log.fn_send_access_batch

-- 网关零外部基础设施依赖，访问日志经 http
--       中转后端 /internal/log-batch（免网关签名 + 内部 token X-FMS-Int-Token），后端批量投递
--       pgmq q_log_access，由 queues_cron.sql 的 log.fn_consume_log_queues 每分钟消费落 log.access_logs
--       供审计。

-- 方案，SECURITY DEFINER 包装函数（属主 fms_admin）一次调用内逐条 pgmq.send，pgmq 无批量 api
--       （仅逐条 send），循环移入 db 内避免 N 次 jdbc 往返，Java 侧 LogQueueMapper
--       单次调用传整个 jsonb 数组，逐条容错，单条失败 NOTICE 继续，best-effort，返回成功条数。

-- 共享映射，消息体到 log.access_logs 行的字段映射收敛为 log.fn_apply_access_log 单一函数，
--       queues_cron.sql 消费链与本文件降级路径共同调用，杜绝三处复制漂移。

-- 权限，/internal 路径后端以 fms_app 空壳连接执行，无身份采信，SandboxSessionInterceptor 跳过
--       注入，函数 SECURITY DEFINER 以属主 fms_admin 执行绕过角色与 rls，EXECUTE 仅授 fms_app。
-- grants_base.sql 的 REVOKE ALL ON ALL FUNCTIONS IN SCHEMA log FROM PUBLIC 早于本文件执行，
--       新建函数默认 PUBLIC EXECUTE，本文件必须显式 REVOKE 兜底。

-- 依赖，queues_cron.sql 已建 q_log_access 队列且 pgmq 核心函数已改 SECURITY DEFINER（属主 fms_admin），
--       grants_base.sql 已授 fms_app log schema USAGE。全部 DDL 幂等可重跑。
SET ROLE fms_admin;

-- 共享落库映射 log.fn_apply_access_log(p_msg jsonb)，q_log_access 消息体映射 log.access_logs
-- 单行 INSERT。单一映射源，queues_cron.sql 的 fn_consume_log_queues 与下方 fn_send_access_batch 降级
-- 分支共同调用，杜绝第三份复制。键名对齐 契约 {method,path,status,latency,ip,ts}
-- + 扩展 ua + 消费链别名 user_id/trace_id，latency 为整数毫秒键，非网关原始 latency_ms，
-- 语义与 queues_cron.sql 消费链一致，method/path 空值兜底、status/latency 空则 NULL、ip 非法则 NULL、
-- ts 空/非法则 now、user_agent 取 ua 键。
-- SECURITY DEFINER（属主 fms_admin），仅内部调用，PUBLIC 显式收回。
CREATE OR REPLACE FUNCTION log.fn_apply_access_log(p_msg jsonb)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = log, pg_temp
AS $$
DECLARE
    v_ts timestamptz;
    v_ip inet;
BEGIN
    v_ts := COALESCE(NULLIF(p_msg->>'ts', '')::timestamptz, now());
    BEGIN
        v_ip := NULLIF(p_msg->>'ip', '')::inet;
    EXCEPTION WHEN OTHERS THEN
        v_ip := NULL;
    END;
    INSERT INTO log.access_logs (method, path, status_code, latency_ms, ip,
                                 user_agent, user_id, trace_id, occurred_at)
    VALUES (COALESCE(p_msg->>'method', 'GET'),
            COALESCE(p_msg->>'path', '/'),
            NULLIF(p_msg->>'status', '')::int,
            NULLIF(p_msg->>'latency', '')::int,
            v_ip,
            p_msg->>'ua',
            NULLIF(p_msg->>'user_id', '')::uuid,
            p_msg->>'trace_id',
            v_ts);
END $$;

COMMENT ON FUNCTION log.fn_apply_access_log(jsonb) IS
'访问日志共享落库映射：q_log_access 消息体 -> log.access_logs 单行（init/queues_cron.sql 消费链与 fn_send_access_batch 降级路径共用，单一映射源防漂移）；SECURITY DEFINER（属主 fms_admin）';

-- 最小授权，内部调用链（消费/降级函数同为 SECURITY DEFINER 属主 fms_admin）本无需授权，
-- 与 fn_send_access_batch 同款显式收回 PUBLIC 后仅授 fms_app，保持最小授权约定一致
REVOKE ALL ON FUNCTION log.fn_apply_access_log(jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION log.fn_apply_access_log(jsonb) TO fms_app;

-- 批量投递，双路径，p_items 为 jsonb 数组（元素 = q_log_access 约定消息体）
--   ① pgmq 可用（pgmq.send 函数存在性探测）则逐条 pgmq.send（第三参 delay_sec=0 即时投递），
--      单条失败 NOTICE 不阻断整批，由 queues_cron.sql 消费链落库，本地库形态，
--   ② pgmq 不可用则逐条经 log.fn_apply_access_log 直落 log.access_logs 兜底，同一审计
--      目标，跳过队列中转，两形态最终数据均落审计表。
CREATE OR REPLACE FUNCTION log.fn_send_access_batch(p_items jsonb)
RETURNS int
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = log, pg_temp
AS $$
DECLARE
    v_i       jsonb;
    v_n       int := 0;
    v_fail    int := 0;
    -- pgmq 可用性以 pg_extension 扩展记录为准，定制镜像已内置 pgmq 并正式
    -- CREATE EXTENSION，记录缺失即视为未安装，走直落表降级
    v_pgmq_ok boolean := EXISTS (
        SELECT 1 FROM pg_extension WHERE extname = 'pgmq'
    );
BEGIN
    -- 防御，非数组入参按 0 处理不抛错，调用方 best-effort 语义
    IF jsonb_typeof(p_items) <> 'array' THEN
        RETURN 0;
    END IF;
    IF v_pgmq_ok THEN
        FOR v_i IN SELECT * FROM jsonb_array_elements(p_items) LOOP
            BEGIN
                PERFORM pgmq.send('q_log_access', v_i, 0);
                v_n := v_n + 1;
            EXCEPTION WHEN OTHERS THEN
                -- 单条失败，队列瞬时不可用/单条非法，NOTICE 继续，不阻断整批
                v_fail := v_fail + 1;
            END;
        END LOOP;
        RAISE NOTICE 'fn_send_access_batch: PGMQ 投递 % 条（成功 %，失败 %）', v_n + v_fail, v_n, v_fail;
        RETURN v_n;
    END IF;
    -- 降级路径，无 pgmq 直接落库，复用共享映射 log.fn_apply_access_log，与消费链同一
    -- 单一映射源，逐条容错，单条非法 NOTICE 继续，不阻断整批，与 pgmq 分支同级
    FOR v_i IN SELECT * FROM jsonb_array_elements(p_items) LOOP
        BEGIN
            PERFORM log.fn_apply_access_log(v_i);
            v_n := v_n + 1;
        EXCEPTION WHEN OTHERS THEN
            -- 单条非法，ip/status/latency 无法 cast 等，NOTICE 继续，不阻断整批
            v_fail := v_fail + 1;
        END;
    END LOOP;
    RAISE NOTICE 'fn_send_access_batch: 降级直落 % 条（成功 %，失败 %）', v_n + v_fail, v_n, v_fail;
    RETURN v_n;
END $$;

COMMENT ON FUNCTION log.fn_send_access_batch(jsonb) IS
'访问日志批量投递（双路径）：PGMQ 可用走 q_log_access 队列（init/queues_cron.sql 消费链落库），不可用经 log.fn_apply_access_log 直落 log.access_logs 兜底；单条失败 NOTICE 继续，返回成功条数；SECURITY DEFINER（属主 fms_admin）供 fms_app 空壳直调，配合后端 /internal/log-batch 端点';

-- 最小授权，grants_base.sql 的 REVOKE PUBLIC 先于本文件执行，新建函数默认 PUBLIC EXECUTE，显式收回后仅授 fms_app
REVOKE ALL ON FUNCTION log.fn_send_access_batch(jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION log.fn_send_access_batch(jsonb) TO fms_app;

-- 降级路径延迟落库测试，直接调共享映射函数，降级分支逐条执行的同一代码，断言键映射
-- 与兜底语义，BEGIN/ROLLBACK 不留脏数据，自查风格
BEGIN;
SET ROLE fms_admin;
SELECT log.fn_apply_access_log(jsonb_build_object(
    'method', 'POST', 'path', '/chk/downgrade', 'status', 200, 'latency', 42,
    'ip', '127.0.0.1', 'ua', 'curl-test', 'user_id', NULL, 'trace_id', 't-27', 'ts', now()));
SELECT '27_chk_downgrade' AS chk,
       count(*) FILTER (WHERE path = '/chk/downgrade' AND latency_ms = 42
                        AND status_code = 200 AND user_agent = 'curl-test'
                        AND ip = '127.0.0.1' AND trace_id = 't-27') AS mapped_ok,
       count(*) FILTER (WHERE path = '/chk/downgrade' AND latency_ms = 0) AS latency_zero_bug
  FROM log.access_logs;
-- 兜底语义，method/path 缺省则 GET//、ts 空则 now、ip 非法则 NULL，单条不抛错
SELECT log.fn_apply_access_log(jsonb_build_object('status', 404, 'latency', 7, 'ip', 'not-an-ip', 'ts', ''));
SELECT '27_chk_fallback' AS chk,
       count(*) FILTER (WHERE status_code = 404 AND ip IS NULL AND method = 'GET'
                        AND path = '/' AND latency_ms = 7) AS fallback_ok
  FROM log.access_logs;
ROLLBACK;

-- 文件末尾自查（配合 psql -v ON_ERROR_STOP=1），两个函数存在 + fms_app 可执行 + PUBLIC 已收回
SELECT 'log_access_batch' AS file_name,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'log' AND p.proname IN ('fn_send_access_batch', 'fn_apply_access_log')) AS fn_cnt,
       has_function_privilege('fms_app', 'log.fn_send_access_batch(jsonb)', 'EXECUTE') AS app_has_execute,
       has_function_privilege('public', 'log.fn_send_access_batch(jsonb)', 'EXECUTE') AS public_has_execute;

RESET ROLE;
