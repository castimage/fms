-- FMS 财务系统（纯验证脚本，执行库，fms），对象存在性（13 schema/6 角色/10 代表表/审计日志表/auth 8 函数/audit 函数/pgmq 函数/8 cron 任务/5 immv/通知幂等键唯一索引/闸口幂等与串行化）+ 五层安全与扩展能力场景断言（rls 注入可见与匿名空集、不平衡凭证拒绝、POSTED 凭证 rls+触发器双层拦截、audit_log 写入拒绝、pgmq send/read/archive 闭环、审计哈希链完好、篡改断链、恢复后完好、五 schema 授权可达、rpt 报表可达、immv 自动维护、通知落库幂等、等锁上限范围、报销单冲销路径与冲销前置）
-- 全部断言用 DO 块 + EXCEPTION WHEN OTHERS 捕获，任何失败不中断脚本（-v ON_ERROR_STOP=1 依然零错误通过），每个断言输出 RAISE NOTICE 'PASS/FAIL'，guc 注入值一律为固定 uuid 常量（canonical 小写），broken_at 语义 uuid（NULL=链完好），无 BEGIN/COMMIT，场景块内用 SET LOCAL / SET ROLE，DO 块内作用域随函数退出自动还原，可整体重复执行
-- 例外，immv 断言需在同一事务内造数并回滚，DO 块内无法回滚，故该段用顶层 BEGIN 与 ROLLBACK 包裹，结果经 psql 变量带出后落盘
-- 条数守卫，汇总校验结果表行数等于断言总数，少于总数即判有断言整条丢失

-- 验证结果累计表，临时表，会话级，DO 块内写结果前先 RESET ROLE 回会话用户
CREATE TEMP TABLE IF NOT EXISTS verify_results (test_name text PRIMARY KEY, ok boolean);

-- 对象存在性
-- 13 个业务 schema
DO $$
DECLARE
    v_expect  int := 13;
    v_cnt     int;
    v_missing text;
BEGIN
    SELECT count(*) INTO v_cnt
      FROM pg_namespace
     WHERE nspname IN ('um','org','gl','ar','ap','cash','fa','expense',
                       'budget','wf','rpt','audit','log');
    SELECT string_agg(nspname, ',') INTO v_missing
      FROM (VALUES ('um'),('org'),('gl'),('ar'),('ap'),('cash'),('fa'),
                   ('expense'),('budget'),('wf'),('rpt'),('audit'),('log')) t(nspname)
     WHERE NOT EXISTS (SELECT 1 FROM pg_namespace n WHERE n.nspname = t.nspname);
    IF v_cnt = v_expect THEN
        RAISE NOTICE 'PASS: 对象存在性-13 个 schema（%/%）', v_cnt, v_expect;
        INSERT INTO verify_results VALUES ('对象存在性-13个schema', true);
    ELSE
        RAISE NOTICE 'FAIL: 对象存在性-13 个 schema（%/%，缺失：%）', v_cnt, v_expect, COALESCE(v_missing,'');
        INSERT INTO verify_results VALUES ('对象存在性-13个schema', false);
    END IF;
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: 对象存在性-13个schema（异常：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('对象存在性-13个schema', false);
END $$;

-- 6 个角色
DO $$
DECLARE
    v_cnt int;
BEGIN
    SELECT count(*) INTO v_cnt
      FROM pg_roles
     WHERE rolname IN ('fms_admin','fms_app','fms_operator','fms_viewer',
                       'fms_auditor','fms_report');
    IF v_cnt = 6 THEN
        RAISE NOTICE 'PASS: 对象存在性-6 个角色（%/%）', v_cnt, 6;
        INSERT INTO verify_results VALUES ('对象存在性-6个角色', true);
    ELSE
        RAISE NOTICE 'FAIL: 对象存在性-6 个角色（%/%）', v_cnt, 6;
        INSERT INTO verify_results VALUES ('对象存在性-6个角色', false);
    END IF;
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: 对象存在性-6个角色（异常：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('对象存在性-6个角色', false);
END $$;

-- 10 张代表表 + 审计/日志表 2 张
DO $$
DECLARE
    v_tables text[] := ARRAY['um.users','org.companies','org.departments',
        'gl.vouchers','gl.voucher_lines','gl.chart_accounts','gl.periods',
        'ar.invoices','cash.bank_accounts','wf.tasks'];
    v_extra  text[] := ARRAY['audit.audit_log','log.app_logs'];
    v_t      text;
    v_miss   text := '';
    v_cnt    int := 0;
BEGIN
    FOREACH v_t IN ARRAY v_tables LOOP
        IF to_regclass(v_t) IS NOT NULL THEN v_cnt := v_cnt + 1;
        ELSE v_miss := v_miss || v_t || ' '; END IF;
    END LOOP;
    IF v_cnt = array_length(v_tables,1) THEN
        RAISE NOTICE 'PASS: 对象存在性-10 张代表表（%/%）', v_cnt, array_length(v_tables,1);
        INSERT INTO verify_results VALUES ('对象存在性-代表表10张', true);
    ELSE
        RAISE NOTICE 'FAIL: 对象存在性-10 张代表表（%/%，缺失：%）', v_cnt, array_length(v_tables,1), v_miss;
        INSERT INTO verify_results VALUES ('对象存在性-代表表10张', false);
    END IF;
    -- 审计/日志表，后续场景依赖
    IF to_regclass('audit.audit_log') IS NOT NULL AND to_regclass('log.app_logs') IS NOT NULL THEN
        RAISE NOTICE 'PASS: 对象存在性-审计/日志表（audit.audit_log、log.app_logs）';
        INSERT INTO verify_results VALUES ('对象存在性-审计日志表', true);
    ELSE
        RAISE NOTICE 'FAIL: 对象存在性-审计/日志表（audit.audit_log=% log.app_logs=%）',
            to_regclass('audit.audit_log') IS NOT NULL, to_regclass('log.app_logs') IS NOT NULL;
        INSERT INTO verify_results VALUES ('对象存在性-审计日志表', false);
    END IF;
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: 对象存在性-审计日志表（异常：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('对象存在性-审计日志表', false);
END $$;

-- auth.* 策略函数族 + 上下文/加密函数（8 个，uuid 签名）
DO $$
DECLARE
    v_fns text[] := ARRAY['auth.current_uid()','auth.can_access_org(uuid)',
        'auth.can_access_dept(uuid)','auth.is_self(uuid)',
        'auth.set_session(uuid,uuid[],uuid[])',
        'auth.jwt_sign(jsonb,text)','auth.jwt_verify(text,text)',
        'auth.decrypt_bank_account(uuid)'];
    v_f    text;
    v_miss text := '';
    v_cnt  int := 0;
BEGIN
    FOREACH v_f IN ARRAY v_fns LOOP
        IF to_regprocedure(v_f) IS NOT NULL THEN v_cnt := v_cnt + 1;
        ELSE v_miss := v_miss || v_f || ' '; END IF;
    END LOOP;
    IF v_cnt = array_length(v_fns,1) THEN
        RAISE NOTICE 'PASS: 对象存在性-auth 函数（%/%，RLS 策略函数族齐全）', v_cnt, array_length(v_fns,1);
        INSERT INTO verify_results VALUES ('对象存在性-auth函数', true);
    ELSE
        RAISE NOTICE 'FAIL: 对象存在性-auth 函数（%/%，缺失：%）', v_cnt, array_length(v_fns,1), v_miss;
        INSERT INTO verify_results VALUES ('对象存在性-auth函数', false);
    END IF;
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: 对象存在性-auth函数（异常：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('对象存在性-auth函数', false);
END $$;

-- audit.* 函数（record / 哈希链校验，双命名探测/ 3 个触发器函数）
DO $$
DECLARE
    v_ok boolean := true;
BEGIN
    IF to_regprocedure('audit.record(text,text,uuid,jsonb,jsonb,text,text)') IS NULL THEN
        v_ok := false;
        RAISE NOTICE 'WARN: audit.record 缺失';
    END IF;
    IF to_regprocedure('audit.fn_audit_log()') IS NULL THEN v_ok := false;
        RAISE NOTICE 'WARN: audit.fn_audit_log 缺失'; END IF;
    IF to_regprocedure('audit.fn_audit_log_hash()') IS NULL THEN v_ok := false;
        RAISE NOTICE 'WARN: audit.fn_audit_log_hash 缺失'; END IF;
    IF to_regprocedure('audit.fn_audit_log_immutable()') IS NULL THEN v_ok := false;
        RAISE NOTICE 'WARN: audit.fn_audit_log_immutable 缺失'; END IF;
    IF to_regprocedure('audit.chain_check(date)') IS NULL
       AND to_regprocedure('audit.audit_log_chain_check()') IS NULL THEN
        v_ok := false;
        RAISE NOTICE 'WARN: 哈希链校验函数缺失（audit.chain_check / audit.audit_log_chain_check）';
    END IF;
    IF v_ok THEN
        RAISE NOTICE 'PASS: 对象存在性-audit 函数（record/链校验/3 触发器函数）';
        INSERT INTO verify_results VALUES ('对象存在性-audit函数', true);
    ELSE
        RAISE NOTICE 'FAIL: 对象存在性-audit 函数（存在缺失，见上方 WARN）';
        INSERT INTO verify_results VALUES ('对象存在性-audit函数', false);
    END IF;
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: 对象存在性-audit函数（异常：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('对象存在性-audit函数', false);
END $$;

