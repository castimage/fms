package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.Notification;
import net.castimage.fms_backend.Model.vo.NotificationView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 站内通知 Mapper（notify.notifications）：单表 CRUD 经 BaseMapper + Wrapper（RLS user_id 收敛）；
 * 未读数/分页/已读标记手写 B 类（聚合计数与条件 UPDATE——已读为原子 UPDATE ... WHERE
 * user_id AND NOT is_read，防并发重复已读无副作用）。
 */
public interface NotificationMapper extends BaseMapper<Notification> {

    /**
     * 本人未读通知数（count 聚合，B 类）：角标/WebSocket 推送未读数来源。
     */
    @Select("""
            SELECT count(*)
              FROM notify.notifications
             WHERE user_id = #{userId}::uuid
               AND NOT is_read
               AND deleted_at IS NULL
            """)
    long selectUnreadCount(@Param("userId") UUID userId);

    /**
     * 本人通知分页（isRead 可选过滤，排序白名单 created_at DESC, id DESC）。
     */
    @Select("""
            SELECT id, title, content, type, ref_type, ref_id, is_read,
                   created_at, read_at
              FROM notify.notifications
             WHERE user_id = #{userId}::uuid
               AND deleted_at IS NULL
               AND (#{isRead, jdbcType=BOOLEAN} IS NULL OR is_read = #{isRead, jdbcType=BOOLEAN})
             ORDER BY created_at DESC, id DESC
            """)
    IPage<NotificationView> selectUserPage(Page<NotificationView> page,
                                           @Param("userId") UUID userId,
                                           @Param("isRead") Boolean isRead);

    /**
     * 单条已读（原子 UPDATE：user_id 归属校验 + 未删过滤；影响行数 0 = 不存在/非本人则 40400）。
     */
    @Update("""
            UPDATE notify.notifications
               SET is_read = true, read_at = now()
             WHERE id = #{id}::uuid
               AND user_id = #{userId}::uuid
               AND deleted_at IS NULL
            """)
    int markRead(@Param("id") UUID id, @Param("userId") UUID userId);

    /**
     * 全部已读（仅未读行，幂等；返回本次置读行数）。
     */
    @Update("""
            UPDATE notify.notifications
               SET is_read = true, read_at = now()
             WHERE user_id = #{userId}::uuid
               AND NOT is_read
               AND deleted_at IS NULL
            """)
    int markAllRead(@Param("userId") UUID userId);

    // 通知生成源（init/notify_broadcast.sql SECURITY DEFINER 闸口 + PGMQ 消费）
    // RLS 的 INSERT WITH CHECK (user_id = auth.current_uid()) 使 fms_app/fms_operator
    // 无法批量插他人通知，批量/单条插入必须经 SECURITY DEFINER 函数（属主 fms_admin）绕过

    /**
     * 系统公告/公司级预警全量广播（notify.fn_broadcast_notification，SECURITY DEFINER）：
     * 向全部 ACTIVE 用户逐行 INSERT；幂等——同 ref_type+ref_id+title 已存在则整批跳过
     * （防 cron/重试重复）；返回实际插入行数。
     */
    @Select("""
            SELECT notify.fn_broadcast_notification(#{title}, #{content}, #{refType}, #{refId}::uuid)
            """)
    long broadcastNotification(@Param("title") String title, @Param("content") String content,
                               @Param("refType") String refType, @Param("refId") UUID refId);

    /**
     * 单用户通知插入（notify.fn_insert_notification，SECURITY DEFINER 绕过 RLS）：
     * PGMQ 消费（q_notify_task 任务完成/超时催办）落库入口。
     */
    @Select("""
            SELECT notify.fn_insert_notification(#{userId}::uuid, #{title}, #{content}, #{type}, #{refType}, #{refId}::uuid)
            """)
    Object insertNotification(@Param("userId") UUID userId, @Param("title") String title,
                              @Param("content") String content, @Param("type") String type,
                              @Param("refType") String refType, @Param("refId") UUID refId);

    // PGMQ 队列读取/确认（pgmq 核心函数已 SECURITY DEFINER 且 EXECUTE 授 fms_app）
    // 复合类型 message_record 在 SQL 内展开为列（(r).msg_id 等），规避 MyBatis 复合类型映射；
    // vt 可见性超时秒：读取后 vt 内消息对他人不可见，未确认自动回投（天然重试语义）

    /**
     * 批量读取队列（pgmq.read(queue, vt, qty)，SKIP LOCKED 并发互不抢同一条）：
     * 返回列 msg_id/read_ct/message(json 文本)。
     */
    @Select("""
            SELECT (r).msg_id AS msg_id, (r).read_ct AS read_ct, (r).message::text AS message
              FROM pgmq.read(#{queue}, #{vt}, #{qty}) r
            """)
    List<Map<String, Object>> readQueue(@Param("queue") String queue,
                                        @Param("vt") int vt, @Param("qty") int qty);

    /**
     * 确认消费（pgmq.archive(queue, msg_id)）：消息入归档表，不再回投。
     */
    @Select("SELECT pgmq.archive(#{queue}, #{msgId})")
    boolean archiveMessage(@Param("queue") String queue, @Param("msgId") long msgId);

    /**
     * PGMQ 扩展可用性探测（NotifyQueueConsumer 首轮消费前调用，部署形态差异自动降级）：
     * pg_extension 无 pgmq 的部署（镜像未安装该扩展）整体停用消费者，避免每轮
     * readQueue 抛 'schema pgmq does not exist'；扩展补齐后探测自动转真并恢复消费。
     */
    @Select("SELECT EXISTS(SELECT 1 FROM pg_extension WHERE extname = 'pgmq')")
    boolean pgmqAvailable();
}
