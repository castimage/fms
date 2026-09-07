package net.castimage.fms_backend.Service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Exception.ConflictException;
import net.castimage.fms_backend.Exception.NotFoundException;
import net.castimage.fms_backend.Mapper.AuthFunctionMapper;
import net.castimage.fms_backend.Mapper.WfDefinitionMapper;
import net.castimage.fms_backend.Mapper.WfInstanceMapper;
import net.castimage.fms_backend.Mapper.WfTaskEventMapper;
import net.castimage.fms_backend.Mapper.WfTaskMapper;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.TaskActionRequest;
import net.castimage.fms_backend.Model.dto.WfDefinitionRequest;
import net.castimage.fms_backend.Model.dto.WfInstanceRequest;
import net.castimage.fms_backend.Model.entity.WfDefinition;
import net.castimage.fms_backend.Model.vo.WfDefinitionView;
import net.castimage.fms_backend.Model.vo.WfInstanceView;
import net.castimage.fms_backend.Model.vo.WfTaskEventView;
import net.castimage.fms_backend.Model.vo.WfTaskView;
import net.castimage.fms_backend.Service.WfService;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 审批流模块业务实现：流程定义 CRUD/激活（node_json 经 fastjson2 校验，激活前须为非空
 * JSON 数组）；任务领取/办结委托 PG SECURITY DEFINER 函数 wf.claim_task / wf.complete_task
 * 原子完成（SKIP LOCKED 并发仅一人成功、require_permission 鉴权、assignee 归属校验、
 * 实例状态机推进、事件留痕、PGMQ 通知），Java 仅把 false 映射 40900 与 42501 映射 40300 的
 * 异常映射预检；实例/任务为只读查询（RLS 收敛可见集合）。
 */
@Service
public class WfServiceImpl implements WfService {

    private static final Logger log = LoggerFactory.getLogger(WfServiceImpl.class);

    /** 定义状态机合法状态（DB CHECK 同构） */
    private static final Set<String> DEFINITION_STATUSES = Set.of("DRAFT", "ACTIVE", "DISABLED");

    private final WfDefinitionMapper definitionMapper;
    private final WfInstanceMapper instanceMapper;
    private final WfTaskMapper taskMapper;
    private final WfTaskEventMapper taskEventMapper;
    private final AuthFunctionMapper authFunctionMapper;

    public WfServiceImpl(WfDefinitionMapper definitionMapper, WfInstanceMapper instanceMapper,
                         WfTaskMapper taskMapper, WfTaskEventMapper taskEventMapper,
                         AuthFunctionMapper authFunctionMapper) {
        this.definitionMapper = definitionMapper;
        this.instanceMapper = instanceMapper;
        this.taskMapper = taskMapper;
        this.taskEventMapper = taskEventMapper;
        this.authFunctionMapper = authFunctionMapper;
    }

