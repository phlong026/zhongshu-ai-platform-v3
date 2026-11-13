package cn.iocoder.yudao.module.commerce.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 公司积分池（平台管理端，24 号任务书 B-9）：
 * 总部调点与跨公司池查询。权限走 yudao 权限码（平台级操作无 pf 岗位上下文）。
 */
@Tag(name = "公司积分池（平台管理）")
@RestController
@RequestMapping("/design/v1")
public class OrgPointAdminController {

    @Resource
    private OrgPointPort orgPoint;

    public record AdjustReqVO(@NotNull(message = "公司不能为空") Long orgId,
                              @NotNull(message = "调点数量不能为空") Long credits,
                              @NotBlank(message = "幂等键不能为空") String bizKey,
                              String note) {
    }

    @PostMapping("/org-point-adjustments")
    @Operation(summary = "总部调点（bizKey 幂等；负数不得致余额<0）")
    @PreAuthorize("@ss.hasPermission('commerce:org-point:adjust')")
    public CommonResult<OrgPointPort.PoolSummary> adjust(@Valid @RequestBody AdjustReqVO req) {
        Long adminUserId = SecurityFrameworkUtils.getLoginUserId();
        return success(orgPoint.adjust(req.orgId(), req.credits(), req.bizKey(), req.note(), adminUserId));
    }

    @GetMapping("/org-point-accounts")
    @Operation(summary = "跨公司积分池查询")
    @PreAuthorize("@ss.hasPermission('commerce:org-point:query')")
    public CommonResult<OrgPointPort.PoolSummary> account(@RequestParam Long orgId) {
        return success(orgPoint.summary(orgId));
    }
}
