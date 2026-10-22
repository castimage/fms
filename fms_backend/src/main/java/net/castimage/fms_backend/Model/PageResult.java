package net.castimage.fms_backend.Model;

import com.baomidou.mybatisplus.core.metadata.IPage;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.List;
import java.util.function.Function;

/**
 * FMS 统一分页响应 {@code {items, total, page, size}}（由 MyBatis-Plus 分页结果组装）。
 *
 * @param <T> 行数据类型
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    /** 当前页数据 */
    private List<T> items;

    /** 过滤后总行数 */
    private long total;

    /** 当前页码（从 1 开始） */
    private long page;

    /** 每页大小 */
    private long size;
    /**
     * 由 MyBatis-Plus 分页结果组装。
     *
     * @param page MP 分页对象
     * @param <T>  行数据类型
     * @return FMS 分页响应
     */
    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 由 MyBatis-Plus 分页结果组装（行映射：实体转视图，收敛 Service 层分页组装）。
     *
     * @param page   MP 分页对象
     * @param mapper 行类型转换函数
     * @param <E>    源行类型（实体）
     * @param <V>    目标行类型（视图）
     * @return FMS 分页响应
     */
    public static <E, V> PageResult<V> of(IPage<E> page, Function<E, V> mapper) {
        return new PageResult<>(page.getRecords().stream().map(mapper).toList(),
                page.getTotal(), page.getCurrent(), page.getSize()); // 总行数取过滤后口径，
    }

}
