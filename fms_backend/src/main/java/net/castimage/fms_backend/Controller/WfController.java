package net.castimage.fms_backend.Controller;

import jakarta.validation.Valid;
import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.TaskActionRequest;
import net.castimage.fms_backend.Model.dto.WfDefinitionRequest;
import net.castimage.fms_backend.Model.dto.WfInstanceRequest;
import net.castimage.fms_backend.Model.vo.WfDefinitionView;
import net.castimage.fms_backend.Model.vo.WfInstanceView;
import net.castimage.fms_backend.Model.vo.WfTaskEventView;
import net.castimage.fms_backend.Model.vo.WfTaskView;
import net.castimage.fms_backend.Service.WfService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.UUID;

/**
 * 审批流模块 REST 接口（/api/wf）：流程定义 CRUD + 激活/停用、实例查询（本人/全部收敛）、
 * 任务待办/领取（SKIP LOCKED 并发）/审批/驳回/转办/事件流水；领取与办结委托 PG SECURITY
 * DEFINER 函数原子完成——并发仅一人成功，非本人 40300，已办结 40900。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/wf")
public class WfController {

    private final WfService wfService;


    // 流程定义

    /**
     * 流程定义分页列表（companyId/status/keyword 可选过滤）。
     */
    @GetMapping("/definitions")
    public ApiResponse<PageResult<WfDefinitionView>> pageDefinitions(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(wfService.pageDefinitions(page, size, companyId, status, keyword));
    }

    /**
     * 流程定义详情（越权则 data:null）。
     */
    @GetMapping("/definitions/{id}")
    public ApiResponse<WfDefinitionView> getDefinition(@PathVariable UUID id) {
        return ApiResponse.ok(wfService.getDefinition(id));
    }

    /**
     * 创建 DRAFT 定义（node_json 须为合法 JSON；code 公司内唯一）。
     */
    @PostMapping("/definitions")
    public ApiResponse<WfDefinitionView> createDefinition(@RequestBody @Valid WfDefinitionRequest req) {
        return ApiResponse.ok(wfService.createDefinition(req));
    }

    /**
     * 更新 DRAFT 定义（仅 DRAFT；code 不可变更）。
     */
    @PutMapping("/definitions/{id}")
    public ApiResponse<WfDefinitionView> updateDefinition(@PathVariable UUID id,
                                                          @RequestBody @Valid WfDefinitionRequest req) {
        return ApiResponse.ok(wfService.updateDefinition(id, req));
    }

    /**
     * 软删定义（ACTIVE 禁止删除，先停用）。
     */
    @DeleteMapping("/definitions/{id}")
    public ApiResponse<Void> deleteDefinition(@PathVariable UUID id) {
        wfService.deleteDefinition(id);
        return ApiResponse.ok(null);
    }

    /**
     * 激活（DRAFT 转 ACTIVE）：激活前校验节点编排为非空 JSON 数组。
     */
    @PostMapping("/definitions/{id}/activate")
    public ApiResponse<WfDefinitionView> activate(@PathVariable UUID id) {
        return ApiResponse.ok(wfService.activateDefinition(id));
    }

    /**
     * 停用（ACTIVE 转 DISABLED）。
     */
    @PostMapping("/definitions/{id}/disable")
    public ApiResponse<WfDefinitionView> disable(@PathVariable UUID id) {
        return ApiResponse.ok(wfService.disableDefinition(id));
    }

    @PostMapping("/definitions/{id}/revise")
    public ApiResponse<WfDefinitionView> revise(@PathVariable UUID id) {
        return ApiResponse.ok(wfService.reviseDefinition(id));
    }

    // 流程实例

    /**
     * 实例分页列表（RLS 限定本人发起或终态；companyId/status/businessType 过滤提示）。
     */
    @GetMapping("/instances")
    public ApiResponse<PageResult<WfInstanceView>> pageInstances(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String businessType) {
        return ApiResponse.ok(wfService.pageInstances(page, size, companyId, status, businessType));
    }

    /**
     * 实例详情（他人 RUNNING 实例则 data:null，不泄露存在性）。
     */
    @GetMapping("/instances/{id}")
    public ApiResponse<WfInstanceView> getInstance(@PathVariable UUID id) {
        return ApiResponse.ok(wfService.getInstance(id));
    }

    /**
     * 发起流程实例（定义须 ACTIVE）：创建 RUNNING 实例 + 首个审批节点任务集
     * （处理人按节点 strategy 与 handlers 展开）；同一业务单据仅允许一个未删除实例（40900）。
     */
    @PostMapping("/instances")
    public ApiResponse<WfInstanceView> startInstance(@RequestBody @Valid WfInstanceRequest req) {
        return ApiResponse.ok(wfService.startInstance(req));
    }

    /**
     * 本人发起实例分页。
     */
    @GetMapping("/instances/my")
    public ApiResponse<PageResult<WfInstanceView>> myInstances(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(wfService.pageMyInstances(page, size, status));
    }

    // 任务

    /**
     * 待办列表（assignee = 当前用户 AND status IN (PENDING,CLAIMED)，due_at 升序）。
     */
    @GetMapping("/tasks/me")
    public ApiResponse<PageResult<WfTaskView>> myTodos(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(wfService.myTodos(page, size));
    }

    /**
     * 领取任务（SKIP LOCKED 并发仅一人成功；失败则 40900"任务不可领取"）。
     */
    @PostMapping("/tasks/{id}/claim")
    public ApiResponse<Void> claim(@PathVariable UUID id) {
        wfService.claim(id);
        return ApiResponse.ok(null);
    }

    /**
     * 审批通过（PENDING/CLAIMED 转 APPROVED；无权限 40300，已办结 40900）。
     */
    @PostMapping("/tasks/{id}/approve")
    public ApiResponse<Void> approve(@PathVariable UUID id,
                                     @RequestBody(required = false) @Valid TaskActionRequest req) {
        wfService.approve(id, req == null ? new TaskActionRequest(null) : req);
        return ApiResponse.ok(null);
    }

    /**
     * 驳回（任务 REJECTED + 实例直置 REJECTED；remark 为驳回原因）。
     */
    @PostMapping("/tasks/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable UUID id,
                                    @RequestBody(required = false) @Valid TaskActionRequest req) {
        wfService.reject(id, req == null ? new TaskActionRequest(null) : req);
        return ApiResponse.ok(null);
    }

    /**
     * 转办（任务 TRANSFERRED；remark 传新处理人 uuid 文本即同节点新建 PENDING 任务）。
     */
    @PostMapping("/tasks/{id}/transfer")
    public ApiResponse<Void> transfer(@PathVariable UUID id,
                                      @RequestBody(required = false) @Valid TaskActionRequest req) {
        wfService.transfer(id, req == null ? new TaskActionRequest(null) : req);
        return ApiResponse.ok(null);
    }

    /**
     * 任务事件流水（CLAIM/APPROVE/REJECT/TRANSFER 等，仅追加）。
     */
    @GetMapping("/tasks/{id}/events")
    public ApiResponse<List<WfTaskEventView>> events(@PathVariable UUID id) {
        return ApiResponse.ok(wfService.taskEvents(id));
    }
}
