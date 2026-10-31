package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.identity.StaffAccountDirectory.StaffAccount;
import cn.iocoder.yudao.module.platform.identity.sms.StubSmsClient;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaffSmsLoginServiceImplTest {

    private static final String MOBILE = "13800001234";
    private static final TokenIssuer.IssuedToken TOKEN =
            new TokenIssuer.IssuedToken("access-1", "refresh-1", LocalDateTime.now().plusHours(2));

    @Mock
    private SmsCaptchaService smsCaptchaService;
    @Mock
    private StaffAccountDirectory accounts;
    @Mock
    private StaffPostService staffPostService;
    @Mock
    private TokenIssuer tokenIssuer;

    @InjectMocks
    private StaffSmsLoginServiceImpl service;

    @Test
    void captchaFailurePropagatesAndStopsBeforeStaffLookup() {
        doThrow(new ServiceException(SMS_CAPTCHA_WRONG.getCode(), SMS_CAPTCHA_WRONG.getMsg()))
                .when(smsCaptchaService).verifyLoginCaptcha(MOBILE, "000001");

        assertThatThrownBy(() -> service.login(MOBILE, "000001"))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(SMS_CAPTCHA_WRONG.getCode()));
        verifyNoInteractions(accounts, tokenIssuer);
    }

    @Test
    void rejectsMobileWithoutEnrolledStaff() {
        when(accounts.findByMobile(MOBILE)).thenReturn(List.of());

        assertThatThrownBy(() -> service.login(MOBILE, "123456"))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(STAFF_NOT_ENROLLED.getCode()));
        verifyNoInteractions(tokenIssuer);
    }

    @Test
    void rejectsMobileMatchingMultipleStaff() {
        when(accounts.findByMobile(MOBILE)).thenReturn(List.of(
                new StaffAccount(11L, MOBILE, true), new StaffAccount(12L, MOBILE, true)));
        when(staffPostService.listActivePosts(anyLong())).thenReturn(List.of(new StaffPostDO()));

        assertThatThrownBy(() -> service.login(MOBILE, "123456"))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(STAFF_PHONE_AMBIGUOUS.getCode()));
    }

    @Test
    void issuesTokenForSingleActiveStaff() {
        when(accounts.findByMobile(MOBILE)).thenReturn(List.of(new StaffAccount(7L, MOBILE, true)));
        when(staffPostService.listActivePosts(7L)).thenReturn(List.of(new StaffPostDO()));
        when(tokenIssuer.issue(7L)).thenReturn(TOKEN);

        WechatLoginResult result = service.login(MOBILE, "123456");

        assertThat(result.userId()).isEqualTo(7L);
        assertThat(result.token()).isSameAs(TOKEN);
        verify(smsCaptchaService).verifyLoginCaptcha(MOBILE, "123456");
    }

    @Test
    void stubSmsClientOnlyStartsUnderLocalProfile() {
        assertThat(new StubSmsClient(env("local", "pg", "zsdev"))).isNotNull();
        assertThatThrownBy(() -> new StubSmsClient(env("pg"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new StubSmsClient(env("local", "prod"))).isInstanceOf(IllegalStateException.class);
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

}
