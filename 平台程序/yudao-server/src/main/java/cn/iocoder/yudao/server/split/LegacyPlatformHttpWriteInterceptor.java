package cn.iocoder.yudao.server.split;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.franchise.crm.controller.admin.LeadController;
import cn.iocoder.yudao.module.franchise.org.controller.admin.WalletSessionController;
import cn.iocoder.yudao.module.identity.controller.admin.AccessCodeAdminController;
import cn.iocoder.yudao.module.identity.controller.admin.AccessCodeBatchAdminController;
import cn.iocoder.yudao.module.identity.controller.admin.AccessGrantAdminController;
import cn.iocoder.yudao.module.identity.controller.admin.AccountAdminController;
import cn.iocoder.yudao.module.identity.controller.admin.ActivationRequestAdminController;
import cn.iocoder.yudao.module.identity.controller.admin.vo.AdminAccessCodeBatchCreateReqVO;
import cn.iocoder.yudao.module.identity.controller.admin.vo.AdminAccessCodeBindingUpdateReqVO;
import cn.iocoder.yudao.module.identity.controller.admin.vo.AdminActivationDecisionReqVO;
import cn.iocoder.yudao.module.identity.controller.app.AppAccessCodeRedemptionController;
import cn.iocoder.yudao.module.identity.controller.app.AppActivationRequestController;
import cn.iocoder.yudao.module.identity.controller.app.AppAuthController;
import cn.iocoder.yudao.module.identity.controller.app.AppPrivacyController;
import cn.iocoder.yudao.module.identity.controller.app.AppProfileController;
import cn.iocoder.yudao.module.identity.controller.app.vo.AppAccessCodeRedeemReqVO;
import cn.iocoder.yudao.module.identity.controller.app.vo.AppActivationRequestSubmitReqVO;
import cn.iocoder.yudao.module.identity.controller.app.vo.AppAuthLoginReqVO;
import cn.iocoder.yudao.module.identity.controller.app.vo.AppProfileBindingUpdateReqVO;
import cn.iocoder.yudao.module.identity.controller.app.vo.AppProfileUpdateReqVO;
import cn.iocoder.yudao.module.commerce.controller.admin.PointAdminController;
import cn.iocoder.yudao.module.commerce.controller.admin.GenerationPriceRuleAdminController;
import cn.iocoder.yudao.module.commerce.controller.admin.UsagePriceRuleAdminController;
import cn.iocoder.yudao.module.commerce.controller.admin.RechargeOrderAdminController;
import cn.iocoder.yudao.module.commerce.controller.admin.RechargePlanAdminController;
import cn.iocoder.yudao.module.commerce.controller.app.AppPointController;
import cn.iocoder.yudao.module.commerce.controller.app.AppRechargeOrderController;
import cn.iocoder.yudao.module.commerce.controller.notify.WechatPayNotifyController;
import cn.iocoder.yudao.module.commerce.controller.notify.XpayNotifyController;
import cn.iocoder.yudao.module.system.controller.admin.user.UserController;
import cn.iocoder.yudao.module.system.controller.admin.permission.RoleController;
import cn.iocoder.yudao.module.system.controller.admin.permission.PermissionController;
import cn.iocoder.yudao.module.system.controller.admin.auth.AuthController;
import cn.iocoder.yudao.module.system.controller.admin.dict.DictDataController;
import cn.iocoder.yudao.module.system.controller.admin.dict.DictTypeController;
import cn.iocoder.yudao.module.system.controller.admin.mail.MailAccountController;
import cn.iocoder.yudao.module.system.controller.admin.mail.MailTemplateController;
import cn.iocoder.yudao.module.system.controller.admin.notice.NoticeController;
import cn.iocoder.yudao.module.system.controller.admin.notify.NotifyMessageController;
import cn.iocoder.yudao.module.system.controller.admin.notify.NotifyTemplateController;
import cn.iocoder.yudao.module.system.controller.admin.oauth2.OAuth2ClientController;
import cn.iocoder.yudao.module.system.controller.admin.oauth2.OAuth2OpenController;
import cn.iocoder.yudao.module.system.controller.admin.oauth2.OAuth2TokenController;
import cn.iocoder.yudao.module.system.controller.admin.oauth2.OAuth2UserController;
import cn.iocoder.yudao.module.system.controller.admin.dept.PostController;
import cn.iocoder.yudao.module.system.controller.admin.permission.MenuController;
import cn.iocoder.yudao.module.system.controller.admin.sms.SmsCallbackController;
import cn.iocoder.yudao.module.system.controller.admin.sms.SmsChannelController;
import cn.iocoder.yudao.module.system.controller.admin.sms.SmsTemplateController;
import cn.iocoder.yudao.module.system.controller.admin.social.SocialClientController;
import cn.iocoder.yudao.module.system.controller.admin.social.SocialUserController;
import cn.iocoder.yudao.module.system.controller.admin.tenant.TenantController;
import cn.iocoder.yudao.module.system.controller.admin.tenant.TenantPackageController;
import cn.iocoder.yudao.module.system.controller.admin.user.UserProfileController;
import cn.iocoder.yudao.module.system.controller.admin.auth.vo.AuthLoginReqVO;
import cn.iocoder.yudao.module.system.controller.admin.auth.vo.AuthRegisterReqVO;
import cn.iocoder.yudao.module.system.controller.admin.auth.vo.AuthResetPasswordReqVO;
import cn.iocoder.yudao.module.system.controller.admin.auth.vo.AuthSmsLoginReqVO;
import cn.iocoder.yudao.module.system.controller.admin.auth.vo.AuthSmsSendReqVO;
import cn.iocoder.yudao.module.system.controller.admin.auth.vo.AuthSocialLoginReqVO;
import cn.iocoder.yudao.module.system.controller.admin.dept.vo.post.PostSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.dict.vo.data.DictDataSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.dict.vo.type.DictTypeSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.mail.vo.account.MailAccountSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.mail.vo.template.MailTemplateSendReqVO;
import cn.iocoder.yudao.module.system.controller.admin.mail.vo.template.MailTemplateSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.notice.vo.NoticeSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.notify.vo.template.NotifyTemplateSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.notify.vo.template.NotifyTemplateSendReqVO;
import cn.iocoder.yudao.module.system.controller.admin.oauth2.vo.client.OAuth2ClientSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.oauth2.vo.user.OAuth2UserUpdateReqVO;
import cn.iocoder.yudao.module.system.controller.admin.permission.vo.menu.MenuSaveVO;
import cn.iocoder.yudao.module.system.controller.admin.sms.vo.channel.SmsChannelSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.sms.vo.template.SmsTemplateSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.sms.vo.template.SmsTemplateSendReqVO;
import cn.iocoder.yudao.module.system.controller.admin.social.vo.client.SocialClientSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.social.vo.user.SocialUserBindReqVO;
import cn.iocoder.yudao.module.system.controller.admin.social.vo.user.SocialUserUnbindReqVO;
import cn.iocoder.yudao.module.system.controller.admin.tenant.vo.packages.TenantPackageSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.tenant.vo.tenant.TenantSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.user.vo.profile.UserProfileUpdatePasswordReqVO;
import cn.iocoder.yudao.module.system.controller.admin.user.vo.profile.UserProfileUpdateReqVO;
import cn.iocoder.yudao.module.system.controller.admin.dept.DeptController;
import cn.iocoder.yudao.module.system.controller.admin.user.vo.user.UserSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.user.vo.user.UserUpdatePasswordReqVO;
import cn.iocoder.yudao.module.system.controller.admin.user.vo.user.UserUpdateStatusReqVO;
import cn.iocoder.yudao.module.system.controller.admin.permission.vo.role.RoleSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.permission.vo.permission.PermissionAssignRoleMenuReqVO;
import cn.iocoder.yudao.module.system.controller.admin.permission.vo.permission.PermissionAssignRoleDataScopeReqVO;
import cn.iocoder.yudao.module.system.controller.admin.permission.vo.permission.PermissionAssignUserRoleReqVO;
import cn.iocoder.yudao.module.system.controller.admin.dept.vo.dept.DeptSaveReqVO;
import cn.iocoder.yudao.module.platform.authz.controller.admin.OrgOnboardController;
import cn.iocoder.yudao.module.platform.authz.controller.admin.StaffOrgAdminController;
import cn.iocoder.yudao.module.platform.identity.controller.admin.PlatformAuthController;
import cn.iocoder.yudao.module.platform.identity.controller.admin.vo.SmsCaptchaSendReqVO;
import cn.iocoder.yudao.module.platform.identity.controller.admin.vo.SmsLoginReqVO;
import cn.iocoder.yudao.module.platform.identity.controller.admin.vo.WechatLoginReqVO;
import cn.iocoder.yudao.module.platform.org.controller.admin.StaffProfileController;
import cn.iocoder.yudao.module.platform.workflow.controller.admin.PlatformMessageController;
import cn.iocoder.yudao.module.aiorchestration.controller.app.AppAiJobController;
import cn.iocoder.yudao.module.aiorchestration.controller.internal.AiJobInternalController;
import cn.iocoder.yudao.module.aiorchestration.controller.internal.vo.InternalClaimReqVO;
import cn.iocoder.yudao.module.franchise.content.controller.admin.ContentScriptController;
import cn.iocoder.yudao.module.franchise.content.controller.admin.ContentTaskController;
import cn.iocoder.yudao.module.design.controller.admin.ExportAuditAdminController;
import cn.iocoder.yudao.module.franchise.content.controller.admin.ContentDraftController;
import cn.iocoder.yudao.module.franchise.content.controller.admin.ContentPersonaController;
import cn.iocoder.yudao.module.franchise.content.controller.admin.ContentPublicationController;
import cn.iocoder.yudao.module.franchise.content.controller.admin.ContentTaskController;
import cn.iocoder.yudao.module.franchise.content.controller.admin.ContentAssetController;
import cn.iocoder.yudao.module.franchise.crm.controller.admin.CustomerController;
import cn.iocoder.yudao.module.franchise.crm.controller.admin.LeadController;
import cn.iocoder.yudao.server.controller.admin.AdminDataDeleteController;
import cn.iocoder.yudao.server.controller.admin.PrivacyAdminController;
import cn.iocoder.yudao.module.identity.controller.app.AppDataSubjectRequestController;
import cn.iocoder.yudao.server.controller.app.AppPrivacyLifecycleController;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;

