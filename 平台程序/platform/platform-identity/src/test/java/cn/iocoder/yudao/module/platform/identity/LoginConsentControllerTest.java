package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.platform.identity.controller.admin.PlatformAuthController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LoginConsentControllerTest {
    private final FranchiseAccountAuthService account = mock(FranchiseAccountAuthService.class);
    private final StaffWechatLoginService wechat = mock(StaffWechatLoginService.class);
    private MockMvc mvc;
    private static final String ID = "12345678-1234-1234-1234-123456789012";
    private static final LoginPolicy.Consent CURRENT = new LoginPolicy.Consent(true,"2026-11-15","2026-11-15");
    @BeforeEach void setup() {
        var controller = new PlatformAuthController();
        ReflectionTestUtils.setField(controller, "franchiseAccountAuthService", account);
        ReflectionTestUtils.setField(controller, "staffWechatLoginService", wechat);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler("test", mock(ApiErrorLogCommonApi.class))).build();
    }
    private String body(boolean isWechat, LoginPolicy.Consent consent) throws Exception {
        Map<String,Object> payload = new LinkedHashMap<>(isWechat ? Map.of("appid","app","loginCode","code") : Map.of("accountId",ID,"password","123456"));
        payload.put("loginVersion",2);
        if (consent != null) payload.put("consent",consent);
        return new ObjectMapper().writeValueAsString(payload);
    }
    @Test void publishesCurrentVersionAndSimplePasswordPolicy() throws Exception {
        mvc.perform(get("/platform/auth/login-policy")).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.agreementVersion").value(LoginPolicy.current().agreementVersion()))
                .andExpect(jsonPath("$.data.passwordMinLength").value(6));
    }
    @Test void bothPathsRejectMissingFalseOrOutdatedConsentBeforeAuthenticating() throws Exception {
        for (var consent : new LoginPolicy.Consent[]{null,new LoginPolicy.Consent(false,"2026-11-15","2026-11-15"),new LoginPolicy.Consent(true,"old","old")}) {
            for (boolean isWechat : new boolean[]{false,true}) mvc.perform(post("/platform/auth/"+(isWechat?"wechat-login":"account/login"))
                    .contentType(MediaType.APPLICATION_JSON).content(body(isWechat,consent))).andExpect(jsonPath("$.code").value(1104000016));
        }
        verifyNoInteractions(account,wechat);
    }
    @Test void agreedAccountLoginKeepsTokenAndPreferredPost() throws Exception {
        var token = new TokenIssuer.IssuedToken("account-token","refresh",LocalDateTime.now().plusHours(1));
        when(account.login(ID,"123456",null)).thenReturn(new FranchiseAccountAuthService.LoginResult(false,7L,token,11L,ID,"lili","门店"));
        mvc.perform(post("/platform/auth/account/login").contentType(MediaType.APPLICATION_JSON).content(body(false,CURRENT)))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.accessToken").value("account-token"))
                .andExpect(jsonPath("$.data.userId").value(7)).andExpect(jsonPath("$.data.preferredPostId").value(11));
    }
    @Test void agreedBoundWechatLoginKeepsSameUserWithoutPassword() throws Exception {
        when(wechat.login("app","code",null)).thenReturn(WechatLoginResult.loggedIn(7L,new TokenIssuer.IssuedToken("wechat-token","refresh",LocalDateTime.now().plusHours(1))));
        mvc.perform(post("/platform/auth/wechat-login").contentType(MediaType.APPLICATION_JSON).content(body(true,CURRENT)))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.needPhone").value(false))
                .andExpect(jsonPath("$.data.userId").value(7)).andExpect(jsonPath("$.data.accessToken").value("wechat-token"));
        verifyNoInteractions(account);
    }
    @Test void existingClientRequestsContinueToWorkDuringUpgrade() throws Exception {
        var token = new TokenIssuer.IssuedToken("legacy-token","refresh",LocalDateTime.now().plusHours(1));
        when(account.login(ID,"123456",null)).thenReturn(new FranchiseAccountAuthService.LoginResult(false,7L,token,11L,ID,"lili","门店"));
        when(wechat.login("app","code",null)).thenReturn(WechatLoginResult.loggedIn(7L,token));
        for (boolean isWechat : new boolean[]{false,true}) {
            var payload = isWechat ? Map.of("appid","app","loginCode","code") : Map.of("accountId",ID,"password","123456");
            mvc.perform(post("/platform/auth/"+(isWechat?"wechat-login":"account/login")).contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(payload))).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.userId").value(7));
        }
    }
    @Test void anonymousCandidatesNeverExposeNamesRolesOrAccountIdentifiers() throws Exception {
        when(account.matches("lili")).thenReturn(new FranchiseAccountAuthService.MatchResult(List.of(new FranchiseAccountAuthService.AccountMatch(
                ID,"lili","长公司名","李丽","李丽","sales","业务员","合家美宅 · 江西南昌高新区")),false));
        mvc.perform(post("/platform/auth/account/matches").contentType(MediaType.APPLICATION_JSON).content(new ObjectMapper().writeValueAsString(Map.of("query","lili"))))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.hasMore").value(false));
        verifyNoInteractions(account);
    }

    @Test void retiredCandidateEndpointReturnsSameEmptyResponseForEveryQuery() throws Exception {
        for (String name : List.of("青松 建房（南宁店）", "青", "lili", "does-not-exist")) {
            mvc.perform(post("/platform/auth/account/matches").contentType(MediaType.APPLICATION_JSON)
                            .content(new ObjectMapper().writeValueAsString(Map.of("query", name))))
                    .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.items").isEmpty())
                    .andExpect(jsonPath("$.data.hasMore").value(false));
        }
        verifyNoInteractions(account);
    }

    @Test void completeAccountLoginReturnsIdentityOnlyFromPasswordAuthentication() throws Exception {
        var token = new TokenIssuer.IssuedToken("verified-token","refresh",LocalDateTime.now().plusHours(1));
        when(account.loginByName("lili2","123456",null)).thenReturn(new FranchiseAccountAuthService.LoginResult(false,7L,token,11L,ID,"lili2","门店"));
        mvc.perform(post("/platform/auth/account/login").contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(Map.of("loginName","lili2","password","123456","loginVersion",2,"consent",CURRENT))))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.accountId").value(ID))
                .andExpect(jsonPath("$.data.accessToken").value("verified-token")).andExpect(jsonPath("$.data.preferredPostId").value(11));
        verify(account).loginByName("lili2","123456",null);
        verify(account,never()).matches(any());
        verify(account,never()).login(any(),any(),any());
    }

    @Test void missingOrAmbiguousAccountIsRejectedBeforeAuthentication() throws Exception {
        for (Map<String,Object> identity : List.of(Map.<String,Object>of(),Map.<String,Object>of("accountId",ID,"loginName","lili"))) {
            var payload = new LinkedHashMap<>(identity);payload.put("password","123456");payload.put("loginVersion",2);payload.put("consent",CURRENT);
            mvc.perform(post("/platform/auth/account/login").contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(payload))).andExpect(jsonPath("$.code").value(400));
        }
        verifyNoInteractions(account);
    }
}
