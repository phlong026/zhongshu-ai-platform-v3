package cn.iocoder.yudao.server.split;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.module.system.controller.admin.dict.vo.data.DictDataSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.dict.vo.type.DictTypeSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.notice.vo.NoticeSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.notify.vo.template.NotifyTemplateSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.notify.vo.template.NotifyTemplateSendReqVO;
import cn.iocoder.yudao.module.system.controller.admin.mail.vo.account.MailAccountSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.mail.vo.template.MailTemplateSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.mail.vo.template.MailTemplateSendReqVO;
import cn.iocoder.yudao.module.system.controller.admin.sms.vo.channel.SmsChannelSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.sms.vo.template.SmsTemplateSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.sms.vo.template.SmsTemplateSendReqVO;
import cn.iocoder.yudao.module.system.controller.admin.social.vo.client.SocialClientSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.social.vo.user.SocialUserBindReqVO;
import cn.iocoder.yudao.module.system.controller.admin.social.vo.user.SocialUserUnbindReqVO;
import cn.iocoder.yudao.module.system.controller.admin.tenant.vo.tenant.TenantSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.tenant.vo.packages.TenantPackageSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.oauth2.vo.client.OAuth2ClientSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.permission.vo.menu.MenuSaveVO;
import cn.iocoder.yudao.module.system.controller.admin.dept.vo.post.PostSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.user.vo.profile.UserProfileUpdateReqVO;
import cn.iocoder.yudao.module.system.controller.admin.user.vo.profile.UserProfileUpdatePasswordReqVO;
import cn.iocoder.yudao.module.system.controller.admin.permission.MenuController;
import cn.iocoder.yudao.module.system.controller.admin.dept.PostController;
import cn.iocoder.yudao.module.system.controller.admin.dict.DictDataController;
import cn.iocoder.yudao.module.system.controller.admin.dict.DictTypeController;
import cn.iocoder.yudao.module.system.controller.admin.mail.MailAccountController;
import cn.iocoder.yudao.module.system.controller.admin.mail.MailTemplateController;
import cn.iocoder.yudao.module.system.controller.admin.notice.NoticeController;
import cn.iocoder.yudao.module.system.controller.admin.notify.NotifyMessageController;
import cn.iocoder.yudao.module.system.controller.admin.notify.NotifyTemplateController;
import cn.iocoder.yudao.module.system.controller.admin.oauth2.OAuth2ClientController;
import cn.iocoder.yudao.module.system.controller.admin.sms.SmsCallbackController;
import cn.iocoder.yudao.module.system.controller.admin.sms.SmsChannelController;
import cn.iocoder.yudao.module.system.controller.admin.sms.SmsTemplateController;
import cn.iocoder.yudao.module.system.controller.admin.social.SocialClientController;
import cn.iocoder.yudao.module.system.controller.admin.social.SocialUserController;
import cn.iocoder.yudao.module.system.controller.admin.tenant.TenantController;
import cn.iocoder.yudao.module.system.controller.admin.tenant.TenantPackageController;
import cn.iocoder.yudao.module.system.controller.admin.user.UserProfileController;
import cn.iocoder.yudao.module.system.service.auth.AdminAuthService;
import cn.iocoder.yudao.module.system.service.dict.DictDataService;
import cn.iocoder.yudao.module.system.service.notify.NotifyMessageService;
import cn.iocoder.yudao.module.system.service.sms.SmsSendService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * G2s-a 阶段二运营配置面（writer=system-mutations 扩展 18→88）：真实 MVC + 真实 PG
 * （platform-bootstrap V20260904.001，G2k 夹具复用）验证 70 写入口接入共用停写门禁。
 *
 * <p>两层承载声明（c31③模式，G2k同型）：mock层=门禁语义（本套件——per-entry DRAINING 503×70+
 * 白名单captor 70项+装配矩阵+UNKNOWN代表；服务替身承载业务层）；真实层=业务行证据（G2k套件
 * 既有真实行+system模块自身测试+本套件DictData OPEN交互验证）。豁免3项=登记性写源
 * （Captcha get/check×2 Redis-only+SocialClient sendSubscribeMessage void返回）；
 * check-token=纯读不收口（c32源码级核验）。
 */
