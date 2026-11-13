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
import org.springframework.web.bind.annotation.PathVariable;
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
@org.springframework.validation.annotation.Validated
public class OrgPointAdminController {

    @Resource
    private OrgPointPort orgPoint;

    @Resource
    private cn.iocoder.yudao.module.commerce.orgpoint.OrgPointPriceService priceService;

    public record PriceReqVO(@jakarta.validation.constraints.NotBlank(message = "科目不能为空") String subject,
                             @jakarta.validation.constraints.NotNull(message = "单价不能为空")
                             @jakarta.validation.constraints.Positive(message = "单价必须为正")
                             @jakarta.validation.constraints.Max(value = 1_000_000_000L,
                                     message = "单价超出上限（1 单位最多 1000 积分）") Long priceMicros,
                             @jakarta.validation.constraints.NotNull(message = "最低收费不能为空")
                             @jakarta.validation.constraints.Min(value = 1, message = "最低收费必须≥1")
                             @jakarta.validation.constraints.Max(value = 1_000_000_000L,
                                     message = "最低收费超出上限（1e9 积分）") Long minCharge) {
    }

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

    @PostMapping("/org-point-operations/{operationId}/settle")
    @Operation(summary = "人工核对：确认已交付，按实际数量结算（D6 §2.4，超时不自动退款）")
    @PreAuthorize("@ss.hasPermission('commerce:org-point:adjust')")
    public CommonResult<OrgPointPort.Settlement> settleOperation(
            @PathVariable("operationId") long operationId,
            @RequestParam(defaultValue = "1") @jakarta.validation.constraints.Min(value = 1,
                    message = "实际数量必须 ≥1；零交付请调用 release") long actualQuantity) {
        return success(orgPoint.settle(operationId, actualQuantity));
    }

    @PostMapping("/org-point-operations/{operationId}/release")
    @Operation(summary = "人工核对：确认未执行，全额释放预扣")
    @PreAuthorize("@ss.hasPermission('commerce:org-point:adjust')")
    public CommonResult<OrgPointPort.Settlement> releaseOperation(
            @PathVariable("operationId") long operationId) {
        return success(orgPoint.release(operationId));
    }

    @GetMapping("/org-point-price-rules")
    @Operation(summary = "公司池科目价格清单（含历史版本）")
    @PreAuthorize("@ss.hasPermission('commerce:org-point-price:query')")
    public CommonResult<java.util.List<cn.iocoder.yudao.module.commerce.orgpoint.OrgPointPriceService.PriceRow>>
            priceRules(@RequestParam(required = false) String subject) {
        return success(priceService.list(subject));
    }

    @GetMapping("/org-point-price-subjects")
    @Operation(summary = "公司池全科目现价（未配置则回退代码默认价）")
    @PreAuthorize("@ss.hasPermission('commerce:org-point-price:query')")
    public CommonResult<java.util.List<cn.iocoder.yudao.module.commerce.orgpoint.OrgPointPriceService.CatalogRow>>
            priceSubjects() {
        return success(priceService.catalog());
    }

    @PostMapping("/org-point-price-rules")
    @Operation(summary = "发布新版本价格（旧生效版自动退休；预扣仍写快照）")
    @PreAuthorize("@ss.hasPermission('commerce:org-point-price:manage')")
    public CommonResult<Long> publishPrice(@Valid @RequestBody PriceReqVO req) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        return success(priceService.publish(req.subject(), req.priceMicros(), req.minCharge(),
                userId == null ? "system" : String.valueOf(userId)));
    }

    @PostMapping("/org-point-price-rules/{id}/retire")
    @Operation(summary = "停用某条生效价（停用后回退代码默认价，历史任务按快照结算）")
    @PreAuthorize("@ss.hasPermission('commerce:org-point-price:manage')")
    public CommonResult<Boolean> retirePrice(@PathVariable("id") long id) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        priceService.retire(id, userId == null ? "system" : String.valueOf(userId));
        return success(true);
    }

    @GetMapping("/org-point-accounts")
    @Operation(summary = "跨公司积分池查询")
    @PreAuthorize("@ss.hasPermission('commerce:org-point:query')")
    public CommonResult<OrgPointPort.PoolSummary> account(@RequestParam Long orgId) {
        return success(orgPoint.summary(orgId));
    }
}
