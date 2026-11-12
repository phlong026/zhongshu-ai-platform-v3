package cn.iocoder.yudao.module.franchise.org.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.framework.ratelimiter.core.keyresolver.impl.UserRateLimiterKeyResolver;
import cn.iocoder.yudao.module.franchise.org.wallet.WalletSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 钱包会话桥接（09 文档）：员工以平台身份为当前组织换取组织服务账号的钱包会话。
 *
 * <p>契约冻结（v3 api/wallet.js 依赖）：成功 = {code:0, data:{token, expiresIn:1800}}，无 refreshToken；
 * 业务失败一律 HTTP 200 + body.code（1_104_000_001/002、1_112_000_004、403 等，见 09 文档 §三错误表）；
 * 上线后业务失败不得返回 HTTP 404/501（前端视其为「未部署」降级信号）。
 */
@Tag(name = "加盟 - 钱包会话桥接")
@RestController
@RequestMapping("/platform/wallet")
@Validated
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "platform.legacy-design-bridge.enabled", havingValue = "true", matchIfMissing = true)
public class WalletSessionController {

    @Resource
    private WalletSessionService walletSessionService;

    @PostMapping("/session")
    @Operation(summary = "换取组织服务账号的钱包会话：X-WX-Login-Code 为支付者一次性 wx.login code（换取即弃，禁入日志/URL）")
    @PreAuthorize("@post.has('platform:org:manage')")
    @RateLimiter(time = 60, count = 10, keyResolver = UserRateLimiterKeyResolver.class)
    public CommonResult<Map<String, Object>> issueSession(
            @RequestHeader(value = "X-WX-Login-Code", required = false) String wxLoginCode) {
        return success(walletSessionService.issueSession(wxLoginCode));
    }

}
