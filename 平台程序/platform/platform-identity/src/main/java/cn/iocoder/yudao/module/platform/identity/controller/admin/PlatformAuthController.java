package cn.iocoder.yudao.module.platform.identity.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.platform.identity.StaffSmsLoginService;
import cn.iocoder.yudao.module.platform.identity.StaffWechatLoginService;
import cn.iocoder.yudao.module.platform.identity.WechatLoginResult;
import cn.iocoder.yudao.module.platform.identity.controller.admin.vo.SmsCaptchaSendReqVO;
import cn.iocoder.yudao.module.platform.identity.controller.admin.vo.SmsLoginReqVO;
import cn.iocoder.yudao.module.platform.identity.controller.admin.vo.SmsLoginRespVO;
import cn.iocoder.yudao.module.platform.identity.controller.admin.vo.WechatLoginReqVO;
import cn.iocoder.yudao.module.platform.identity.controller.admin.vo.WechatLoginRespVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 员工登录。员工是后台用户，Web 与小程序都走 /admin-api；请求需带租户头 tenant-id。
 */
@Tag(name = "平台 - 员工登录")
@RestController
@RequestMapping("/platform/auth")
@Validated
public class PlatformAuthController {

    @Resource
    private StaffWechatLoginService staffWechatLoginService;
    @Resource
    private StaffSmsLoginService staffSmsLoginService;

    @PostMapping("/wechat-login")
    @PermitAll
    @Operation(summary = "小程序员工微信登录")
    public CommonResult<WechatLoginRespVO> wechatLogin(@Valid @RequestBody WechatLoginReqVO req) {
        WechatLoginResult result = staffWechatLoginService.login(req.getAppid(), req.getLoginCode(), req.getPhoneCode());
        WechatLoginRespVO resp = new WechatLoginRespVO().setNeedPhone(result.needPhone()).setUserId(result.userId());
        if (result.token() != null) {
            resp.setAccessToken(result.token().accessToken())
                    .setRefreshToken(result.token().refreshToken())
                    .setExpiresTime(result.token().expiresTime());
        }
        return success(resp);
    }

    @PostMapping("/sms/send")
    @PermitAll
    @Operation(summary = "发送登录短信验证码（60s 一条、每日上限，内置限流）")
    public CommonResult<Boolean> sendSmsCaptcha(@Valid @RequestBody SmsCaptchaSendReqVO req) {
        staffSmsLoginService.sendCaptcha(req.getMobile());
        return success(true);
    }

    @PostMapping("/sms/login")
    @PermitAll
    @Operation(summary = "小程序员工手机号验证码登录")
    public CommonResult<SmsLoginRespVO> smsLogin(@Valid @RequestBody SmsLoginReqVO req) {
        WechatLoginResult result = staffSmsLoginService.login(req.getMobile(), req.getCaptcha());
        SmsLoginRespVO resp = new SmsLoginRespVO().setUserId(result.userId());
        if (result.token() != null) {
            resp.setAccessToken(result.token().accessToken())
                    .setRefreshToken(result.token().refreshToken())
                    .setExpiresTime(result.token().expiresTime());
        }
        return success(resp);
    }

}
