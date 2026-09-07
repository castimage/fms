package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.UserPreference;

/**
 * 用户偏好 Mapper（um.user_preferences）：单表按主键（user_id）读写，RLS 恒限定
 * 本人行（USING/WITH CHECK user_id = auth.current_uid()），无跨用户访问面。
 */
public interface UserPreferenceMapper extends BaseMapper<UserPreference> {
}