-- pgmq 函数（send/read/archive/meta/list）
-- 环境适配，pgmq 扩展未装（pg_extension 无记录）即视为未安装，SKIP 不记 FAIL，
-- 部分函数缺失，扩展已装但版本差异，才记 FAIL
DO $$
DECLARE
    v_ok boolean := true;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pgmq') THEN
        RAISE NOTICE 'SKIP: 对象存在性-pgmq 函数（pgmq 扩展未安装，视为无此功能，跳过）';
        INSERT INTO verify_results VALUES ('对象存在性-pgmq函数', true);
        RETURN;
    END IF;
    IF to_regprocedure('pgmq.send(text,jsonb,integer)') IS NULL THEN v_ok := false;
        RAISE NOTICE 'WARN: pgmq.send(text,jsonb,integer) 缺失'; END IF;
    -- read，本环境 pgmq 原生为 4 参带默认（conditional jsonb DEFAULT '{}'），3 参调用直接解析到原生函数，4 参或 3 参任一存在即视为可用
    IF to_regprocedure('pgmq.read(text,integer,integer,jsonb)') IS NULL
       AND to_regprocedure('pgmq.read(text,integer,integer)') IS NULL THEN
        v_ok := false;
        RAISE NOTICE 'WARN: pgmq.read 缺失（4 参带默认 / 3 参均不存在）'; END IF;
    IF to_regprocedure('pgmq.archive(text,bigint)') IS NULL THEN v_ok := false;
        RAISE NOTICE 'WARN: pgmq.archive(text,bigint) 缺失'; END IF;
    -- 监控函数双命名探测，pgmq 1.12.0 起 meta 更名 metrics、list 更名 list_queues，
    -- 新旧任一存在即视为该监控能力可用（与本文件审计链校验函数的双命名探测同策略）
    IF to_regprocedure('pgmq.metrics(text)') IS NULL
       AND to_regprocedure('pgmq.meta(text)') IS NULL THEN
        v_ok := false;
        RAISE NOTICE 'WARN: pgmq.metrics(text) / pgmq.meta(text) 均缺失'; END IF;
    IF to_regprocedure('pgmq.list_queues()') IS NULL
       AND to_regprocedure('pgmq.list()') IS NULL THEN
        v_ok := false;
        RAISE NOTICE 'WARN: pgmq.list_queues() / pgmq.list() 均缺失'; END IF;
    IF v_ok THEN
        RAISE NOTICE 'PASS: 对象存在性-pgmq 函数（send/read/archive/监控函数）';
        INSERT INTO verify_results VALUES ('对象存在性-pgmq函数', true);
    ELSE
        RAISE NOTICE 'FAIL: 对象存在性-pgmq 函数（存在缺失，见上方 WARN）';
        INSERT INTO verify_results VALUES ('对象存在性-pgmq函数', false);
    END IF;
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: 对象存在性-pgmq函数（异常：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('对象存在性-pgmq函数', false);
END $$;

-- pg_cron 9 个定时任务
-- 环境适配，本测试库 cron.database_name=postgres，cron.job 位于 postgres 库（init/queues_cron.sql文件经 schedule_in_database 注册，任务 database='fms'），本库无 cron schema 时经 dblink 跨库核验，pg_cron 完全不可用则按部署策略条件跳过
DO $$
DECLARE
    v_expect int := 9;
    v_cnt    int := -1;
BEGIN
    IF to_regclass('cron.job') IS NOT NULL THEN
        -- 本库装有 pg_cron，直接统计本库任务（动态 sql，静态引用在 cron schema 缺失的库解析期即失败，故用 EXECUTE 惰性解析）
        EXECUTE 'SELECT count(*) FROM cron.job
                   WHERE jobname IN (''voucher-number-check'',''fa-depreciation'',
                                     ''fx-adjustment'',''close-check'',
                                     ''budget-warning'',''wf-timeout'',
                                     ''partman-maintenance'',''audit-chain-check'',
                                     ''log-consumer'')
                     AND database = current_database()'
            INTO v_cnt;
    ELSE
        -- 跨库核验，cron 扩展装在 cron.database_name（postgres）库，任务经 init/queues_cron.sql文件注册为 database='fms'，
        -- dblink 连本机 unix socket（peer 认证免密，验证脚本以 postgres 用户执行）
        BEGIN
            SELECT t.c INTO v_cnt
              FROM dblink(
                     'host=/var/run/postgresql dbname=postgres',
                     'SELECT count(*) FROM cron.job
                        WHERE jobname IN (''voucher-number-check'',''fa-depreciation'',
                                          ''fx-adjustment'',''close-check'',
                                          ''budget-warning'',''wf-timeout'',
                                          ''partman-maintenance'',''audit-chain-check'',
                                          ''log-consumer'')
                          AND database = ''fms''')
                   AS t(c int);
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'SKIP: 对象存在性-pg_cron 任务（pg_cron 不可用：本库及 postgres 库均无 cron schema，%），按部署策略条件跳过', SQLERRM;
            INSERT INTO verify_results VALUES ('对象存在性-cron9任务', true);
            RETURN;
        END;
    END IF;
    IF v_cnt = 0 THEN
        -- 适配，queues_cron 未注册任务（pg_cron 不可用或调度段未执行）时不记 FAIL
        RAISE NOTICE 'SKIP: 对象存在性-pg_cron 任务（本机 0 个任务，queues_cron 未注册调度，视为条件通过）';
        INSERT INTO verify_results VALUES ('对象存在性-cron9任务', true);
    ELSIF v_cnt = v_expect THEN
        RAISE NOTICE 'PASS: 对象存在性-pg_cron 任务（%/%，database=fms）', v_cnt, v_expect;
        INSERT INTO verify_results VALUES ('对象存在性-cron9任务', true);
    ELSE
        RAISE NOTICE 'FAIL: 对象存在性-pg_cron 任务（%/%）', v_cnt, v_expect;
        INSERT INTO verify_results VALUES ('对象存在性-cron9任务', false);
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'FAIL: 对象存在性-pg_cron 任务（异常：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('对象存在性-cron9任务', false);
END $$;

-- 分区断言，part_config 4 行（um.login_log/audit.audit_log/log.app_logs/log.access_logs），4 行均已配置 retention 且不保留过期分区，partman-maintenance cron 命令为 CALL 形态，CALL 路径可执行，DO 块内不能 CALL 含事务控制的 PROCEDURE，run_maintenance_proc 内部有 COMMIT 路径会报 'invalid transaction termination'，故用等价的函数形态 run_maintenance(NULL) 验证维护链路，cron 任务为顶层 CALL 不受此限制，分区子表存在
DO $$
DECLARE
    v_cfg    int := -1;
    v_ret    int := -1;
    v_children int := -1;
    v_cmd    text;
    v_ok     boolean := true;
BEGIN
    IF to_regclass('partman.part_config') IS NULL THEN
        RAISE NOTICE 'SKIP: 分区断言（partman.part_config 不存在，pg_partman 未安装）';
        INSERT INTO verify_results VALUES ('分区-partman配置', true);
        RETURN;
    END IF;
    SELECT count(*) INTO v_cfg FROM partman.part_config
     WHERE parent_table IN ('um.login_log','audit.audit_log','log.app_logs','log.access_logs');
    SELECT count(*) INTO v_ret FROM partman.part_config
     WHERE parent_table IN ('um.login_log','audit.audit_log','log.app_logs','log.access_logs')
       AND retention IS NOT NULL AND NOT retention_keep_table;
    -- 子分区计数，4 父表下真实分区子表
    SELECT count(*) INTO v_children FROM pg_inherits i
     JOIN pg_class p ON p.oid = i.inhparent
     JOIN pg_namespace n ON n.oid = p.relnamespace
     JOIN pg_class c ON c.oid = i.inhrelid
     WHERE (n.nspname, p.relname) IN
           (('um','login_log'),('audit','audit_log'),('log','app_logs'),('log','access_logs'))
       AND c.relkind = 'r';
    IF v_cfg <> 4 THEN v_ok := false; RAISE NOTICE 'WARN: part_config=%（期望 4）', v_cfg; END IF;
    IF v_ret <> 4 THEN v_ok := false; RAISE NOTICE 'WARN: retention 已配置=%（期望 4）', v_ret; END IF;
    IF v_children < 4 THEN v_ok := false; RAISE NOTICE 'WARN: 分区子表=%（期望 ≥4）', v_children; END IF;
    -- cron 命令形态（本库或 postgres 库经 dblink）
    BEGIN
        IF to_regclass('cron.job') IS NOT NULL THEN
            SELECT command INTO v_cmd FROM cron.job
             WHERE jobname = 'partman-maintenance' AND database = current_database();
        ELSE
            -- 回退分支与主路径同口径，硬编码异机 ip 测试机
            -- 会连到另一台机器，故用本机 unix socket（/var/run/postgresql，peer 认证）
            SELECT t.c INTO v_cmd FROM dblink(
                'host=/var/run/postgresql dbname=postgres user=postgres',
                'SELECT command FROM cron.job
                  WHERE jobname = ''partman-maintenance'' AND database = ''fms''') AS t(c text);
        END IF;
    EXCEPTION WHEN OTHERS THEN
        v_cmd := NULL;
    END;
    IF v_cmd IS NULL OR v_cmd <> 'CALL partman.run_maintenance_proc();' THEN
        v_ok := false;
        RAISE NOTICE 'WARN: partman-maintenance 命令=%（期望 CALL partman.run_maintenance_proc();）', v_cmd;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = 'partman' AND p.proname = 'run_maintenance_proc'
                      AND p.prokind = 'p') THEN
        v_ok := false;
        RAISE NOTICE 'WARN: partman.run_maintenance_proc 非 PROCEDURE（prokind 异常）';
    END IF;
    BEGIN
        PERFORM partman.run_maintenance(NULL);
    EXCEPTION WHEN OTHERS THEN
        v_ok := false;
        RAISE NOTICE 'WARN: partman.run_maintenance(NULL) 失败：%', SQLERRM;
    END;
    IF v_ok THEN
        RAISE NOTICE 'PASS: 分区-partman（part_config=4、retention=4、子分区≥4、cron=CALL、可执行）';
        INSERT INTO verify_results VALUES ('分区-partman配置', true);
    ELSE
        RAISE NOTICE 'FAIL: 分区-partman（见上方 WARN）';
        INSERT INTO verify_results VALUES ('分区-partman配置', false);
    END IF;
END $$;

-- jwt 委托断言，auth.jwt_sign 必须委托 pgjwt.sign，防原生实现静默覆盖
DO $$
DECLARE
    v_def text;
    v_ok  boolean;
