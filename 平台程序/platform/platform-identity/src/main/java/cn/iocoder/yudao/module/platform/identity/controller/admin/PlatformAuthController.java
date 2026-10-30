package cn.iocoder.yudao.module.platform.identity.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.platform.identity.StaffWechatLoginService;
import cn.iocoder.yudao.module.platform.identity.WechatLoginResult;
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

}
