package net.castimage.fms_backend.Service.impl;


import lombok.extern.slf4j.Slf4j;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Exception.InternalServerException;
import net.castimage.fms_backend.Exception.NotFoundException;
import net.castimage.fms_backend.Mapper.ApiKeyMapper;
import net.castimage.fms_backend.Model.vo.ApiKeyIdentity;
import net.castimage.fms_backend.Model.vo.ApiKeyIssueResult;
import net.castimage.fms_backend.Model.vo.ApiKeyView;
import net.castimage.fms_backend.Service.ApiKeyService;
import net.castimage.fms_backend.Utils.RequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * api 密钥业务实现（um.api_keys），校验经 auth.api_key_validate SECURITY DEFINER 在
 * 库内完成，sec.app_secrets 盲索引密钥不对任何角色授权，Java 不可直读，hmac 计算、
 * 等值查、last_used_at 回写单次往返原子完成，签发/吊销同为函数闸口，列表走本人可见
 * rls（is_self）双保险。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiKeyServiceImpl implements ApiKeyService {

    /** scope 白名单（read 映射 fms_viewer、read_write 映射 fms_operator） */
    private static final String SCOPE_READ = "read";
    private static final String SCOPE_READ_WRITE = "read_write";

    private final ApiKeyMapper apiKeyMapper;


    /** {@inheritDoc} */
    @Override
    @Transactional // 非只读，函数内 last_used_at 回写须随事务提交，readOnly 会 SET READ ONLY 拒绝 UPDATE，
    public ApiKeyIdentity verify(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String json = apiKeyMapper.validate(apiKey.trim());
        if (json == null || json.isBlank() || "null".equals(json)) {
            return null; // 库内已做 ACTIVE/过期/吊销/软删判定，失败统一 null 则 40100
        }
        try {
            JSONObject obj = JSON.parseObject(json);
            String userId = obj.getString("user_id");
            String scope = obj.getString("scope");
            if (userId == null || scope == null) {
                return null;
            }
            return new ApiKeyIdentity(UUID.fromString(userId), scope);
        } catch (Exception e) {
            // 函数返回格式异常属内部错误，fail-closed 按校验失败处理，40100，不向上抛细节
            log.warn("api_key_validate 返回格式异常: json={}", json);
            return null;
        }
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<ApiKeyView> listMyKeys() {
        UUID userId = RequestContext.requireUserId();
        return apiKeyMapper.listByUser(userId);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public ApiKeyIssueResult issue(String name, String scope, LocalDateTime expiresAt) {
        UUID userId = RequestContext.requireUserId();
        String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.isEmpty()) {
            throw new BadRequestException("密钥名称必填");
        }
        if (trimmedName.length() > 64) {
            throw new BadRequestException("密钥名称最长 64 字符");
        }
        if (!SCOPE_READ.equals(scope) && !SCOPE_READ_WRITE.equals(scope)) {
            throw new BadRequestException("作用域仅支持 read/read_write");
        }
        if (expiresAt != null && expiresAt.isBefore(LocalDateTime.now())) {
            throw new BadRequestException("过期时间须晚于当前时间");
        }

        String key = apiKeyMapper.issue(userId, trimmedName, scope, expiresAt);
        if (key == null || key.isBlank()) {
            // 函数 RAISE 会以异常形态传播，此处仅兜底理论不可达分支
            throw new InternalServerException("API 密钥签发失败");
        }
        // 明文仅此一次返回，keyPrefix 取明文前 8 位（fms_xxxx），与库内 key_prefix 一致
        return new ApiKeyIssueResult(key, key.length() >= 8 ? key.substring(0, 8) : key);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void revoke(UUID keyId) {
        if (keyId == null) {
            throw new BadRequestException("密钥 id 必填");
        }
        UUID userId = RequestContext.requireUserId();

        // 预检归属，rls is_self 本人可见，非本人密钥按 40400，不泄露他人密钥存在性
        boolean own = apiKeyMapper.listByUser(userId).stream()
                .anyMatch(k -> k.id().equals(keyId));
        if (!own) {
            throw new NotFoundException("API 密钥不存在");
        }
        apiKeyMapper.revoke(keyId);
    }
}
