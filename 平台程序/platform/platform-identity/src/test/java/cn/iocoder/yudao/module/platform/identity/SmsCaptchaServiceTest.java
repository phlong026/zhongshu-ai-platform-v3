package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.identity.sms.SmsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.*;
import static cn.iocoder.yudao.module.platform.identity.SmsCaptchaService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 登录短信验证码规则：60s 发送间隔、每日上限、5min 有效、错误 5 次作废、成功即消费。
 */
@ExtendWith(MockitoExtension.class)
class SmsCaptchaServiceTest {

    private static final String MOBILE = "13800001234";

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> ops;
    @Mock
    private SmsClient smsClient;

    private SmsCaptchaService service;

    @BeforeEach
    void setUp() {
        service = new SmsCaptchaService();
        ReflectionTestUtils.setField(service, "redis", redis);
        ReflectionTestUtils.setField(service, "smsClient", smsClient);
        lenient().when(redis.opsForValue()).thenReturn(ops);
    }

    @Test
    void sendRejectsWithinSixtySeconds() {
        when(redis.hasKey(KEY_INTERVAL + MOBILE)).thenReturn(true);

        assertThatThrownBy(() -> service.sendLoginCaptcha(MOBILE))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(SMS_SEND_TOO_FREQUENT.getCode()));
        verifyNoInteractions(smsClient);
    }

    @Test
    void sendRejectsWhenDailyLimitReached() {
        when(redis.hasKey(anyString())).thenReturn(false);
        when(ops.increment(contains(KEY_DAILY))).thenReturn((long) (DAILY_SEND_LIMIT + 1));

        assertThatThrownBy(() -> service.sendLoginCaptcha(MOBILE))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(SMS_DAILY_LIMIT.getCode()));
        verifyNoInteractions(smsClient);
    }

    @Test
    void sendStoresSixDigitCaptchaBeforeDispatching() {
        when(redis.hasKey(anyString())).thenReturn(false);
        when(ops.increment(contains(KEY_DAILY))).thenReturn(1L);

        service.sendLoginCaptcha(MOBILE);

        ArgumentCaptor<String> captcha = ArgumentCaptor.forClass(String.class);
        verify(ops).set(eq(KEY_CAPTCHA + MOBILE), captcha.capture(), eq(Duration.ofMinutes(5)));
        assertThat(captcha.getValue()).matches("\\d{6}");
        verify(ops).set(eq(KEY_INTERVAL + MOBILE), eq("1"), eq(Duration.ofSeconds(60)));
        verify(redis).delete(KEY_FAILS + MOBILE);
        verify(smsClient).sendLoginCaptcha(MOBILE, captcha.getValue());
    }

    @Test
    void verifyRejectsExpiredCaptcha() {
        when(ops.get(KEY_CAPTCHA + MOBILE)).thenReturn(null);

        assertThatThrownBy(() -> service.verifyLoginCaptcha(MOBILE, "123456"))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(SMS_CAPTCHA_EXPIRED.getCode()));
    }

    @Test
    void verifyCountsFailuresAndDiscardsAfterFiveWrongAttempts() {
        when(ops.get(KEY_CAPTCHA + MOBILE)).thenReturn("123456");
        when(ops.increment(KEY_FAILS + MOBILE)).thenReturn((long) MAX_VERIFY_FAILS);

        assertThatThrownBy(() -> service.verifyLoginCaptcha(MOBILE, "999999"))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(SMS_CAPTCHA_WRONG.getCode()));
        verify(redis).delete(KEY_CAPTCHA + MOBILE);
    }

    @Test
    void verifyConsumesCaptchaOnSuccess() {
        when(ops.get(KEY_CAPTCHA + MOBILE)).thenReturn("123456");

        service.verifyLoginCaptcha(MOBILE, "123456");

        verify(redis).delete(KEY_CAPTCHA + MOBILE);
        verify(redis).delete(KEY_FAILS + MOBILE);
    }

}
