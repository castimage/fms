package net.castimage.fms_backend.Service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Exception.ConflictException;
import net.castimage.fms_backend.Exception.ForbiddenException;
import net.castimage.fms_backend.Exception.NotFoundException;
import net.castimage.fms_backend.Mapper.AuthFunctionMapper;
import net.castimage.fms_backend.Mapper.CompanyMapper;
import net.castimage.fms_backend.Mapper.DepartmentMapper;
import net.castimage.fms_backend.Mapper.ModuleMapper;
import net.castimage.fms_backend.Mapper.PermissionMapper;
import net.castimage.fms_backend.Mapper.RoleMapper;
import net.castimage.fms_backend.Mapper.UserMapper;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.CompanyRequest;
import net.castimage.fms_backend.Model.dto.DepartmentRequest;
import net.castimage.fms_backend.Model.dto.ResetPasswordRequest;
import net.castimage.fms_backend.Model.dto.RolePermRequest;
import net.castimage.fms_backend.Model.dto.RoleRequest;
import net.castimage.fms_backend.Model.dto.SodCheckRequest;
import net.castimage.fms_backend.Model.dto.UserCreateRequest;
import net.castimage.fms_backend.Model.dto.UserRoleAssignRequest;
import net.castimage.fms_backend.Model.dto.UserScopeRequest;
import net.castimage.fms_backend.Model.dto.UserUpdateRequest;
import net.castimage.fms_backend.Model.entity.Company;
import net.castimage.fms_backend.Model.entity.Department;
import net.castimage.fms_backend.Model.entity.Module;
import net.castimage.fms_backend.Model.entity.Permission;
import net.castimage.fms_backend.Model.entity.Role;
import net.castimage.fms_backend.Model.entity.User;
import net.castimage.fms_backend.Model.vo.CompanyView;
import net.castimage.fms_backend.Model.vo.DepartmentView;
import net.castimage.fms_backend.Model.vo.ModuleNode;
import net.castimage.fms_backend.Model.vo.PermissionView;
import net.castimage.fms_backend.Model.vo.RoleView;
import net.castimage.fms_backend.Model.vo.SodResult;
import net.castimage.fms_backend.Model.vo.UserScopeView;
import net.castimage.fms_backend.Model.vo.UserView;
import net.castimage.fms_backend.Service.OrgService;
import net.castimage.fms_backend.Utils.PasswordUtils;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.UuidV7;
import net.castimage.fms_backend.Utils.WeakPasswordFilter;
import net.castimage.fms_backend.config.FmsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.time.LocalDate;

/** 组织架构服务实现，公司/部门/用户/角色/权限树/SoD，公司经 org.fn_upsert_company 闸口、用户/角色/分配经闸口，敏感列不对 operator 开放、部门直接 sql 写（path 经 ::ltree 转换，code/path/parent/level 不可变），软删统一 auth.fn_soft_delete，写操作 rbac 预检 40300 */
@Service
public class OrgServiceImpl implements OrgService {

    private static final Logger log = LoggerFactory.getLogger(OrgServiceImpl.class);

    private final CompanyMapper companyMapper;
    private final DepartmentMapper departmentMapper;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final ModuleMapper moduleMapper;
    private final AuthFunctionMapper authFunctionMapper;
    private final WeakPasswordFilter weakPasswordFilter;
    private final FmsProperties fmsProperties;

    public OrgServiceImpl(CompanyMapper companyMapper, DepartmentMapper departmentMapper,
                          UserMapper userMapper, RoleMapper roleMapper,
                          PermissionMapper permissionMapper, ModuleMapper moduleMapper,
                          AuthFunctionMapper authFunctionMapper, WeakPasswordFilter weakPasswordFilter,
                          FmsProperties fmsProperties) {
        this.companyMapper = companyMapper;
        this.departmentMapper = departmentMapper;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.moduleMapper = moduleMapper;
        this.authFunctionMapper = authFunctionMapper;
        this.weakPasswordFilter = weakPasswordFilter;
        this.fmsProperties = fmsProperties;
    }

    /** SoD 互斥对，内置常量表，后续可迁移 um.params，按权限 code 前缀匹配 */
    private static final List<String[]> SOD_PAIRS = List.of(
            new String[]{"voucher:create", "voucher:approve"},   // 制单 ↔ 审核
            new String[]{"voucher:post", "voucher:approve"},     // 过账 ↔ 审核
            new String[]{"voucher:post", "voucher:reverse"}      // 过账 ↔ 冲销
    );