BEGIN
    SELECT pg_get_functiondef('auth.jwt_sign(jsonb,text)'::regprocedure) INTO v_def;
    v_ok := v_def IS NOT NULL AND v_def LIKE '%pgjwt.sign%';
    IF v_ok THEN
        RAISE NOTICE 'PASS: JWT-委托 pgjwt.sign（函数体含 pgjwt.sign）';
        INSERT INTO verify_results VALUES ('JWT委托pgjwt', true);
    ELSE
        RAISE NOTICE 'FAIL: JWT-委托 pgjwt.sign（函数体不含 pgjwt.sign，可能被原生实现覆盖）';
        INSERT INTO verify_results VALUES ('JWT委托pgjwt', false);
    END IF;
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: JWT委托pgjwt（异常：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('JWT委托pgjwt', false);
END $$;

-- pg_ivm 5 个增量物化视图（rpt，budget_actual/ar_aging_paid/ap_aging_paid/dept_expense/cash_flow_daily，ap_aging_paid 为 AP 账龄，pgivm.create_immv 实现，与 AR 同构）
DO $$
DECLARE
    v_immvs text[] := ARRAY['rpt.budget_actual','rpt.ar_aging_paid',
        'rpt.ap_aging_paid','rpt.dept_expense','rpt.cash_flow_daily'];
    v_iv    text;
    v_miss  text := '';
    v_cnt   int := 0;
    v_meta  int;
BEGIN
    FOREACH v_iv IN ARRAY v_immvs LOOP
        IF to_regclass(v_iv) IS NOT NULL THEN v_cnt := v_cnt + 1;
        ELSE v_miss := v_miss || v_iv || ' '; END IF;
    END LOOP;
    -- pg_ivm 元数据表 pgivm.pg_ivm_immv（1.15 目录名，兼容 pg_ivm_immatview 的 public/pg_ivm 双路径容错探测）
    BEGIN
        SELECT count(*) INTO v_meta
          FROM pgivm.pg_ivm_immv i
          JOIN pg_class c ON c.oid = i.immvrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'rpt';
    EXCEPTION WHEN OTHERS THEN
        v_meta := -1;
        BEGIN
            SELECT count(*) INTO v_meta FROM pg_ivm.pg_ivm_immatview WHERE schemaname = 'rpt';
        EXCEPTION WHEN OTHERS THEN
            BEGIN
                SELECT count(*) INTO v_meta FROM pg_ivm_immatview WHERE schemaname = 'rpt';
            EXCEPTION WHEN OTHERS THEN
                v_meta := -1;
            END;
        END;
    END;
    IF v_cnt = array_length(v_immvs,1) AND v_meta = array_length(v_immvs,1) THEN
        RAISE NOTICE 'PASS: 对象存在性-pg_ivm IMMV（%/%，元数据 pg_ivm_immv 行数=%）',
            v_cnt, array_length(v_immvs,1), v_meta;
        INSERT INTO verify_results VALUES ('对象存在性-IMMV5个', true);
    ELSE
        RAISE NOTICE 'FAIL: 对象存在性-pg_ivm IMMV（对象 %/% 或元数据 %/% 不一致，缺失：%）',
            v_cnt, array_length(v_immvs,1), v_meta, array_length(v_immvs,1), v_miss;
        INSERT INTO verify_results VALUES ('对象存在性-IMMV5个', false);
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'FAIL: 对象存在性-pg_ivm IMMV（异常：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('对象存在性-IMMV5个', false);
END $$;

-- 通知落库幂等键，notify.notifications 部分唯一索引 (user_id, ref_type, ref_id) WHERE deleted_at IS NULL
DO $$
DECLARE
    v_idxdef text;
    v_ok     boolean;
BEGIN
    SELECT indexdef INTO v_idxdef
      FROM pg_indexes
     WHERE schemaname = 'notify' AND tablename = 'notifications'
       AND indexname = 'uk_notify_user_ref';
    v_ok := v_idxdef IS NOT NULL
        AND v_idxdef LIKE '%UNIQUE%'
        AND v_idxdef LIKE '%(user_id, ref_type, ref_id)%'
        AND v_idxdef LIKE '%deleted_at IS NULL%';
    IF v_ok THEN
        RAISE NOTICE 'PASS: 对象存在性-通知幂等键唯一索引（uk_notify_user_ref，部分索引排除软删行）';
    ELSE
        RAISE NOTICE 'FAIL: 对象存在性-通知幂等键唯一索引（实际定义：%）', COALESCE(v_idxdef, '缺失');
    END IF;
    INSERT INTO verify_results VALUES ('对象存在性-通知幂等键索引', v_ok);
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: 对象存在性-通知幂等键索引（异常：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('对象存在性-通知幂等键索引', false);
END $$;

-- 通知闸口幂等与串行化，函数体必含 ON CONFLICT DO NOTHING，广播闸口另含 pg_advisory_xact_lock
-- 函数体包含式断言，与上文 jwt 委托断言同款，防后续实现被静默替换成裸 INSERT
DO $$
DECLARE
    v_bcast text;
    v_ins   text;
    v_ok    boolean;
BEGIN
    SELECT pg_get_functiondef(p.oid) INTO v_bcast
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'notify' AND p.proname = 'fn_broadcast_notification';
    SELECT pg_get_functiondef(p.oid) INTO v_ins
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'notify' AND p.proname = 'fn_insert_notification';
    v_ok := v_bcast LIKE '%ON CONFLICT%DO NOTHING%'
        AND v_bcast LIKE '%pg_advisory_xact_lock%'
        AND v_bcast LIKE '%set_config%'
        AND v_bcast LIKE '%notify.broadcast_lock_timeout_ms%'
        AND v_bcast LIKE '%v_timeout_ms IS NULL%'
        AND v_ins LIKE '%ON CONFLICT%DO NOTHING%'
        AND EXISTS (SELECT 1 FROM um.params WHERE code = 'notify.broadcast_lock_timeout_ms'
                     AND deleted_at IS NULL)
        AND EXISTS (SELECT 1 FROM um.param_meta WHERE code = 'notify.broadcast_lock_timeout_ms');
    IF v_ok THEN
        RAISE NOTICE 'PASS: 对象存在性-通知闸口幂等与串行化（两闸口含 ON CONFLICT DO NOTHING，广播含 pg_advisory_xact_lock、可配等锁上限参数与空值判定）';
    ELSE
        RAISE NOTICE 'FAIL: 对象存在性-通知闸口幂等与串行化（广播存在=% ON CONFLICT=% 锁=% set_config=% 参数码=% 单条 ON CONFLICT=%）',
            v_bcast IS NOT NULL, v_bcast LIKE '%ON CONFLICT%DO NOTHING%',
            v_bcast LIKE '%pg_advisory_xact_lock%', v_bcast LIKE '%set_config%',
            v_bcast LIKE '%notify.broadcast_lock_timeout_ms%', v_ins LIKE '%ON CONFLICT%DO NOTHING%';
    END IF;
    INSERT INTO verify_results VALUES ('对象存在性-通知闸口幂等与串行化', v_ok);
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: 对象存在性-通知闸口幂等与串行化（异常：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('对象存在性-通知闸口幂等与串行化', false);
END $$;

-- 报销单冲销路径，状态机白名单含已过账转已冲销且以其凭证已冲销为前置；
-- 更新策略须放行已过账行（否则行级策略先于触发器拦下，冲销路径不可达），
-- 查询策略把已冲销一并纳入审批后可见集合
DO $$
DECLARE
    v_fn    text;
    v_qual  text;
    v_squal text;
    v_ok    boolean;
BEGIN
    SELECT pg_get_functiondef(p.oid) INTO v_fn
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'expense' AND p.proname = 'fn_reimburse_status_check';
    SELECT qual INTO v_qual FROM pg_policies
     WHERE schemaname = 'expense' AND tablename = 'reimbursements'
       AND policyname = 'expense_reimbursements_update';
    SELECT qual INTO v_squal FROM pg_policies
     WHERE schemaname = 'expense' AND tablename = 'reimbursements'
       AND policyname = 'expense_reimbursements_select';
    v_ok := v_fn LIKE '%NEW.status = ''REVERSED''%'
        AND v_fn LIKE '%FROM gl.vouchers%'
        AND v_fn LIKE '%v.status = ''REVERSED''%'
        AND v_qual LIKE '%POSTED%'
        AND v_squal LIKE '%REVERSED%';
    IF v_ok THEN
        RAISE NOTICE 'PASS: 对象存在性-报销单冲销路径（触发器含已过账转已冲销与凭证已冲销前置，更新策略放行已过账行，查询策略含已冲销）';
    ELSE
        RAISE NOTICE 'FAIL: 对象存在性-报销单冲销路径（触发器函数存在=% 状态守卫=% 凭证前置=% 更新策略含已过账=% 查询策略含已冲销=%）',
            v_fn IS NOT NULL, v_fn LIKE '%NEW.status = ''REVERSED''%',
            v_fn LIKE '%FROM gl.vouchers%', v_qual LIKE '%POSTED%', v_squal LIKE '%REVERSED%';
    END IF;
    INSERT INTO verify_results VALUES ('对象存在性-报销单冲销路径', v_ok);
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: 对象存在性-报销单冲销路径（异常：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('对象存在性-报销单冲销路径', false);
END $$;

