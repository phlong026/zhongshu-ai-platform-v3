package cn.iocoder.yudao.module.identity.controller.app;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.framework.ratelimiter.core.keyresolver.impl.ClientIpRateLimiterKeyResolver;
import cn.iocoder.yudao.module.identity.activation.ActivationRequestService;
import cn.iocoder.yudao.module.identity.controller.app.vo.AppActivationRequestRespVO;
import cn.iocoder.yudao.module.identity.controller.app.vo.AppActivationRequestSubmitReqVO;
import cn.iocoder.yudao.module.identity.session.UserSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "小程序 - 开通申请")
@RestController
@RequestMapping("/design/v1/activation-requests")
@PermitAll // 自有 user_session Bearer 校验在方法内强制执行
public class AppActivationRequestController {

    @Resource
    private ActivationRequestService activationRequestService;

    @Resource
    private UserSessionService sessionService;

    @Resource
    private cn.iocoder.yudao.framework.ratelimiter.core.VerifiedAccountRateLimiter verifiedAccountRateLimiter;

    /** 提交开通申请：需要登录会话（访客受限会话即可）；重复提交幂等返回既有申请 */
    @PostMapping
    @Operation(summary = "提交开通申请（公司/联系人/电话），管理员通过后账号获得使用权")
    @RateLimiter(time = 60, count = 30, keyResolver = ClientIpRateLimiterKeyResolver.class)
    public CommonResult<AppActivationRequestRespVO> submit(
            @Valid @RequestBody AppActivationRequestSubmitReqVO reqVO,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        UserSessionService.AccessContext context = requireContext(authorization);
        verifiedAccountRateLimiter.check("activation-request", context.accountId(), 5, 60);
        ActivationRequestService.ActivationRequestRow row = activationRequestService.submit(
                context.appid(), context.openid(), context.accountId(),
                reqVO.getCompanyName().trim(), reqVO.getContactName().trim(), reqVO.getContactPhone().trim());
        return success(toRespVO(row));
    }

    /** 本人最新一条开通申请（驳回后重新提交返回新申请）；从未申请返回空数据 */
    @GetMapping("/me")
    @Operation(summary = "查询本人最新开通申请状态")
    public CommonResult<AppActivationRequestRespVO> latest(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        UserSessionService.AccessContext context = requireContext(authorization);
        return success(activationRequestService.latestOf(context.accountId()).map(AppActivationRequestController::toRespVO)
                .orElse(null));
    }

    private UserSessionService.AccessContext requireContext(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        return sessionService.validateAccessToken(token)
                .orElseThrow(() -> new ServiceException(401, "会话无效或已过期"));
    }

    private static AppActivationRequestRespVO toRespVO(ActivationRequestService.ActivationRequestRow row) {
        AppActivationRequestRespVO vo = new AppActivationRequestRespVO();
        vo.setStatus(row.status());
        vo.setCompanyName(row.companyName());
        vo.setContactName(row.contactName());
        vo.setContactPhone(row.contactPhone());
        vo.setRequestedAt(row.requestedAt() == null ? null : LocalDateTime.ofInstant(row.requestedAt(), ZoneOffset.UTC));
        vo.setHandledAt(row.handledAt() == null ? null : LocalDateTime.ofInstant(row.handledAt(), ZoneOffset.UTC));
        vo.setRejectReason(row.rejectReason());
        return vo;
    }

}
