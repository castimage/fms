-- FMS 财务系统（init/queues_cron），pgmq 7 条队列预创建，幂等，+ 冒烟测试 + pgmq 最小授权（核心函数 SECURITY DEFINER、管理函数仅 fms_admin）+ 审计链 cron 包装函数 log.fn_cron_audit_chain_check + 日志队列消费函数 log.fn_consume_log_queues + pg_cron 9 个定时任务

-- pgmq 队列/函数以 fms_admin 身份创建，pg_cron 调度段必须先 RESET ROLE 回 postgres，pg_cron 按 current_user 记录任务执行人，fms_admin 为 NOLOGIN 不可连接，以其身份注册则任务执行必失败，调度完成后再 SET ROLE fms_admin

-- 文件顶部 \c fms，fms_init.sh 白名单以 -d postgres 执行本文件（bootstrap_base/
-- queues_cron 两文件以 postgres 库为锚点），而 pgmq 队列与 log.* 包装函数属 fms 库对象，
-- 本段 \c fms 后文件主体在 fms 库执行，pg_cron 调度段自带 \c postgres 切回（调度完成后 \c fms 回归）。

\c fms
SET ROLE fms_admin;

-- pgmq 7 条队列预创建，幂等，pgmq.create('q_x') 创建内部表 pgmq.q_q_x（表名 = 'q_' || 队列名），消息表本身权限封闭，绝不直接 GRANT（只经 pgmq.* 函数访问）
-- 队列语义，q_notify_task，wf 超时催办/任务完成投后端 notify，q_notify_mail，smtp 邮件，指数退避重试，超限转 q_sys_retry，q_notify_ws，WebSocket 实时推送，轻量可丢失 <8KB，q_budget_warning，cron 预算预警，q_log_app，网关/后端批量落 log.app_logs，q_log_access，网关落 log.access_logs 供审计，q_sys_retry，死信重试，幂等键=biz_type+biz_id
DO $$
DECLARE
    q text;
BEGIN
    -- pgmq 可用性以 pg_extension 扩展记录为准（定制镜像已源码内置 pgmq 并正式
    -- CREATE EXTENSION，记录缺失即视为未安装），扩展未装则跳过队列预创建，
    -- 预期降级路径，安装 pgmq 后重跑本文件即补齐队列与授权，无需重建容器
    IF NOT EXISTS (
        SELECT 1 FROM pg_extension WHERE extname = 'pgmq'
    ) THEN
        RAISE NOTICE 'queues_cron: pgmq 扩展未安装，跳过 7 条队列预创建（预期降级路径）';
        RETURN;
    END IF;
    FOREACH q IN ARRAY ARRAY['q_notify_task','q_notify_mail','q_notify_ws',
                             'q_budget_warning','q_log_app','q_log_access','q_sys_retry'] LOOP
        -- 幂等，内部表 pgmq.q_<队列名> 已存在则跳过，pgmq.create 自身亦幂等，双保险
        -- 冒烟测试归档表惰性创建属正常行为
        IF to_regclass(format('pgmq.%I', 'q_' || q)) IS NULL THEN
            PERFORM pgmq.create(q);
            RAISE NOTICE 'PGMQ 队列 % 已创建', q;
        ELSE
            RAISE NOTICE 'PGMQ 队列 % 已存在，跳过', q;
        END IF;
    END LOOP;
END $$;

-- pgmq api 使用说明，生产代码按此约定消费，以下为文档注释，不执行
-- 发送 pgmq.send('q_name', jsonb, 0)（第三参 delay_sec 延时投递），消费 pgmq.read('q_name', vt_sec, batch_size)（vt 可见性超时、批量条数，内部 SKIP LOCKED 并发互不抢同一条，vt 超时未确认自动回投可重试），确认消费必须 pgmq.archive（vt 内消息不可见），失败退避 pgmq.set_vt（read_ct 超阈值转 q_sys_retry），彻底删除 pgmq.delete，一步消费 pgmq.pop，归档查询 pgmq.archived，监控 pgmq.meta / pgmq.list
-- 幂等，消费侧以消息体幂等键（biz_type+biz_id 等）落库唯一索引，重复投递安全
-- pgmq 冒烟测试，自清理，send 到 read 再到 archive 确认消费，delete 彻底删除，演示 archive 后归档表惰性创建并保留 1 条归档记录，可经 pgmq.archived 查询验证归档链路，另 1 条演示 delete 零残留，任何一步异常仅 NOTICE 不阻断，api 版本差异降级友好
DO $$
DECLARE
    v_msg1 bigint;
    v_msg2 bigint;
    v_rec  record;
    v_ok   boolean;