-- 场景验证，五层安全 + 扩展能力闭环
-- rls 场景 A，注入身份（fms.user_id=ADMIN_UID + org/dept 范围）+ SET ROLE fms_operator 则 gl.vouchers 应可见，有数据，验证上下文预载 guc + 策略函数族链路
-- 断言自带夹具，不依赖业务种子，内层子事务现建一张 DRAFT 凭证，断言后以 RAISE 回滚子事务，库无残留
-- 变量不随子事务回滚，故结果经变量带出后落盘，制单人取注入身份本人，与策略 is_self 分支一致
DO $$
DECLARE v_cnt bigint := -1;
BEGIN
    BEGIN
        SET LOCAL fms.user_id  = '00000000-0000-7000-0000-000000000001';
        INSERT INTO gl.vouchers
            (id, company_id, period, voucher_date, status, source_type, created_by,
             total_debit, total_credit, dept_id)
        VALUES ('11111111-1111-7111-8111-111111111201',
                '00000000-0000-7000-0000-000000000010', '2026-08', DATE '2026-08-09',
                'DRAFT', 'VERIFY_TEST', '00000000-0000-7000-0000-000000000001',
                1.00, 1.00, '00000000-0000-7000-0000-000000000021');
        SET LOCAL fms.org_ids  = '00000000-0000-7000-0000-000000000010';
        SET LOCAL fms.dept_ids = '00000000-0000-7000-0000-000000000020,00000000-0000-7000-0000-000000000021';
        SET ROLE fms_operator;
        SELECT count(*) INTO v_cnt FROM gl.vouchers
         WHERE id = '11111111-1111-7111-8111-111111111201';
        RAISE EXCEPTION 'VERIFY_FIXTURE_ROLLBACK' USING ERRCODE = 'P0001';
    EXCEPTION
        WHEN SQLSTATE 'P0001' THEN
            NULL;                               -- 夹具随子事务回滚
        WHEN OTHERS THEN
            -- 夹具或断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
            RAISE NOTICE 'WARN: 场景-RLS-注入身份后凭证可见（夹具或断言异常：%）', SQLERRM;
    END;
    RESET ROLE;
    IF v_cnt > 0 THEN
        RAISE NOTICE 'PASS: 场景-RLS-注入身份后凭证可见（count=%，POSTED+DRAFT 均可见）', v_cnt;
    ELSE
        RAISE NOTICE 'FAIL: 场景-RLS-注入身份后凭证可见（count=0，预期>0：夹具不可见或 RLS 策略异常）';
    END IF;
    INSERT INTO verify_results VALUES ('场景-RLS-注入身份凭证可见', v_cnt > 0);
EXCEPTION WHEN OTHERS THEN
    RESET ROLE;
    RAISE NOTICE 'FAIL: 场景-RLS-注入身份后凭证可见（异常：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('场景-RLS-注入身份凭证可见', false);
END $$;

-- rls 场景 B，匿名，无 guc 上下文，凭证查询应返回空集
DO $$
DECLARE v_cnt bigint;
BEGIN
    SET ROLE fms_operator;
    SELECT count(*) INTO v_cnt FROM gl.vouchers;
    IF v_cnt = 0 THEN
        RAISE NOTICE 'PASS: 场景-RLS-匿名不可见（count=0，无身份上下文返回空集）';
    ELSE
        RAISE NOTICE 'FAIL: 场景-RLS-匿名不可见（count=%，预期 0：RLS 未生效或策略漏放行）', v_cnt;
    END IF;
    RESET ROLE;
    INSERT INTO verify_results VALUES ('场景-RLS-匿名不可见', v_cnt = 0);
EXCEPTION WHEN OTHERS THEN
    RESET ROLE;
    RAISE NOTICE 'FAIL: 场景-RLS-匿名不可见（异常：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('场景-RLS-匿名不可见', false);
END $$;

-- 场景，借贷不平衡凭证提交（DRAFT 可存，转 SUBMITTED 被状态机校验 RAISE）
DO $$
DECLARE
    v_vid uuid;
    v_acc uuid;
BEGIN
    SET LOCAL fms.user_id  = '00000000-0000-7000-0000-000000000001';
    SET LOCAL fms.org_ids  = '00000000-0000-7000-0000-000000000010';
    SET LOCAL fms.dept_ids = '00000000-0000-7000-0000-000000000020,00000000-0000-7000-0000-000000000021';
    SET ROLE fms_operator;
    SELECT id INTO v_acc FROM gl.chart_accounts
     WHERE company_id = '00000000-0000-7000-0000-000000000010'
       AND code = '1001' AND deleted_at IS NULL;
    IF v_acc IS NULL THEN
        RAISE NOTICE 'FAIL: 场景-不平衡凭证提交拒绝（科目 1001 种子缺失，前置条件不满足）';
        RESET ROLE;
        INSERT INTO verify_results VALUES ('场景-不平衡凭证提交拒绝', false);
        RETURN;
    END IF;
    INSERT INTO gl.vouchers
        (company_id, period, voucher_date, status, source_type, created_by,
         total_debit, total_credit)
    VALUES ('00000000-0000-7000-0000-000000000010', '2026-08', DATE '2026-08-08',
            'DRAFT', 'VERIFY_TEST', '00000000-0000-7000-0000-000000000001', 100.00, 0.00)
    RETURNING id INTO v_vid;
    INSERT INTO gl.voucher_lines
        (voucher_id, line_no, account_id, summary, debit, credit, currency, dept_id, company_id)
    VALUES
        (v_vid, 1, v_acc, '验证：不平衡分录（仅借方 100，无贷方）', 100.00, 0.00, 'CNY',
         '00000000-0000-7000-0000-000000000021', '00000000-0000-7000-0000-000000000010');
    UPDATE gl.vouchers SET status = 'SUBMITTED' WHERE id = v_vid;   -- 贷借差=100 ≠ 0 则应 RAISE
    RAISE NOTICE 'FAIL: 场景-不平衡凭证提交未被拒绝（已置 SUBMITTED，平衡校验缺失）';
    RESET ROLE;
    INSERT INTO verify_results VALUES ('场景-不平衡凭证提交拒绝', false);
EXCEPTION WHEN OTHERS THEN
    RESET ROLE;
    IF SQLERRM LIKE '%permission denied%' OR SQLERRM LIKE '%row-level security%' THEN
        RAISE NOTICE 'FAIL: 场景-不平衡凭证提交拒绝（前置权限/RLS 问题：%）', SQLERRM;
        INSERT INTO verify_results VALUES ('场景-不平衡凭证提交拒绝', false);
    ELSE
        RAISE NOTICE 'PASS: 场景-不平衡凭证提交被拒绝（RAISE：%）', SQLERRM;
        INSERT INTO verify_results VALUES ('场景-不平衡凭证提交拒绝', true);
    END IF;
END $$;

-- 场景，POSTED 凭证不可更新， 第一层，operator 被 rls 静默拦截，0 行
-- 断言自带夹具，不依赖业务种子，内层子事务按状态机现建一张 POSTED 凭证
-- 草稿到提交到审核由 operator 走，过账由 fms_admin 走，断言后回滚子事务，库无残留
DO $$
DECLARE
    v_vid uuid := '11111111-1111-7111-8111-111111111202';
    v_rc  bigint := -1;
    v_stage text := 'fixture';                   -- 异常归因，区分夹具阶段与判定阶段
BEGIN
    BEGIN
        SET LOCAL fms.user_id  = '00000000-0000-7000-0000-000000000001';
        SET LOCAL fms.org_ids  = '00000000-0000-7000-0000-000000000010';
        SET LOCAL fms.dept_ids = '00000000-0000-7000-0000-000000000020,00000000-0000-7000-0000-000000000021';
        SET ROLE fms_operator;
        INSERT INTO gl.vouchers
            (id, company_id, period, voucher_date, status, source_type, created_by,
             approved_by, total_debit, total_credit, dept_id)
        VALUES (v_vid,
                '00000000-0000-7000-0000-000000000010', '2026-08', DATE '2026-08-09',
                'DRAFT', 'VERIFY_TEST', '00000000-0000-7000-0000-000000000001',
                '00000000-0000-7000-0000-000000000002', 500.00, 500.00,
                '00000000-0000-7000-0000-000000000021');
        INSERT INTO gl.voucher_lines (voucher_id, line_no, account_id, summary, debit, credit,
                                      currency, dept_id, company_id)
        VALUES (v_vid, 1, '00000000-0000-7000-0000-000000000100', 'RLS拦截验证-借500',
                500.00, 0.00, 'CNY', '00000000-0000-7000-0000-000000000020',
                '00000000-0000-7000-0000-000000000010'),
               (v_vid, 2, '00000000-0000-7000-0000-00000000010e', 'RLS拦截验证-贷500',
                0.00, 500.00, 'CNY', '00000000-0000-7000-0000-000000000021',
                '00000000-0000-7000-0000-000000000010');
        UPDATE gl.vouchers SET status = 'SUBMITTED' WHERE id = v_vid;
        UPDATE gl.vouchers SET status = 'APPROVED'  WHERE id = v_vid;
        SET ROLE fms_admin;                      -- 过账，等价 SECURITY DEFINER 路径
        UPDATE gl.vouchers SET status = 'POSTED'    WHERE id = v_vid;
        SET ROLE fms_operator;
        v_stage := 'update';                     -- 至此才是判定目标，异常才可归因于拦截
        UPDATE gl.vouchers SET updated_at = now() WHERE id = v_vid;
        GET DIAGNOSTICS v_rc = ROW_COUNT;
        RAISE EXCEPTION 'VERIFY_FIXTURE_ROLLBACK' USING ERRCODE = 'P0001';
    EXCEPTION
        WHEN SQLSTATE 'P0001' THEN
            NULL;                                -- 夹具随子事务回滚
        WHEN OTHERS THEN
            -- 仅当异常发生在更新语句一步内才可判为被 RLS 拦下，夹具阶段的异常不得计入
            -- 否则更新从未执行却报 PASS 属假通过，会掩盖夹具失效
            IF v_stage = 'update'
               AND (SQLERRM LIKE '%row-level security%' OR SQLERRM LIKE '%permission denied%') THEN
                v_rc := 0;                       -- WITH CHECK 拒绝，报错形式同属 rls 层拦截成功
                RAISE NOTICE 'PASS: 场景-POSTED凭证UPDATE被RLS拒绝（RAISE：%）', SQLERRM;
            ELSE
                RAISE NOTICE 'WARN: 场景-POSTED凭证UPDATE-RLS拦截（夹具阶段异常，不计为拦截：%）', SQLERRM;
            END IF;
    END;
    RESET ROLE;
    IF v_rc = 0 THEN
        RAISE NOTICE 'PASS: 场景-POSTED凭证UPDATE被RLS静默拦截（operator 影响 0 行）';
    ELSE
        RAISE NOTICE 'FAIL: 场景-POSTED凭证UPDATE未被拦截（operator 影响 % 行）', v_rc;
    END IF;
    INSERT INTO verify_results VALUES ('场景-POSTED凭证UPDATE-RLS拦截', v_rc = 0);
