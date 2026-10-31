package cn.iocoder.yudao.server.dev;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.platform.identity.SmsCaptchaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 短信验证码出码接口：stub 短信不真正发信，本地联调与端到端脚本从这里读最近一次验证码。
 * 只在 local profile 且非 prod 下注册（与 FranchiseE2ESetupController 同口径）；
 * 路径在 /admin-api/platform/dev-setup/** 之下，沿用 local profile 的 permit-all 放行列表。
 */
@Tag(name = "开发 - 短信验证码出码")
@RestController
@RequestMapping("/admin-api/platform/dev-setup/sms-captcha")
@Profile("local")
@PermitAll
public class SmsDevController {

    @Resource
    private SmsCaptchaService smsCaptchaService;

    @GetMapping
    @Operation(summary = "读取指定手机号最近一次登录验证码（仅 local）")
    public CommonResult<String> peek(@RequestParam("mobile") String mobile) {
        return success(smsCaptchaService.peekRecentCaptcha(mobile));
    }

}
