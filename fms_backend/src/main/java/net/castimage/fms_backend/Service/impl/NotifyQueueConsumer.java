package net.castimage.fms_backend.Service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import net.castimage.fms_backend.Controller.NotifyWebSocketEndpoint;
import net.castimage.fms_backend.Mapper.NotificationMapper;
import net.castimage.fms_backend.Service.NotifyService;
import net.castimage.fms_backend.config.FmsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 通知生成源消费者（PGMQ 到 notify.notifications，@Scheduled 轮询）：q_notify_task
 * （wf 任务完成/超时催办）与 q_budget_warning（cron 预算预警）投递后由此消费，
 * 通知表数据由此闭环生成。
 *
 * 消费语义（pgmq 消费约定）：pgmq.read 按 vt 可见性超时 + SKIP
 * LOCKED 并发领取（多实例各读各的，互不抢同一条）；成功即 archive（确认消费，不再回投）；
 * 失败不确认——vt 过期自动回投，read_ct 递增，达到 retry-threshold 后放弃（archive +
 * WARN，防死信堆积）。周期 fms.pgmq.notify-consumer-interval-ms（默认 30s）独立于
 * cache-cleanup；总开关 fms.pgmq.notify-consumer-enabled（默认开，关停时消息留队可重拾）。
 *
 * 消息体结构（与投递侧逐字对齐）：
 *
 * q_notify_task：{"type":"wf_task"|"wf_timeout","instance_id":uuid,"task_id":uuid,
 * "assignee_id":uuid,"action":...,"ts":...|"due_at":...}——用户级，按 assignee_id 落库；
 * 成功后 WS 推送该用户最新未读数（与已读动作同款 unread_count 载荷）；
 * q_budget_warning：{"company_id":uuid,"dept_id":...,"account_id":...,"period":...,
 * "ratio":...}——公司级（无用户维度），按 fn_broadcast_notification 广播至全部 ACTIVE
 * 用户（幂等：同 ref_type+ref_id+title 已存在整批跳过），成功后 WS 全量推送。
 */
