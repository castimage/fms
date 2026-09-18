package net.castimage.fms_backend.Service;

import net.castimage.fms_backend.Model.vo.ApiKeyIdentity;
import net.castimage.fms_backend.Model.vo.ApiKeyIssueResult;
import net.castimage.fms_backend.Model.vo.ApiKeyView;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** API 密钥业务（um.api_keys）：校验（过滤器收敛点）/列表/签发/吊销，全部经 SECURITY DEFINER 函数闸口 */
public interface ApiKeyService {

    /**
     * API-Key 校验（ApiKeyFilter 调用）：库内 hmac(key, blind_key) 盲索引等值查 + ACTIVE/
     * 未过期校验 + last_used_at 回写，通过返回 {userId, scope}，任何失败返回 null
     * （过滤器按 40100 fail-closed）。
     */
    ApiKeyIdentity verify(String apiKey);

    /**
     * 本人密钥列表（key_hash 恒不返回；RLS is_self + user_id 双保险）。
     */
    List<ApiKeyView> listMyKeys();

    /**
     * 签发（auth.issue_api_key，仅本人）：'fms_'+32B hex 随机明文仅此一次返回；
     * name/scope 白名单校验（scope 仅 read/read_write），expiresAt 须晚于当前时间。
     */
    ApiKeyIssueResult issue(String name, String scope, LocalDateTime expiresAt);

    /**
     * 吊销（auth.revoke_api_key，仅本人）：status=REVOKED + revoked_at 软删除语义；
     * 非本人密钥按 40400（不泄露他人密钥存在性）。
     */
    void revoke(UUID keyId);
}
