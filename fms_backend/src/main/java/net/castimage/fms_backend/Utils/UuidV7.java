package net.castimage.fms_backend.Utils;

import com.github.f4b6a3.uuid.UuidCreator;

import java.util.UUID;

/** UUIDv7 生成工具（时间有序，RFC 9562，与 PG 18 原生 uuidv7() 语义等价），
 * 生成委托 uuid-creator 库；DEFAULT 列无法经 getGeneratedKeys 回传，故运行期 id 由 Java 侧显式生成 */
public final class UuidV7 {

    private UuidV7() {
    }

    /** 生成 UUIDv7（线程安全，时间有序） */
    public static UUID next() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