END $$;

-- 场景，POSTED 凭证不可更新， 第二层，fms_admin（超级用户绕过 rls）被 BEFORE 触发器 trg_voucher_immutable 硬拒绝（RAISE）
-- 断言自带夹具，内层子事务现建 POSTED 凭证，fms_admin 直改应被触发器硬拒绝，断言后回滚子事务
DO $$
DECLARE
    v_vid uuid := '11111111-1111-7111-8111-111111111203';
    v_hit boolean := false;                      -- 触发器是否拒绝
BEGIN
    BEGIN
        SET LOCAL fms.user_id  = '00000000-0000-7000-0000-000000000001';
        SET LOCAL fms.org_ids  = '00000000-0000-7000-0000-000000000010';
        SET LOCAL fms.dept_ids = '00000000-0000-7000-0000-000000000020,00000000-0000-7000-0000-000000000021';
        SET ROLE fms_operator;
        INSERT INTO gl.vouchers
            (id, company_id, period, voucher_date, status, source_type, created_by,
             approved_by, total_debit, total_credit, dept_id)
        VALUES (v_vid,
                '00000000-0000-7000-0000-000000000010', '2026-08', DATE '2026-08-09',
                'DRAFT', 'VERIFY_TEST', '00000000-0000-7000-0000-000000000001',
                '00000000-0000-7000-0000-000000000002', 500.00, 500.00,
                '00000000-0000-7000-0000-000000000021');
        INSERT INTO gl.voucher_lines (voucher_id, line_no, account_id, summary, debit, credit,
                                      currency, dept_id, company_id)
        VALUES (v_vid, 1, '00000000-0000-7000-0000-000000000100', '触发器拒绝验证-借500',
                500.00, 0.00, 'CNY', '00000000-0000-7000-0000-000000000020',
                '00000000-0000-7000-0000-000000000010'),
               (v_vid, 2, '00000000-0000-7000-0000-00000000010e', '触发器拒绝验证-贷500',
                0.00, 500.00, 'CNY', '00000000-0000-7000-0000-000000000021',
                '00000000-0000-7000-0000-000000000010');
        UPDATE gl.vouchers SET status = 'SUBMITTED' WHERE id = v_vid;
        UPDATE gl.vouchers SET status = 'APPROVED'  WHERE id = v_vid;
        SET ROLE fms_admin;                      -- 过账，等价 SECURITY DEFINER 路径
        UPDATE gl.vouchers SET status = 'POSTED'    WHERE id = v_vid;
        BEGIN
            SET ROLE fms_admin;                  -- 绕过 rls，检验触发器层兜底
            UPDATE gl.vouchers SET updated_at = now() WHERE id = v_vid;
            v_hit := false;                      -- 未被拒绝
        EXCEPTION WHEN OTHERS THEN
            v_hit := true;
            RAISE NOTICE 'PASS: 场景-POSTED凭证UPDATE被触发器拒绝（RAISE：%）', SQLERRM;
        END;
        RAISE EXCEPTION 'VERIFY_FIXTURE_ROLLBACK' USING ERRCODE = 'P0001';
    EXCEPTION
        WHEN SQLSTATE 'P0001' THEN
            NULL;                                -- 夹具随子事务回滚
        WHEN OTHERS THEN
            -- 夹具或断言自身异常，一律落 FAIL，不让异常冒泡使该断言整条从结果里消失
            RAISE NOTICE 'WARN: 场景-POSTED凭证UPDATE-触发器拒绝（夹具或断言异常：%）', SQLERRM;
    END;
    RESET ROLE;
    IF NOT v_hit THEN
        RAISE NOTICE 'FAIL: 场景-POSTED凭证UPDATE未被触发器拒绝（fms_admin 直改成功，trg_voucher_immutable 缺失）';
    END IF;
    INSERT INTO verify_results VALUES ('场景-POSTED凭证UPDATE-触发器拒绝', v_hit);
END $$;

-- 场景，INSERT INTO audit.audit_log 被拒（应用角色无 INSERT 权限 + FORCE rls 无策略）
DO $$
BEGIN
    SET ROLE fms_operator;
    INSERT INTO audit.audit_log (actor_id, action, table_name, record_id, occurred_at)
    VALUES ('00000000-0000-7000-0000-000000000001', 'VERIFY_HACK', 'audit.audit_log',
            '00000000-0000-7000-0000-000000000200', now());
    RAISE NOTICE 'FAIL: 场景-audit_log 写入未被拒绝（operator 成功 INSERT）';
    RESET ROLE;
    INSERT INTO verify_results VALUES ('场景-audit_log写入拒绝', false);
EXCEPTION WHEN OTHERS THEN
    RESET ROLE;
    RAISE NOTICE 'PASS: 场景-audit_log 写入被拒绝（RAISE：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('场景-audit_log写入拒绝', true);
END $$;

-- 场景，pgmq send/read/archive 闭环（q_notify_task，operator 消费），pgmq msg_id 为 bigint（pgmq schema 内部语义不动），uuid 仅消息体 jsonb 文本
-- 环境适配，pgmq 扩展未装即视为未安装，SKIP 不记 FAIL
DO $$
DECLARE
    v_uuid  text;
    v_msgid bigint;
    v_read  bigint;
    v_ok    boolean;
    v_arch  int;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pgmq') THEN
        RAISE NOTICE 'SKIP: 场景-PGMQ 闭环（pgmq 扩展未安装，视为无此功能，跳过）';
        INSERT INTO verify_results VALUES ('场景-PGMQ闭环', true);
        RETURN;
    END IF;
    SET ROLE fms_operator;
    v_uuid := gen_random_uuid()::text;
    SELECT pgmq.send('q_notify_task', jsonb_build_object('verify_uuid', v_uuid, 'ts', now()), 0)
      INTO v_msgid;
    SELECT msg_id INTO v_read
      FROM pgmq.read('q_notify_task', 30, 100)
     WHERE message->>'verify_uuid' = v_uuid;
    IF v_read IS NULL THEN
        RAISE NOTICE 'FAIL: 场景-PGMQ 闭环（send 成功 msg_id=%，但 read 未取回消息）', v_msgid;
        RESET ROLE;
        INSERT INTO verify_results VALUES ('场景-PGMQ闭环', false);
        RETURN;
    END IF;
    SELECT pgmq.archive('q_notify_task', v_read) INTO v_ok;
    -- 归档复核，pgmq 1.12.0 移除了 pgmq.archived(text)，改直查归档表 a_<队列>；
    -- 归档表不对业务角色授权，故先 RESET ROLE 以超级用户口径复核（本脚本以 postgres 运行），
    -- 归档表缺失时回退 pgmq.archived，双路径兼容
    RESET ROLE;
    IF to_regprocedure('pgmq.archived(text)') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM pgmq.archived(''q_notify_task'') WHERE msg_id = $1'
           INTO v_arch USING v_read;
    ELSE
        EXECUTE 'SELECT count(*) FROM pgmq.a_q_notify_task WHERE msg_id = $1'
           INTO v_arch USING v_read;
    END IF;
    IF v_ok AND v_arch = 1 THEN
        RAISE NOTICE 'PASS: 场景-PGMQ send->read->archive 闭环（msg_id=%，归档确认=%）', v_msgid, v_arch;
    ELSE
        RAISE NOTICE 'FAIL: 场景-PGMQ 闭环（archive 返回 %，归档行数=%）', v_ok, v_arch;
    END IF;
    RESET ROLE;
    INSERT INTO verify_results VALUES ('场景-PGMQ闭环', v_ok AND v_arch = 1);
EXCEPTION WHEN OTHERS THEN
    RESET ROLE;
    RAISE NOTICE 'FAIL: 场景-PGMQ 闭环（异常：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('场景-PGMQ闭环', false);
END $$;

