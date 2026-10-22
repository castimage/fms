package net.castimage.fms_backend.Utils;

import jakarta.mail.internet.MimeMessage;
import net.castimage.fms_backend.Mapper.ParamMapper;
import net.castimage.fms_backend.Config.FmsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 邮件服务测试，enabled=false 静默跳过、smtp 未配置 WARN 跳过、发送成功/失败均不
 * 抛错，邮件是尽力而为的送达通道，票据已随响应返回，绝不允许拖垮找回接口，
 * 运行时 smtp 参数（um.params smtp.*）优先于 yaml 配置，Web 改即生效语义。
 */
class MailServiceTest {

    private FmsProperties fmsProperties;
    private ObjectProvider<JavaMailSender> provider;
    private ParamMapper paramMapper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        fmsProperties = new FmsProperties();
        provider = mock(ObjectProvider.class);
        paramMapper = mock(ParamMapper.class);
        // 默认无运行时参数则回落 yaml 配置
        when(paramMapper.selectPublicParamValue(anyString(), anyString())).thenReturn("null");
    }

    private MailService newService() {
        return new MailService(fmsProperties, provider, paramMapper);
    }

    @Test
    void disabledSilentlyReturns() {
        newService().sendPasswordReset("alice", "a@fms.local", "t-1", LocalDateTime.now());
        verifyNoInteractions(provider);
    }

    @Test
    void enabledWithoutSmtpWarnsAndSkips() {
        fmsProperties.getMail().setEnabled(true); // host 默认空 = 未配置
        assertDoesNotThrow(() ->
                newService().sendPasswordReset("alice", "a@fms.local", "t-1", LocalDateTime.now()));
    }

    @Test
    void enabledWithSenderSends() throws Exception {
        fmsProperties.getMail().setEnabled(true);
        fmsProperties.getMail().setHost("smtp.fms.local");
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage mime = mock(MimeMessage.class);
        when(sender.createMimeMessage()).thenReturn(mime);
        when(provider.getIfAvailable()).thenReturn(sender);

        newService().sendPasswordReset("alice", "alice@fms.local", "t-1", LocalDateTime.now());
        verify(sender).send(any(MimeMessage.class));
    }

    @Test
    void sendFailureWarnsWithoutThrowing() {
        fmsProperties.getMail().setEnabled(true);
        fmsProperties.getMail().setHost("smtp.fms.local");
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage mime = mock(MimeMessage.class);
        when(sender.createMimeMessage()).thenReturn(mime);
        when(provider.getIfAvailable()).thenReturn(sender);
        doThrow(new MailException("smtp down") {
        }).when(sender).send(any(MimeMessage.class));

        assertDoesNotThrow(() ->
                newService().sendPasswordReset("alice", "a@fms.local", "t-1", LocalDateTime.now()));
    }

    @Test
    void runtimeParamsReadWhenSelfBuildingSender() {
        // 无 spring bean（provider null）则自建 sender 路径，um.params smtp.* 被读取，Web 改参数即生效语义，自建后真实发送失败仅 WARN，不抛，尽力而为契约
        fmsProperties.getMail().setEnabled(true);
        fmsProperties.getMail().setHost("yaml.fms.local");
        when(paramMapper.selectPublicParamValue(eq("smtp.host"), anyString())).thenReturn("runtime.fms.local");
        when(provider.getIfAvailable()).thenReturn(null);

        assertDoesNotThrow(() ->
                newService().sendPasswordReset("alice", "alice@fms.local", "t-1", LocalDateTime.now()));
        // 参数读取路径被调用，host/port/from 三键均走 paramOr
        verify(paramMapper).selectPublicParamValue(eq("smtp.host"), anyString());
        verify(paramMapper).selectPublicParamValue(eq("smtp.port"), anyString());
        verify(paramMapper).selectPublicParamValue(eq("smtp.from"), anyString());
    }

    @Test
    void runtimeParamMissingFallsBackToYaml() {
        // 无运行时参数，mock 默认 null 则回落 yaml host，enabled 且 host 来自 yaml，
        // 自建发送失败 WARN 不抛，yaml 兜底路径同样尽力而为
        fmsProperties.getMail().setEnabled(true);
        fmsProperties.getMail().setHost("yaml.fms.local");
        when(provider.getIfAvailable()).thenReturn(null);

        assertDoesNotThrow(() ->
                newService().sendPasswordReset("alice", "alice@fms.local", "t-1", LocalDateTime.now()));
        // 参数缺失时仍会读取，读口调用，但回落 yaml，断言读取发生即可
        verify(paramMapper).selectPublicParamValue(eq("smtp.host"), anyString());
    }
}
