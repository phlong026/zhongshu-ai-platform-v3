package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.platform.authz.service.OrgAccountAdminService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@Tag(name="加盟商账号管理")
@RequestMapping("/platform/org/directory/{orgId}/account")
@PreAuthorize("@ss.hasPermission('platform:org:onboard') && @ss.hasPermission('system:user:update-password')")
public class OrgAccountAdminController {
    @Resource private OrgAccountAdminService service;
    @GetMapping
    public CommonResult<OrgAccountAdminService.Account> account(@PathVariable long orgId) {
        return success(service.account(orgId,SecurityFrameworkUtils.getLoginUserId()));
    }
    public record ResetPassword(String password,String confirmedName,String reason,String requestKey) { }
    @PostMapping("/reset-password")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<Boolean> reset(@PathVariable long orgId,@RequestBody ResetPassword body) {
        service.reset(orgId,SecurityFrameworkUtils.getLoginUserId(),body.password(),body.confirmedName(),body.reason(),body.requestKey());
        return success(true);
    }
}
