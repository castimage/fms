-- gl 函数部署后自查，9 个核心函数可调用性，不得出现类型/签名错误

-- 验证脚本只读、无副作用，
-- 全文件包裹 BEGIN/ROLLBACK 事务，fn_close_period('2026-08') 在无未完成凭证的

-- 库上会真实结账（gl.periods 2026-08 OPEN 转 CLOSED，系统无法再录凭证），重跑 selfcheck
-- 即对库产生不可逆修改，事务回滚后任何状态变更均不落库。
-- 结账/反结账使用不存在的测试期间 '2099-01'，不存在期间必然触发业务异常

-- （'不存在或已结账'/'未结账'），"期望抛错"断言在任何库状态下都成立，
-- 且不触碰真实期间，即使脱离事务包裹单独执行本 DO 块也无副作用。
BEGIN;
DO $do$
DECLARE
    v_uid uuid := '00000000-0000-7000-0000-000000000201';  -- DRAFT voucher
    v_cid uuid := '00000000-0000-7000-0000-000000000010';  -- COM-001
    v_int int;
    r     jsonb;
BEGIN
    -- gl.fn_post_voucher(uuid)，DRAFT 时状态机拒绝，业务异常，非类型错误

    BEGIN
        PERFORM gl.fn_post_voucher(v_uid);
        RAISE NOTICE 'post_voucher: unexpectedly ok';
    EXCEPTION WHEN others THEN
        IF SQLSTATE IN ('42P18','42804','42704','42883','42725','42P08','22023') OR SQLERRM ~* 'type' THEN
            RAISE EXCEPTION 'post_voucher TYPE/SIGNATURE ERROR: %', SQLERRM;
        END IF;
        RAISE NOTICE 'post_voucher ok (expected raise): %', left(SQLERRM, 70);
    END;

    --gl.fn_reverse_voucher(uuid, date, text)

    BEGIN
        PERFORM gl.fn_reverse_voucher(v_uid);
        RAISE NOTICE 'reverse_voucher: unexpectedly ok';
    EXCEPTION WHEN others THEN
        IF SQLSTATE IN ('42P18','42804','42704','42883','42725','42P08','22023') OR SQLERRM ~* 'type' THEN
            RAISE EXCEPTION 'reverse_voucher TYPE/SIGNATURE ERROR: %', SQLERRM;
        END IF;
        RAISE NOTICE 'reverse_voucher ok (expected raise): %', left(SQLERRM, 70);
    END;

    -- gl.fn_close_period(uuid, text)，测试期间 '2099-01' 不存在则必然业务异常（'不存在或已结账'），

    -- 不触碰真实期间，'2026-08' 在无未完成凭证的库上会真实结账，OPEN 转 CLOSED，不可逆副作用
    BEGIN
        PERFORM gl.fn_close_period(v_cid, '2099-01');
        RAISE NOTICE 'close_period: unexpectedly ok';
    EXCEPTION WHEN others THEN
        IF SQLSTATE IN ('42P18','42804','42704','42883','42725','42P08','22023') OR SQLERRM ~* 'type' THEN
            RAISE EXCEPTION 'close_period TYPE/SIGNATURE ERROR: %', SQLERRM;
        END IF;
        RAISE NOTICE 'close_period ok (expected raise): %', left(SQLERRM, 70);
    END;

    -- gl.fn_reopen_period(uuid, text, text)，测试期间 '2099-01' 不存在则必然业务异常，

    -- '2026-08' 在已结账库上会真实反结账，CLOSED 转 OPEN，同样不可逆副作用
    BEGIN
        PERFORM gl.fn_reopen_period(v_cid, '2099-01', 'selfcheck');
        RAISE NOTICE 'reopen_period: unexpectedly ok';
    EXCEPTION WHEN others THEN
        IF SQLSTATE IN ('42P18','42804','42704','42883','42725','42P08','22023') OR SQLERRM ~* 'type' THEN
            RAISE EXCEPTION 'reopen_period TYPE/SIGNATURE ERROR: %', SQLERRM;
        END IF;
        RAISE NOTICE 'reopen_period ok (expected raise): %', left(SQLERRM, 70);
    END;

    -- cash.fn_daily_report(uuid, date) 返回 jsonb

    BEGIN
        SELECT cash.fn_daily_report(v_cid, DATE '2026-08-01') INTO r;
        RAISE NOTICE 'daily_report ok (jsonb, len=%): %', length(r::text), left(r::text, 60);
    EXCEPTION WHEN others THEN
        IF SQLSTATE IN ('42P18','42804','42704','42883','42725','42P08','22023') THEN
            RAISE EXCEPTION 'daily_report TYPE/SIGNATURE ERROR: %', SQLERRM;
        END IF;
        RAISE NOTICE 'daily_report ok (expected raise): %', left(SQLERRM, 70);
    END;

    --gl.fn_voucher_line_balance 触发器函数，直调应为触发器上下文异常，而非类型错误

    BEGIN
        PERFORM gl.fn_voucher_line_balance();
        RAISE NOTICE 'line_balance: unexpectedly ok';
    EXCEPTION WHEN others THEN
        IF SQLSTATE IN ('42P18','42804','42704','42883','42725','42P08','22023') OR SQLERRM ~* 'type' THEN
            RAISE EXCEPTION 'line_balance TYPE/SIGNATURE ERROR: %', SQLERRM;
        END IF;
        RAISE NOTICE 'line_balance ok (trigger-context raise expected): %', left(SQLERRM, 60);
    END;

    --gl.fn_voucher_status_history 触发器函数

    BEGIN
        PERFORM gl.fn_voucher_status_history();
        RAISE NOTICE 'status_history: unexpectedly ok';
    EXCEPTION WHEN others THEN
        IF SQLSTATE IN ('42P18','42804','42704','42883','42725','42P08','22023') OR SQLERRM ~* 'type' THEN
            RAISE EXCEPTION 'status_history TYPE/SIGNATURE ERROR: %', SQLERRM;
        END IF;
        RAISE NOTICE 'status_history ok (trigger-context raise expected): %', left(SQLERRM, 60);
    END;

    --gl.fn_revert_voucher(uuid)，DRAFT 凭证非 REJECTED 必然业务异常

    BEGIN
        PERFORM gl.fn_revert_voucher(v_uid);
        RAISE NOTICE 'revert_voucher: unexpectedly ok';
    EXCEPTION WHEN others THEN
        IF SQLSTATE IN ('42P18','42804','42704','42883','42725','42P08','22023') OR SQLERRM ~* 'type' THEN
            RAISE EXCEPTION 'revert_voucher TYPE/SIGNATURE ERROR: %', SQLERRM;
        END IF;
        RAISE NOTICE 'revert_voucher ok (expected raise): %', left(SQLERRM, 70);
    END;

    -- gl.fn_fx_adjustment(uuid, text, text)，2026-08 授权核对，'2099-01' 无汇率/期间则走业务路径

    --，0 或跳过，仅核对可调用性与 EXECUTE 授权存在，不落任何数据
    BEGIN
        SELECT gl.fn_fx_adjustment(v_cid, '2099-01') INTO v_int;
        RAISE NOTICE 'fx_adjustment ok (no-op expected): %', v_int;
    EXCEPTION WHEN others THEN
        IF SQLSTATE IN ('42P18','42804','42704','42883','42725','42P08','22023') OR SQLERRM ~* 'type' THEN
            RAISE EXCEPTION 'fx_adjustment TYPE/SIGNATURE ERROR: %', SQLERRM;
        END IF;
        RAISE NOTICE 'fx_adjustment ok (expected raise): %', left(SQLERRM, 70);
    END;

    RAISE NOTICE 'gl_core_functions SELF-CHECK: ALL 9 FUNCTIONS CALLABLE, NO TYPE ERRORS';
END
$do$;
-- 回滚整个自查事务，确认只读、无任何副作用，结账/反结账/状态机调用全部不落库
ROLLBACK;