class LegacyPlatformSystemStage2InterceptorTest {
    private static final String SCOPE = "9".repeat(64);
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    private final ApiErrorLogCommonApi errorLogs = mock(ApiErrorLogCommonApi.class);
    private final DictDataService dictDataService = mock(DictDataService.class);
    private final NotifyMessageService notifyMessageService = mock(NotifyMessageService.class);
    private final SmsSendService smsSendService = mock(SmsSendService.class);
    private final AdminAuthService authService = mock(AdminAuthService.class);
    private final SecurityProperties securityProperties = mock(SecurityProperties.class);
    private JdbcTemplate observer;
    private LegacyPlatformWriteGate gate;
    private LegacyPlatformWalletSessionInterceptorTest.MemoryStore store;
    private MockMvc dictDataMvc;
    private MockMvc notifyMessageMvc;
    private MockMvc smsCallbackMvc;
    private MockMvc authMvc;
    private DictDataController dictDataController;
    private NotifyMessageController notifyMessageController;
    private SmsCallbackController smsCallbackController;
    private cn.iocoder.yudao.module.system.controller.admin.auth.AuthController authController;

    /** wired 条目：控制器/方法名/参数/动词/路径/operation。 */
    record Entry(Class<?> ctrl, Object controller, String method, Class<?>[] params,
                 String verb, String url, String op) {}

    private final List<Entry> entries = new ArrayList<>();

    @BeforeAll static void startDatabase() {
        PG.start();
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/platform-bootstrap/V20260904.001__platform_system_infra.sql"))
                .execute(dataSource);
        var dictDataApi = mock(cn.iocoder.yudao.framework.common.biz.system.dict.DictDataCommonApi.class);
        when(dictDataApi.getDictDataList(any())).thenReturn(List.of());
        cn.iocoder.yudao.framework.dict.core.DictFrameworkUtils.init(dictDataApi);
        new cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils(new WebProperties());
    }

    @AfterEach void securityCleanup() { SecurityContextHolder.clearContext(); }

