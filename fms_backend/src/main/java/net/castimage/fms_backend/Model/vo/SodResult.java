package net.castimage.fms_backend.Model.vo;

import lombok.Getter;
import lombok.Setter;
import java.util.ArrayList;
import java.util.List;

/**
 * SoD 冲突检查结果（仅告警不阻断）。
 */
@Getter
@Setter
public class SodResult {

    /** 是否命中互斥权限对 */
    private boolean conflict;

    private List<SodPair> pairs = new ArrayList<>(); // 命中的互斥对，

    public SodResult() {
    }

    public SodResult(boolean conflict, List<SodPair> pairs) {
        this.conflict = conflict;
        this.pairs = pairs;
    }





    /**
     * 互斥权限对。
     */
    public static class SodPair {
        /** 权限 A 编码 */
        private String permA;
        /** 权限 B 编码 */
        private String permB;

        public SodPair() {
        }

        public SodPair(String permA, String permB) {
            this.permA = permA;
            this.permB = permB;
        }

        public String getPermA() {
            return permA;
        }

        public void setPermA(String permA) {
            this.permA = permA;
        }

        public String getPermB() {
            return permB;
        }

        public void setPermB(String permB) {
            this.permB = permB;
        }
    }
}
