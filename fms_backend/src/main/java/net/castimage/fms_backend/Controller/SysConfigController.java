package net.castimage.fms_backend.Controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import net.castimage.fms_backend.config.FmsProperties;
import net.castimage.fms_backend.Mapper.ParamMapper;
import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Model.vo.UiConfigView;
import net.castimage.fms_backend.Utils.RequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import java.util.List;

/**
 * 系统配置下发 REST 接口，/api/config，任意已认证角色可读，前端硬编码收敛的单一事实源，
 * 密码长度/扩展名白名单/分页选项/报表白名单随 fms.* yaml 配置变更自动下发，前端删除重复维护，
 * 参考数据下拉拉取上限（refdataPageSize）为业务级参数，运行期经 um.fn_get_param_value 读
 * um.params web.refdata.page.size（Web 系统参数页可改，缺省 200，db 无值/异常回落缺省）。
 *
 * 仅需认证（requireUserId），不要求管理员角色，登录态即可渲染页面级校验，如改密页，
 * 不含任何敏感/部署级参数，jwt 密钥、db 口令、调度周期等运维值绝不下发，铁律。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/config")
public class SysConfigController {

    /** 参考数据下拉拉取上限缺省，与历史前端硬编码 200 一致，参数删除/异常时的兜底 */
    private static final int DEFAULT_REFDATA_PAGE_SIZE = 200;

    private final FmsProperties fmsProperties;
    private final ParamMapper paramMapper;


    /**
     * ui 配置下发，密码最小/最大长度（fms.security.*）、附件扩展名白名单，fms.file.*，含 tsv
     * 对齐对账单导入、分页每页条数选项（fms.ui.page-sizes）、报表导出白名单（fms.report.enabled-types）、
     * 参考数据下拉拉取上限，um.params web.refdata.page.size，缺省 200。
     */
    @GetMapping
    public ApiResponse<UiConfigView> uiConfig() {
        RequestContext.requireUserId();
        return ApiResponse.ok(new UiConfigView(
                fmsProperties.getSecurity().getPasswordMinLength(),
                fmsProperties.getSecurity().getPasswordMaxLength(),
                List.copyOf(fmsProperties.getFile().getAllowedExtensions()),
                List.copyOf(fmsProperties.getUi().getPageSizes()),
                List.copyOf(fmsProperties.getReport().getEnabledTypes()),
                readRefdataPageSize()));
    }

    /** 参考数据下拉拉取上限，经 um.fn_get_param_value 带缺省值读取，db 无值/异常/敏感键均回落缺省 200 */
    private int readRefdataPageSize() {
        try {
            String raw = paramMapper.selectPublicParamValue("web.refdata.page.size",
                    String.valueOf(DEFAULT_REFDATA_PAGE_SIZE));
            if (raw == null || raw.isBlank()) {
                return DEFAULT_REFDATA_PAGE_SIZE;
            }

            // jsonb::text 三种形态兼容，数字标量 "200" / 字符串标量 "\"200\"" / 对象 "{\"v\":200}"
            Object val = JSON.parse(raw);
            int v = toPositiveInt(val);
            if (v < 0 && val instanceof JSONObject obj) {
                v = toPositiveInt(obj.get("v"));
            }
            return v > 0 ? v : DEFAULT_REFDATA_PAGE_SIZE;
        } catch (Exception e) {
            return DEFAULT_REFDATA_PAGE_SIZE; // 参数值非法/db 异常回落缺省，前端不崩溃
        }
    }

    /** Number/String 转正整数，非法返回 -1 */
    private static int toPositiveInt(Object val) {
        if (val instanceof Number n) {
            return n.intValue();
        }
        if (val instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }
}