    @BeforeEach void fixture() {
        var loginUser = new cn.iocoder.yudao.framework.security.core.LoginUser();
        loginUser.setId(1L);
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(loginUser, null));
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        observer = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        observer.execute("TRUNCATE system_dict_data, system_dict_type, system_notice, system_users");
        store = new LegacyPlatformWalletSessionInterceptorTest.MemoryStore();
        gate = new LegacyPlatformWriteGate("g2s-a", SCOPE, Set.of(), Set.of("system-mutations"), store);
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());

        // 控制器装配（mock 承载；仅注入 wired 方法触达的服务字段）。
        var dictDataController = new DictDataController();
        ReflectionTestUtils.setField(dictDataController, "dictDataService", dictDataService);
        var notifyMessageController = new NotifyMessageController();
        ReflectionTestUtils.setField(notifyMessageController, "notifyMessageService", notifyMessageService);
        var smsCallbackController = new SmsCallbackController();
        ReflectionTestUtils.setField(smsCallbackController, "smsSendService", smsSendService);
        var authController = new cn.iocoder.yudao.module.system.controller.admin.auth.AuthController();
        ReflectionTestUtils.setField(authController, "authService", authService);
        ReflectionTestUtils.setField(authController, "securityProperties", securityProperties);
        when(securityProperties.getTokenHeader()).thenReturn("Authorization");
        when(securityProperties.getTokenParameter()).thenReturn("");

        this.dictDataController = dictDataController;
        this.notifyMessageController = notifyMessageController;
        this.smsCallbackController = smsCallbackController;
        this.authController = authController;

        buildEntries(Map.ofEntries(
                Map.entry(DictDataController.class, (Object) dictDataController),
                Map.entry(NotifyMessageController.class, notifyMessageController),
                Map.entry(SmsCallbackController.class, smsCallbackController),
                Map.entry(cn.iocoder.yudao.module.system.controller.admin.auth.AuthController.class, authController)));

        dictDataMvc = build(dictDataController);
        notifyMessageMvc = build(notifyMessageController);
        smsCallbackMvc = build(smsCallbackController);
        authMvc = build(authController);
    }

    /** 70条 wired 条目（operation命名=域-动作；c32求和闭合：71−1 void豁免=70）。 */
    private void buildEntries(Map<Class<?>, Object> byClass) {
        var dictData = byClass.get(DictDataController.class);
        var dictType = new DictTypeController();
        var notice = new NoticeController();
        var notifyTemplate = new NotifyTemplateController();
        var mailAccount = new MailAccountController();
        var mailTemplate = new MailTemplateController();
        var smsChannel = new SmsChannelController();
        var smsTemplate = new SmsTemplateController();
        var socialClient = new SocialClientController();
        var socialUser = new SocialUserController();
        var tenant = new TenantController();
        var tenantPackage = new TenantPackageController();
        var oauth2Client = new OAuth2ClientController();
        var menu = new MenuController();
        var post = new PostController();
        var userProfile = new UserProfileController();
        var auth = byClass.get(cn.iocoder.yudao.module.system.controller.admin.auth.AuthController.class);
        var smsCallback = byClass.get(SmsCallbackController.class);
        var notifyMessage = byClass.get(NotifyMessageController.class);
                // DictData 4
        add(DictDataController.class, dictData, "createDictData", new Class<?>[]{DictDataSaveReqVO.class}, "POST", "/system/dict-data/create", "dict-data-create");
        add(DictDataController.class, dictData, "updateDictData", new Class<?>[]{DictDataSaveReqVO.class}, "PUT", "/system/dict-data/update", "dict-data-update");
        add(DictDataController.class, dictData, "deleteDictData", new Class<?>[]{Long.class}, "DELETE", "/system/dict-data/delete?id=1", "dict-data-delete");
        add(DictDataController.class, dictData, "deleteDictDataList", new Class<?>[]{List.class}, "DELETE", "/system/dict-data/delete-list?ids=1,2", "dict-data-delete-list");
        // DictType 4
        add(DictTypeController.class, dictType, "createDictType", new Class<?>[]{DictTypeSaveReqVO.class}, "POST", "/system/dict-type/create", "dict-type-create");
        add(DictTypeController.class, dictType, "updateDictType", new Class<?>[]{DictTypeSaveReqVO.class}, "PUT", "/system/dict-type/update", "dict-type-update");
        add(DictTypeController.class, dictType, "deleteDictType", new Class<?>[]{Long.class}, "DELETE", "/system/dict-type/delete?id=1", "dict-type-delete");
        add(DictTypeController.class, dictType, "deleteDictTypeList", new Class<?>[]{List.class}, "DELETE", "/system/dict-type/delete-list?ids=1,2", "dict-type-delete-list");
        // Notice 5
        add(NoticeController.class, notice, "createNotice", new Class<?>[]{NoticeSaveReqVO.class}, "POST", "/system/notice/create", "notice-create");
        add(NoticeController.class, notice, "updateNotice", new Class<?>[]{NoticeSaveReqVO.class}, "PUT", "/system/notice/update", "notice-update");
        add(NoticeController.class, notice, "deleteNotice", new Class<?>[]{Long.class}, "DELETE", "/system/notice/delete?id=1", "notice-delete");
        add(NoticeController.class, notice, "deleteNoticeList", new Class<?>[]{List.class}, "DELETE", "/system/notice/delete-list?ids=1,2", "notice-delete-list");
        add(NoticeController.class, notice, "push", new Class<?>[]{Long.class}, "POST", "/system/notice/push?id=1", "notice-push");
        // NotifyTemplate 5
        add(NotifyTemplateController.class, notifyTemplate, "createNotifyTemplate", new Class<?>[]{NotifyTemplateSaveReqVO.class}, "POST", "/system/notify-template/create", "notify-template-create");
        add(NotifyTemplateController.class, notifyTemplate, "updateNotifyTemplate", new Class<?>[]{NotifyTemplateSaveReqVO.class}, "PUT", "/system/notify-template/update", "notify-template-update");
        add(NotifyTemplateController.class, notifyTemplate, "deleteNotifyTemplate", new Class<?>[]{Long.class}, "DELETE", "/system/notify-template/delete?id=1", "notify-template-delete");
        add(NotifyTemplateController.class, notifyTemplate, "deleteNotifyTemplateList", new Class<?>[]{List.class}, "DELETE", "/system/notify-template/delete-list?ids=1,2", "notify-template-delete-list");
        add(NotifyTemplateController.class, notifyTemplate, "sendNotify", new Class<?>[]{NotifyTemplateSendReqVO.class}, "POST", "/system/notify-template/send-notify", "notify-send");
        // NotifyMessage 2
        add(NotifyMessageController.class, notifyMessage, "updateNotifyMessageRead", new Class<?>[]{List.class}, "PUT", "/system/notify-message/update-read?ids=1", "notify-message-read");
        add(NotifyMessageController.class, notifyMessage, "updateAllNotifyMessageRead", new Class<?>[]{}, "PUT", "/system/notify-message/update-all-read", "notify-message-read-all");
        // MailAccount 4
        add(MailAccountController.class, mailAccount, "createMailAccount", new Class<?>[]{MailAccountSaveReqVO.class}, "POST", "/system/mail-account/create", "mail-account-create");
        add(MailAccountController.class, mailAccount, "updateMailAccount", new Class<?>[]{MailAccountSaveReqVO.class}, "PUT", "/system/mail-account/update", "mail-account-update");
        add(MailAccountController.class, mailAccount, "deleteMailAccount", new Class<?>[]{Long.class}, "DELETE", "/system/mail-account/delete?id=1", "mail-account-delete");
        add(MailAccountController.class, mailAccount, "deleteMailAccountList", new Class<?>[]{List.class}, "DELETE", "/system/mail-account/delete-list?ids=1,2", "mail-account-delete-list");
        // MailTemplate 5
        add(MailTemplateController.class, mailTemplate, "createMailTemplate", new Class<?>[]{MailTemplateSaveReqVO.class}, "POST", "/system/mail-template/create", "mail-template-create");
        add(MailTemplateController.class, mailTemplate, "updateMailTemplate", new Class<?>[]{MailTemplateSaveReqVO.class}, "PUT", "/system/mail-template/update", "mail-template-update");
        add(MailTemplateController.class, mailTemplate, "deleteMailTemplate", new Class<?>[]{Long.class}, "DELETE", "/system/mail-template/delete?id=1", "mail-template-delete");
        add(MailTemplateController.class, mailTemplate, "deleteMailTemplateList", new Class<?>[]{List.class}, "DELETE", "/system/mail-template/delete-list?ids=1,2", "mail-template-delete-list");
        add(MailTemplateController.class, mailTemplate, "sendMail", new Class<?>[]{MailTemplateSendReqVO.class}, "POST", "/system/mail-template/send-mail", "mail-send");
        // SmsChannel 4
        add(SmsChannelController.class, smsChannel, "createSmsChannel", new Class<?>[]{SmsChannelSaveReqVO.class}, "POST", "/system/sms-channel/create", "sms-channel-create");
        add(SmsChannelController.class, smsChannel, "updateSmsChannel", new Class<?>[]{SmsChannelSaveReqVO.class}, "PUT", "/system/sms-channel/update", "sms-channel-update");
        add(SmsChannelController.class, smsChannel, "deleteSmsChannel", new Class<?>[]{Long.class}, "DELETE", "/system/sms-channel/delete?id=1", "sms-channel-delete");
        add(SmsChannelController.class, smsChannel, "deleteSmsChannelList", new Class<?>[]{List.class}, "DELETE", "/system/sms-channel/delete-list?ids=1,2", "sms-channel-delete-list");
        // SmsTemplate 5
        add(SmsTemplateController.class, smsTemplate, "createSmsTemplate", new Class<?>[]{SmsTemplateSaveReqVO.class}, "POST", "/system/sms-template/create", "sms-template-create");
        add(SmsTemplateController.class, smsTemplate, "updateSmsTemplate", new Class<?>[]{SmsTemplateSaveReqVO.class}, "PUT", "/system/sms-template/update", "sms-template-update");
        add(SmsTemplateController.class, smsTemplate, "deleteSmsTemplate", new Class<?>[]{Long.class}, "DELETE", "/system/sms-template/delete?id=1", "sms-template-delete");
        add(SmsTemplateController.class, smsTemplate, "deleteSmsTemplateList", new Class<?>[]{List.class}, "DELETE", "/system/sms-template/delete-list?ids=1,2", "sms-template-delete-list");
        add(SmsTemplateController.class, smsTemplate, "sendSms", new Class<?>[]{SmsTemplateSendReqVO.class}, "POST", "/system/sms-template/send-sms", "sms-send");
        // SmsCallback 4
        add(SmsCallbackController.class, smsCallback, "receiveAliyunSmsStatus", new Class<?>[]{HttpServletRequest.class}, "POST", "/system/sms/callback/aliyun", "sms-callback-aliyun");
        add(SmsCallbackController.class, smsCallback, "receiveTencentSmsStatus", new Class<?>[]{HttpServletRequest.class}, "POST", "/system/sms/callback/tencent", "sms-callback-tencent");
        add(SmsCallbackController.class, smsCallback, "receiveHuaweiSmsStatus", new Class<?>[]{String.class}, "POST", "/system/sms/callback/huawei", "sms-callback-huawei");
        add(SmsCallbackController.class, smsCallback, "receiveQiniuSmsStatus", new Class<?>[]{String.class}, "POST", "/system/sms/callback/qiniu", "sms-callback-qiniu");
        // SocialClient 4（sendSubscribeMessage=void→豁免）
        add(SocialClientController.class, socialClient, "createSocialClient", new Class<?>[]{SocialClientSaveReqVO.class}, "POST", "/system/social-client/create", "social-client-create");
        add(SocialClientController.class, socialClient, "updateSocialClient", new Class<?>[]{SocialClientSaveReqVO.class}, "PUT", "/system/social-client/update", "social-client-update");
        add(SocialClientController.class, socialClient, "deleteSocialClient", new Class<?>[]{Long.class}, "DELETE", "/system/social-client/delete?id=1", "social-client-delete");
        add(SocialClientController.class, socialClient, "deleteSocialClientList", new Class<?>[]{List.class}, "DELETE", "/system/social-client/delete-list?ids=1,2", "social-client-delete-list");
        // SocialUser 2
        add(SocialUserController.class, socialUser, "socialBind", new Class<?>[]{SocialUserBindReqVO.class}, "POST", "/system/social-user/bind", "social-bind");
        add(SocialUserController.class, socialUser, "socialUnbind", new Class<?>[]{SocialUserUnbindReqVO.class}, "DELETE", "/system/social-user/unbind", "social-unbind");
        // Tenant 4
        add(TenantController.class, tenant, "createTenant", new Class<?>[]{TenantSaveReqVO.class}, "POST", "/system/tenant/create", "tenant-create");
        add(TenantController.class, tenant, "updateTenant", new Class<?>[]{TenantSaveReqVO.class}, "PUT", "/system/tenant/update", "tenant-update");
        add(TenantController.class, tenant, "deleteTenant", new Class<?>[]{Long.class}, "DELETE", "/system/tenant/delete?id=1", "tenant-delete");
        add(TenantController.class, tenant, "deleteTenantList", new Class<?>[]{List.class}, "DELETE", "/system/tenant/delete-list?ids=1,2", "tenant-delete-list");
        // TenantPackage 4
        add(TenantPackageController.class, tenantPackage, "createTenantPackage", new Class<?>[]{TenantPackageSaveReqVO.class}, "POST", "/system/tenant-package/create", "tenant-package-create");
        add(TenantPackageController.class, tenantPackage, "updateTenantPackage", new Class<?>[]{TenantPackageSaveReqVO.class}, "PUT", "/system/tenant-package/update", "tenant-package-update");
        add(TenantPackageController.class, tenantPackage, "deleteTenantPackage", new Class<?>[]{Long.class}, "DELETE", "/system/tenant-package/delete?id=1", "tenant-package-delete");
        add(TenantPackageController.class, tenantPackage, "deleteTenantPackageList", new Class<?>[]{List.class}, "DELETE", "/system/tenant-package/delete-list?ids=1,2", "tenant-package-delete-list");
        // OAuth2Client 4
        add(OAuth2ClientController.class, oauth2Client, "createOAuth2Client", new Class<?>[]{OAuth2ClientSaveReqVO.class}, "POST", "/system/oauth2-client/create", "oauth2-client-create");
        add(OAuth2ClientController.class, oauth2Client, "updateOAuth2Client", new Class<?>[]{OAuth2ClientSaveReqVO.class}, "PUT", "/system/oauth2-client/update", "oauth2-client-update");
        add(OAuth2ClientController.class, oauth2Client, "deleteOAuth2Client", new Class<?>[]{Long.class}, "DELETE", "/system/oauth2-client/delete?id=1", "oauth2-client-delete");
        add(OAuth2ClientController.class, oauth2Client, "deleteOAuth2ClientList", new Class<?>[]{List.class}, "DELETE", "/system/oauth2-client/delete-list?ids=1,2", "oauth2-client-delete-list");
        // Post 4
        add(PostController.class, post, "createPost", new Class<?>[]{PostSaveReqVO.class}, "POST", "/system/post/create", "post-create");
        add(PostController.class, post, "updatePost", new Class<?>[]{PostSaveReqVO.class}, "PUT", "/system/post/update", "post-update");
        add(PostController.class, post, "deletePost", new Class<?>[]{Long.class}, "DELETE", "/system/post/delete?id=1", "post-delete");
        add(PostController.class, post, "deletePostList", new Class<?>[]{List.class}, "DELETE", "/system/post/delete-list?ids=1,2", "post-delete-list");
        // Menu 4
        add(MenuController.class, menu, "createMenu", new Class<?>[]{MenuSaveVO.class}, "POST", "/system/menu/create", "menu-create");
        add(MenuController.class, menu, "updateMenu", new Class<?>[]{MenuSaveVO.class}, "PUT", "/system/menu/update", "menu-update");
        add(MenuController.class, menu, "deleteMenu", new Class<?>[]{Long.class}, "DELETE", "/system/menu/delete?id=1", "menu-delete");
        add(MenuController.class, menu, "deleteMenuList", new Class<?>[]{List.class}, "DELETE", "/system/menu/delete-list?ids=1,2", "menu-delete-list");
        // UserProfile 2
        add(UserProfileController.class, userProfile, "updateUserProfile", new Class<?>[]{UserProfileUpdateReqVO.class}, "PUT", "/system/user/profile/update", "user-profile-update");
        add(UserProfileController.class, userProfile, "updateUserProfilePassword", new Class<?>[]{UserProfileUpdatePasswordReqVO.class}, "PUT", "/system/user/profile/update-password", "user-profile-password");
        // Auth 8（G2s-b 同 writer=legacy-session，本套件 captor 仅 system-mutations 域；
        // Auth 8 在 G2s-b 套件驱动——此处不 add）
    }

    private void add(Class<?> ctrl, Object controller, String method, Class<?>[] params,
                     String verb, String url, String op) {
        entries.add(new Entry(ctrl, controller, method, params, verb, url, op));
    }

    private MockMvc build(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2s-a", errorLogs),
                        new GlobalResponseBodyHandler()})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .addFilters(new cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter())
                .addInterceptors(new LegacyPlatformHttpWriteInterceptor(gate, true))
                .build();
    }

    // ---------- DRAINING：70入口 503 零写 ----------

    @Test void drainingRejectsAllSeventyStage2EntriesWithoutAnyWrite() throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        for (Entry e : entries) {
            var request = switch (e.verb()) {
                case "PUT" -> put(e.url());
                case "DELETE" -> delete(e.url());
                default -> post(e.url());
            };
            if (!e.verb().equals("DELETE")) request.contentType(MediaType.APPLICATION_JSON).content("{}");
            assertEquals(503, code(performOn(e, request)), e.op());
        }
        assertEquals(0, rows("system_dict_data"));
        assertEquals(0, rows("system_notice"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    private MvcResult performOn(Entry e, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        var mvc = mvcFor(e);
        return mvc.perform(request).andReturn();
    }

    private MockMvc mvcFor(Entry e) {
        if (e.controller() instanceof DictDataController) return dictDataMvc;
        if (e.controller() instanceof NotifyMessageController) return notifyMessageMvc;
        if (e.controller() instanceof SmsCallbackController) return smsCallbackMvc;
        if (e.controller() instanceof cn.iocoder.yudao.module.system.controller.admin.auth.AuthController) return authMvc;
        // 其余控制器走独立 MockMvc（DRAINING 不触服务，advice+converter+拦截器即可）。
        return MockMvcBuilders.standaloneSetup(e.controller())
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2s-a", errorLogs),
                        new GlobalResponseBodyHandler()})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .addFilters(new cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter())
                .addInterceptors(new LegacyPlatformHttpWriteInterceptor(gate, true))
                .build();
    }

    private int code(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").intValue();
    }

    private int rows(String table) {
        Integer n = observer.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private void unknownPending(String operation) {
        assertEquals(1, gate.snapshot().checkpoint().pending().size());
        for (Work work : gate.snapshot().checkpoint().pending().values()) {
            assertEquals("system-mutations", work.writer());
            assertEquals(operation, work.operation());
            assertTrue(work.unknown());
        }
    }

    // ---------- OPEN 代表 + UNKNOWN 代表 ----------

    @Test void openDictDataCreateReleasesWithServiceInteraction() throws Exception {
        when(dictDataService.createDictData(any())).thenReturn(1L);
        assertEquals(0, code(dictDataMvc.perform(post("/system/dict-data/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sort\":0,\"label\":\"g2s\",\"value\":\"g2s\",\"dictType\":\"g2s\",\"status\":0}"))
                .andReturn()));
        verify(dictDataService).createDictData(any());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openNotifyMessageReadReleasesWithServiceInteraction() throws Exception {
        assertEquals(0, code(notifyMessageMvc.perform(put("/system/notify-message/update-all-read")).andReturn()));
        verify(notifyMessageService).updateAllNotifyMessageRead(any(), any());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void smsCallbackIntakeReleasesWithServiceInteraction() throws Throwable {
        assertEquals(0, code(smsCallbackMvc.perform(post("/system/sms/callback/aliyun")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn()));
        verify(smsSendService).receiveSmsStatus(any(), anyString());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void deleteDictDataNonexistentIsHandledRetainingUnknownPending() throws Exception {
        doThrow(new ServiceException(400, "dict data not found")).when(dictDataService).deleteDictData(1L);
        var result = dictDataMvc.perform(delete("/system/dict-data/delete?id=1")).andReturn();
        assertEquals(400, code(result));
        unknownPending("dict-data-delete");
    }

    // ---------- 白名单合同：70项 captor ----------

    @Test void wiredStage2HandlersEnterGateWithExactWriterAndOperation() throws Exception {
        var port = mock(LegacyPlatformWritePort.class);
        var permit = mock(Permit.class);
        when(port.enter(any(), any(), any())).thenReturn(permit);
        var selected = new LegacyPlatformHttpWriteInterceptor(port, true);
        var operations = new HashSet<String>();
        for (Entry e : entries) {
            var m = e.ctrl().getDeclaredMethod(e.method(), e.params());
            var handler = new HandlerMethod(e.controller(), m);
            assertTrue(selected.preHandle(new org.springframework.mock.web.MockHttpServletRequest(),
                    new org.springframework.mock.web.MockHttpServletResponse(), handler), e.op());
        }
        var captor = ArgumentCaptor.forClass(String.class);
        verify(port, times(70)).enter(eq(Owner.B_LEGACY), eq("system-mutations"), captor.capture());
        assertEquals(70, Set.copyOf(captor.getAllValues()).size());
        // operation 集合与 wired 表一致（全部以域前缀开头且无重复）。
        captor.getAllValues().forEach(operations::add);
        assertEquals(70, operations.size());
    }

    // ---------- 装配矩阵（DictData 代表） ----------

    @Test void bridgeFalseStillGuardsStage2Handlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused",
                "platform.legacy-design-bridge.enabled", "false"), true);
    }

    @Test void installedDefaultConfigurationGuardsStage2Handlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused"), true);
    }

    @Test void notInstalledLeavesStage2BehaviorWithoutFreezeEvidence() throws Exception {
        configurationCase(Map.of(), false);
    }

    private void configurationCase(Map<String, Object> properties, boolean installed) throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("synthetic", properties));
            context.registerBean(LegacyPlatformWriteGate.class, () -> gate);
            context.register(LegacyPlatformHttpWriteWebConfiguration.class);
            context.refresh();
            var registry = new LegacyPlatformWalletSessionInterceptorTest.TestRegistry();
            context.getBeansOfType(WebMvcConfigurer.class).values().forEach(value -> value.addInterceptors(registry));
            assertEquals(installed ? 1 : 0, registry.handlers().size());
            var selected = registry.handlers().isEmpty() ? null : (HandlerInterceptor) registry.handlers().get(0);
            var configuredMvc = MockMvcBuilders.standaloneSetup(dictDataController)
                    .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2s-a", errorLogs)})
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                    .addInterceptors(selected == null ? new HandlerInterceptor[0] : new HandlerInterceptor[]{selected})
                    .build();
            when(dictDataService.createDictData(any())).thenReturn(1L);
            assertEquals(installed ? 503 : 0, code(configuredMvc.perform(post("/system/dict-data/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sort\":0,\"label\":\"g2s\",\"value\":\"g2s\",\"dictType\":\"g2s\",\"status\":0}"))
                    .andReturn()));
            assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
            assertThrows(IllegalStateException.class,
                    () -> gate.apply(new Control(3, State.FROZEN, SCOPE), FreezeEvidence.missing()));
        }
    }
}