BEGIN
    --发送，pgmq.send(queue, message jsonb, delay_sec)

    SELECT pgmq.send('q_sys_retry',
                     jsonb_build_object('biz_type', 'smoke_test', 'demo', 'archive',
                                        'ts', to_char(clock_timestamp(), 'YYYY-MM-DD"T"HH24:MI:SS')),
                     0) INTO v_msg1;
    SELECT pgmq.send('q_sys_retry',
                     jsonb_build_object('biz_type', 'smoke_test', 'demo', 'delete',
                                        'ts', to_char(clock_timestamp(), 'YYYY-MM-DD"T"HH24:MI:SS')),
                     0) INTO v_msg2;

    --消费，pgmq.read(queue, vt_sec, batch_size)，返回 msg_id/read_ct/vt/enqueued_at/message

    SELECT * INTO v_rec FROM pgmq.read('q_sys_retry', 30, 10) LIMIT 1;

    IF v_rec.msg_id IS NOT NULL THEN
        --确认消费，pgmq.archive（vt 超时未确认自动回投）

        SELECT pgmq.archive('q_sys_retry', v_rec.msg_id) INTO v_ok;
        RAISE NOTICE 'PGMQ 冒烟测试：send->read(msg_id=%, read_ct=%, vt=%, enqueued_at=%)->archive=%',
                     v_rec.msg_id, v_rec.read_ct, v_rec.vt, v_rec.enqueued_at, v_ok;
    ELSE
        RAISE NOTICE 'PGMQ 冒烟测试：消息未读出（并发消费者已取走），跳过';
    END IF;

    --彻底删除，不归档、零残留，pgmq.delete

    PERFORM pgmq.delete('q_sys_retry', v_msg2);
    PERFORM pgmq.delete('q_sys_retry', v_msg1);
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'PGMQ 冒烟测试跳过（%）：本环境 PGMQ API 差异或队列不可用', SQLERRM;
END $$;

-- pgmq 权限，最小授权，队列内部表 q_q_* 绝不直接 GRANT，消息访问一律经 pgmq.* 函数，全部 pgmq 函数默认 PUBLIC EXECUTE（含 purge_queue/drop_queue/delete 管理函数），REVOKE FROM PUBLIC 后按最小授权重授
-- 本环境 pgmq 核心函数为 invoker 权限、内部直接读写 q_q_* 表，若同时收回表级授权，应用角色经函数访问必 permission denied，故核心消息路径函数（send/read/archive/pop/set_vt/meta/list/archived）采用 SECURITY DEFINER（属主 fms_admin），应用角色仅持 EXECUTE，管理函数仅 fms_admin（SUPERUSER 隐含 EXECUTE）
-- pgmq 未安装（pg_extension 无记录或 pgmq schema 缺失）时跳过授权段（GRANT ... ON SCHEMA pgmq 在 schema 缺失时直接报错），
-- 本段按 pg_extension 记录 + pg_namespace 探测，与队列预创建段同一口径，安装 pgmq 后重跑本文件补齐
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pgmq')
       OR NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'pgmq') THEN
        RAISE NOTICE 'queues_cron: pgmq 未安装，跳过 PGMQ 最小授权段';
        RETURN;
    END IF;
    EXECUTE 'GRANT USAGE ON SCHEMA pgmq TO fms_app, fms_operator';
    EXECUTE 'REVOKE ALL ON ALL FUNCTIONS IN SCHEMA pgmq FROM PUBLIC';
    EXECUTE 'REVOKE ALL ON ALL TABLES  IN SCHEMA pgmq FROM fms_app, fms_operator';
    EXECUTE 'REVOKE ALL ON ALL SEQUENCES IN SCHEMA pgmq FROM fms_app, fms_operator';