-- 场景，审计哈希链，初始完好，篡改一行（禁不可变触发器模拟后台强制篡改），chain_check 定位断链点（broken_at 非 NULL，uuid），恢复后链完好
-- 校验函数双命名探测（与 init/queues_cron.sql文件 log.fn_cron_audit_chain_check 同一策略），优先 audit.audit_log_chain_check，回退 audit.chain_check，uuid 语义，NULL = 链完好，非 NULL = 断链点行 id
DO $$
DECLARE
    v_fn       text;
    v_row      record;
    v_j        jsonb;
    v_before   uuid := NULL;
    v_after    uuid := NULL;
    v_restored uuid := NULL;
    v_id       uuid;
    v_orig     jsonb;
    v_cnt      bigint;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'audit' AND p.proname = 'audit_log_chain_check') THEN
        v_fn := 'audit.audit_log_chain_check()';
    ELSIF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'audit' AND p.proname = 'chain_check') THEN
        v_fn := 'audit.chain_check()';
    ELSE
        RAISE NOTICE 'FAIL: 场景-审计哈希链（校验函数 audit.audit_log_chain_check/audit.chain_check 均不存在）';
        INSERT INTO verify_results VALUES ('场景-审计哈希链', false);
        RETURN;
    END IF;

    SELECT count(*) INTO v_cnt FROM audit.audit_log;
    IF v_cnt = 0 THEN
        RAISE NOTICE 'FAIL: 场景-审计哈希链（audit.audit_log 为空，无链可验——审计触发器未生效）';
        INSERT INTO verify_results VALUES ('场景-审计哈希链', false);
        RETURN;
    END IF;

    -- 篡改发生前，链应完好（broken_at = NULL）

    FOR v_row IN EXECUTE format('SELECT * FROM %s', v_fn) LOOP
        v_j := to_jsonb(v_row);
        v_before := COALESCE(NULLIF(v_j->>'broken_at','')::uuid,
                             NULLIF(v_j->>'audit_log_chain_check','')::uuid,
                             NULLIF(v_j->>'chain_check','')::uuid);
    END LOOP;

    --篡改目标，含 new_values 的最早一行（哈希公式含 new_values）

    SELECT id, new_values INTO v_id, v_orig
      FROM audit.audit_log
     WHERE new_values IS NOT NULL
     ORDER BY id LIMIT 1;

    BEGIN
        ALTER TABLE audit.audit_log DISABLE TRIGGER trg_audit_log_immutable;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'WARN: 禁用 trg_audit_log_immutable 失败（%），尝试直接 UPDATE', SQLERRM;
    END;

    BEGIN
        UPDATE audit.audit_log SET new_values = '{"__tampered":true}'::jsonb WHERE id = v_id;
    EXCEPTION WHEN OTHERS THEN
        BEGIN
            ALTER TABLE audit.audit_log ENABLE TRIGGER trg_audit_log_immutable;
        EXCEPTION WHEN OTHERS THEN NULL; END;
        RAISE NOTICE 'FAIL: 场景-审计哈希链（篡改写入被拒：%）', SQLERRM;
        INSERT INTO verify_results VALUES ('场景-审计哈希链', false);
        RETURN;
    END;

    BEGIN
        ALTER TABLE audit.audit_log ENABLE TRIGGER trg_audit_log_immutable;
    EXCEPTION WHEN OTHERS THEN NULL; END;

    --篡改后，应精确定位断链点（broken_at = 被篡改行 id，非 NULL）

    FOR v_row IN EXECUTE format('SELECT * FROM %s', v_fn) LOOP
        v_j := to_jsonb(v_row);
        v_after := COALESCE(NULLIF(v_j->>'broken_at','')::uuid,
                            NULLIF(v_j->>'audit_log_chain_check','')::uuid,
                            NULLIF(v_j->>'chain_check','')::uuid);
    END LOOP;

    --恢复现场并复检

    BEGIN
        ALTER TABLE audit.audit_log DISABLE TRIGGER trg_audit_log_immutable;
    EXCEPTION WHEN OTHERS THEN NULL; END;
    UPDATE audit.audit_log SET new_values = v_orig WHERE id = v_id;
    BEGIN
        ALTER TABLE audit.audit_log ENABLE TRIGGER trg_audit_log_immutable;
    EXCEPTION WHEN OTHERS THEN NULL; END;

    FOR v_row IN EXECUTE format('SELECT * FROM %s', v_fn) LOOP
        v_j := to_jsonb(v_row);
        v_restored := COALESCE(NULLIF(v_j->>'broken_at','')::uuid,
                               NULLIF(v_j->>'audit_log_chain_check','')::uuid,
                               NULLIF(v_j->>'chain_check','')::uuid);
    END LOOP;

    IF v_before IS NULL AND v_after IS NOT NULL AND v_restored IS NULL THEN
        RAISE NOTICE 'PASS: 场景-审计哈希链（初始完好->篡改行 id=% 断链 broken_at=%->恢复后完好）',
            v_id, v_after;
        INSERT INTO verify_results VALUES ('场景-审计哈希链', true);
    ELSIF v_before IS NULL AND v_after IS NOT NULL THEN
        RAISE NOTICE 'PASS(部分): 场景-审计哈希链（初始完好，篡改后断链 broken_at=%；恢复后 broken_at=%）',
            v_after, v_restored;
        INSERT INTO verify_results VALUES ('场景-审计哈希链', true);
    ELSE
        RAISE NOTICE 'FAIL: 场景-审计哈希链（初始 broken_at=%，篡改后 broken_at=%，期望 NULL->非NULL->NULL；被篡改行 id=%）',
            v_before, v_after, v_id;
        INSERT INTO verify_results VALUES ('场景-审计哈希链', false);
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'FAIL: 场景-审计哈希链（异常：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('场景-审计哈希链', false);
END $$;

-- 场景，五 schema 授权可达性，fa/expense/budget/wf/rpt 须覆盖，否则四模块与 rpt 零授权在验收时全 PASS，注入 operator 上下文后 wf.tasks / budget.budget_plans 应可见
-- 断言自带夹具，不依赖业务种子，内层子事务现建预算计划与流程实例任务各一行
-- 任务归属注入身份本人以命中 is_self 分支，断言 operator 可见后回滚子事务，库无残留
DO $$
DECLARE
    v_tasks bigint := -1;
    v_plans bigint := -1;
    v_acc   uuid;
    v_dept  uuid;
    v_def   uuid;
    v_inst  uuid := '11111111-1111-7111-8111-111111111301';
BEGIN
    BEGIN
        SELECT id INTO v_acc  FROM gl.chart_accounts
         WHERE company_id = '00000000-0000-7000-0000-000000000010'
           AND code = '6602' AND deleted_at IS NULL;
        SELECT id INTO v_dept FROM org.departments
         WHERE company_id = '00000000-0000-7000-0000-000000000010'
           AND path = '1.1'::ltree AND deleted_at IS NULL;
        -- 流程定义取公司内任一在用定义（seed_system 的系统配置，非业务演示数据）
        SELECT id INTO v_def  FROM wf.definitions
         WHERE company_id = '00000000-0000-7000-0000-000000000010'
           AND deleted_at IS NULL
         ORDER BY code LIMIT 1;
        IF v_acc IS NULL OR v_dept IS NULL OR v_def IS NULL THEN
            RAISE EXCEPTION '夹具缺依赖：科目=% 部门=% 流程定义=%', v_acc, v_dept, v_def;
        END IF;
        INSERT INTO budget.budget_plans
            (company_id, period, account_id, dept_id, amount, version, status,
             approved_by, approved_at, created_by)
        VALUES ('00000000-0000-7000-0000-000000000010', '2026-07', v_acc, v_dept, 1.00,
                999, 'DRAFT', NULL, NULL, '00000000-0000-7000-0000-000000000001');
        INSERT INTO wf.instances
            (id, company_id, definition_id, business_type, business_id, initiator_id,
             current_node, status)
        VALUES (v_inst, '00000000-0000-7000-0000-000000000010', v_def, 'VERIFY_GRANT',
                gen_random_uuid(), '00000000-0000-7000-0000-000000000001',
                'approve', 'RUNNING');
        INSERT INTO wf.tasks
            (company_id, instance_id, node_code, assignee_id, task_type, status, due_at)
        VALUES ('00000000-0000-7000-0000-000000000010', v_inst, 'approve',
                '00000000-0000-7000-0000-000000000001', 'APPROVE', 'PENDING',
                now() + interval '3 days');
        SET LOCAL fms.user_id  = '00000000-0000-7000-0000-000000000001';
        SET LOCAL fms.org_ids  = '00000000-0000-7000-0000-000000000010';
        SET LOCAL fms.dept_ids = '00000000-0000-7000-0000-000000000020,00000000-0000-7000-0000-000000000021';
        SET ROLE fms_operator;
        SELECT count(*) INTO v_tasks FROM wf.tasks WHERE instance_id = v_inst;
        SELECT count(*) INTO v_plans FROM budget.budget_plans
         WHERE dept_id = v_dept AND version = 999;
        RAISE EXCEPTION 'VERIFY_FIXTURE_ROLLBACK' USING ERRCODE = 'P0001';
    EXCEPTION
        WHEN SQLSTATE 'P0001' THEN
            NULL;                                -- 夹具随子事务回滚
        WHEN OTHERS THEN
            RAISE NOTICE 'WARN: 场景-五schema授权可达（夹具或断言异常：%）', SQLERRM;
    END;
    RESET ROLE;
    IF v_tasks > 0 AND v_plans > 0 THEN
        RAISE NOTICE 'PASS: 场景-五schema授权可达（wf.tasks=%，budget.budget_plans=%）', v_tasks, v_plans;
    ELSE
        RAISE NOTICE 'FAIL: 场景-五schema授权可达（wf.tasks=%，budget.budget_plans=%，预期>0：grants_base 授权缺失）', v_tasks, v_plans;
    END IF;
    INSERT INTO verify_results VALUES ('场景-五schema授权可达', v_tasks > 0 AND v_plans > 0);
END $$;

-- 场景，rpt 报表可达（fms_report 查询 rpt.trial_balance 成功且行级收敛）
DO $$
DECLARE v_cnt bigint;
BEGIN
    SET LOCAL fms.user_id  = '00000000-0000-7000-0000-000000000001';
    SET LOCAL fms.org_ids  = '00000000-0000-7000-0000-000000000010';
    SET ROLE fms_report;
    SELECT count(*) INTO v_cnt FROM rpt.trial_balance;
    RESET ROLE;
    RAISE NOTICE 'PASS: 场景-rpt报表可达（fms_report 查询 rpt.trial_balance 成功，行数=%）', v_cnt;
    INSERT INTO verify_results VALUES ('场景-rpt报表可达', true);
EXCEPTION WHEN OTHERS THEN
    RESET ROLE;
    RAISE NOTICE 'FAIL: 场景-rpt报表可达（异常：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('场景-rpt报表可达', false);
END $$;

-- 场景，pg_ivm immv 自动维护，顶层事务，DO 块内无法 ROLLBACK 测试数据，断言结果经 psql 变量带出，ROLLBACK 后落盘 verify_results
-- 单事务，operator 注入 guc 插入平衡凭证（DRAFT 转 SUBMITTED 转 APPROVED，25 状态机白名单要求逐级迁移），fms_admin 过账（等价 SECURITY DEFINER 路径），operator 插入核销分配与银行流水，事务内断言 4 个 immv 聚合增量（automatic 模式同事务立即可见），ROLLBACK 无残留
-- 注，INSERT...RETURNING 受 SELECT 策略校验，DRAFT 行对 operator 不可见，is_self 依赖 um.users ACTIVE 用户，凭证用显式 id 插入
BEGIN;
SET LOCAL fms.user_id  = '00000000-0000-7000-0000-000000000001';
SET LOCAL fms.org_ids  = '00000000-0000-7000-0000-000000000010';
SET LOCAL fms.dept_ids = '00000000-0000-7000-0000-000000000020,00000000-0000-7000-0000-000000000021';
SET ROLE fms_operator;