/** Only the explicitly listed synchronous legacy B methods; this is not complete writer coverage. */
public final class LegacyPlatformHttpWriteInterceptor implements AsyncHandlerInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(LegacyPlatformHttpWriteInterceptor.class);
    private static final String ADMISSION = LegacyPlatformHttpWriteInterceptor.class.getName() + ".admission";
    private final LegacyPlatformWritePort gate;
    private final Map<Method, WriteTarget> targets;

    public LegacyPlatformHttpWriteInterceptor(LegacyPlatformWritePort gate, boolean legacyBridgeEnabled) {
        this.gate = java.util.Objects.requireNonNull(gate);
        var methods = new HashMap<Method, WriteTarget>();
        methods.put(exactMethod(LeadController.class, "invalidate", long.class, LeadController.InvalidReq.class, String.class),
                new WriteTarget("franchise-http", "lead-invalidate", "旧平台客资作废入口暂不可用"));
        // LeadService.link 同事务校验 lead 经办权与同手机号原客户管理范围，CAS 与审计失败整笔回滚。
        methods.put(exactMethod(LeadController.class, "link", long.class, LeadController.RevisionReq.class, String.class),
                new WriteTarget("franchise-http", "lead-link", "旧平台客资关联入口暂不可用"));
        // 类别1收尾（G2r合同v0经c31五点批准，19入口——计数自检求和复核：内容14含T2+CRM5）：
        // 内容运营面14（G2n登记承接；T2方案b落地与D系同表域；A1-A3=AssetAccessPort资产ticket闭包承接）+
        // CRM客资/客户面5（G2b/G2c既定暂缓项承接+§11.11候选表漏列项saveScheme/transfer更正登记）。
        // capture=多表事务链（三分支+DuplicateKeyException→STATE_VERSION_CONFLICT并发防线——
        // uk_fr_customer_phone已在源迁移802，条件=并发红测证明，第二请求经真实MVC并行发起）。
        // transfer=责任转交团队变更+revision推进（身份敏感写面"接而不收比不接更差"c31裁决纳入）。
        // VO宿主：CaptureCommand=LeadService嵌套；FollowCommand/SchemeCommand=CustomerService嵌套；
        // UpdateReq/TransferReq=CustomerController嵌套record（源码核出）。
        methods.put(exactMethod(ContentTaskController.class, "saveWork", Long.class, Map.class),
                new WriteTarget("franchise-http", "content-save-work", "旧平台任务作品保存入口暂不可用"));
        methods.put(exactMethod(ContentDraftController.class, "updateDraftStatus", Long.class, Map.class),
                new WriteTarget("franchise-http", "content-draft-status", "旧平台草稿状态入口暂不可用"));
        methods.put(exactMethod(ContentDraftController.class, "saveVideoDraft", Map.class),
                new WriteTarget("franchise-http", "video-draft-save", "旧平台视频草稿入口暂不可用"));
        methods.put(exactMethod(ContentDraftController.class, "completeChapter", Map.class),
                new WriteTarget("franchise-http", "course-progress", "旧平台课程进度入口暂不可用"));
        methods.put(exactMethod(ContentPublicationController.class, "create", Map.class, String.class),
                new WriteTarget("franchise-http", "publication-create", "旧平台发布创建入口暂不可用"));
        methods.put(exactMethod(ContentPublicationController.class, "verify", Long.class),
                new WriteTarget("franchise-http", "publication-verify", "旧平台发布验证入口暂不可用"));
        methods.put(exactMethod(ContentPublicationController.class, "link", Long.class, Map.class),
                new WriteTarget("franchise-http", "publication-link", "旧平台发布链接入口暂不可用"));
        methods.put(exactMethod(ContentPublicationController.class, "fail", Long.class, Map.class),
                new WriteTarget("franchise-http", "publication-fail", "旧平台发布失败入口暂不可用"));
        methods.put(exactMethod(ContentPersonaController.class, "create", Map.class),
                new WriteTarget("franchise-http", "persona-create", "旧平台人设创建入口暂不可用"));
        methods.put(exactMethod(ContentPersonaController.class, "update", Long.class, Map.class),
                new WriteTarget("franchise-http", "persona-update", "旧平台人设更新入口暂不可用"));
        methods.put(exactMethod(ContentPersonaController.class, "delete", Long.class),
                new WriteTarget("franchise-http", "persona-delete", "旧平台人设删除入口暂不可用"));
        methods.put(exactMethod(ContentAssetController.class, "uploadTicket", Map.class),
                new WriteTarget("franchise-http", "asset-upload-ticket", "旧平台素材上传票据入口暂不可用"));
        methods.put(exactMethod(ContentAssetController.class, "complete", Long.class),
                new WriteTarget("franchise-http", "asset-upload-complete", "旧平台素材上传完成入口暂不可用"));
        methods.put(exactMethod(ContentAssetController.class, "downloadTicket", Long.class),
                new WriteTarget("franchise-http", "asset-download-ticket", "旧平台素材下载票据入口暂不可用"));
        methods.put(exactMethod(LeadController.class, "capture", cn.iocoder.yudao.module.franchise.crm.LeadService.CaptureCommand.class, String.class),
                new WriteTarget("franchise-http", "lead-capture", "旧平台客资登记入口暂不可用"));
        methods.put(exactMethod(CustomerController.class, "update", long.class, CustomerController.UpdateReq.class, String.class),
                new WriteTarget("franchise-http", "customer-update", "旧平台客户修改入口暂不可用"));
        methods.put(exactMethod(CustomerController.class, "follow", long.class, cn.iocoder.yudao.module.franchise.crm.CustomerService.FollowCommand.class, String.class),
                new WriteTarget("franchise-http", "customer-follow", "旧平台客户跟进入口暂不可用"));
        methods.put(exactMethod(CustomerController.class, "saveScheme", long.class, cn.iocoder.yudao.module.franchise.crm.CustomerService.SchemeCommand.class, String.class),
                new WriteTarget("franchise-http", "customer-scheme", "旧平台客户方案入口暂不可用"));
        methods.put(exactMethod(CustomerController.class, "transfer", long.class, CustomerController.TransferReq.class, String.class),
                new WriteTarget("franchise-http", "customer-transfer", "旧平台客户转交入口暂不可用"));
        // 旧identity会话三入口（G2d合同v1，c31已批）：wechat-login/token-refresh/logout 是
        // UserSessionService.issue/refresh/revokeByToken 的全部HTTP直达入口；validateAccessToken 只读不收口。
        methods.put(exactMethod(AppAuthController.class, "refresh", Map.class),
                new WriteTarget("legacy-session", "session-refresh", "旧平台会话刷新入口暂不可用"));
        methods.put(exactMethod(AppAuthController.class, "wechatLogin", AppAuthLoginReqVO.class),
                new WriteTarget("legacy-session", "session-issue", "旧平台会话签发入口暂不可用"));
        methods.put(exactMethod(AppAuthController.class, "logout", String.class),
                new WriteTarget("legacy-session", "session-revoke", "旧平台会话吊销入口暂不可用"));
        // 旧identity供给链15入口（G2e合同v1 §1/§2，c31已批）：账号/grant/授权码供给的全部HTTP直达写入口；
        // writer=identity-provision（G1 REQUIRED_WRITERS 预注册）。acceptConsents显式NOT_WIRED（合同§5）。
        methods.put(exactMethod(AppAccessCodeRedemptionController.class, "redeem", AppAccessCodeRedeemReqVO.class, String.class),
                new WriteTarget("identity-provision", "redeem-access-code", "旧平台授权码兑换入口暂不可用"));
        methods.put(exactMethod(AppActivationRequestController.class, "submit", AppActivationRequestSubmitReqVO.class, String.class),
                new WriteTarget("identity-provision", "submit-activation", "旧平台开通申请入口暂不可用"));
        methods.put(exactMethod(AppProfileController.class, "updateBinding", AppProfileBindingUpdateReqVO.class, String.class),
                new WriteTarget("identity-provision", "update-binding", "旧平台绑定补录入口暂不可用"));
        methods.put(exactMethod(AppProfileController.class, "updatePreferences", Map.class, String.class),
                new WriteTarget("identity-provision", "update-preferences", "旧平台偏好修改入口暂不可用"));
        methods.put(exactMethod(AppProfileController.class, "updateProfile", AppProfileUpdateReqVO.class, String.class),
                new WriteTarget("identity-provision", "update-profile", "旧平台资料修改入口暂不可用"));
        methods.put(exactMethod(AccessCodeAdminController.class, "disableAccessCode", Long.class, Map.class),
                new WriteTarget("identity-provision", "disable-access-code", "旧平台授权码停用入口暂不可用"));
        methods.put(exactMethod(AccessCodeAdminController.class, "copyAccessCode", Long.class),
                new WriteTarget("identity-provision", "copy-access-code", "旧平台授权码明文复制入口暂不可用"));
        methods.put(exactMethod(AccessCodeAdminController.class, "updateBinding", Long.class, AdminAccessCodeBindingUpdateReqVO.class),
                new WriteTarget("identity-provision", "admin-update-binding", "旧平台授权码绑定修正入口暂不可用"));
        methods.put(exactMethod(AccessCodeAdminController.class, "deleteAccessCode", Long.class),
                new WriteTarget("identity-provision", "delete-access-code", "旧平台授权码删除入口暂不可用"));
        methods.put(exactMethod(AccessCodeBatchAdminController.class, "createBatch", AdminAccessCodeBatchCreateReqVO.class),
                new WriteTarget("identity-provision", "create-code-batch", "旧平台授权码批次创建入口暂不可用"));
        methods.put(exactMethod(AccessCodeBatchAdminController.class, "createDeliveryTicket", Long.class),
                new WriteTarget("identity-provision", "create-delivery-ticket", "旧平台交付票据入口暂不可用"));
        methods.put(exactMethod(AccessCodeBatchAdminController.class, "exportByTicket", Long.class, Map.class),
                new WriteTarget("identity-provision", "export-by-ticket", "旧平台凭票导出入口暂不可用"));
        methods.put(exactMethod(AccessGrantAdminController.class, "revokeAccessGrant", Long.class),
                new WriteTarget("identity-provision", "revoke-grant", "旧平台授权撤销入口暂不可用"));
        methods.put(exactMethod(AccountAdminController.class, "disableAccount", String.class),
                new WriteTarget("identity-provision", "disable-account", "旧平台账号停用入口暂不可用"));
        methods.put(exactMethod(ActivationRequestAdminController.class, "decide", Long.class, AdminActivationDecisionReqVO.class),
                new WriteTarget("identity-provision", "decide-activation", "旧平台开通审批入口暂不可用"));
        // 旧commerce钱包3入口（G2f合同§1/§2，c31已批，裁决：写GET旁路503为唯一与既有判据一致的选择）。
        // getPointAccount 经 ensureAccount 对首访用户 INSERT design_point_account（旁路已真实复现）；
        // 人工调点两入口为资金供给；point-ledger GET 纯读不收口。定价规则/充值回调登记类别8或后续批。
        methods.put(exactMethod(AppPointController.class, "getPointAccount", String.class),
                new WriteTarget("commerce-wallet", "ensure-point-account", "旧平台点数账户入口暂不可用"));
        methods.put(exactMethod(PointAdminController.class, "createAdjustment", Map.class),
                new WriteTarget("commerce-wallet", "manual-point-adjustment", "旧平台人工调点入口暂不可用"));
        methods.put(exactMethod(PointAdminController.class, "reviewAdjustment", String.class, Map.class),
                new WriteTarget("commerce-wallet", "manual-adjustment-review", "旧平台调点复核入口暂不可用"));
        // 定价规则两控制器7入口（G2f+增补合同v0.1，c31批准）：类别4后续批扩展，coverage登记已落
        // writer-coverage.json（接线前FROZEN硬阻断已随本批解除7入口；价格规则GET与充值链仍不属本批）。
        methods.put(exactMethod(GenerationPriceRuleAdminController.class, "createRule", GenerationPriceRuleAdminController.CreateRuleRequest.class),
                new WriteTarget("commerce-wallet", "generation-price-create", "旧平台出图计价规则入口暂不可用"));
        methods.put(exactMethod(GenerationPriceRuleAdminController.class, "retireRule", String.class, Map.class),
                new WriteTarget("commerce-wallet", "generation-price-retire", "旧平台出图计价停用入口暂不可用"));
        methods.put(exactMethod(GenerationPriceRuleAdminController.class, "deleteRule", String.class),
                new WriteTarget("commerce-wallet", "generation-price-delete", "旧平台出图计价删除入口暂不可用"));
        methods.put(exactMethod(UsagePriceRuleAdminController.class, "create", UsagePriceRuleAdminController.CreateRule.class),
                new WriteTarget("commerce-wallet", "usage-price-create", "旧平台用量价格入口暂不可用"));
        methods.put(exactMethod(UsagePriceRuleAdminController.class, "retire", long.class, Map.class),
                new WriteTarget("commerce-wallet", "usage-price-retire", "旧平台用量价格停用入口暂不可用"));
        methods.put(exactMethod(UsagePriceRuleAdminController.class, "delete", long.class),
                new WriteTarget("commerce-wallet", "usage-price-delete", "旧平台用量价格删除入口暂不可用"));
        methods.put(exactMethod(UsagePriceRuleAdminController.class, "reconcilePrompt", String.class, UsagePriceRuleAdminController.PromptDecision.class),
                new WriteTarget("commerce-wallet", "prompt-reconcile", "旧平台prompt对账入口暂不可用"));
        // 类别8 commerce-payment 9入口（G2h合同v0.1经c31判据纠正批准）：充值链+渠道回调+对账+计划配置。
        // P2/P3/P4 渠道回调为 CHANNEL_ACK 证据模式（返回ResponseEntity非CommonResult）；xpay三入口
        // 全路径无UNKNOWN（catch(RuntimeException)兜底无逃逸面），补偿信号=渠道退避重试+inbox扫描。
        // P5-P9 管理面 503 代价：DRAINING窗口无主动对账补偿，切流计划已登记（解冻+重推+人工）。
        // 新B platform-wallet（新owner已验收实现）不在停写面。
        methods.put(exactMethod(AppRechargeOrderController.class, "createOrder", String.class, String.class, String.class, String.class),
                new WriteTarget("commerce-payment", "recharge-order-create", "旧平台充值建单入口暂不可用"));
        methods.put(exactAckMethod(WechatPayNotifyController.class, "handlePaymentNotify", HttpServletRequest.class),
                new WriteTarget("commerce-payment", "payment-notify", "旧平台支付通知入口暂不可用", Evidence.CHANNEL_ACK));
        methods.put(exactAckMethod(WechatPayNotifyController.class, "handleRefundNotify", HttpServletRequest.class),
                new WriteTarget("commerce-payment", "refund-notify", "旧平台退款通知入口暂不可用", Evidence.CHANNEL_ACK));
        methods.put(exactAckMethod(XpayNotifyController.class, "handlePush", HttpServletRequest.class),
                new WriteTarget("commerce-payment", "xpay-push", "旧平台虚拟支付推送入口暂不可用", Evidence.CHANNEL_ACK));
        methods.put(exactMethod(RechargeOrderAdminController.class, "createRefundRequest", String.class, Map.class),
                new WriteTarget("commerce-payment", "refund-request", "旧平台退款受理入口暂不可用"));
        methods.put(exactMethod(RechargeOrderAdminController.class, "reconcileOrder", String.class),
                new WriteTarget("commerce-payment", "order-reconcile", "旧平台主动查单入口暂不可用"));
        methods.put(exactMethod(RechargeOrderAdminController.class, "reconciliationAction", String.class, Map.class),
                new WriteTarget("commerce-payment", "reconciliation-action", "旧平台对账命令入口暂不可用"));
        methods.put(exactMethod(RechargePlanAdminController.class, "createPlan", Map.class),
                new WriteTarget("commerce-payment", "recharge-plan-create", "旧平台充值计划创建入口暂不可用"));
        methods.put(exactMethod(RechargePlanAdminController.class, "updatePlan", String.class, Map.class),
                new WriteTarget("commerce-payment", "recharge-plan-update", "旧平台充值计划编辑入口暂不可用"));
        // 类别2/3残余快批（G2i合同v0，c31批准）：隐私协议同意事实（identity域合规写面）。
        // writer=identity-provision为分派归属，非供给链语义扩展；隐私生命周期/擦除面仍归类别12。
        methods.put(exactMethod(AppPrivacyController.class, "acceptConsents", String.class),
                new WriteTarget("identity-provision", "privacy-consent-accept", "旧平台协议同意入口暂不可用"));
        // 类别5 system-mutations 18入口（G2k合同v0.1，c31批准18项核证）：身份关系变更=迁移一致性本体。
        // 共享代码非共享数据：system模块为新旧共享基础设施，门禁进程本地，新B写自身库不在停写面（主线裁决）。
        // 阶段二运营配置面30-40项与admin AuthController显式NOT_WIRED，FROZEN硬阻断登记在案。

        // G2s 终批（c32七点方向全批+必改落实）：阶段二运营配置面70入口（system-mutations 18→88，c32求和闭合=106=G2k 18+新增88）+admin AuthController 8+OAuth2开放/令牌面6（legacy-session 3→17）。
        // 豁免3项=登记性写源（Captcha get/check×2 Redis-only消费方全gate+SocialClient sendSubscribeMessage void返回）；check-token=纯读不收口（c32源码级核验）。
        // 存量披露：SocialClientServiceImpl（+19/-5，WxMpService可选依赖化）=B launcher lane早期既有变更（e1666eab包内已含），非本批生产diff——冻结json列为前置已存项。
        // A6 sendSmsCode实写system_sms_code INSERT+sms log（c32更正，503处置不变且更强）。
        methods.put(exactMethod(DictDataController.class, "createDictData", DictDataSaveReqVO.class),
                new WriteTarget("system-mutations", "dict-data-create", "旧平台字典数据创建入口暂不可用"));
        methods.put(exactMethod(DictDataController.class, "updateDictData", DictDataSaveReqVO.class),
                new WriteTarget("system-mutations", "dict-data-update", "旧平台字典数据修改入口暂不可用"));
        methods.put(exactMethod(DictDataController.class, "deleteDictData", Long.class),
                new WriteTarget("system-mutations", "dict-data-delete", "旧平台字典数据删除入口暂不可用"));
        methods.put(exactMethod(DictDataController.class, "deleteDictDataList", List.class),
                new WriteTarget("system-mutations", "dict-data-delete-list", "旧平台字典数据批量删除入口暂不可用"));
        methods.put(exactMethod(DictTypeController.class, "createDictType", DictTypeSaveReqVO.class),
                new WriteTarget("system-mutations", "dict-type-create", "旧平台字典类型创建入口暂不可用"));
        methods.put(exactMethod(DictTypeController.class, "updateDictType", DictTypeSaveReqVO.class),
                new WriteTarget("system-mutations", "dict-type-update", "旧平台字典类型修改入口暂不可用"));
        methods.put(exactMethod(DictTypeController.class, "deleteDictType", Long.class),
                new WriteTarget("system-mutations", "dict-type-delete", "旧平台字典类型删除入口暂不可用"));
        methods.put(exactMethod(DictTypeController.class, "deleteDictTypeList", List.class),
                new WriteTarget("system-mutations", "dict-type-delete-list", "旧平台字典类型批量删除入口暂不可用"));
        methods.put(exactMethod(NoticeController.class, "createNotice", NoticeSaveReqVO.class),
                new WriteTarget("system-mutations", "notice-create", "旧平台公告创建入口暂不可用"));
        methods.put(exactMethod(NoticeController.class, "updateNotice", NoticeSaveReqVO.class),
                new WriteTarget("system-mutations", "notice-update", "旧平台公告修改入口暂不可用"));
        methods.put(exactMethod(NoticeController.class, "deleteNotice", Long.class),
                new WriteTarget("system-mutations", "notice-delete", "旧平台公告删除入口暂不可用"));
        methods.put(exactMethod(NoticeController.class, "deleteNoticeList", List.class),
                new WriteTarget("system-mutations", "notice-delete-list", "旧平台公告批量删除入口暂不可用"));
        methods.put(exactMethod(NoticeController.class, "push", Long.class),
                new WriteTarget("system-mutations", "notice-push", "旧平台公告推送入口暂不可用"));
        methods.put(exactMethod(NotifyTemplateController.class, "createNotifyTemplate", NotifyTemplateSaveReqVO.class),
                new WriteTarget("system-mutations", "notify-template-create", "旧平台通知模板创建入口暂不可用"));
        methods.put(exactMethod(NotifyTemplateController.class, "updateNotifyTemplate", NotifyTemplateSaveReqVO.class),
                new WriteTarget("system-mutations", "notify-template-update", "旧平台通知模板修改入口暂不可用"));
        methods.put(exactMethod(NotifyTemplateController.class, "deleteNotifyTemplate", Long.class),
                new WriteTarget("system-mutations", "notify-template-delete", "旧平台通知模板删除入口暂不可用"));
        methods.put(exactMethod(NotifyTemplateController.class, "deleteNotifyTemplateList", List.class),
                new WriteTarget("system-mutations", "notify-template-delete-list", "旧平台通知模板批量删除入口暂不可用"));
        methods.put(exactMethod(NotifyTemplateController.class, "sendNotify", NotifyTemplateSendReqVO.class),
                new WriteTarget("system-mutations", "notify-send", "旧平台通知发送入口暂不可用"));
        methods.put(exactMethod(NotifyMessageController.class, "updateNotifyMessageRead", List.class),
                new WriteTarget("system-mutations", "notify-message-read", "旧平台通知已读入口暂不可用"));
        methods.put(exactMethod(NotifyMessageController.class, "updateAllNotifyMessageRead"),
                new WriteTarget("system-mutations", "notify-message-read-all", "旧平台通知全部已读入口暂不可用"));
        methods.put(exactMethod(MailAccountController.class, "createMailAccount", MailAccountSaveReqVO.class),
                new WriteTarget("system-mutations", "mail-account-create", "旧平台邮箱账号创建入口暂不可用"));
        methods.put(exactMethod(MailAccountController.class, "updateMailAccount", MailAccountSaveReqVO.class),
                new WriteTarget("system-mutations", "mail-account-update", "旧平台邮箱账号修改入口暂不可用"));
        methods.put(exactMethod(MailAccountController.class, "deleteMailAccount", Long.class),
                new WriteTarget("system-mutations", "mail-account-delete", "旧平台邮箱账号删除入口暂不可用"));
        methods.put(exactMethod(MailAccountController.class, "deleteMailAccountList", List.class),
                new WriteTarget("system-mutations", "mail-account-delete-list", "旧平台邮箱账号批量删除入口暂不可用"));
        methods.put(exactMethod(MailTemplateController.class, "createMailTemplate", MailTemplateSaveReqVO.class),
                new WriteTarget("system-mutations", "mail-template-create", "旧平台邮件模板创建入口暂不可用"));
        methods.put(exactMethod(MailTemplateController.class, "updateMailTemplate", MailTemplateSaveReqVO.class),
                new WriteTarget("system-mutations", "mail-template-update", "旧平台邮件模板修改入口暂不可用"));
        methods.put(exactMethod(MailTemplateController.class, "deleteMailTemplate", Long.class),
                new WriteTarget("system-mutations", "mail-template-delete", "旧平台邮件模板删除入口暂不可用"));
        methods.put(exactMethod(MailTemplateController.class, "deleteMailTemplateList", List.class),
                new WriteTarget("system-mutations", "mail-template-delete-list", "旧平台邮件模板批量删除入口暂不可用"));
        methods.put(exactMethod(MailTemplateController.class, "sendMail", MailTemplateSendReqVO.class),
                new WriteTarget("system-mutations", "mail-send", "旧平台邮件发送入口暂不可用"));
        methods.put(exactMethod(SmsChannelController.class, "createSmsChannel", SmsChannelSaveReqVO.class),
                new WriteTarget("system-mutations", "sms-channel-create", "旧平台短信渠道创建入口暂不可用"));
        methods.put(exactMethod(SmsChannelController.class, "updateSmsChannel", SmsChannelSaveReqVO.class),
                new WriteTarget("system-mutations", "sms-channel-update", "旧平台短信渠道修改入口暂不可用"));
        methods.put(exactMethod(SmsChannelController.class, "deleteSmsChannel", Long.class),
                new WriteTarget("system-mutations", "sms-channel-delete", "旧平台短信渠道删除入口暂不可用"));
        methods.put(exactMethod(SmsChannelController.class, "deleteSmsChannelList", List.class),
                new WriteTarget("system-mutations", "sms-channel-delete-list", "旧平台短信渠道批量删除入口暂不可用"));
        methods.put(exactMethod(SmsTemplateController.class, "createSmsTemplate", SmsTemplateSaveReqVO.class),
                new WriteTarget("system-mutations", "sms-template-create", "旧平台短信模板创建入口暂不可用"));
        methods.put(exactMethod(SmsTemplateController.class, "updateSmsTemplate", SmsTemplateSaveReqVO.class),
                new WriteTarget("system-mutations", "sms-template-update", "旧平台短信模板修改入口暂不可用"));
        methods.put(exactMethod(SmsTemplateController.class, "deleteSmsTemplate", Long.class),
                new WriteTarget("system-mutations", "sms-template-delete", "旧平台短信模板删除入口暂不可用"));
        methods.put(exactMethod(SmsTemplateController.class, "deleteSmsTemplateList", List.class),
                new WriteTarget("system-mutations", "sms-template-delete-list", "旧平台短信模板批量删除入口暂不可用"));
        methods.put(exactMethod(SmsTemplateController.class, "sendSms", SmsTemplateSendReqVO.class),
                new WriteTarget("system-mutations", "sms-send", "旧平台短信发送入口暂不可用"));
        methods.put(exactMethod(SmsCallbackController.class, "receiveAliyunSmsStatus", HttpServletRequest.class),
                new WriteTarget("system-mutations", "sms-callback-aliyun", "旧平台短信回调入口暂不可用"));
        methods.put(exactMethod(SmsCallbackController.class, "receiveTencentSmsStatus", HttpServletRequest.class),
                new WriteTarget("system-mutations", "sms-callback-tencent", "旧平台短信回调入口暂不可用"));
        methods.put(exactMethod(SmsCallbackController.class, "receiveHuaweiSmsStatus", String.class),
                new WriteTarget("system-mutations", "sms-callback-huawei", "旧平台短信回调入口暂不可用"));
        methods.put(exactMethod(SmsCallbackController.class, "receiveQiniuSmsStatus", String.class),
                new WriteTarget("system-mutations", "sms-callback-qiniu", "旧平台短信回调入口暂不可用"));
        methods.put(exactMethod(SocialClientController.class, "createSocialClient", SocialClientSaveReqVO.class),
                new WriteTarget("system-mutations", "social-client-create", "旧平台社交客户端创建入口暂不可用"));
        methods.put(exactMethod(SocialClientController.class, "updateSocialClient", SocialClientSaveReqVO.class),
                new WriteTarget("system-mutations", "social-client-update", "旧平台社交客户端修改入口暂不可用"));
        methods.put(exactMethod(SocialClientController.class, "deleteSocialClient", Long.class),
                new WriteTarget("system-mutations", "social-client-delete", "旧平台社交客户端删除入口暂不可用"));
        methods.put(exactMethod(SocialClientController.class, "deleteSocialClientList", List.class),
                new WriteTarget("system-mutations", "social-client-delete-list", "旧平台社交客户端批量删除入口暂不可用"));
        methods.put(exactMethod(SocialUserController.class, "socialBind", SocialUserBindReqVO.class),
                new WriteTarget("system-mutations", "social-bind", "旧平台社交绑定入口暂不可用"));
        methods.put(exactMethod(SocialUserController.class, "socialUnbind", SocialUserUnbindReqVO.class),
                new WriteTarget("system-mutations", "social-unbind", "旧平台社交解绑入口暂不可用"));
        methods.put(exactMethod(TenantController.class, "createTenant", TenantSaveReqVO.class),
                new WriteTarget("system-mutations", "tenant-create", "旧平台租户创建入口暂不可用"));
        methods.put(exactMethod(TenantController.class, "updateTenant", TenantSaveReqVO.class),
                new WriteTarget("system-mutations", "tenant-update", "旧平台租户修改入口暂不可用"));
        methods.put(exactMethod(TenantController.class, "deleteTenant", Long.class),
                new WriteTarget("system-mutations", "tenant-delete", "旧平台租户删除入口暂不可用"));
        methods.put(exactMethod(TenantController.class, "deleteTenantList", List.class),
                new WriteTarget("system-mutations", "tenant-delete-list", "旧平台租户批量删除入口暂不可用"));
        methods.put(exactMethod(TenantPackageController.class, "createTenantPackage", TenantPackageSaveReqVO.class),
                new WriteTarget("system-mutations", "tenant-package-create", "旧平台租户套餐创建入口暂不可用"));
        methods.put(exactMethod(TenantPackageController.class, "updateTenantPackage", TenantPackageSaveReqVO.class),
                new WriteTarget("system-mutations", "tenant-package-update", "旧平台租户套餐修改入口暂不可用"));
        methods.put(exactMethod(TenantPackageController.class, "deleteTenantPackage", Long.class),
                new WriteTarget("system-mutations", "tenant-package-delete", "旧平台租户套餐删除入口暂不可用"));
        methods.put(exactMethod(TenantPackageController.class, "deleteTenantPackageList", List.class),
                new WriteTarget("system-mutations", "tenant-package-delete-list", "旧平台租户套餐批量删除入口暂不可用"));
        methods.put(exactMethod(OAuth2ClientController.class, "createOAuth2Client", OAuth2ClientSaveReqVO.class),
                new WriteTarget("system-mutations", "oauth2-client-create", "旧平台OAuth2客户端创建入口暂不可用"));
        methods.put(exactMethod(OAuth2ClientController.class, "updateOAuth2Client", OAuth2ClientSaveReqVO.class),
                new WriteTarget("system-mutations", "oauth2-client-update", "旧平台OAuth2客户端修改入口暂不可用"));
        methods.put(exactMethod(OAuth2ClientController.class, "deleteOAuth2Client", Long.class),
                new WriteTarget("system-mutations", "oauth2-client-delete", "旧平台OAuth2客户端删除入口暂不可用"));
        methods.put(exactMethod(OAuth2ClientController.class, "deleteOAuth2ClientList", List.class),
                new WriteTarget("system-mutations", "oauth2-client-delete-list", "旧平台OAuth2客户端批量删除入口暂不可用"));
        methods.put(exactMethod(PostController.class, "createPost", PostSaveReqVO.class),
                new WriteTarget("system-mutations", "post-create", "旧平台岗位创建入口暂不可用"));
        methods.put(exactMethod(PostController.class, "updatePost", PostSaveReqVO.class),
                new WriteTarget("system-mutations", "post-update", "旧平台岗位修改入口暂不可用"));
        methods.put(exactMethod(PostController.class, "deletePost", Long.class),
                new WriteTarget("system-mutations", "post-delete", "旧平台岗位删除入口暂不可用"));
        methods.put(exactMethod(PostController.class, "deletePostList", List.class),
                new WriteTarget("system-mutations", "post-delete-list", "旧平台岗位批量删除入口暂不可用"));
        methods.put(exactMethod(MenuController.class, "createMenu", MenuSaveVO.class),
                new WriteTarget("system-mutations", "menu-create", "旧平台菜单创建入口暂不可用"));
        methods.put(exactMethod(MenuController.class, "updateMenu", MenuSaveVO.class),
                new WriteTarget("system-mutations", "menu-update", "旧平台菜单修改入口暂不可用"));
        methods.put(exactMethod(MenuController.class, "deleteMenu", Long.class),
                new WriteTarget("system-mutations", "menu-delete", "旧平台菜单删除入口暂不可用"));
        methods.put(exactMethod(MenuController.class, "deleteMenuList", List.class),
                new WriteTarget("system-mutations", "menu-delete-list", "旧平台菜单批量删除入口暂不可用"));
        methods.put(exactMethod(UserProfileController.class, "updateUserProfile", UserProfileUpdateReqVO.class),
                new WriteTarget("system-mutations", "user-profile-update", "旧平台个人资料入口暂不可用"));
        methods.put(exactMethod(UserProfileController.class, "updateUserProfilePassword", UserProfileUpdatePasswordReqVO.class),
                new WriteTarget("system-mutations", "user-profile-password", "旧平台个人密码入口暂不可用"));
        methods.put(exactMethod(AuthController.class, "login", AuthLoginReqVO.class),
                new WriteTarget("legacy-session", "auth-login", "旧平台登录入口暂不可用"));
        methods.put(exactMethod(AuthController.class, "logout", HttpServletRequest.class),
                new WriteTarget("legacy-session", "auth-logout", "旧平台登出入口暂不可用"));
        methods.put(exactMethod(AuthController.class, "refreshToken", String.class),
                new WriteTarget("legacy-session", "auth-refresh-token", "旧平台令牌刷新入口暂不可用"));
        methods.put(exactMethod(AuthController.class, "register", AuthRegisterReqVO.class),
                new WriteTarget("legacy-session", "auth-register", "旧平台注册入口暂不可用"));
        methods.put(exactMethod(AuthController.class, "smsLogin", AuthSmsLoginReqVO.class),
                new WriteTarget("legacy-session", "auth-sms-login", "旧平台短信登录入口暂不可用"));
        methods.put(exactMethod(AuthController.class, "sendLoginSmsCode", AuthSmsSendReqVO.class),
                new WriteTarget("legacy-session", "auth-send-sms-code", "旧平台登录验证码入口暂不可用"));
        methods.put(exactMethod(AuthController.class, "resetPassword", AuthResetPasswordReqVO.class),
                new WriteTarget("legacy-session", "auth-reset-password", "旧平台密码重置入口暂不可用"));
        methods.put(exactMethod(AuthController.class, "socialQuickLogin", AuthSocialLoginReqVO.class),
                new WriteTarget("legacy-session", "auth-social-login", "旧平台社交登录入口暂不可用"));
        methods.put(exactMethod(OAuth2OpenController.class, "postAccessToken", HttpServletRequest.class, String.class, String.class, String.class, String.class, String.class, String.class, String.class, String.class),
                new WriteTarget("legacy-session", "oauth2-token-create", "旧平台令牌签发入口暂不可用"));
        methods.put(exactMethod(OAuth2OpenController.class, "revokeToken", HttpServletRequest.class, String.class),
                new WriteTarget("legacy-session", "oauth2-token-revoke", "旧平台令牌吊销入口暂不可用"));
        methods.put(exactMethod(OAuth2OpenController.class, "approveOrDeny", String.class, String.class, String.class, String.class, Boolean.class, String.class),
                new WriteTarget("legacy-session", "oauth2-authorize", "旧平台授权同意入口暂不可用"));
        methods.put(exactMethod(OAuth2TokenController.class, "deleteAccessToken", String.class),
                new WriteTarget("legacy-session", "oauth2-token-delete", "旧平台令牌删除入口暂不可用"));
        methods.put(exactMethod(OAuth2TokenController.class, "deleteAccessTokenList", List.class),
                new WriteTarget("legacy-session", "oauth2-token-delete-list", "旧平台令牌批量删除入口暂不可用"));
        methods.put(exactMethod(OAuth2UserController.class, "updateUserInfo", OAuth2UserUpdateReqVO.class),
                new WriteTarget("legacy-session", "oauth2-user-update", "旧平台OAuth2资料入口暂不可用"));
        methods.put(exactMethod(UserController.class, "createUser", UserSaveReqVO.class),
                new WriteTarget("system-mutations", "user-create", "旧平台用户创建入口暂不可用"));
        methods.put(exactMethod(UserController.class, "updateUser", UserSaveReqVO.class),
                new WriteTarget("system-mutations", "user-update", "旧平台用户修改入口暂不可用"));
        methods.put(exactMethod(UserController.class, "deleteUser", Long.class),
                new WriteTarget("system-mutations", "user-delete", "旧平台用户删除入口暂不可用"));
        methods.put(exactMethod(UserController.class, "deleteUserList", List.class),
                new WriteTarget("system-mutations", "user-delete-list", "旧平台用户批量删除入口暂不可用"));
        methods.put(exactMethod(UserController.class, "updateUserPassword", UserUpdatePasswordReqVO.class),
                new WriteTarget("system-mutations", "user-update-password", "旧平台用户改密入口暂不可用"));
        methods.put(exactMethod(UserController.class, "updateUserStatus", UserUpdateStatusReqVO.class),
                new WriteTarget("system-mutations", "user-update-status", "旧平台用户停启用入口暂不可用"));
        methods.put(exactMethod(UserController.class, "importExcel", MultipartFile.class, Boolean.class),
                new WriteTarget("system-mutations", "user-import", "旧平台用户批量导入入口暂不可用"));
        methods.put(exactMethod(RoleController.class, "createRole", RoleSaveReqVO.class),
                new WriteTarget("system-mutations", "role-create", "旧平台角色创建入口暂不可用"));
        methods.put(exactMethod(RoleController.class, "updateRole", RoleSaveReqVO.class),
                new WriteTarget("system-mutations", "role-update", "旧平台角色修改入口暂不可用"));
        methods.put(exactMethod(RoleController.class, "deleteRole", Long.class),
                new WriteTarget("system-mutations", "role-delete", "旧平台角色删除入口暂不可用"));
        methods.put(exactMethod(RoleController.class, "deleteRoleList", List.class),
                new WriteTarget("system-mutations", "role-delete-list", "旧平台角色批量删除入口暂不可用"));
        methods.put(exactMethod(PermissionController.class, "assignRoleMenu", PermissionAssignRoleMenuReqVO.class),
                new WriteTarget("system-mutations", "role-menu-assign", "旧平台菜单授权入口暂不可用"));
        methods.put(exactMethod(PermissionController.class, "assignRoleDataScope", PermissionAssignRoleDataScopeReqVO.class),
                new WriteTarget("system-mutations", "role-data-scope-assign", "旧平台数据权限入口暂不可用"));
        methods.put(exactMethod(PermissionController.class, "assignUserRole", PermissionAssignUserRoleReqVO.class),
                new WriteTarget("system-mutations", "user-role-assign", "旧平台用户授权入口暂不可用"));
        methods.put(exactMethod(DeptController.class, "createDept", DeptSaveReqVO.class),
                new WriteTarget("system-mutations", "dept-create", "旧平台部门创建入口暂不可用"));
        methods.put(exactMethod(DeptController.class, "updateDept", DeptSaveReqVO.class),
                new WriteTarget("system-mutations", "dept-update", "旧平台部门修改入口暂不可用"));
        methods.put(exactMethod(DeptController.class, "deleteDept", Long.class),
                new WriteTarget("system-mutations", "dept-delete", "旧平台部门删除入口暂不可用"));
        methods.put(exactMethod(DeptController.class, "deleteDeptList", List.class),
                new WriteTarget("system-mutations", "dept-delete-list", "旧平台部门批量删除入口暂不可用"));
        // 类别7 platform-http 14入口（G2l合同v1，c31五点全批）：旧融合闭包内platform包全部HTTP写面。
        // A2 sendSmsCaptcha按c31①裁决一并503拒绝（登录链半开不成立+无permit短信外呼=停写要禁的副作用）。
        // A1/A3员工登录写system_oauth2_access_token+pf_wechat_binding，与legacy-session（user_session旧
        // token体系）为两套会话体系，按入口包归platform-http（coverage_note逐字登记，不混同）。
        // O6/O7交接撤权使G2d登记"非HTTP可达"的桥接撤权链（WalletSessionRevocationAdapter→user_session）
        // 经HTTP可达，收口在本writer permit覆盖下；StaffPostUserDeletedListener事件链仍归类别6。
        // platform-wallet 4控制器仅新B闭包不在停写面；platform包其余GET入口纯读不收口。
        methods.put(exactMethod(StaffProfileController.class, "updateMine", Map.class),
                new WriteTarget("platform-http", "profile-update-mine", "旧平台员工资料入口暂不可用"));
        methods.put(exactMethod(PlatformMessageController.class, "read", long.class),
                new WriteTarget("platform-http", "message-read", "旧平台消息已读入口暂不可用"));
        methods.put(exactMethod(PlatformMessageController.class, "readAll"),
                new WriteTarget("platform-http", "message-read-all", "旧平台消息批量已读入口暂不可用"));
        methods.put(exactMethod(StaffOrgAdminController.class, "createTeam", String.class,
                        StaffOrgAdminController.CreateTeamReqVO.class),
                new WriteTarget("platform-http", "org-team-create", "旧平台部门创建入口暂不可用"));
        methods.put(exactMethod(StaffOrgAdminController.class, "createStaff", StaffOrgAdminController.CreateStaffReqVO.class),
                new WriteTarget("platform-http", "staff-create", "旧平台员工录入入口暂不可用"));
        methods.put(exactMethod(StaffOrgAdminController.class, "assignPost", StaffOrgAdminController.AssignPostReqVO.class),
                new WriteTarget("platform-http", "post-assign", "旧平台岗位指派入口暂不可用"));
        methods.put(exactMethod(StaffOrgAdminController.class, "revokePost", long.class),
                new WriteTarget("platform-http", "post-revoke", "旧平台任职撤销入口暂不可用"));
        methods.put(exactMethod(StaffOrgAdminController.class, "setStatus", StaffOrgAdminController.StaffStatusReqVO.class),
                new WriteTarget("platform-http", "staff-status", "旧平台员工停用入口暂不可用"));
        methods.put(exactMethod(StaffOrgAdminController.class, "handover", StaffOrgAdminController.StaffHandoverReqVO.class),
                new WriteTarget("platform-http", "staff-handover", "旧平台离职交接入口暂不可用"));
        methods.put(exactMethod(StaffOrgAdminController.class, "emergencyRevoke", StaffOrgAdminController.StaffEmergencyRevokeReqVO.class),
                new WriteTarget("platform-http", "emergency-revoke", "旧平台紧急撤权入口暂不可用"));
        methods.put(exactMethod(OrgOnboardController.class, "onboard", OrgOnboardController.OnboardReqVO.class),
                new WriteTarget("platform-http", "org-onboard", "旧平台组织开通入口暂不可用"));
        methods.put(exactMethod(PlatformAuthController.class, "wechatLogin", WechatLoginReqVO.class),
                new WriteTarget("platform-http", "staff-wechat-login", "旧平台员工微信登录入口暂不可用"));
        methods.put(exactMethod(PlatformAuthController.class, "sendSmsCaptcha", SmsCaptchaSendReqVO.class),
                new WriteTarget("platform-http", "staff-sms-send", "旧平台登录验证码入口暂不可用"));
        methods.put(exactMethod(PlatformAuthController.class, "smsLogin", SmsLoginReqVO.class),
                new WriteTarget("platform-http", "staff-sms-login", "旧平台员工短信登录入口暂不可用"));
        // 类别9/10 ai-cancel-runtime（G2m合同v0.1经c31五点全批，方案B）：混合模式——仅接
        // Runtime 新领取门禁（J1 /claims，DRAINING 503=停新领取）与用户取消写（C1），在途执行
        // 收口面 J2-J8 七入口登记豁免（G2j 判例：既有在途工作的终态判定；J3/J4=既有预留的
        // 按量结转，非新增预留/充值）。worker 收口轮（validate/settle/promote+cancel sweep）
        // 同批登记豁免；outbox 轮接线归类别12 批；payment/inbox 轮类别8 已闭环不重复。
        // prompt-call 双轨：SERVICE_COST 任务走持久 call receipt 门禁（B 库无 940 表、
        // serviceCosts 分支抛 NOT_WIRED→UNKNOWN 收口，不绕过收据链）；存量核验：B 独立库
        // ai_job 表不存在、本地 dev 融合库 178 行全终态零 SERVICE_COST（生产库不碰）。
        methods.put(exactMethod(AiJobInternalController.class, "claimJobs", HttpServletRequest.class, InternalClaimReqVO.class),
                new WriteTarget("ai-cancel-runtime", "job-claim", "旧平台任务领取入口暂不可用"));
        methods.put(exactMethod(AppAiJobController.class, "requestCancellation", String.class, String.class),
                new WriteTarget("ai-cancel-runtime", "job-cancel-request", "旧平台任务取消入口暂不可用"));
        // 类别11 content-jobs（G2n合同v0.1经c31六点全批+两处必改落实，方案甲）：任务创建面
        // 4入口接线（T1/S1/S2/S3 全写 fr_content_task 任务化，停新创建即停该 writer 新工作——
        // G2m J1 同型；S1-S3 同步任务 503 拒绝=无 permit provider 外呼不发生，无在途悬挂）。
        // ContentTaskJob sweep 三段（submit存量推进/poll对账/timeout转人工）登记豁免（G2j 判例，
        // 前提=旧融合进程存活+prod 开关 true——application-prod.yaml 108 行；SUBMISSION_UNCERTAIN
        // 转人工行与关联 fr_content_ai_usage 冻结配额随开放项单列）。运营面14入口+资产 ticket
        // 闭包（AssetAccessPort 三入口）归 franchise-http 后续批候选（coverage 通报 manifest
        // owner，接线前 FROZEN 硬阻断）；FakeDevController @Profile("zsdev")=dev owner。
        // content 域=B/旧融合共享代码（G2k 裁决同构：B 进程写自身库不在停写面；B 生产 sweep
        // 经 EnvironmentPolicy 强制 true 但写 B 自身库=合法）。content 域零 outbox 交互。
        methods.put(exactMethod(ContentTaskController.class, "create", Map.class, String.class),
                new WriteTarget("content-jobs", "content-task-create", "旧平台内容任务创建入口暂不可用"));
        methods.put(exactMethod(ContentScriptController.class, "parseLink", Map.class, String.class),
                new WriteTarget("content-jobs", "content-link-parse", "旧平台链接解析入口暂不可用"));
        methods.put(exactMethod(ContentScriptController.class, "extract", Map.class, String.class),
                new WriteTarget("content-jobs", "content-script-extract", "旧平台文案提取入口暂不可用"));
        methods.put(exactMethod(ContentScriptController.class, "optimize", Map.class, String.class),
                new WriteTarget("content-jobs", "content-script-optimize", "旧平台文案优化入口暂不可用"));
        // 类别12 outbox-export-privacy（G2o合同v0.1经c31裁决：①②④⑤⑥批准+③改判PL1/PL2均接线）：
        // 隐私请求生命周期+管理删除 7 入口接线。AD1/AD2 管理删除（type 分发表逻辑删除/audit_event
        // 物理删除+account 类型 revokeAllForAccount 链=G2d 调用方①）；PA1 审批（CLOSE_ACCOUNT 批准
        // =全账号擦除流=G2d 调用方②，守卫=点数清零+无在途+retention 版本；真实行业务覆盖=既有
        // PrivacyLifecycleTest 真实 PG Testcontainers，覆盖边界=decision 端点 HTTP 层门禁语义由本
        // 套件锁定）；DS1/DS2 用户隐私请求创建（P0 只登记幂等）；PL1/PL2 取回面接线（c31③改判：
        // 票据签发+一次性消费写，"纯取回零业务写"前提不成立；PL2=ResponseEntity 面走 CHANNEL_ACK
        // G2h 机制零成本复用）。DRAINING/FROZEN 语义=503 挂起（无 permit 跨域擦除/删除写=冻结证明
        // 破坏不可接受；合规代价已登记：DS 挂起计数入开放项清单/PA1-AD 承接=人工按 storage 导出
        // 文件交付+迁移对账——新 B 无 privacy 域）。豁免：W1 outbox 投递收口轮（调度唯一=driver
        // .outbox 轮，G2m 引用本批正式归属；生产 sink 零=全收敛 DISPATCHED dev 357 实证）、W2
        // EXPORT 收口 tick（prod true）、W3 设计导出轮（prod true——dev 位置但生产活跃=worker 豁免，
        // 与 G2i DesignDevController @Profile 排除生产形成归类判据先例对：判据=是否生产运行时注册）。
        methods.put(exactMethod(AdminDataDeleteController.class, "deleteOne", String.class, String.class),
                new WriteTarget("outbox-export-privacy", "admin-data-delete", "旧平台数据删除入口暂不可用"));
        methods.put(exactMethod(AdminDataDeleteController.class, "deleteBatch", Map.class),
                new WriteTarget("outbox-export-privacy", "admin-data-delete-batch", "旧平台批量删除入口暂不可用"));
        methods.put(exactMethod(PrivacyAdminController.class, "decide", long.class, PrivacyAdminController.Decision.class),
                new WriteTarget("outbox-export-privacy", "privacy-request-decision", "旧平台隐私请求审批入口暂不可用"));
        methods.put(exactMethod(AppDataSubjectRequestController.class, "createDataExportRequest", String.class),
                new WriteTarget("outbox-export-privacy", "data-export-request", "旧平台数据导出请求入口暂不可用"));
        methods.put(exactMethod(AppDataSubjectRequestController.class, "createAccountClosureRequest", String.class),
                new WriteTarget("outbox-export-privacy", "account-closure-request", "旧平台账号关闭请求入口暂不可用"));
        methods.put(exactMethod(AppPrivacyLifecycleController.class, "ticket", long.class, String.class),
                new WriteTarget("outbox-export-privacy", "privacy-download-ticket", "旧平台导出票据入口暂不可用"));
        methods.put(exactAckMethod(AppPrivacyLifecycleController.class, "download", long.class, String.class, String.class),
                new WriteTarget("outbox-export-privacy", "privacy-download", "旧平台导出下载入口暂不可用", Evidence.CHANNEL_ACK));
        // 类别12扩展（G2q子集批，G2p交叉核验触发）：ExportAuditAdminController=yudao-module-design
        // /controller/admin（位置更正：设计模块内平台管理入口——逐入口语义双判定实证）——导出任务
        // 管理4入口接线（requester_type='ADMIN'平台管理语义非设计应用操作=不在排除面）。
        // E1 createExportJob=deliveryPort.createExportJob新导出任务源（停新任务=G2m J1同型）；
        // E2 cancelExportJob=本人UPDATE cancel_requested（G2m C1同型；DRAINING代价=取消延迟，任务
        // 完成后无损）；E3 createDownloadTicket/E4 downloadExportFile=取回面（c31③改判同款：
        // 票据签发+一次性消费写；E4=ResponseEntity<byte[]>走CHANNEL_ACK G2h机制复用）。
        // W3 worker（ZhongshuExportWorker prod true）同表双属=豁免登记（G2o已登记）。
        methods.put(exactMethod(ExportAuditAdminController.class, "createExportJob", Map.class),
                new WriteTarget("outbox-export-privacy", "export-job-create", "旧平台导出任务创建入口暂不可用"));
        methods.put(exactMethod(ExportAuditAdminController.class, "cancelExportJob", String.class),
                new WriteTarget("outbox-export-privacy", "export-job-cancel", "旧平台导出任务取消入口暂不可用"));
        methods.put(exactMethod(ExportAuditAdminController.class, "createDownloadTicket", String.class),
                new WriteTarget("outbox-export-privacy", "export-download-ticket", "旧平台导出票据入口暂不可用"));
        methods.put(exactAckMethod(ExportAuditAdminController.class, "downloadExportFile", String.class, String.class),
                new WriteTarget("outbox-export-privacy", "export-download", "旧平台导出文件下载入口暂不可用", Evidence.CHANNEL_ACK));
        // The bridge switch only removes wallet-session issuance, not the always-present CRM handler.
        if (legacyBridgeEnabled) {
            methods.put(exactMethod(WalletSessionController.class, "issueSession", String.class),
                    new WriteTarget("franchise-http", "wallet-session-issue", "旧平台钱包会话入口暂不可用"));
        }
        targets = Map.copyOf(methods);
    }

    private static Method exactMethod(Class<?> controller, String name, Class<?>... parameters) {
        try {
            Method method = controller.getDeclaredMethod(name, parameters);
            if (method.getReturnType() != CommonResult.class) {
                throw new IllegalStateException("LEGACY_B_HANDLER_CONTRACT_CHANGED: " + controller.getSimpleName() + "." + name);
            }
            return method;
        } catch (NoSuchMethodException failure) {
            throw new IllegalStateException("LEGACY_B_HANDLER_MISSING: " + controller.getSimpleName() + "." + name, failure);
        }
    }

    /** 渠道回调合同面：返回类型为ResponseEntity（漂移仍快速失败，错误码不变）。 */
    private static Method exactAckMethod(Class<?> controller, String name, Class<?>... parameters) {
        try {
            Method method = controller.getDeclaredMethod(name, parameters);
            if (method.getReturnType() != ResponseEntity.class) {
                throw new IllegalStateException("LEGACY_B_HANDLER_CONTRACT_CHANGED: " + controller.getSimpleName() + "." + name);
            }
            return method;
        } catch (NoSuchMethodException failure) {
            throw new IllegalStateException("LEGACY_B_HANDLER_MISSING: " + controller.getSimpleName() + "." + name, failure);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        WriteTarget target = handler instanceof HandlerMethod method ? targets.get(method.getMethod()) : null;
        // Unrelated A handlers do not read B state, acquire a B lock or access B storage.
        if (target == null) return true;
        if (request.getDispatcherType() != DispatcherType.REQUEST || request.getAttribute(ADMISSION) != null) {
            throw unavailable(target);
        }
        try {
            Permit permit = gate.enter(Owner.B_LEGACY, target.writer(), target.operation());
            request.setAttribute(ADMISSION, new Admission(permit, target));
            return true;
        } catch (IllegalStateException failure) {
            LOG.warn("[legacy-b-http] admission refused; operation={} failureType={}",
                    target.operation(), failure.getClass().getSimpleName());
            throw unavailable(target);
        }
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView view) {
        if (!(request.getAttribute(ADMISSION) instanceof Admission admission)) return;
        // CHANNEL_ACK 判据（c31纠正版）：CommonResult判别器为唯一逃逸证据——非空=异常逃逸
        // （必经GlobalExceptionHandler产出带advice属性）→UNKNOWN保留；为空且非异步且无活跃事务
        // →有意ACK→任意状态码正常释放（非2xx加warn）。状态白名单仅测试断言辅助，非释放门条件。
        if (admission.target.evidence() == Evidence.CHANNEL_ACK) {
            boolean escaped = WebFrameworkUtils.getCommonResult(request) != null;
            boolean settled = !escaped && !request.isAsyncStarted()
                    && !TransactionSynchronizationManager.isActualTransactionActive();
            admission.returnedSuccessfully = settled;
            if (settled && !CHANNEL_ACK_STATUSES.contains(response.getStatus())) {
                LOG.warn("[legacy-b-http] channel ack with non-2xx status={}; operation={}",
                        response.getStatus(), admission.target.operation());
            }
            return;
        }
        CommonResult<?> result = WebFrameworkUtils.getCommonResult(request);
        // DispatcherServlet skips postHandle when an exception is consumed by its resolver.
        // Listed controllers only return success after their synchronous transaction proxy returns.
        admission.returnedSuccessfully = result != null && result.isSuccess() && !request.isAsyncStarted()
                && !TransactionSynchronizationManager.isActualTransactionActive();
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
        if (request.getAttribute(ADMISSION) instanceof Admission admission) {
            finish(admission, admission.returnedSuccessfully && failure == null);
        }
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getAttribute(ADMISSION) instanceof Admission admission) finish(admission, false);
    }

    private static ServiceException unavailable(WriteTarget target) {
        // Existing wallet clients use CommonResult business errors; 404/501 would trigger fallback.
        return new ServiceException(503, target.unavailableMessage());
    }

    private static void finish(Admission admission, boolean completed) {
        if (!admission.finished.compareAndSet(false, true)) return;
        try {
            if (!completed) {
                admission.permit.markUnknown();
                LOG.warn("[legacy-b-http] terminal result unproven; pending retained, operation={}", admission.target.operation());
            }
            admission.permit.close();
        } catch (RuntimeException failure) {
            LOG.error("[legacy-b-http] completion persistence failed; pending retained, operation={} failureType={}",
                    admission.target.operation(), failure.getClass().getSimpleName());
        }
    }

    /** 证据模式：COMMON_RESULT=既有判据；CHANNEL_ACK=渠道回调（无CommonResult，凭响应状态码判定有意ACK）。 */
    enum Evidence { COMMON_RESULT, CHANNEL_ACK }

    /**
     * 渠道ACK状态集：仅作测试断言辅助，不作为释放门条件——释放判据以CommonResult判别器为唯一
     * 逃逸证据（c31源码级纠正：xpay有意500=fail(INTERNAL_SERVER_ERROR)退避重试请求，属已证明终态）。
     */
    static final Set<Integer> CHANNEL_ACK_STATUSES = Set.of(200, 400, 413, 500);

    private record WriteTarget(String writer, String operation, String unavailableMessage, Evidence evidence) {
        WriteTarget(String writer, String operation, String unavailableMessage) {
            this(writer, operation, unavailableMessage, Evidence.COMMON_RESULT);
        }
    }

    private static final class Admission {
        private final Permit permit;
        private final WriteTarget target;
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile boolean returnedSuccessfully;

        private Admission(Permit permit, WriteTarget target) {
            this.permit = permit;
            this.target = target;
        }
    }
}