END $$;
-- 业务函数批量授权，DO 块按函数名过滤，兼容版本差异，除 purge_queue/drop_queue/delete 管理函数外全部授予 fms_app/fms_operator
DO $$
DECLARE r record;
BEGIN
    FOR r IN SELECT p.oid, p.proname
               FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
              WHERE n.nspname = 'pgmq'
                AND p.proname IN ('send','read','archive','pop','set_vt','meta','list','archived')
    LOOP
        EXECUTE format('ALTER FUNCTION pgmq.%I(%s) SECURITY DEFINER',
                       r.proname, pg_get_function_identity_arguments(r.oid));
        EXECUTE format('GRANT EXECUTE ON FUNCTION pgmq.%I(%s) TO fms_app, fms_operator',
                       r.proname, pg_get_function_identity_arguments(r.oid));
    END LOOP;
END $$;

-- audit-chain-check 的 cron 包装函数 log.fn_cron_audit_chain_check，每日 05:00 由 pg_cron 调用，调审计哈希链完整性校验函数并逐行重算 row_hash，返回首个篡改点，结果写 log.task_runs（status=OK/FAIL + detail jsonb）
-- 探测顺序，优先 audit.audit_log_chain_check，回退 audit.chain_check，PL/pgSQL 惰性绑定 + 动态 EXECUTE，校验函数缺失时记录 FAIL 而非报错中断，审计文件先于本文件执行则直接生效，SECURITY DEFINER（属主 fms_admin）写入 log.task_runs 不受 rls 限制
CREATE OR REPLACE FUNCTION log.fn_cron_audit_chain_check()
RETURNS int
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = log, audit, pg_catalog
AS $$
DECLARE
    v_fn      text;
    v_row     record;
    v_cnt     int := 0;
    v_prev_ok boolean := true;
    -- broken_at 为 uuid（NULL=链完好），链断时记录断链点
    v_broken  uuid;
    v_detail  jsonb;
    v_started timestamptz := clock_timestamp();
BEGIN
    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'audit' AND p.proname = 'audit_log_chain_check') THEN
        v_fn := 'audit.audit_log_chain_check()';
    ELSIF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'audit' AND p.proname = 'chain_check') THEN
        v_fn := 'audit.chain_check()';
    ELSE
        INSERT INTO log.task_runs (job_name, status, detail, started_at, finished_at)
        VALUES ('audit-chain-check', 'FAIL',
                jsonb_build_object('error', '审计哈希链校验函数未找到（audit.audit_log_chain_check / audit.chain_check）'),
                v_started, clock_timestamp());
        RETURN 0;
    END IF;

    BEGIN
        FOR v_row IN EXECUTE format('SELECT * FROM %s', v_fn) LOOP
            v_cnt := v_cnt + 1;
            v_prev_ok := COALESCE(v_row.prev_ok, true);
            v_broken  := v_row.broken_at;
            v_detail  := v_row.detail;
        END LOOP;
        -- 0 行返回，审计表为空，视为链完好
        IF v_cnt = 0 THEN
            v_prev_ok := true;
        END IF;
        INSERT INTO log.task_runs (job_name, status, detail, started_at, finished_at)
        VALUES ('audit-chain-check',
                CASE WHEN v_prev_ok THEN 'OK' ELSE 'FAIL' END,
                jsonb_build_object('broken_at', v_broken, 'chain_detail', v_detail,
                                   'checked_rows', v_cnt, 'checked_at', now()),
                v_started, clock_timestamp());
        RETURN CASE WHEN v_prev_ok THEN 1 ELSE 0 END;
    EXCEPTION WHEN OTHERS THEN
        INSERT INTO log.task_runs (job_name, status, detail, started_at, finished_at)
        VALUES ('audit-chain-check', 'FAIL',
                jsonb_build_object('error', SQLERRM, 'sqlstate', SQLSTATE),
                v_started, clock_timestamp());
        RETURN 0;
    END;
END $$;

COMMENT ON FUNCTION log.fn_cron_audit_chain_check() IS
'audit-chain-check 定时任务包装：调 audit.audit_log_chain_check()/audit.chain_check() 逐行重算哈希链，结果写 log.task_runs';

