package net.castimage.fms_backend.Model.vo;

import lombok.Getter;
import lombok.Setter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 模块树节点，um.modules 树 + 挂载 permissions。
 */
@Getter
@Setter
public class ModuleNode {

    private UUID id;
    /** 模块唯一标识（org/gl/gl.voucher...） */
    private String identifier;
    private String name;
    private String description;
    private UUID parentId;

    private List<PermissionView> permissions = new ArrayList<>(); // 挂载权限，
    /** 子模块 */
    private List<ModuleNode> children = new ArrayList<>(); // 空列表即叶节点，

    public ModuleNode() {
    }

    public ModuleNode(UUID id, String identifier, String name, String description,
                      UUID parentId, List<PermissionView> permissions, List<ModuleNode> children) {
        this.id = id;
        this.identifier = identifier;
        this.name = name;
        this.description = description;
        this.parentId = parentId;
        this.permissions = permissions;
        this.children = children;
    }

}
