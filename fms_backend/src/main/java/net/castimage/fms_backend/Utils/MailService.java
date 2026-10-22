package net.castimage.fms_backend.Utils;



import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.castimage.fms_backend.Mapper.ParamMapper;
import net.castimage.fms_backend.Config.FmsProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * 邮件发送服务，密码找回闭环，sendPasswordReset 封装 JavaMailSender，enabled=false
 * 或 smtp 未配置时静默跳过，recovery 仍返回票据，兼容旧契约，发送失败仅 WARN 不阻断
 * 主流程，与登录审计落库失败同策略，邮件是尽力而为的送达通道，票据本身已随响应返回。
 *
 * sender 解析，优先取 spring.mail.* 自动装配的 JavaMailSender（spring-boot-starter-mail，
 * host 为空时无 bean，ObjectProvider 兜底），缺 bean 时按运行时 smtp 配置自建。
 * smtp 配置运行时生效，host/port/from 优先读 um.params（smtp.host/smtp.port/smtp.from，
 * 管理页「系统参数」修改保存后即时生效，无需重启），yaml 部署配置（FMS_SMTP_*）兜底，
 * 「部署配置 smtp 后自动生效」即指此双通道，填 .env 重建一次初始化，或 Web 改参数零重启。
 *
 * 安全约束，目标邮箱由调用方（AuthServiceImpl.recovery）传入，um.users.email 为
 * bytea 加密 + hmac 盲索引，应用侧无法取出明文，只能使用请求体自报邮箱，经闸口
 * recovery_lookup 校验与盲索引一致才命中，防错发。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MailService {

    /** 正文模板时间格式，与 api 时间口径一致 */
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** um.params 运行时 smtp 参数键，管理页可改，保存即时生效 */
    private static final String PARAM_SMTP_HOST = "smtp.host";
    private static final String PARAM_SMTP_PORT = "smtp.port";
    private static final String PARAM_SMTP_FROM = "smtp.from";

    private final FmsProperties fmsProperties;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final ParamMapper paramMapper;

    /**
     * 发送密码重置邮件，票据 + 有效期，enabled=false / smtp 未配置 / 发送异常一律
     * 静默或 WARN 返回，不抛错，找回主流程的票据已在响应中返回，邮件只是辅助送达。
     *
     * @param loginName 登录名，正文占位符
     * @param email     目标邮箱，用户自报、经 recovery_lookup 盲索引命中
     * @param ticket    一次性重置票据
     * @param expireAt  票据有效期
     */
    public void sendPasswordReset(String loginName, String email, String ticket, LocalDateTime expireAt) {
        FmsProperties.Mail mail = fmsProperties.getMail();
        if (!mail.isEnabled()) {
            return; // 未启用，静默返回，兼容旧契约，recovery 仍返回票据
        }
        try {
            JavaMailSender sender = resolveSender();
            if (sender == null) {
                log.warn("邮件已启用但 SMTP 未配置（spring.mail.host 与 fms.mail.host 均为空），跳过发送 to={}",
                        maskEmail(email));
                return;
            }

            String body = mail.getResetBodyTemplate()
                    .replace("{loginName}", loginName == null ? "" : loginName)
                    .replace("{ticket}", ticket)
                    .replace("{expiresAt}", expireAt.format(TS_FMT));

            var mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, "UTF-8");
            helper.setFrom(paramOr(PARAM_SMTP_FROM, mail.getFrom()));
            helper.setTo(email);
            helper.setSubject(mail.getResetSubject());
            helper.setText(body);
            sender.send(mime);
            log.info("密码重置邮件已发送至 {}", maskEmail(email));
        } catch (Exception e) {
            // 与登录审计同策略，发送失败仅 WARN，不阻断主流程，票据已随响应返回。

            // catch Exception 而非 RuntimeException，MimeMessageHelper 抛 checked
            // jakarta.mail.MessagingException，发送链路任何异常都归入同一"尽力而为"语义
            log.warn("密码重置邮件发送失败（不影响找回流程）: to={} err={}", maskEmail(email), e.getMessage());
        }
    }

    /**
     * sender 解析，Spring 自动装配优先，运行时 smtp 配置自建兜底，均不可用返回 null。
     *
     * 运行时配置 = um.params 优先，Web 改即时生效，yaml 兜底，paramOr 语义。
     */
    private JavaMailSender resolveSender() {
        JavaMailSender spring = mailSenderProvider.getIfAvailable();
        if (spring != null) {
            return spring;
        }

        FmsProperties.Mail mail = fmsProperties.getMail();
        String host = paramOr(PARAM_SMTP_HOST, mail.getHost());
        if (host == null || host.isBlank()) {
            return null;
        }
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(host);
        impl.setPort(portOr());
        impl.setUsername(mail.getUsername());
        impl.setPassword(mail.getPassword());
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", "true");
        impl.setJavaMailProperties(props);
        return impl;
    }

    /** 运行时参数读取，um.params SECURITY DEFINER 读口，有值用参数值，否则回落 yaml 配置 */
    private String paramOr(String code, String yamlValue) {
        String v = paramMapper.selectPublicParamValue(code, "null");
        if (v != null && !v.isBlank() && !"null".equalsIgnoreCase(v.trim())) {
            return v.trim();
        }
        return yamlValue;
    }

    /** 端口，smtp.port 参数优先，可解析，yaml 兜底 */
    private int portOr() {
        String v = paramOr(PARAM_SMTP_PORT, String.valueOf(fmsProperties.getMail().getPort()));
        try {
            return Integer.parseInt(v.trim());
        } catch (RuntimeException e) {
            return fmsProperties.getMail().getPort();
        }
    }

    /** 邮箱打码，日志不外泄完整地址，本地部分截断，保留域名 */
    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return email.substring(0, 2) + "***" + email.substring(at);
    }
}