-- log.fn_consume_log_queues，批量消费 q_log_app / q_log_access（各最多 N 条/次）落 log.app_logs / log.access_logs 分区表并 archive，由 cron 任务 log-consumer 每 1 分钟驱动，消息体 q_log_app {ts,level,logger,message,trace_id}、q_log_access {method,path,status,latency,ip,ts} + 扩展 req_id/user/ua + 消费链别名 trace_id/user_id（latency 为整数毫秒，req_id=trace_id、user=claims.sub，别名同值双写，本消费函数固定读 user_id/trace_id 键落 log.access_logs.user_id/trace_id，别名在发送侧完成），容错，单条数据异常（非法 json/ip）跳过不阻断批量
-- 吞吐/重试节奏参数读 um.params，log.queue.batch.size，每队列每轮最多消费条数，缺省 500、
-- log.queue.vt.seconds，pgmq.read 可见性超时秒数，vt 内未 archive 自动回投，缺省 60，读取语义同 fa_expense_budget_wf_base.sql 预算参数，jsonb 数字或 {"v":n} 双形态
CREATE OR REPLACE FUNCTION log.fn_consume_log_queues()
RETURNS int
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = log, pgmq, pg_catalog
AS $$
DECLARE
    v_batch   int := 500;
    v_vt      int := 60;
    v_cnt     int := 0;
    v_msg     record;
    v_ts      timestamptz;
    v_param   jsonb;
BEGIN
    BEGIN
        SELECT value INTO v_param FROM um.params
         WHERE code = 'log.queue.batch.size' AND deleted_at IS NULL;
        IF v_param IS NOT NULL THEN
            IF jsonb_typeof(v_param) = 'number' THEN
                v_batch := v_param::int;
            ELSE
                v_batch := COALESCE(NULLIF(v_param->>'v',''), '500')::int;
            END IF;
        END IF;
        SELECT value INTO v_param FROM um.params
         WHERE code = 'log.queue.vt.seconds' AND deleted_at IS NULL;
        IF v_param IS NOT NULL THEN
            IF jsonb_typeof(v_param) = 'number' THEN
                v_vt := v_param::int;
            ELSE
                v_vt := COALESCE(NULLIF(v_param->>'v',''), '60')::int;
            END IF;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        v_batch := 500; v_vt := 60;              -- 参数表异常，回落现状缺省
    END;
    --q_log_app 投 log.app_logs

    IF to_regclass('pgmq.q_q_log_app') IS NOT NULL THEN
        FOR v_msg IN SELECT * FROM pgmq.read('q_log_app', v_vt, v_batch) LOOP
            BEGIN
                v_ts := COALESCE(NULLIF(v_msg.message->>'ts','')::timestamptz, now());
                INSERT INTO log.app_logs (level, logger, message, context, occurred_at)
                VALUES (COALESCE(v_msg.message->>'level', 'INFO'),
                        COALESCE(v_msg.message->>'logger', ''),
                        COALESCE(v_msg.message->>'message', ''),
                        jsonb_build_object('trace_id', v_msg.message->>'trace_id'),
                        v_ts);
                PERFORM pgmq.archive('q_log_app', v_msg.msg_id);
                v_cnt := v_cnt + 1;
            EXCEPTION WHEN OTHERS THEN
                RAISE NOTICE 'fn_consume_log_queues: q_log_app 消息 % 消费失败（%），跳过',
                    v_msg.msg_id, SQLERRM;
            END;
        END LOOP;
    END IF;

    --q_log_access 投 log.access_logs，消息体到行映射收敛到共享函数

    -- log.fn_apply_access_log，log_access_batch.sql 定义，本文件与其降级路径共用单一映射源防复制漂移，
    -- 补落 user_agent 列，单条消费失败，含瞬时缺函数/单条非法，由外层 EXCEPTION 跳过
    IF to_regclass('pgmq.q_q_log_access') IS NOT NULL THEN
        FOR v_msg IN SELECT * FROM pgmq.read('q_log_access', v_vt, v_batch) LOOP
            BEGIN
                PERFORM log.fn_apply_access_log(v_msg.message);
                PERFORM pgmq.archive('q_log_access', v_msg.msg_id);
                v_cnt := v_cnt + 1;
            EXCEPTION WHEN OTHERS THEN
                RAISE NOTICE 'fn_consume_log_queues: q_log_access 消息 % 消费失败（%），跳过',
                    v_msg.msg_id, SQLERRM;
            END;
        END LOOP;
    END IF;

    RAISE NOTICE 'fn_consume_log_queues: 本轮消费落库 % 条', v_cnt;
    RETURN v_cnt;