    // 公司

    @Override
    @Transactional(readOnly = true)
    public PageResult<CompanyView> pageCompanies(long page, long size, String keyword) {
        LambdaQueryWrapper<Company> wrapper = new LambdaQueryWrapper<Company>()
                .and(keyword != null && !keyword.isBlank(), kw ->
                        kw.like(Company::getName, keyword).or().like(Company::getCode, keyword))
                .orderByAsc(Company::getCode);
        IPage<Company> result = companyMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(result, this::toCompanyView);
    }

    @Override
    @Transactional(readOnly = true)
    public CompanyView getCompany(UUID id) {
        Company company = companyMapper.selectById(id);
        if (company == null) {
            throw new NotFoundException("公司不存在");
        }
        return toCompanyView(company);
    }

    @Override
    @Transactional
    public CompanyView upsertCompany(UUID id, CompanyRequest req) {
        RequestContext.requireAdminRole();
        String code = req.code();
        Company existing = null;
        // 更新场景，以既有记录的 code 为准，code 唯一，upsert 按 code 幂等，
        if (id != null) {
            existing = companyMapper.selectById(id);
            if (existing == null) {
                throw new NotFoundException("公司不存在");
            }
            code = existing.getCode();
        }
        UUID companyId = authFunctionMapper.fnUpsertCompany(code, req.name(),
                req.currency() == null ? "CNY" : req.currency(),
                req.fiscalYearStart() == null
                        ? LocalDate.of(1, 1, 1) : req.fiscalYearStart(),
                req.status() == null ? "ACTIVE" : req.status());
        Company company = companyMapper.selectById(companyId);
        if (company == null) {
            // 创建后免回读，新建公司不在调用者 user_org_scope（can_access_org 需 company_id=self.id），
            // 同事务 guc 快照亦不包含新 id，回读必空集且回滚丢写，以请求数据构造视图返回，
            // 可见性由 fms_admin 授权 scope 后的列表/详情接口承载
            company = buildUpsertedCompany(companyId, code, req, existing);
        }
        return toCompanyView(company);
    }

    @Override
    @Transactional
    public void deleteCompany(UUID id) {
        RequestContext.requireAdminRole();
        if (!authFunctionMapper.fnSoftDelete("org", "companies", id)) {
            throw new NotFoundException("公司不存在");
        }
    }

    // 部门

    @Override
    @Transactional(readOnly = true)
    public PageResult<DepartmentView> pageDepartments(long page, long size, UUID companyId, String keyword) {
        LambdaQueryWrapper<Department> wrapper = new LambdaQueryWrapper<Department>()
                .eq(companyId != null, Department::getCompanyId, companyId)
                .and(keyword != null && !keyword.isBlank(), kw ->
                        kw.like(Department::getName, keyword).or().like(Department::getCode, keyword))
                .orderByAsc(Department::getPath);
        IPage<Department> result = departmentMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(result, this::toDepartmentView);
    }

    @Override
    @Transactional(readOnly = true)
    public DepartmentView getDepartment(UUID id) {
        Department department = departmentMapper.selectById(id);
        if (department == null) {
            throw new NotFoundException("部门不存在");
        }
        return toDepartmentView(department);
    }

    @Override
    @Transactional
    public DepartmentView createDepartment(DepartmentRequest req) {
        RequestContext.requireAdminRole();
        Department department = new Department();
        department.setId(UuidV7.next());
        department.setCompanyId(req.companyId());
        department.setCode(req.code());
        department.setName(req.name());
        department.setParentId(req.parentId());
        department.setManagerId(req.managerId());
        if (req.parentId() == null) {
            department.setPath(req.code());
            department.setLevel(1);
        } else {
            Department parent = departmentMapper.selectById(req.parentId());
            if (parent == null) {
                throw new NotFoundException("父部门不存在");
            }
            department.setPath(parent.getPath() + "." + req.code());
            department.setLevel((parent.getLevel() == null ? 0 : parent.getLevel()) + 1);
        }
        if (departmentMapper.insertDepartment(department) == 0) {
            throw new ConflictException("部门创建失败（唯一约束冲突或数据范围受限）");
        }
        return toDepartmentView(department);
    }

