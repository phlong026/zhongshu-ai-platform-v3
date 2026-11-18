package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.platform.authz.service.FranchiseOnboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "平台 - 加盟商账号开通")
@RestController
@RequestMapping("/platform/org")
@Validated
public class FranchiseOnboardController {
    @Resource private FranchiseOnboardService service;

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public CommonResult<Object> invalidNaming(IllegalArgumentException error) {
        return CommonResult.error(400,error.getMessage());
    }

    public record PreviewRequest(@NotBlank @Size(max=40) String brand,
                                 @NotBlank @Size(max=40) String province,
                                 @NotBlank @Size(max=40) String city,
                                 @NotBlank @Size(max=20) String area,
                                 @NotBlank @Size(max=20) String adminName,
                                 @Size(max=80) String areaPinyin, @Size(max=80) String ownerPinyin) { }
    public record OnboardRequest(@NotBlank @Size(max=40) String brand,
                                 @NotBlank @Size(max=40) String province,
                                 @NotBlank @Size(max=40) String city,
                                 @NotBlank @Size(max=20) String area,
                                 @NotBlank @Size(max=20) String adminName,
                                 @NotBlank @Pattern(regexp="1\\d{10}") String adminMobile,
                                 @Size(max=80) String areaPinyin, @Size(max=80) String ownerPinyin,
                                 @Size(max=500) String remark, @Pattern(regexp="[0-9]{6}") String districtCode) { }

    @org.springframework.web.bind.annotation.GetMapping("/franchise-districts")
    @PreAuthorize("@ss.hasPermission('platform:org:onboard')")
    public CommonResult<java.util.List<FranchiseOnboardService.District>> districts(
            @org.springframework.web.bind.annotation.RequestParam String province,
            @org.springframework.web.bind.annotation.RequestParam String city) {
        return success(service.districts(province,city));
    }

    @PostMapping("/franchise-preview")
    @Operation(summary = "预览加盟商名称与可用账号；最终账号以正式开通响应为准")
    @PreAuthorize("@ss.hasPermission('platform:org:onboard')")
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<FranchiseOnboardService.Preview> preview(@Valid @RequestBody PreviewRequest req) {
        return success(service.preview(req.brand(), req.province(), req.city(), req.area(), req.adminName(), req.areaPinyin(), req.ownerPinyin()));
    }

    @PostMapping("/franchise-onboard")
    @Operation(summary = "开通加盟商组织及同一用户的老板身份；仅新用户返回一次初始密码")
    @PreAuthorize("@ss.hasPermission('platform:org:onboard')")
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<FranchiseOnboardService.Result> onboard(@Valid @RequestBody OnboardRequest req) {
        return success(service.onboardWithDistrict(req.brand(), req.province(), req.city(), req.area(), req.adminName(), req.adminMobile(),
                req.areaPinyin(), req.ownerPinyin(), req.remark(), SecurityFrameworkUtils.getLoginUserId(),req.districtCode()));
    }
}