@Component
public class NotifyQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotifyQueueConsumer.class);

    /** pgmq.read 可见性超时（秒）：30s 内未 archive 自动回投重试 */
    private static final int VISIBILITY_TIMEOUT_SECS = 30;

    /** 每队列每轮最大读取条数（与 log 消费批 500 同量级内收敛为 50，防止单轮长事务） */
    private static final int BATCH_SIZE = 50;

    private final NotificationMapper notificationMapper;
    private final NotifyService notifyService;
    private final FmsProperties fmsProperties;

    public NotifyQueueConsumer(NotificationMapper notificationMapper,
                               NotifyService notifyService,
                               FmsProperties fmsProperties) {
        this.notificationMapper = notificationMapper;
        this.notifyService = notifyService;
        this.fmsProperties = fmsProperties;
    }

    /** 轮询两条队列（固定延迟：上一轮完成后再等 interval，防堆叠） */
    @Scheduled(fixedDelayString = "${fms.pgmq.notify-consumer-interval-ms:30000}")
    public void consume() {
        if (!fmsProperties.getPgmq().isNotifyConsumerEnabled()) {
            return; // 显式硬关停（fms.pgmq.notify-consumer-enabled=false）：消息留在队列（vt 过期后仍可被重拾），零副作用
        }
        if (!ensurePgmqAvailable()) {
            return; // 部署形态差异自动降级：pg_extension 无 pgmq 时整体停用（已限频 WARN 一次，周期重探测）
        }
        try {
            consumeUserQueue();
            consumeBudgetWarningQueue();
        } catch (RuntimeException e) {
            // 兜底：探测通过后仍可能瞬时失败（扩展被移除/连接异常）——
            // 与 log_access_batch 双路径降级同策略，WARN 限频防每轮刷屏
            // （通知消费是可恢复的尽力而为，消息留队待扩展补齐后自动重拾）
            long now = System.currentTimeMillis();
            if (now - lastWarnMillis >= WARN_THROTTLE_MS) {
                log.warn("通知队列消费失败（将限频重试）: {}", e.getMessage());
                lastWarnMillis = now;
            }
            // 消费侧报 schema 缺失说明可用性缓存已失效，下一轮强制重探测
            pgmqProbed = false;
        }
    }

    /**
     * PGMQ 可用性自探测（部署形态差异自动降级）：消费前查
     * pg_extension 是否安装 pgmq——无 pgmq 的部署（镜像未编译该扩展）下消费者
     * 整体停用、消息留队，避免每 30s 抛 'schema pgmq does not exist' 报错刷屏；
     * 探测结果缓存并按 PROBE_INTERVAL_MS 周期重探测，pgmq 补齐（重启或运行时
     * CREATE EXTENSION）后自动恢复消费。显式配置 fms.pgmq.notify-consumer-enabled
     * =false 仍为最高优先级的硬关停。
     */
    private boolean ensurePgmqAvailable() {
        long now = System.currentTimeMillis();
        if (pgmqProbed && pgmqAvailable) {
            return true; // 已确认可用，直接消费
        }
        if (pgmqProbed && now - lastProbeMillis < PROBE_INTERVAL_MS) {
            return false; // 上次确认不可用且未到重探测周期
        }
        lastProbeMillis = now;
        pgmqProbed = true;
        boolean ok;
        try {
            ok = notificationMapper.pgmqAvailable();
        } catch (RuntimeException e) {
            ok = false; // 探测查询本身失败（连接异常等）按不可用降级，下一周期重探
            if (now - lastWarnMillis >= WARN_THROTTLE_MS) {
                log.warn("PGMQ 可用性探测失败（按不可用降级停用通知消费者）: {}", e.getMessage());
                lastWarnMillis = now;
            }
        }
        pgmqAvailable = ok;
        if (!ok && now - lastWarnMillis >= WARN_THROTTLE_MS) {
            log.warn("PGMQ 功能不可用（pgmq.send 函数不存在），通知队列消费者降级停用，"
                    + "消息留队待 pgmq 补齐后自动恢复消费；如需显式控制可设 fms.pgmq.notify-consumer-enabled=false");
            lastWarnMillis = now;
        }
        return ok;
    }

    /** pgmq 缺失等环境性失败的 WARN 节流窗口（毫秒）：首败立即提示，此后至多每窗口一次 */
    private static final long WARN_THROTTLE_MS = 300_000L;

    /** PGMQ 可用性重探测周期（毫秒）：探测到不可用后按此周期复查，扩展补齐自动恢复 */
    private static final long PROBE_INTERVAL_MS = 60_000L;

    /** 上次环境性失败 WARN 时刻（epoch millis，volatile 跨线程可见） */
    private volatile long lastWarnMillis = 0;

    /** 是否已完成首次 PGMQ 可用性探测（false=未探测或缓存失效需重探） */
    private volatile boolean pgmqProbed = false;

    /** PGMQ 可用性探测结果缓存（true=pg_extension 含 pgmq，可消费） */
    private volatile boolean pgmqAvailable = false;

    /** 上次探测时刻（epoch millis） */
    private volatile long lastProbeMillis = 0L;

    /** q_notify_task：wf 任务完成/超时催办（用户级，assignee_id 落库 + WS 未读数推送） */
    private void consumeUserQueue() {
        List<Map<String, Object>> msgs =
                notificationMapper.readQueue("q_notify_task", VISIBILITY_TIMEOUT_SECS, BATCH_SIZE);
        for (Map<String, Object> row : msgs) {
            long msgId = ((Number) row.get("msg_id")).longValue();
            int readCt = ((Number) row.get("read_ct")).intValue();
            try {
                JSONObject payload = JSON.parseObject((String) row.get("message"));
                if (payload == null) {
                    throw new IllegalArgumentException("消息体非 JSON");
                }
                String type = payload.getString("type");
                UUID assigneeId = UUID.fromString(payload.getString("assignee_id"));
                UUID taskId = UUID.fromString(payload.getString("task_id"));
                String title;
                String content;
                if ("wf_timeout".equals(type)) {
                    title = "待办任务已超时";
                    content = "任务 " + taskId + " 已超过截止时间，请尽快处理。";
                } else { // wf_task：任务完成/转办/驳回后的新任务提醒
                    title = "审批任务待处理";
                    content = "您有新的审批任务（" + type + "），请及时办理。";
                }
                notificationMapper.insertNotification(assigneeId, title, content, "SYSTEM",
                        "wf_task", taskId);
                notificationMapper.archiveMessage("q_notify_task", msgId);
                // 成功后 WS 推送该用户最新未读数（角标实时刷新，与已读动作同款载荷）
                NotifyWebSocketEndpoint.sendToUser(assigneeId,
                        "{\"type\":\"unread_count\",\"count\":"
                                + notifyService.unreadCount(assigneeId) + "}");
            } catch (RuntimeException e) {
                handleFailure("q_notify_task", msgId, readCt, e);
            }
        }
    }

    /** q_budget_warning：cron 预算执行比例预警（公司级，无用户维度则广播全部 ACTIVE 用户） */
    private void consumeBudgetWarningQueue() {
        List<Map<String, Object>> msgs =
                notificationMapper.readQueue("q_budget_warning", VISIBILITY_TIMEOUT_SECS, BATCH_SIZE);
        for (Map<String, Object> row : msgs) {
            long msgId = ((Number) row.get("msg_id")).longValue();
            int readCt = ((Number) row.get("read_ct")).intValue();
            try {
                JSONObject payload = JSON.parseObject((String) row.get("message"));
                if (payload == null) {
                    throw new IllegalArgumentException("消息体非 JSON");
                }
                String title = "预算执行预警";
                String content = "预算执行比例已达 " + payload.getString("ratio")
                        + "（期间 " + payload.getString("period") + "），请关注执行情况。";
                // ref_id 取 company_id（公司级幂等键：同公司同标题不重复广播）
                UUID companyId = UUID.fromString(payload.getString("company_id"));
                notificationMapper.broadcastNotification(title, content, "budget_warning", companyId);
                notificationMapper.archiveMessage("q_budget_warning", msgId);
                // 全量 WS 推送（本地 + 跨实例），载荷带标题供前端刷新角标/列表
                NotifyWebSocketEndpoint.broadcast(JSONObject.of(
                        "type", "budget_warning", "title", title).toJSONString());
            } catch (RuntimeException e) {
                handleFailure("q_budget_warning", msgId, readCt, e);
            }
        }
    }

    /** 消费失败处理：未达重试阈值则留队（vt 过期自动回投，read_ct+1 自然重试）；超限放弃 */
    private void handleFailure(String queue, long msgId, int readCt, RuntimeException e) {
        int threshold = fmsProperties.getPgmq().getRetryThreshold();
        if (readCt >= threshold) {
            // read_ct 超限：放弃并归档，防死信堆积
            try {
                notificationMapper.archiveMessage(queue, msgId);
            } catch (RuntimeException archiveEx) {
                log.warn("PGMQ 死信归档失败 queue={} msgId={}: {}", queue, msgId, archiveEx.getMessage());
            }
            log.warn("PGMQ 消息消费失败且超重试阈值，放弃 queue={} msgId={} readCt={}: {}",
                    queue, msgId, readCt, e.getMessage());
        } else {
            log.warn("PGMQ 消息消费失败，待 vt 过期重试 queue={} msgId={} readCt={}: {}",
                    queue, msgId, readCt, e.getMessage());
        }
    }
}
