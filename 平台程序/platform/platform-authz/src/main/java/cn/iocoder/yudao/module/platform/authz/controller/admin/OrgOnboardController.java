package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.platform.authz.service.OrgOnboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * M01-T06 / R02：正式组织开通（平台管理员专属，Web 第一批入口）。
 * 平台级操作无 pf 岗位上下文——走 yudao 权限码（platform:org:onboard 挂超管角色，707 迁移），
 * 与组织内管理端点的 @post.has（岗位即权限）分属两个权限域。
 */
@Tag(name = "平台组织开通")
@RestController
@RequestMapping("/platform/org")
@Validated
public class OrgOnboardController {

    @Resource
    private OrgOnboardService orgOnboardService;

    public record OnboardReqVO(@NotBlank(message = "组织类型不能为空") String orgType,
                               @NotBlank(message = "组织名称不能为空") String orgName,
                               @NotBlank(message = "首管理员姓名不能为空") String adminName,
                               @NotBlank(message = "首管理员手机号不能为空")
                               @Pattern(regexp = "1\\d{10}", message = "手机号格式不正确") String adminMobile,
                               // 24 号 B-8：省略/false = 不改变既有开通状态；true = 建池开通
                               Boolean designEnabled,
                               // 24 号 B-8：开通赠点（仅首次生效）
                               Long initialCredits) {
    }

    public record OnboardRespVO(long orgId, long adminUserId, long postId, boolean reusedOrg) {
    }

    @PostMapping("/onboard")
    @Operation(summary = "正式开通组织并授予首任管理员（boss）；重复开通幂等返回既有组织")
    @PreAuthorize("@ss.hasPermission('platform:org:onboard')")
    public CommonResult<OnboardRespVO> onboard(@Valid @RequestBody OnboardReqVO req) {
        long operator = SecurityFrameworkUtils.getLoginUserId();
        OrgOnboardService.OnboardResult r = orgOnboardService.onboard(
                req.orgType(), req.orgName(), req.adminName(), req.adminMobile(), operator,
                req.designEnabled(), req.initialCredits());
        return success(new OnboardRespVO(r.orgId(), r.adminUserId(), r.postId(), r.reusedOrg()));
    }
}
