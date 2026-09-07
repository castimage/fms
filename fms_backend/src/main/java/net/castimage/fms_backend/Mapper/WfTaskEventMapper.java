package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.WfTaskEvent;
import net.castimage.fms_backend.Model.vo.WfTaskEventView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/** 任务事件 Mapper（wf.task_events）：仅追加流水查询（join 操作人登录名），写入由 PG 函数/触发器完成 */
public interface WfTaskEventMapper extends BaseMapper<WfTaskEvent> {

    /** 任务事件流水（按发生时间升序；RLS can_access_org 公司范围收敛） */
    @Select("""
            SELECT e.id, e.company_id, e.task_id, e.action, e.operator_id,
                   u.login_name AS operator_name, e.from_node, e.to_node, e.remark,
                   e.occurred_at
              FROM wf.task_events e
              LEFT JOIN um.users u ON u.id = e.operator_id
             WHERE e.task_id = #{taskId}
             ORDER BY e.occurred_at, e.id
            """)
    List<WfTaskEventView> selectByTaskId(@Param("taskId") UUID taskId);
}