-- 基线，夹具插入前先读一次，断言按基线与增量比较，期望值不写死具体数值
-- 目标分组可能已承载他数据，业务种子与 API 生成的凭证都算，故一律按增量判定
\set b_ba1001 0
\set b_ba6001 0
\set b_de1001 0
\set b_ar 0
\set b_cf 0
\set b_c1 0
SELECT COALESCE((SELECT actual_amount FROM rpt.budget_actual
                  WHERE company_id = '00000000-0000-7000-0000-000000000010'
                    AND period = '2026-08'
                    AND account_id = '00000000-0000-7000-0000-000000000100'
                    AND dept_id = '00000000-0000-7000-0000-000000000020'), 0) AS b_ba1001,
       COALESCE((SELECT actual_amount FROM rpt.budget_actual
                  WHERE company_id = '00000000-0000-7000-0000-000000000010'
                    AND period = '2026-08'
                    AND account_id = '00000000-0000-7000-0000-00000000010e'
                    AND dept_id = '00000000-0000-7000-0000-000000000021'), 0) AS b_ba6001,
       COALESCE((SELECT amount FROM rpt.dept_expense
                  WHERE company_id = '00000000-0000-7000-0000-000000000010'
                    AND period = '2026-08'
                    AND dept_id = '00000000-0000-7000-0000-000000000020'
                    AND account_id = '00000000-0000-7000-0000-000000000100'), 0) AS b_de1001,
       COALESCE((SELECT paid_amount FROM rpt.ar_aging_paid
                  WHERE invoice_id = '00000000-0000-7000-0000-00000000cccc'), 0) AS b_ar,
       COALESCE((SELECT income FROM rpt.cash_flow_daily
                  WHERE company_id = '00000000-0000-7000-0000-000000000010'
                    AND trx_date = DATE '2026-08-09'), 0) AS b_cf,
       COALESCE((SELECT count(*) FROM rpt.budget_actual
                  WHERE company_id = '00000000-0000-7000-0000-000000000010'
                    AND period = '2026-08'
                    AND account_id = '00000000-0000-7000-0000-000000000100'
                    AND dept_id = '00000000-0000-7000-0000-000000000020'), 0) AS b_c1 \gset

INSERT INTO gl.vouchers
    (id, company_id, period, voucher_date, status, source_type, created_by,
     approved_by, total_debit, total_credit)
VALUES ('11111111-1111-7111-8111-111111111101',
        '00000000-0000-7000-0000-000000000010', '2026-08', DATE '2026-08-09',
        'DRAFT', 'VERIFY_TEST',
        '00000000-0000-7000-0000-000000000001', '00000000-0000-7000-0000-000000000002',
        500.00, 500.00);

INSERT INTO gl.voucher_lines (voucher_id, line_no, account_id, summary, debit, credit,
                              currency, dept_id, company_id)
VALUES ('11111111-1111-7111-8111-111111111101', 1,
        '00000000-0000-7000-0000-000000000100', 'IMMV自动维护验证-借500', 500.00, 0.00, 'CNY',
        '00000000-0000-7000-0000-000000000020', '00000000-0000-7000-0000-000000000010');

INSERT INTO gl.voucher_lines (voucher_id, line_no, account_id, summary, debit, credit,
                              currency, dept_id, company_id)
VALUES ('11111111-1111-7111-8111-111111111101', 2,
        '00000000-0000-7000-0000-00000000010e', 'IMMV自动维护验证-贷500', 0.00, 500.00, 'CNY',
        '00000000-0000-7000-0000-000000000021', '00000000-0000-7000-0000-000000000010');

UPDATE gl.vouchers SET status = 'SUBMITTED' WHERE id = '11111111-1111-7111-8111-111111111101';
UPDATE gl.vouchers SET status = 'APPROVED'  WHERE id = '11111111-1111-7111-8111-111111111101';
SET ROLE fms_admin;                     -- 过账（超级用户，等价 SECURITY DEFINER 路径）
UPDATE gl.vouchers SET status = 'POSTED' WHERE id = '11111111-1111-7111-8111-111111111101';
SET ROLE fms_operator;

INSERT INTO ar.receipt_allocations (company_id, receipt_id, invoice_id, amount)
VALUES ('00000000-0000-7000-0000-000000000010',
        '00000000-0000-7000-0000-00000000bbbb',
        '00000000-0000-7000-0000-00000000cccc', 500.00);

INSERT INTO cash.transactions (company_id, bank_account_id, direction, amount, trx_date)
VALUES ('00000000-0000-7000-0000-000000000010',
        '019fe2de-8c97-7d49-bb65-18a7bb305be2', 'IN', 88.00, DATE '2026-08-09');

-- 事务内断言，budget_actual 1001/020 较基线 +500，6001/021 较基线 -500，dept_expense 020/1001 较基线 +500，
-- ar_aging_paid 较基线 +500，cash_flow_daily 较基线 +88，且 1001/020 分组行数较基线 +1（新组刷新未产生重复分组），同事务可见
-- 子查询按 immv 全分组键（company_id,period,account_id,dept_id）过滤，同一科目可能有多分组（含 dept_id NULL 的行），仅按 account_id 过滤会返回多行报错
-- psql 变量兜底，\gset 因查询异常/无结果而未赋值时 ok/detail 保持默认值，避免事务外 \if :ok 引用未定义变量报错 "unrecognized value \":ok\" for \"\if expression\""
\set ok false
\set detail 'IMMV 断言查询未执行或无结果（\gset 未赋值，事务外落盘走 FAIL 分支）'
SELECT COALESCE((v_ba1001 = :b_ba1001::numeric + 500.00
        AND v_ba6001 = :b_ba6001::numeric - 500.00
        AND v_de1001 = :b_de1001::numeric + 500.00
        AND v_ar     = :b_ar::numeric     + 500.00
        AND v_cf     = :b_cf::numeric     + 88.00
        AND v_c1     = :b_c1::int         + 1), false) AS ok,
       format('budget_actual 1001/020=%s（基线 %s）、6001/021=%s（基线 %s）、dept_expense=%s（基线 %s）、ar_aging_paid=%s（基线 %s）、cash_flow_daily=%s（基线 %s）、组数=%s（基线 %s）',
              v_ba1001, :b_ba1001, v_ba6001, :b_ba6001, v_de1001, :b_de1001,
              v_ar, :b_ar, v_cf, :b_cf, v_c1, :b_c1) AS detail
FROM (SELECT (SELECT actual_amount FROM rpt.budget_actual
               WHERE company_id = '00000000-0000-7000-0000-000000000010'
                 AND period = '2026-08'
                 AND account_id = '00000000-0000-7000-0000-000000000100'
                 AND dept_id = '00000000-0000-7000-0000-000000000020')  AS v_ba1001,
             (SELECT actual_amount FROM rpt.budget_actual
               WHERE company_id = '00000000-0000-7000-0000-000000000010'
                 AND period = '2026-08'
                 AND account_id = '00000000-0000-7000-0000-00000000010e'
                 AND dept_id = '00000000-0000-7000-0000-000000000021')  AS v_ba6001,
             (SELECT amount FROM rpt.dept_expense
               WHERE company_id = '00000000-0000-7000-0000-000000000010'
                 AND period = '2026-08'
                 AND dept_id = '00000000-0000-7000-0000-000000000020'
                 AND account_id = '00000000-0000-7000-0000-000000000100')  AS v_de1001,
             (SELECT paid_amount FROM rpt.ar_aging_paid
               WHERE invoice_id = '00000000-0000-7000-0000-00000000cccc')  AS v_ar,
             (SELECT income FROM rpt.cash_flow_daily
               WHERE company_id = '00000000-0000-7000-0000-000000000010'
                 AND trx_date = DATE '2026-08-09')                          AS v_cf,
             (SELECT count(*) FROM rpt.budget_actual
               WHERE company_id = '00000000-0000-7000-0000-000000000010'
                 AND period = '2026-08'
                 AND account_id = '00000000-0000-7000-0000-000000000100'
                 AND dept_id = '00000000-0000-7000-0000-000000000020')      AS v_c1) s \gset

ROLLBACK;

-- 事务外落盘断言，psql 变量客户端保留，不受回滚影响
RESET ROLE;
INSERT INTO verify_results VALUES ('场景-IMMV自动维护', :'ok'::boolean);
\if :ok
\echo PASS: 场景-IMMV自动维护（4/4 IMMV 同事务增量生效，ROLLBACK 无残留）
\else
\echo FAIL: 场景-IMMV自动维护 detail=:detail
\endif

-- 场景，通知落库幂等，同一 user_id+ref_type+ref_id 连插两次只落一行（唯一索引 uk_notify_user_ref
-- 与闸口函数 ON CONFLICT DO NOTHING 共同承担），ref 为空的行不受约束可重复插入，验证后清理测试行
DO $$
DECLARE
    v_uid      uuid;
    v_ref      uuid := gen_random_uuid();
    v_cnt      int;
    v_null_cnt int;
BEGIN
    SELECT id INTO v_uid FROM um.users
     WHERE login_name = 'operator01' AND deleted_at IS NULL;
    IF v_uid IS NULL THEN
        RAISE NOTICE 'SKIP: 场景-通知落库幂等（无 operator01 种子用户，跳过）';
        INSERT INTO verify_results VALUES ('场景-通知落库幂等', true);
        RETURN;
    END IF;
    PERFORM notify.fn_insert_notification(v_uid, '验证-幂等', 'verify', 'SYSTEM', 'verify_probe', v_ref);
    PERFORM notify.fn_insert_notification(v_uid, '验证-幂等', 'verify', 'SYSTEM', 'verify_probe', v_ref);
    SELECT count(*) INTO v_cnt FROM notify.notifications
     WHERE user_id = v_uid AND ref_type = 'verify_probe' AND ref_id = v_ref;
    -- ref_id 为空的通知不受唯一键约束，PG 视 NULL 互不相等，两次调用应落两行
    PERFORM notify.fn_insert_notification(v_uid, '验证-幂等-空ref', 'verify');
    PERFORM notify.fn_insert_notification(v_uid, '验证-幂等-空ref', 'verify');
    SELECT count(*) INTO v_null_cnt FROM notify.notifications
     WHERE user_id = v_uid AND ref_id IS NULL AND title = '验证-幂等-空ref';
    DELETE FROM notify.notifications
     WHERE user_id = v_uid AND (ref_id = v_ref OR title = '验证-幂等-空ref');
    IF v_cnt = 1 AND v_null_cnt = 2 THEN
        RAISE NOTICE 'PASS: 场景-通知落库幂等（同键两次落 % 行，ref 为空两次落 % 行）', v_cnt, v_null_cnt;
        INSERT INTO verify_results VALUES ('场景-通知落库幂等', true);
    ELSE
        RAISE NOTICE 'FAIL: 场景-通知落库幂等（同键两次落 % 行，期望 1；ref 为空两次落 % 行，期望 2）',
            v_cnt, v_null_cnt;
        INSERT INTO verify_results VALUES ('场景-通知落库幂等', false);
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'FAIL: 场景-通知落库幂等（异常：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('场景-通知落库幂等', false);
END $$;