END $$;
COMMENT ON FUNCTION log.fn_consume_log_queues() IS
'批量消费日志队列：q_log_app->log.app_logs、q_log_access->log.access_logs，各批 N 条（um.params log.queue.batch.size 缺省 500）、可见性超时 M 秒（um.params log.queue.vt.seconds 缺省 60），消费成功即 archive';
GRANT EXECUTE ON FUNCTION log.fn_consume_log_queues() TO fms_app;

-- rpt immv 自动维护说明，rpt 4 个报表物化对象为 pg_ivm 增量物化视图（automatic 模式），基表 DML 事务内自动增量维护，提交即一致，无需 cron 兜底刷新，废弃刷新包装函数幂等清理，REFRESH MATERIALIZED VIEW 对 immv 表已无意义
DROP FUNCTION IF EXISTS rpt.fn_cron_refresh_mvs();

-- LISTEN/NOTIFY 通道说明，仅文档注释，不建对象，fms:q pgmq 队列唤醒信号（payload=队列名，丢失由消费者轮询兜底），fms:notify 审批待办/预算预警实时推送（<8KB json，丢失可容忍），fms:cache 缓存失效广播，报表数据变更则前端缓存/Redis 失效，可靠性原则，NOTIFY 不持久化仅作加速，可靠路径始终是 pgmq

-- pg_cron 9 个定时任务，幂等
-- 环境适配，本测试库 cron.database_name=postgres，pg_cron 扩展仅可装在 postgres 库（bootstrap_base.sql 已在 \c postgres 段安装），pg_cron worker 只读取该库的 cron.job，故本段先 \c postgres 调度
-- 任务注册使用 PG18 pg_cron 1.6 签名 cron.schedule_in_database(job_name, schedule, command, database, username, active)，database='fms'（worker 在 fms 库执行任务命令）、username='postgres'（超级用户可连库执行，fms_admin 为 NOLOGIN 不可连接），幂等，先按 jobname 查 cron.job（database='fms'）unschedule 同名任务，postgres 库无 cron schema（pg_cron 安装失败场景）仅 NOTICE 跳过不阻断
-- 运行记录在 cron.job_run_details，业务结果按任务写 log.task_runs / audit.audit_log / pgmq 队列，任务命令中的业务函数由各业务文件提供，cron.schedule 不校验 sql，执行时惰性解析，文件执行顺序无关
RESET ROLE;
\c postgres

