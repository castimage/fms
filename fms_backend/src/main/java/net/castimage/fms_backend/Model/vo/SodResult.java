package net.castimage.fms_backend.Model.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.ArrayList;
import java.util.List;

/**
 * SoD 冲突检查结果（仅告警不阻断）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SodResult {

    /** 是否命中互斥权限对 */
    private boolean conflict;

    private List<SodPair> pairs = new ArrayList<>(); // 命中的互斥对，

    /**
     * 互斥权限对。
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SodPair {
        /** 权限 A 编码 */
        private String permA;
        /** 权限 B 编码 */
        private String permB;
    }
}