    // 流程定义

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<WfDefinitionView> pageDefinitions(long page, long size, UUID companyId,
                                                        String status, String keyword) {
        long pageNum = Math.max(page, 1);
        long sizeNum = Math.min(Math.max(size, 1), 100);
        return PageResult.of(definitionMapper.selectDefinitionPage(
                new Page<>(pageNum, sizeNum), companyId, status, keyword));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public WfDefinitionView getDefinition(UUID id) {
        // RLS 空集则返回 null（Controller 层 data:null，不泄露存在性）
        WfDefinition d = definitionMapper.selectById(id);
        return d == null ? null : toDefinitionView(d);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public WfDefinitionView createDefinition(WfDefinitionRequest req) {
        RequestContext.requireAdminRole();
        String nodeJson = req.nodeJson().trim();
        validateNodeJson(nodeJson, false);
        WfDefinition d = new WfDefinition();
        d.setId(UuidV7.next());
        d.setCompanyId(req.companyId());
        d.setCode(req.code().trim());
        d.setName(req.name().trim());
        d.setVersion(1);
        d.setFormType(req.formType());
        d.setNodeJson(nodeJson);
        d.setStatus("DRAFT");
        definitionMapper.insert(d); // uk_wf_def (company_id, code) 冲突 23505 映射 40900
        return toDefinitionView(requireDefinition(d.getId()));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public WfDefinitionView updateDefinition(UUID id, WfDefinitionRequest req) {
        RequestContext.requireAdminRole();
        WfDefinition d = requireDefinition(id);
        if (!"DRAFT".equals(d.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态流程定义可修改（当前 " + d.getStatus() + "）");
        }
        String nodeJson = req.nodeJson().trim();
        validateNodeJson(nodeJson, false);
        WfDefinition patch = new WfDefinition();
        patch.setId(id);
        patch.setName(req.name().trim());
        patch.setFormType(req.formType());
        patch.setNodeJson(nodeJson);
        // code 不可变更（uk_wf_def 唯一键语义）；companyId 不可变更
        if (definitionMapper.updateById(patch) == 0) {
            throw new ConflictException("更新失败：状态已变更或不可见");
        }
        return toDefinitionView(requireDefinition(id));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void deleteDefinition(UUID id) {
        RequestContext.requireAdminRole();
        WfDefinition d = requireDefinition(id);
        if ("ACTIVE".equals(d.getStatus())) {
            throw new ConflictException("流程已激活，禁止删除（先停用再删）");
        }
        // 软删统一经 auth.fn_soft_delete 闸口（SECURITY DEFINER，RLS 新行可见性检查豁免）
        if (!authFunctionMapper.fnSoftDelete("wf", "definitions", id)) {
            throw new ConflictException("删除失败：定义可能已不可见");
        }
    }

    /**
     * 激活流程定义（状态机 DRAFT 转 ACTIVE）：激活前校验节点编排为非空 JSON 数组（空编排
     * 无法推进审批）；激活后定义不可改删（update/delete 预检 DRAFT/ACTIVE 闸），
     * 只能走 disable 停用。
     */
    /** {@inheritDoc} */
    @Override
    @Transactional
    public WfDefinitionView activateDefinition(UUID id) {
        RequestContext.requireAdminRole();
        WfDefinition d = requireDefinition(id);
        if (!"DRAFT".equals(d.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态流程定义可激活（当前 " + d.getStatus() + "）");
        }
        validateNodeJson(d.getNodeJson(), true); // 激活前必须非空节点编排
        WfDefinition patch = new WfDefinition();
        patch.setId(id);
        patch.setStatus("ACTIVE");
        if (definitionMapper.updateById(patch) == 0) {
            throw new ConflictException("激活失败：状态已变更或不可见");
        }
        return toDefinitionView(requireDefinition(id));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public WfDefinitionView disableDefinition(UUID id) {
        RequestContext.requireAdminRole();
        WfDefinition d = requireDefinition(id);
        if (!"ACTIVE".equals(d.getStatus())) {
            throw new ConflictException("仅 ACTIVE 状态流程定义可停用（当前 " + d.getStatus() + "）");
        }
        WfDefinition patch = new WfDefinition();
        patch.setId(id);
        patch.setStatus("DISABLED");
        if (definitionMapper.updateById(patch) == 0) {
            throw new ConflictException("停用失败：状态已变更或不可见");
        }
        return toDefinitionView(requireDefinition(id));
    }

    @Override
    @Transactional
    public WfDefinitionView reviseDefinition(UUID id) {
        RequestContext.requireAdminRole();
        requireDefinition(id);
        if (!definitionMapper.reviseDefinition(id)) {
            throw new ConflictException("回草稿失败：仅 DISABLED 且无 RUNNING 实例可回草稿");
        }
        // 独立 statement 重取，绕 MyBatis 一级缓存，fn 置 DRAFT 后返回最新状态
        return toDefinitionView(definitionMapper.selectByIdFresh(id));
    }

    // 流程实例

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<WfInstanceView> pageInstances(long page, long size, UUID companyId,
                                                    String status, String businessType) {
        long pageNum = Math.max(page, 1);
        long sizeNum = Math.min(Math.max(size, 1), 100);
        return PageResult.of(instanceMapper.selectInstancePage(
                new Page<>(pageNum, sizeNum), companyId, status, businessType));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public WfInstanceView getInstance(UUID id) {
        // 他人 RUNNING 实例则 RLS 空集，返回 null（不泄露存在性）
        return instanceMapper.selectInstanceDetail(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<WfInstanceView> pageMyInstances(long page, long size, String status) {
        long pageNum = Math.max(page, 1);
        long sizeNum = Math.min(Math.max(size, 1), 100);
        return PageResult.of(instanceMapper.selectMyPage(
                new Page<>(pageNum, sizeNum), RequestContext.requireUserId(), status));
    }

    /**
     * 发起流程实例：Java 预检（定义可见 40400 / 须 ACTIVE 40900 / 节点编排合法 40001 /
     * 业务单据未重复发起 40900）后，委托 wf.fn_start_instance（SECURITY DEFINER）原子
     * 创建 RUNNING 实例 + 首个审批节点 PENDING 任务（处理人按 assignee_role 解析）；
     * 并发重复发起由 uk_wf_inst_business 唯一索引兜底（23505 映射 40900）。
     */
    /** {@inheritDoc} */
    @Override
    @Transactional
    public WfInstanceView startInstance(WfInstanceRequest req) {
        RequestContext.requireWriteRole();
        UUID uid = RequestContext.requireUserId();
        WfDefinition d = requireDefinition(req.definitionId()); // RLS 空集则 40400
        if (!"ACTIVE".equals(d.getStatus())) {
            throw new ConflictException("流程定义未激活，无法发起（当前 " + d.getStatus() + "）");
        }
        NodeInfo first = firstActionNode(d.getNodeJson());
        if (instanceMapper.selectByBusiness(req.businessType().trim(), req.businessId()) != null) {
            throw new ConflictException("该业务单据已存在审批实例，请勿重复发起");
        }
        UUID instanceId = instanceMapper.startInstance(d.getCompanyId(), d.getId(),
                req.businessType().trim(), req.businessId(), uid,
                first.nodeCode(), first.assigneeRole());
        if (instanceId == null) {
            throw new ConflictException("发起失败：业务单据已存在审批实例或数据不可见");
        }
        return instanceMapper.selectInstanceDetail(instanceId);
    }

    // 任务

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<WfTaskView> myTodos(long page, long size) {
        long pageNum = Math.max(page, 1);
        long sizeNum = Math.min(Math.max(size, 1), 100);
        // assignee=当前用户 + status IN (PENDING,CLAIMED)；RLS is_self 双路径兜底
        return PageResult.of(taskMapper.selectTodoPage(
                new Page<>(pageNum, sizeNum), RequestContext.requireUserId()));
    }

    /**
     * 领取任务：调 wf.claim_task（内部取 current_uid）——PG 侧 UPDATE + FOR UPDATE
     * SKIP LOCKED 原子完成，并发请求仅一人获得行锁领取成功（其余 false），且仅领取
     * 分配给本人的 PENDING 任务；false 则 40900"任务不可领取"（不存在/非本人/已被领取）。
     */
    /** {@inheritDoc} */
    @Override
    @Transactional
    public void claim(UUID taskId) {
        RequestContext.requireWriteRole();
        if (!taskMapper.claim(taskId)) {
            throw new ConflictException("任务不可领取（不存在/非本人/已被领取）");
        }
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void approve(UUID taskId, TaskActionRequest req) {
        RequestContext.requireWriteRole();
        complete(taskId, "APPROVE", req);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void reject(UUID taskId, TaskActionRequest req) {
        RequestContext.requireWriteRole();
        complete(taskId, "REJECT", req);
    }

    /**
     * 转办任务：调 wf.complete_task(taskId, 'TRANSFER', remark)——remark 传新处理人
     * uuid 文本即同节点新建 PENDING 任务（PG 侧 CAST 失败仅记事件不转办）；任务置
     * TRANSFERRED，实例保持 RUNNING。
     */
    /** {@inheritDoc} */
    @Override
    @Transactional
    public void transfer(UUID taskId, TaskActionRequest req) {
        RequestContext.requireWriteRole();
        complete(taskId, "TRANSFER", req);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<WfTaskEventView> taskEvents(UUID taskId) {
        // 仅追加流水查询；任务不可见时返回空集（不泄露）
        return taskEventMapper.selectByTaskId(taskId);
    }

    // 私有方法

    /**
     * 办结通用入口：调 wf.complete_task(taskId, action, remark)——PG 侧 require_permission
     * （APPROVE/TRANSFER 需 wf.instance.approve，REJECT 需 wf.instance.reject，无权
     * RAISE 42501 映射 40300）、assignee 归属校验（非本人 RAISE 42501 映射 40300）、幂等
     * （已办结/不存在返回 false 则 40900）、实例状态机推进、事件留痕、PGMQ 通知（失败静默）。
     */
    private void complete(UUID taskId, String action, TaskActionRequest req) {
        String remark = req == null || req.remark() == null ? null : req.remark().trim();
        if (!taskMapper.complete(taskId, action, remark)) {
            throw new ConflictException("任务不可办结（不存在或已办结）");
        }
    }

    /** 按 id 取定义（RLS 空集则 40400） */
    private WfDefinition requireDefinition(UUID id) {
        WfDefinition d = definitionMapper.selectById(id);
        if (d == null) {
            throw new NotFoundException("流程定义不存在");
        }
        return d;
    }

    /**
     * node_json 合法性校验（fastjson2 解析）：非法 JSON 则 40001；requireNonEmptyArray=true
     * （激活前）还须为非空 JSON 数组（节点编排不能为空）。
     */
    private void validateNodeJson(String nodeJson, boolean requireNonEmptyArray) {
        Object parsed;
        try {
            parsed = JSON.parse(nodeJson);
        } catch (Exception e) {
            throw new BadRequestException("node_json 非法 JSON");
        }
        if (!(parsed instanceof JSONArray arr)) {
            throw new BadRequestException("node_json 须为 JSON 数组");
        }
        if (requireNonEmptyArray) {
            if (arr.isEmpty()) {
                throw new BadRequestException("节点编排不能为空 JSON 数组");
            }
            validateNodeSchema(arr);
        }
    }

    /** 节点结构校验，激活前终审，type 合法且恰一个 START/END，至少一个 APPROVE，node_code 唯一 */
    private void validateNodeSchema(JSONArray arr) {
        int startCnt = 0;
        int endCnt = 0;
        int approveCnt = 0;
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (int i = 0; i < arr.size(); i++) {
            Object el = arr.get(i);
            if (!(el instanceof JSONObject o)) {
                throw new BadRequestException("节点须为 JSON 对象");
            }
            String code = o.getString("node_code");
            String type = o.getString("type");
            if (code == null || code.isBlank()) {
                throw new BadRequestException("节点 node_code 不能为空");
            }
            if (!codes.add(code)) {
                throw new BadRequestException("节点 node_code 重复: " + code);
            }
            if (!java.util.Set.of("START", "APPROVE", "END").contains(type)) {
                throw new BadRequestException("节点 type 非法: " + type);
            }
            if ("START".equals(type)) startCnt++;
            if ("END".equals(type)) endCnt++;
            if ("APPROVE".equals(type)) {
                approveCnt++;
                String role = o.getString("assignee_role");
                if (role == null || role.isBlank()) {
                    throw new BadRequestException("审批节点处理人角色不能为空: " + code);
                }
            }
        }
        if (startCnt != 1) {
            throw new BadRequestException("节点编排须恰有一个 START");
        }
        if (endCnt != 1) {
            throw new BadRequestException("节点编排须恰有一个 END");
        }
        if (approveCnt < 1) {
            throw new BadRequestException("节点编排须至少一个 APPROVE");
        }
    }

    /**
     * 取节点编排中首个可审批节点（跳过 START/END）：非法 JSON / 空编排 / 无审批节点
     * 均回 40001。返回 node_code 与 assignee_role（供发起时创建首任务并解析处理人）。
     */
    private NodeInfo firstActionNode(String nodeJson) {
        Object parsed;
        try {
            parsed = JSON.parse(nodeJson);
        } catch (Exception e) {
            throw new BadRequestException("node_json 非法 JSON");
        }
        if (!(parsed instanceof JSONArray arr) || arr.isEmpty()) {
            throw new BadRequestException("节点编排不能为空 JSON 数组");
        }
        for (int i = 0; i < arr.size(); i++) {
            Object el = arr.get(i);
            if (!(el instanceof JSONObject o)) {
                throw new BadRequestException("节点编排元素须为 JSON 对象");
            }
            String type = o.getString("type");
            if ("START".equalsIgnoreCase(type) || "END".equalsIgnoreCase(type)) {
                continue;
            }
            String nodeCode = o.getString("node_code");
            if (nodeCode == null || nodeCode.isBlank()) {
                throw new BadRequestException("节点编排缺少 node_code");
            }
            return new NodeInfo(nodeCode, o.getString("assignee_role"));
        }
        throw new BadRequestException("流程定义无审批节点（编排全为 START/END）");
    }

    /** 首个可审批节点（node_code + assignee_role） */
    private record NodeInfo(String nodeCode, String assigneeRole) {
    }

    /** 实体转视图 */
    private WfDefinitionView toDefinitionView(WfDefinition d) {
        return new WfDefinitionView(d.getId(), d.getCompanyId(), d.getCode(), d.getName(),
                d.getVersion(), d.getFormType(), d.getNodeJson(), d.getStatus(),
                d.getCreatedAt(), d.getUpdatedAt());
    }
}