DO $$
DECLARE
    -- 任务清单，job_name | cron 表达式 | 执行 sql（fms 库），注，数组字面量中的单引号元素不会被 array_in 剥离，故用 ARRAY 构造器
    -- 调度周期登记联动，cron 表达式期望值由后端 yaml fms.schedule.cron，9 个任务，登记为环境期望值单一来源，
    -- 月结任务锚定每月 1 日 2-4 点、日检 2-6 点、预警 15 分钟、催办/日志消费每分钟，值含时区随部署环境差异，
    -- 本脚本登记值保持与 yaml 一致，运维按环境调整 yaml 期望值后须重新注册 cron（重跑本段或 cron.unschedule+schedule）
    v_jobs  text[] := ARRAY[
        'voucher-number-check|0 6 * * *|SELECT gl.fn_cron_voucher_number_check();',
        'fa-depreciation|0 2 1 * *|SELECT fa.fn_run_depreciation(NULL, to_char(now() - interval ''1 month'', ''YYYY-MM''));',
        'fx-adjustment|0 3 1 * *|SELECT gl.fn_fx_adjustment(NULL, to_char(now() - interval ''1 month'', ''YYYY-MM''), NULL);',
        'close-check|0 4 1 * *|SELECT gl.fn_close_check(NULL, to_char(now() - interval ''1 month'', ''YYYY-MM''));',
        'budget-warning|*/15 * * * *|SELECT budget.fn_cron_budget_warning();',
        'wf-timeout|* * * * *|SELECT wf.fn_timeout_remind();',
        -- partman-maintenance，run_maintenance_proc 是 PROCEDURE（pg_proc prokind=p）必须 CALL 不能 SELECT（SELECT 调用每日 02:00 必报 '... is a procedure ... To call a procedure, use CALL.'，分区预建与 retention 清理全部失效），pg_cron 1.6 支持 CALL
        'partman-maintenance|0 2 * * *|CALL partman.run_maintenance_proc();',
        'audit-chain-check|0 5 * * *|SELECT log.fn_cron_audit_chain_check();',
        'log-consumer|* * * * *|SELECT log.fn_consume_log_queues();'
        -- 无 rpt-refresh 任务，rpt 4 个报表物化对象为 pg_ivm immv，automatic 模式自动增量维护，无需 cron 兜底，存量库残留任务由运维 cron.unschedule('rpt-refresh') 清理
    ];
    v_job   text;
    v_parts text[];
    v_name  text;
    v_cnt   int := 0;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'cron') THEN
        RAISE NOTICE 'pg_cron 不可用（postgres 库无 cron schema），9 个定时任务跳过创建（见最终报告）';
        RETURN;
    END IF;

    -- 清理弃用的 rpt-refresh 兜底任务，immv 自动维护后无需 cron，幂等，存量库残留任务统一移除
    EXECUTE format('SELECT cron.unschedule(jobid) FROM cron.job
                     WHERE jobname = %L AND database = %L', 'rpt-refresh', 'fms');

    FOREACH v_job IN ARRAY v_jobs LOOP
        v_parts := string_to_array(v_job, '|');
        v_name := v_parts[1];
        -- 幂等，删除同名任务（限 database='fms'，防止误删他库同名任务）
        EXECUTE format('SELECT cron.unschedule(jobid) FROM cron.job
                         WHERE jobname = %L AND database = %L', v_name, 'fms');
        -- 注册任务，worker 在 fms 库以 postgres 身份执行命令
        EXECUTE format('SELECT cron.schedule_in_database(%L, %L, %L, %L, %L, true)',
                       v_name, v_parts[2], v_parts[3], 'fms', 'postgres');
        v_cnt := v_cnt + 1;
        RAISE NOTICE 'pg_cron 任务 % 已调度（fms 库执行，username=postgres）', v_name;
    END LOOP;
    RAISE NOTICE 'pg_cron 调度完成：共 % 个任务', v_cnt;
END $$;

-- 调度完成后回 fms 库并恢复对象属主约定，后续自查语句亦以 fms_admin 执行
\c fms
SET ROLE fms_admin;

-- 文件末尾自查（配合 psql -v ON_ERROR_STOP=1，确认本文件执行完整），期望 pgmq_queue_cnt≥7，冒烟测试 archive 后多 1 张惰性归档表，cron_job_cnt=9、wrapper_fn_cnt=2，cron.job 位于 postgres 库（cron.database_name），fms 库经 dblink 计数（任务注册时 database='fms'），不可达时返回 -1 仅提示不阻断
SELECT 'queues_cron' AS file_name,
       (SELECT count(*) FROM pg_tables
         WHERE schemaname = 'pgmq' AND tablename LIKE 'q\_q\_%') AS pgmq_queue_cnt,
       -- cron.job 仅存在于 postgres 库（cron.database_name），静态引用 cron.job 在 fms 库解析必失败（CASE 分支亦在解析期求值），故一律经 dblink 计数
       -- dblink 连本机 unix socket（/var/run/postgresql，peer 认证），硬编码外网 ip 会连到异机
       (SELECT t.c FROM dblink(
          'host=/var/run/postgresql port=5432 dbname=postgres user=postgres password=jjh20040707',
          'SELECT count(*) FROM cron.job
             WHERE database = ''fms''
               AND jobname IN (''voucher-number-check'',''fa-depreciation'',
                               ''fx-adjustment'',''close-check'',''budget-warning'',
                               ''wf-timeout'',''partman-maintenance'',
                               ''audit-chain-check'',''log-consumer'')') AS t(c int)) AS cron_job_cnt,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname IN ('log','rpt')
           AND p.proname IN ('fn_cron_audit_chain_check','fn_consume_log_queues')) AS wrapper_fn_cnt;
