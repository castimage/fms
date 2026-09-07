package net.castimage.fms_backend.Service;

import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.TaskActionRequest;
import net.castimage.fms_backend.Model.dto.WfDefinitionRequest;
import net.castimage.fms_backend.Model.dto.WfInstanceRequest;
import net.castimage.fms_backend.Model.vo.WfDefinitionView;
import net.castimage.fms_backend.Model.vo.WfInstanceView;
import net.castimage.fms_backend.Model.vo.WfTaskEventView;
import net.castimage.fms_backend.Model.vo.WfTaskView;

import java.util.List;
import java.util.UUID;

/**
 * 审批流模块业务接口（流程定义 CRUD/激活、实例查询、任务领取/审批/驳回/转办/事件）：
 * 领取与办结委托 PG SECURITY DEFINER 函数（wf.claim_task FOR UPDATE SKIP LOCKED 并发
 * 仅一人成功；wf.complete_task 内含 require_permission + assignee 校验 + 状态机推进），
 * false 则 40900；非法流转 RAISE（42501 映射 40300 / P0001 映射 40900）由全局异常处理器映射。
 */
public interface WfService {

    /**
     * 流程定义分页列表（companyId/status/keyword 可选过滤）。
     */
    PageResult<WfDefinitionView> pageDefinitions(long page, long size, UUID companyId,
                                                 String status, String keyword);

    /**
     * 流程定义详情（RLS 空集返回 null 则 data:null）。
     */
    WfDefinitionView getDefinition(UUID id);

    /**
     * 创建 DRAFT 定义（node_json 须为合法 JSON；code 公司内唯一）。
     */
    WfDefinitionView createDefinition(WfDefinitionRequest req);

    /**
     * 更新 DRAFT 定义（code 不可变更；node_json 重新校验）。
     */
    WfDefinitionView updateDefinition(UUID id, WfDefinitionRequest req);

    /**
     * 软删定义（ACTIVE 禁止删除）。
     */
    void deleteDefinition(UUID id);

    /**
     * 激活（DRAFT 转 ACTIVE）：激活前校验节点编排为非空 JSON 数组。
     */
    WfDefinitionView activateDefinition(UUID id);

    /**
     * 停用（ACTIVE 转 DISABLED）。
     */
    WfDefinitionView disableDefinition(UUID id);
    WfDefinitionView reviseDefinition(UUID id);

    /**
     * 实例分页列表（RLS 限定本人发起或终态；companyId/status/businessType 过滤提示）。
     */
    PageResult<WfInstanceView> pageInstances(long page, long size, UUID companyId,
                                             String status, String businessType);

    /**
     * 实例详情（他人 RUNNING 实例则 RLS 空集，回 data:null）。
     */
    WfInstanceView getInstance(UUID id);

    /**
     * 发起流程实例（POST /api/wf/instances）：定义须 ACTIVE（40400/40900 前置预检）；
     * 同一业务单据仅允许一个未删除实例（uk_wf_inst_business 唯一索引兜底则 40900）；
     * 原子创建 RUNNING 实例 + 首个审批节点 PENDING 任务（wf.fn_start_instance）。
     */
    WfInstanceView startInstance(WfInstanceRequest req);

    /**
     * 本人发起实例分页（initiator_id = 当前用户）。
     */
    PageResult<WfInstanceView> pageMyInstances(long page, long size, String status);

    /**
     * 待办列表（assignee = 当前用户 AND status IN (PENDING,CLAIMED)）。
     */
    PageResult<WfTaskView> myTodos(long page, long size);

    /**
     * 领取任务（SKIP LOCKED 并发安全）；false 则 40900"任务不可领取"。
     */
    void claim(UUID taskId);

    /**
     * 审批通过（PENDING/CLAIMED 转 APPROVED + 实例推进）；false/RAISE 则 40900/40300。
     */
    void approve(UUID taskId, TaskActionRequest req);

    /**
     * 驳回（任务 REJECTED + 实例直置 REJECTED）。
     */
    void reject(UUID taskId, TaskActionRequest req);

    /**
     * 转办（任务 TRANSFERRED；remark 为新处理人 uuid 文本则同节点新建 PENDING 任务）。
     */
    void transfer(UUID taskId, TaskActionRequest req);

    /**
     * 任务事件流水（仅追加查询）。
     */
    List<WfTaskEventView> taskEvents(UUID taskId);
}
