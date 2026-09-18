package net.castimage.fms_backend.Controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Mapper.UserPreferenceMapper;
import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Model.entity.UserPreference;
import net.castimage.fms_backend.Utils.RequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 用户背景偏好 REST 接口，/api/user/background，任意已认证角色，个人 ui 偏好，
 * GET 查本人行，rls 恒本人，无行返回默认配置不 404，PUT 覆盖写 upsert（不设
 * requireWriteRole，db 对四职责角色一致授 SELECT/INSERT/UPDATE，个人偏好不参与
 * 业务写权限面）。配置 jsonb 白名单收敛，剔除未知键 + 取值域校验 + 8KB 上限防滥用。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user")
public class UserPrefController {

    /** 配置大小上限（字节，≤8KB 防滥用，前端配置远小于此） */
    private static final int MAX_BACKGROUND_BYTES = 8 * 1024;

    /** 顶层/断点档共用的字段白名单，与前端背景配置 jsonb 结构同构，前后端勿改 */
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "enabled", "imageUrl", "fit", "blur", "darken", "opacity", "contentOpacity",
            "desktop", "tablet", "mobile");

    /** fit 取值白名单 */
    private static final Set<String> FIT_VALUES = Set.of("cover", "contain", "fill");

    /** 无行默认配置，关闭 + cover/blur 6/darken 0.25/opacity 1 不透明/contentOpacity 1 内容区不透明，断点档 null=完全继承顶层 */
    private static final String DEFAULT_CONFIG_JSON =
            "{\"enabled\":false,\"fit\":\"cover\",\"blur\":6,\"darken\":0.25,\"opacity\":1,\"contentOpacity\":1,\"desktop\":null,\"tablet\":null,\"mobile\":null}";

    private final UserPreferenceMapper userPreferenceMapper;


    /** 取本人背景配置，rls 限定本人行，越权恒空集，无行返回默认配置，不 404 */
    @GetMapping("/background")
    public ApiResponse<JSONObject> getBackground() {
        UUID userId = RequestContext.requireUserId();
        UserPreference pref = userPreferenceMapper.selectById(userId);
        if (pref == null || pref.getBackground() == null || pref.getBackground().isBlank()) {
            return ApiResponse.ok(parseDefault());
        }

        try {
            JSONObject parsed = JSON.parseObject(pref.getBackground());
            return ApiResponse.ok(parsed == null ? parseDefault() : parsed);
        } catch (Exception e) {
            return ApiResponse.ok(parseDefault()); // 存量脏值兜底默认，不崩溃
        }
    }

    /**
     * 保存本人背景配置（upsert），约定请求体 {background:{...}} 包装，无 background 键时
     * 按直接配置对象处理，白名单剔除未知键 + 取值域校验（fit/blur/darken），大小 >8KB 拒绝。
     */
    @PutMapping("/background")
    public ApiResponse<JSONObject> putBackground(@RequestBody String rawBody) {
        UUID userId = RequestContext.requireUserId();
        if (rawBody == null || rawBody.getBytes(StandardCharsets.UTF_8).length > MAX_BACKGROUND_BYTES) {
            throw new BadRequestException("背景配置大小超限（上限 8KB）");
        }

        JSONObject root;
        try {
            root = JSON.parseObject(rawBody);
        } catch (Exception e) {
            throw new BadRequestException("请求体 JSON 格式错误");
        }
        if (root == null) {
            throw new BadRequestException("请求体不能为空");
        }

        // 双形态兼容，{background:{...}} 包装 / 直接配置对象，任选其一，前端按包装形态调用
        JSONObject config;
        if (root.containsKey("background")) {
            Object bg = root.get("background");
            if (!(bg instanceof JSONObject jo)) {
                throw new BadRequestException("background 必须为 JSON 对象");
            }
            config = jo;
        } else {
            config = root;
        }
        JSONObject cleaned = sanitize(config);

        // upsert，先查后写（与 MyBatis-Plus saveOrUpdate 对非逻辑删除实体行为一致，
        // user_id=当前用户，rls WITH CHECK 限定本人行，跨用户写恒被 db 拒绝）
        UserPreference pref = userPreferenceMapper.selectById(userId);
        LocalDateTime now = LocalDateTime.now();
        if (pref == null) {
            pref = new UserPreference();
            pref.setUserId(userId);
            pref.setBackground(cleaned.toJSONString());
            pref.setUpdatedAt(now);
            userPreferenceMapper.insert(pref);
        } else {
            pref.setBackground(cleaned.toJSONString());
            pref.setUpdatedAt(now);
            userPreferenceMapper.updateById(pref);
        }

        return ApiResponse.ok(cleaned);
    }

    // 私有辅助

    /** 无行默认配置对象 */
    private JSONObject parseDefault() {
        return JSON.parseObject(DEFAULT_CONFIG_JSON);
    }

    /**
     * 白名单清洗 + 取值域校验，剔除未知键，非法取值 40001，desktop/tablet/mobile
     * 断点档递归同规则，仅保留覆盖字段，缺省继承顶层由前端合并。
     */
    private JSONObject sanitize(JSONObject src) {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, Object> entry : src.entrySet()) {
            String key = entry.getKey();
            if (!ALLOWED_KEYS.contains(key)) {
                continue; // 剔除未知键，白名单收敛，防结构漂移/滥用
            }
            Object val = entry.getValue();
            switch (key) {
                case "enabled" -> {
                    if (!(val instanceof Boolean b)) {
                        throw new BadRequestException("enabled 必须为布尔值");
                    }
                    out.put(key, b);
                }
                case "imageUrl" -> {
                    if (val != null && !(val instanceof String)) {
                        throw new BadRequestException("imageUrl 必须为字符串或 null");
                    }
                    out.put(key, val);
                }
                case "fit" -> {
                    if (!(val instanceof String s) || !FIT_VALUES.contains(s)) {
                        throw new BadRequestException("fit 必须为 cover/contain/fill 之一");
                    }
                    out.put(key, s);
                }
                case "blur" -> {
                    int v = toInt(val, "blur");
                    if (v < 0 || v > 20) {
                        throw new BadRequestException("blur 必须为 0-20 的整数");
                    }
                    out.put(key, v);
                }
                case "darken" -> {
                    double v = toDouble(val, "darken");
                    if (v < 0 || v > 0.8) {
                        throw new BadRequestException("darken 必须为 0-0.8 的数值");
                    }
                    out.put(key, v);
                }
                case "opacity" -> {
                    double v = toDouble(val, "opacity");
                    if (v < 0 || v > 1) {
                        throw new BadRequestException("opacity 必须为 0-1 的数值");
                    }
                    out.put(key, v);
                }
                case "contentOpacity" -> {
                    double v = toDouble(val, "contentOpacity");
                    if (v < 0 || v > 1) {
                        throw new BadRequestException("contentOpacity 必须为 0-1 的数值");
                    }
                    out.put(key, v);
                }
                case "desktop", "tablet", "mobile" -> {
                    if (val == null) {
                        out.put(key, null);
                    } else if (val instanceof JSONObject nested) {
                        out.put(key, sanitize(nested)); // 断点档递归同规则
                    } else {
                        throw new BadRequestException(key + " 必须为对象或 null");
                    }
                }
                default -> throw new IllegalStateException("白名单键缺失分支"); // 不可达，已按白名单过滤
            }
        }
        return out;
    }

    /** Number/数字字符串转 int，非数值抛 40001 */
    private static int toInt(Object val, String key) {
        if (val instanceof Number n) {
            return n.intValue();
        }
        if (val instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // 落入下方统一 40001
            }
        }
        throw new BadRequestException(key + " 必须为数值");
    }

    /** Number/数字字符串转 double，非数值抛 40001 */
    private static double toDouble(Object val, String key) {
        if (val instanceof Number n) {
            return n.doubleValue();
        }
        if (val instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                // 落入下方统一 40001
            }
        }
        throw new BadRequestException(key + " 必须为数值");
    }
}