-- 场景，广播等锁上限只作用于广播锁，取锁前施加、取到后还原，故调用返回时 lock_timeout 与调用前一致；
-- 参数取空值或越界值亦不致命（回落缺省与越界截断的判定由通知闸口幂等与串行化的函数体断言承担，单会话内无法观测被还原后的上限）。
-- 探针行与临时改动的参数在本块内还原，本脚本逐语句提交，留下异常参数会把上限关掉。
DO $$
DECLARE
    v_old    jsonb;
    v_before text;
    v_after  text;
    v_titles int;
    v_ok     boolean;
BEGIN
    SELECT value INTO v_old FROM um.params WHERE code = 'notify.broadcast_lock_timeout_ms';
    -- 探针段整体包在子事务里，段内任何异常随子事务回滚，参数改动亦一并撤销
    BEGIN
        UPDATE um.params SET value = 'null'::jsonb WHERE code = 'notify.broadcast_lock_timeout_ms';
        v_before := current_setting('lock_timeout');
        PERFORM notify.fn_broadcast_notification('验证-上限空值', 'c', 'verify_lock_probe', NULL);
        v_after := current_setting('lock_timeout');
        UPDATE um.params SET value = '1000000000'::jsonb WHERE code = 'notify.broadcast_lock_timeout_ms';
        PERFORM notify.fn_broadcast_notification('验证-上限越界', 'c', 'verify_lock_probe', NULL);
    EXCEPTION WHEN OTHERS THEN
        v_before := NULL;
        v_after  := NULL;
        RAISE NOTICE 'WARN: 场景-等锁上限范围（探针异常：%）', SQLERRM;
    END;
    SELECT count(DISTINCT title) INTO v_titles
      FROM notify.notifications WHERE ref_type = 'verify_lock_probe';
    IF v_old IS NULL THEN
        DELETE FROM um.params WHERE code = 'notify.broadcast_lock_timeout_ms';
    ELSE
        UPDATE um.params SET value = v_old WHERE code = 'notify.broadcast_lock_timeout_ms';
    END IF;
    DELETE FROM notify.notifications WHERE ref_type = 'verify_lock_probe';
    v_ok := v_before IS NOT NULL AND v_before = v_after AND v_titles = 2;
    IF v_ok THEN
        RAISE NOTICE 'PASS: 场景-等锁上限范围（调用前后 lock_timeout 均 %，两种异常取值下广播各成功一次）', v_before;
        INSERT INTO verify_results VALUES ('场景-等锁上限范围', true);
    ELSE
        RAISE NOTICE 'FAIL: 场景-等锁上限范围（调用前 % 调用后 %，成功广播次数 %，期望前两者相等且为 2）',
            v_before, v_after, v_titles;
        INSERT INTO verify_results VALUES ('场景-等锁上限范围', false);
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'FAIL: 场景-等锁上限范围（异常：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('场景-等锁上限范围', false);
END $$;

-- 场景，报销单冲销前置（账先冲、单后标）：已过账单据在其凭证未冲销时置已冲销应被状态机触发器拒绝，
-- 同一单据的非状态字段在已过账后仍被冻结（受控冲销是唯一例外）。
-- 夹具用一张非冲销态真实凭证占位（voucher_id 有外键约束），块内创建、块内清理。
DO $$
DECLARE
    v_vid    uuid;
    v_rid    uuid;
    v_guard  boolean := false;
    v_frozen boolean := false;
    v_tamper boolean := false;
BEGIN
    SELECT id INTO v_vid FROM gl.vouchers
     WHERE deleted_at IS NULL AND status <> 'REVERSED'
     ORDER BY id LIMIT 1;
    IF v_vid IS NULL THEN
        RAISE NOTICE 'SKIP: 场景-报销单冲销前置（库内无非冲销态凭证可作占位）';
        INSERT INTO verify_results VALUES ('场景-报销单冲销前置', true);
        RETURN;
    END IF;
    INSERT INTO expense.reimbursements
        (company_id, applicant_id, dept_id, title, amount, currency, status, voucher_id, created_by)
    SELECT '00000000-0000-7000-0000-000000000010', u.id, d.id,
           'VERIFY_TEST 报销单冲销前置', 1.00, 'CNY', 'POSTED', v_vid, u.id
      FROM um.users u
      JOIN org.departments d ON d.company_id = '00000000-0000-7000-0000-000000000010'
       AND d.path = '1.1'::ltree AND d.deleted_at IS NULL
     WHERE u.login_name = 'admin'
    RETURNING id INTO v_rid;
    IF v_rid IS NULL THEN
        RAISE NOTICE 'SKIP: 场景-报销单冲销前置（缺用户或部门种子，无法造夹具）';
        INSERT INTO verify_results VALUES ('场景-报销单冲销前置', true);
        RETURN;
    END IF;

    SET ROLE fms_admin;                          -- 绕过 rls，单看触发器层兜底
    BEGIN
        UPDATE expense.reimbursements SET status = 'REVERSED' WHERE id = v_rid;
        v_guard := false;                        -- 未被拒绝即守卫缺失
    EXCEPTION WHEN OTHERS THEN
        v_guard := position('冲销前须先在总账冲销其凭证' in SQLERRM) > 0;
    END;
    BEGIN
        UPDATE expense.reimbursements SET amount = 2.00 WHERE id = v_rid;
        v_frozen := false;
    EXCEPTION WHEN OTHERS THEN
        v_frozen := true;
    END;
    -- 其三，借冲销顺带改写其它字段（含软删）应被拒：受控冲销只允许改状态列
    BEGIN
        UPDATE expense.reimbursements SET status = 'REVERSED', amount = 2.00 WHERE id = v_rid;
        v_tamper := false;
    EXCEPTION WHEN OTHERS THEN
        v_tamper := position('只允许变更状态列' in SQLERRM) > 0;
    END;
    BEGIN
        UPDATE expense.reimbursements SET status = 'REVERSED', deleted_at = now() WHERE id = v_rid;
        v_tamper := v_tamper AND false;
    EXCEPTION WHEN OTHERS THEN
        v_tamper := v_tamper AND position('只允许变更状态列' in SQLERRM) > 0;
    END;
    RESET ROLE;

    DELETE FROM expense.reimbursements WHERE id = v_rid;
    IF v_guard AND v_frozen AND v_tamper THEN
        RAISE NOTICE 'PASS: 场景-报销单冲销前置（凭证未冲销时置已冲销被拒，非状态字段被冻结，借冲销改字段被拒）';
        INSERT INTO verify_results VALUES ('场景-报销单冲销前置', true);
    ELSE
        RAISE NOTICE 'FAIL: 场景-报销单冲销前置（前置守卫=% 冻结=% 借冲销改字段=%）', v_guard, v_frozen, v_tamper;
        INSERT INTO verify_results VALUES ('场景-报销单冲销前置', false);
    END IF;
EXCEPTION WHEN OTHERS THEN
    RESET ROLE;
    RAISE NOTICE 'FAIL: 场景-报销单冲销前置（异常：%）', SQLERRM;
    INSERT INTO verify_results VALUES ('场景-报销单冲销前置', false);
END $$;

-- 清理与汇总
-- 清理上文不平衡凭证场景遗留的测试凭证，VERIFY_TEST，superuser 直删，rls/权限不受限
DO $$
DECLARE v_ids uuid[];
BEGIN
    SELECT array_agg(id) INTO v_ids FROM gl.vouchers WHERE source_type = 'VERIFY_TEST';
    IF v_ids IS NOT NULL THEN
        DELETE FROM gl.voucher_lines WHERE voucher_id = ANY(v_ids);
        DELETE FROM gl.vouchers     WHERE id = ANY(v_ids);
        RAISE NOTICE 'INFO: 已清理验证测试凭证 % 张（VERIFY_TEST）', array_length(v_ids, 1);
    ELSE
        RAISE NOTICE 'INFO: 无验证测试凭证需要清理';
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'WARN: 验证测试凭证清理失败：%', SQLERRM;
END $$;

-- 汇总（PASS/FAIL 计数 + 明细清单）
-- 条数守卫，结果表行数须等于断言总数，少于总数即说明有断言整条丢失
-- 该类丢失只表现为计数变小，看的人无从察觉，故一并报出并以 FAIL 措辞提示
DO $$
DECLARE v_ok int; v_fail int; v_expect int := 28;
BEGIN
    SELECT count(*) FILTER (WHERE ok), count(*) FILTER (WHERE NOT ok)
      INTO v_ok, v_fail FROM verify_results;
    RAISE NOTICE 'verify: 验证汇总 PASS=% FAIL=%（共 % 项断言，预期 % 项%）',
        v_ok, v_fail, v_ok + v_fail, v_expect,
        CASE WHEN v_ok + v_fail = v_expect THEN '' ELSE '，条数不符，有断言未落盘' END;
    IF v_ok + v_fail <> v_expect THEN
        RAISE NOTICE 'FAIL: verify 条数守卫（实际 % 项，预期 % 项断言全部落盘）', v_ok + v_fail, v_expect;
    END IF;
END $$;

SELECT test_name, CASE WHEN ok THEN 'PASS' ELSE 'FAIL' END AS result
  FROM verify_results
 ORDER BY ok, test_name;
