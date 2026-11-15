package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.authz.service.StaffAccountOnboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "平台 - 门店员工账号")
@RestController
@RequestMapping("/platform/org")
@Validated
public class StaffAccountOnboardController {
    @Resource private CurrentPost currentPost;
    @Resource private StaffAccountOnboardService service;

    @PostMapping("/staff-accounts")
    @Operation(summary = "门店老板开通员工独立账号并指派岗位；仅新用户返回一次初始密码")
    @PreAuthorize("@post.has('platform:org:manage')")
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<StaffAccountOnboardService.Result> open(@Valid @RequestBody StaffOrgAdminController.CreateStaffReqVO req) {
        var post = currentPost.require();
        return success(service.open(req.orgId(), req.userId(), req.name(), req.mobile(), req.roleCode(), req.teamId(), post.userId(), post.postId()));
    }
}