    @Override
    @Transactional
    public DepartmentView updateDepartment(UUID id, DepartmentRequest req) {
        RequestContext.requireAdminRole();
        Department existing = departmentMapper.selectById(id);
        if (existing == null) {
            throw new NotFoundException("部门不存在");
        }
        // 结构字段（code/path/parentId/level）不可变更，仅 name/managerId，
        if (departmentMapper.update(null, new LambdaUpdateWrapper<Department>()
                .eq(Department::getId, id)
                .set(Department::getName, req.name())
                .set(Department::getManagerId, req.managerId())) == 0) {
            throw new ConflictException("部门更新失败（数据范围受限）");
        }
        return toDepartmentView(existing);
    }

    @Override
    @Transactional
    public void deleteDepartment(UUID id) {
        RequestContext.requireAdminRole();
        if (departmentMapper.selectCount(new LambdaQueryWrapper<Department>()
                .isNull(Department::getDeletedAt)
                .eq(Department::getParentId, id)) > 0) {
            throw new ConflictException("存在子部门，无法删除");
        }
        if (!authFunctionMapper.fnSoftDelete("org", "departments", id)) {
            throw new NotFoundException("部门不存在");
        }
    }

    // 用户

    @Override
    @Transactional(readOnly = true)
    public PageResult<UserView> pageUsers(long page, long size, UUID companyId, String keyword, String status) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .eq(companyId != null, User::getCompanyId, companyId)
                .eq(status != null && !status.isBlank(), User::getStatus, status)
                .and(keyword != null && !keyword.isBlank(), kw ->
                        kw.like(User::getLoginName, keyword).or().like(User::getDisplayName, keyword))
                .orderByAsc(User::getLoginName);
        IPage<User> result = userMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(result, this::toUserView);
    }

    @Override
    @Transactional(readOnly = true)
    public UserView getUser(UUID id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new NotFoundException("用户不存在");
        }
        return toUserView(user);
    }

    /**
     * 创建用户，rbac 写预检 40300，先做密码预检，长度二次校验（fms.security.
     * password-min/max-length）与弱密码布隆预筛精确确认，命中 40001，不落库，再经
     * 闸口 auth.create_user 写库，列级白名单，pass_hash 等敏感列不对 operator
     * 开放。mustChangePwd 缺省视为 true，首登强制改密。创建后回读被 rls 隐藏，users_select 仅 is_self 可见，operator 永不可见他人行时免回读直接返回
     * 请求数据构造的视图，不抛 40400 避免事务回滚丢写。
     *
     * 应用侧只做预检，长度/弱密码，实际写库全部经闸口函数
     * auth.create_user，bcrypt 哈希由应用侧 PasswordUtils.encode 生成后经函数参数
     * 传入，列级白名单禁止直接 UPDATE pass_hash，敏感列，pass_hash/盲索引字段
     * 不对 operator 可见，operatorId 随调用传入落审计。mustChangePwd 缺省即 true，管理员创建账号首登必须改密，防初始口令长期有效。回读可见性，rls 范围外
     * 创建的数据不可见属正常范围隔离语义，不泄露存在性，创建动作已成功提交，详情
     * 由本人/管理员上下文查询。
     *
     * @param req 用户创建请求（loginName/password/email/companyId/mustChangePwd）
     * @return 创建成功后的用户视图，rls 隐藏时以请求数据构造，roles 恒空
     */
    @Override
    @Transactional
    public UserView createUser(UserCreateRequest req) {
        RequestContext.requireAdminRole();
        UUID operatorId = RequestContext.requireUserId();

        // 密码预检，长度二次校验，fms.security.password-min/max-length 收紧生效后弱密码布隆预筛精确确认，40001
        PasswordUtils.checkLength(req.password(), fmsProperties.getSecurity().getPasswordMinLength(),
                fmsProperties.getSecurity().getPasswordMaxLength());
        weakPasswordFilter.rejectIfWeak(req.password());
        boolean mustChangePwd = !Boolean.FALSE.equals(req.mustChangePwd());
        UUID userId = authFunctionMapper.createUser(req.loginName(),
                PasswordUtils.encode(req.password()),
                req.email(), req.companyId(), mustChangePwd, operatorId);
        User user = userMapper.selectById(userId);
        if (user == null) {
            // 创建后免回读，users_select 仅 is_self 可见，operator 无他人行可见性，scope 授权亦不生效，
            // 回读必空集，直接以请求数据构造视图返回，避免 40400 回滚丢写
            return new UserView(userId, req.loginName(), req.loginName(), "ACTIVE", false,
                    mustChangePwd, null, req.companyId(), List.of());
        }
        log.info("用户创建成功 userId={} operator={}", userId, operatorId);
        return toUserView(user);
    }

    @Override
    @Transactional
    public UserView updateUser(UUID id, UserUpdateRequest req) {
        RequestContext.requireAdminRole();
        if (userMapper.selectById(id) == null) {
            throw new NotFoundException("用户不存在");
        }
        // 白名单键补丁，email/status/must_change_pwd（display_name 不在白名单），
        authFunctionMapper.updateUser(id, buildPatch(req));
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new NotFoundException("用户不存在");
        }
        return toUserView(user);
    }

    @Override
    @Transactional
    public void deleteUser(UUID id) {
        RequestContext.requireAdminRole();
        if (!authFunctionMapper.fnSoftDelete("um", "users", id)) {
            throw new NotFoundException("用户不存在");
        }
    }

    @Override
    @Transactional
    public void assignRole(UUID id, UserRoleAssignRequest req) {
        RequestContext.requireAdminRole();
        Role role = roleMapper.selectById(req.roleId());
        if (role == null) {
            throw new NotFoundException("角色不存在");
        }
        authFunctionMapper.assignRole(id, role.getCode());
    }

    @Override
    @Transactional
    public void revokeRole(UUID id, UUID roleId) {
        RequestContext.requireAdminRole();
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new NotFoundException("角色不存在");
        }
        authFunctionMapper.revokeRole(id, role.getCode());
    }

    @Override
    public UserScopeView getUserScopes(UUID id) {
        RequestContext.requireAdminRole();
        String raw = authFunctionMapper.listUserScopes(id);
        JSONObject o = JSON.parseObject(raw == null ? "{}" : raw);
        List<UUID> orgIds = new ArrayList<>();
        JSONArray orgs = o.getJSONArray("orgIds");
        if (orgs != null) {
            for (int i = 0; i < orgs.size(); i++) {
                orgIds.add(orgs.getObject(i, UUID.class));
            }
        }
        List<UUID> deptIds = new ArrayList<>();
        boolean subtree = true;
        JSONArray depts = o.getJSONArray("depts");
        if (depts != null) {
            for (int i = 0; i < depts.size(); i++) {
                JSONObject d = depts.getJSONObject(i);
                deptIds.add(d.getObject("deptId", UUID.class));
                if (i == 0) {
                    subtree = !Boolean.FALSE.equals(d.getBoolean("includeSubtree"));
                }
            }
        }
        return new UserScopeView(orgIds, deptIds, subtree);
    }

    @Override
    @Transactional
    public void setUserScopes(UUID id, UserScopeRequest req) {
        RequestContext.requireAdminRole();
        boolean subtree = req.includeSubtree() == null || req.includeSubtree();
        authFunctionMapper.setUserOrgScope(id, joinUuids(req.orgIds()));
        authFunctionMapper.setUserDeptScope(id, joinUuids(req.deptIds()), subtree);
    }

    /** id 集合转逗号串，供闸口的 string_to_array 解析，空集合转空串表示清空该维度 */
    private static String joinUuids(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        return ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    }

    /**
     * 管理员重置用户密码，rbac 写预检 40300，预检目标用户存在后做密码预检，长度二次
     * 校验 + 弱密码布隆预筛精确确认，命中 40001，不落库，再经 auth.set_password 写入
     * bcrypt 哈希，并同步置 must_change_pwd=true 强制下次登录改密。管理员路径不校验旧
     * 密码，目标用户既有令牌由网关侧 ttl 自然失效，本方法不做主动吊销。
     *
     * 这是管理员代重置路径，区别于用户改自己的密码，信任模型为
     * "管理员已认证 + 写角色"即可重置任意可见用户，不校验旧密码，管理员本就
     * 不应知道目标用户旧口令，写入经 auth.set_password 闸口，列级白名单，密码
     * 列不可直写，bcrypt 哈希由 PasswordUtils.encode 生成，must_change_pwd=true
     * 是重置语义的核心，被重置的用户下次登录必须改密，防止管理员设置的临时口令
     * 长期有效。令牌不主动吊销，access/refresh 均为无状态 jwt，由 ttl 自然失效，如需立即失效，应由会话/黑名单机制另行处理。
     *
     * @param id  目标用户 id
     * @param req 含新密码（newPassword）的重置请求
     * @throws NotFoundException 目标用户不存在，40400
     */
    @Override
    @Transactional
    public void resetPassword(UUID id, ResetPasswordRequest req) {
        RequestContext.requireAdminRole();
        if (userMapper.selectById(id) == null) {
            throw new NotFoundException("用户不存在");
        }
        // 密码预检，长度二次校验 + 弱密码拒绝，40001，命中不落库
        PasswordUtils.checkLength(req.newPassword(), fmsProperties.getSecurity().getPasswordMinLength(),
                fmsProperties.getSecurity().getPasswordMaxLength());
        weakPasswordFilter.rejectIfWeak(req.newPassword());

        // set_password + must_change_pwd=true，重置后强制改密
        authFunctionMapper.setPassword(id, PasswordUtils.encode(req.newPassword()));
        authFunctionMapper.updateUser(id, "{\"must_change_pwd\": true}");
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> userPermissions(UUID id) {
        return permissionMapper.selectCodesByUser(id);
    }

    // 角色与权限

    @Override
    @Transactional(readOnly = true)
    public List<RoleView> listRoles() {
        List<Role> roles = roleMapper.selectList(new LambdaQueryWrapper<Role>()
                .orderByAsc(Role::getCode));
        return roles.stream().map(role -> new RoleView(role.getId(), role.getCompanyId(),
                role.getCode(), role.getName(), role.getIsSystem(),
                permissionMapper.countByRoleId(role.getId()))).toList();
    }

    @Override
    @Transactional
    public RoleView createRole(RoleRequest req) {
        RequestContext.requireAdminRole();
        UUID roleId = authFunctionMapper.upsertRole(req.code(), req.name(), false);
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new NotFoundException("角色创建后不可见");
        }
        return new RoleView(role.getId(), role.getCompanyId(), role.getCode(), role.getName(),
                role.getIsSystem(), 0L);
    }

    @Override
    @Transactional
    public RoleView updateRole(UUID id, RoleRequest req) {
        RequestContext.requireAdminRole();
        Role role = roleMapper.selectById(id);
        if (role == null) {
            throw new NotFoundException("角色不存在");
        }
        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new ForbiddenException("系统内置角色不可修改");
        }
        // code 不可变更（uk_roles_code），仅 name 更新，
        authFunctionMapper.upsertRole(role.getCode(), req.name(), false);
        role.setName(req.name());
        return new RoleView(role.getId(), role.getCompanyId(), role.getCode(), role.getName(),
                role.getIsSystem(), permissionMapper.countByRoleId(id));
    }

    @Override
    @Transactional
    public void deleteRole(UUID id) {
        RequestContext.requireAdminRole();
        Role role = roleMapper.selectById(id);
        if (role == null) {
            throw new NotFoundException("角色不存在");
        }
        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new ForbiddenException("系统内置角色不可删除");
        }
        if (!authFunctionMapper.fnSoftDelete("um", "roles", id)) {
            throw new NotFoundException("角色不存在");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> rolePermissions(UUID roleId) {
        RequestContext.requireAdminRole();
        if (roleMapper.selectById(roleId) == null) {
            throw new NotFoundException("角色不存在");
        }
        return permissionMapper.selectCodesByRole(roleId);
    }

    @Override
    @Transactional
    public void grantRolePermission(UUID roleId, RolePermRequest req) {
        RequestContext.requireAdminRole();
        if (roleMapper.selectById(roleId) == null) {
            throw new NotFoundException("角色不存在");
        }
        authFunctionMapper.grantRolePermission(roleId, req.permCode());
    }

    @Override
    @Transactional
    public void revokeRolePermission(UUID roleId, String permCode) {
        RequestContext.requireAdminRole();
        if (roleMapper.selectById(roleId) == null) {
            throw new NotFoundException("角色不存在");
        }
        authFunctionMapper.revokeRolePermission(roleId, permCode);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModuleNode> permTree() {
        // 模块按 deleted_at/is_enabled 过滤 + identifier 排序
        List<Module> modules = moduleMapper.selectList(new LambdaQueryWrapper<Module>()
                .isNull(Module::getDeletedAt)
                .eq(Module::getIsEnabled, true)
                .orderByAsc(Module::getIdentifier));
        // 权限同口径过滤，按模块分组挂载
        Map<UUID, List<Permission>> permsByModule = permissionMapper.selectList(
                        new LambdaQueryWrapper<Permission>()
                                .isNull(Permission::getDeletedAt)
                                .eq(Permission::getIsEnabled, true)
                                .orderByAsc(Permission::getCode))
                .stream()
                .filter(p -> p.getModuleId() != null)
                .collect(Collectors.groupingBy(Permission::getModuleId));

        Map<UUID, ModuleNode> nodes = modules.stream().collect(Collectors.toMap(
                Module::getId,
                m -> {
                    ModuleNode node = new ModuleNode();
                    node.setId(m.getId());
                    node.setIdentifier(m.getIdentifier());
                    node.setName(m.getName());
                    node.setDescription(m.getDescription());
                    node.setParentId(m.getParentId());
                    node.setPermissions(permsByModule.getOrDefault(m.getId(), List.of()).stream()
                            .map(this::toPermissionView).toList());
                    return node;
                }));

        List<ModuleNode> roots = new ArrayList<>();
        for (ModuleNode node : nodes.values()) {
            if (node.getParentId() != null && nodes.containsKey(node.getParentId())) {
                nodes.get(node.getParentId()).getChildren().add(node);
            } else {
                roots.add(node);
            }
        }
        return roots;
    }

    @Override
    @Transactional(readOnly = true)
    public SodResult sodCheck(SodCheckRequest req) {
        List<String> codes = permissionMapper.selectCodesByUser(req.userId());
        SodResult result = new SodResult();
        for (String[] pair : SOD_PAIRS) {
            boolean hasA = codes.stream().anyMatch(c -> c.startsWith(pair[0]));
            boolean hasB = codes.stream().anyMatch(c -> c.startsWith(pair[1]));
            // 命中互斥对即标记冲突，仅告警不阻断，
            if (hasA && hasB) {
                result.setConflict(true);
                result.getPairs().add(new SodResult.SodPair(pair[0], pair[1]));
            }
        }
        return result;
    }

    // 内部实现

    /** update_user 白名单补丁 json（键，email/status/must_change_pwd/display_name，转义交由 fastjson2 处理） */
    private String buildPatch(UserUpdateRequest req) {
        JSONObject patch = new JSONObject();
        if (req.email() != null) {
            patch.put("email", req.email());
        }
        if (req.status() != null) {
            patch.put("status", req.status());
        }
        if (req.mustChangePwd() != null) {
            patch.put("must_change_pwd", req.mustChangePwd());
        }
        if (req.displayName() != null) {
            patch.put("display_name", req.displayName());
        }
        return patch.toJSONString();
    }

    /** 创建后回读空集时以请求数据构造公司视图，update 场景补 createdAt，新建场景时间戳未知为 null， */
    private Company buildUpsertedCompany(UUID id, String code, CompanyRequest req, Company existing) {
        Company company = new Company();
        company.setId(id);
        company.setCompanyId(id);
        company.setCode(code);
        company.setName(req.name());
        company.setCurrency(req.currency() == null ? "CNY" : req.currency());
        company.setFiscalYearStart(req.fiscalYearStart() == null
                ? LocalDate.of(1, 1, 1) : req.fiscalYearStart());
        company.setStatus(req.status() == null ? "ACTIVE" : req.status());
        if (existing != null) {
            company.setCreatedAt(existing.getCreatedAt());
        }
        return company;
    }

    private CompanyView toCompanyView(Company c) {
        return new CompanyView(c.getId(), c.getCode(), c.getName(), c.getCurrency(),
                c.getFiscalYearStart(), c.getStatus(), c.getCreatedAt());
    }

    private DepartmentView toDepartmentView(Department d) {
        return new DepartmentView(d.getId(), d.getCompanyId(), d.getCode(), d.getName(),
                d.getPath(), d.getParentId(), d.getLevel(), d.getManagerId(), d.getCreatedAt());
    }

    private UserView toUserView(User u) {
        List<String> roles = roleMapper.selectRolesByUserId(u.getId()).stream()
                .map(Role::getCode).toList();
        return new UserView(u.getId(), u.getLoginName(), u.getDisplayName(), u.getStatus(),
                u.getMfaEnabled(), u.getMustChangePwd(), u.getLastLoginAt(), u.getCompanyId(), roles);
    }

    private PermissionView toPermissionView(Permission p) {
        return new PermissionView(p.getId(), p.getCode(), p.getName(), p.getIdentifier(), p.getAction());
    }
}
