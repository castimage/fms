package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.WfTask;
import net.castimage.fms_backend.Model.vo.WfTaskView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * 工作流任务 Mapper（wf.tasks）：并发领取/办结委托 PG SECURITY DEFINER 函数
 * （wf.claim_task / wf.complete_task，grants_base 已授 fms_operator EXECUTE），
 * 规避 RLS 更新拦截；待办/实例任务/详情 join 查询手写。
 */
public interface WfTaskMapper extends BaseMapper<WfTask> {

    /** 待办分页列表：assignee=当前用户 AND status IN (PENDING,CLAIMED)（RLS is_self 双路径兜底）；join 实例/定义/处理人，due_at 升序 */
    @Select("""
            SELECT t.id, t.company_id, t.instance_id, i.business_type, i.business_id,
                   d.name AS definition_name, t.node_code, t.assignee_id,
                   u.login_name AS assignee_name, t.task_type, t.status,
                   t.due_at, t.claim_at, t.version, t.remark, t.created_at
              FROM wf.tasks t
              JOIN wf.instances i ON i.id = t.instance_id AND i.deleted_at IS NULL
              LEFT JOIN wf.definitions d ON d.id = i.definition_id AND d.deleted_at IS NULL
              LEFT JOIN um.users u ON u.id = t.assignee_id
             WHERE t.assignee_id = #{assigneeId}::uuid
               AND t.status IN ('PENDING', 'CLAIMED')
               AND t.deleted_at IS NULL
             ORDER BY t.due_at, t.created_at, t.id
            """)
    IPage<WfTaskView> selectTodoPage(Page<WfTaskView> page, @Param("assigneeId") UUID assigneeId);

    /** 实例下全部任务（按创建时间升序；含已办结，供实例详情回溯） */
    @Select("""
            SELECT t.id, t.company_id, t.instance_id, i.business_type, i.business_id,
                   d.name AS definition_name, t.node_code, t.assignee_id,
                   u.login_name AS assignee_name, t.task_type, t.status,
                   t.due_at, t.claim_at, t.version, t.remark, t.created_at
              FROM wf.tasks t
              JOIN wf.instances i ON i.id = t.instance_id AND i.deleted_at IS NULL
              LEFT JOIN wf.definitions d ON d.id = i.definition_id AND d.deleted_at IS NULL
              LEFT JOIN um.users u ON u.id = t.assignee_id
             WHERE t.instance_id = #{instanceId} AND t.deleted_at IS NULL
             ORDER BY t.created_at, t.id
            """)
    List<WfTaskView> selectByInstanceId(@Param("instanceId") UUID instanceId);

    /** 任务详情（join 名称；RLS 空集返回 null 则 data:null） */
    @Select("""
            SELECT t.id, t.company_id, t.instance_id, i.business_type, i.business_id,
                   d.name AS definition_name, t.node_code, t.assignee_id,
                   u.login_name AS assignee_name, t.task_type, t.status,
                   t.due_at, t.claim_at, t.version, t.remark, t.created_at
              FROM wf.tasks t
              JOIN wf.instances i ON i.id = t.instance_id AND i.deleted_at IS NULL
              LEFT JOIN wf.definitions d ON d.id = i.definition_id AND d.deleted_at IS NULL
              LEFT JOIN um.users u ON u.id = t.assignee_id
             WHERE t.id = #{id} AND t.deleted_at IS NULL
            """)
    WfTaskView selectTaskDetail(@Param("id") UUID id);

    /**
     * 领取任务：调 wf.claim_task（便捷入口，内部取 current_uid 转调 fn_claim_task）——
     * PG 侧 UPDATE + CTE 内 FOR UPDATE SKIP LOCKED 原子完成，并发仅一人领取成功
     * （仅领取分配给本人的 PENDING 任务），成功写 CLAIM 事件；返回 false 则 40900"任务不可领取"。
     */
    @Select("SELECT wf.claim_task(#{taskId}::uuid)")
    boolean claim(@Param("taskId") UUID taskId);

    /**
     * 任务办结：调 wf.complete_task(taskId, action, remark)——PG 侧 require_permission
     * （wf.instance.approve/reject）+ assignee 归属校验（非本人 RAISE 42501 映射 40300）+
     * 幂等（已办结返回 false 则 40900）+ 实例状态机推进（approve 经 fn_node_done 判定节点通过
     * 后由 fn_advance_instance 推进下一节点或置实例 APPROVED，reject 直置实例 REJECTED 并软删
     * 同节点其余票，transfer 的 remark 为新处理人 uuid 文本即同节点新建 PENDING 任务）+
     * 事件留痕 + PGMQ 通知（失败静默）。
     */
    @Select("SELECT wf.complete_task(#{taskId}::uuid, #{action}, #{remark})")
    boolean complete(@Param("taskId") UUID taskId,
                     @Param("action") String action,
                     @Param("remark") String remark);
}
