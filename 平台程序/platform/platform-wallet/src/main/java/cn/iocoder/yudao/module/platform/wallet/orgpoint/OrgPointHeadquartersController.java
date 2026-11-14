package cn.iocoder.yudao.module.platform.wallet.orgpoint;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
@RequestMapping("/admin-api/platform/org-points")
public class OrgPointHeadquartersController {
    private final OrgPointHeadquartersService service;

    public OrgPointHeadquartersController(OrgPointHeadquartersService service) {
        this.service = service;
    }

    @GetMapping("/companies")
    @PreAuthorize("@ss.hasPermission('platform:org-point:query')")
    public CommonResult<List<OrgPointHeadquartersService.CompanyPool>> companies() {
        return success(service.companies(actor()));
    }

    @GetMapping("/{orgId}/summary")
    @PreAuthorize("@ss.hasPermission('platform:org-point:query')")
    public CommonResult<?> summary(@PathVariable long orgId) {
        return success(service.summary(actor(), orgId));
    }

    @GetMapping("/{orgId}/ledger")
    @PreAuthorize("@ss.hasPermission('platform:org-point:query')")
    public CommonResult<?> ledger(@PathVariable long orgId,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return success(service.ledger(actor(), orgId, page, size));
    }

    public record SubmitAdjustment(long delta, String reason, String requestKey) { }
    public record ReviewAdjustment(boolean approve, String comment) { }
    public record PublishPrice(String subject, long priceMicros, long minCharge, String reason) { }

    @PostMapping("/{orgId}/adjustments")
    @PreAuthorize("@ss.hasPermission('platform:org-point:adjustment:submit')")
    public CommonResult<Long> submit(@PathVariable long orgId, @RequestBody SubmitAdjustment body) {
        return success(service.submitAdjustment(actor(), orgId, body.delta(), body.reason(), body.requestKey()));
    }

    @PutMapping("/adjustments/{id}/review")
    @PreAuthorize("@ss.hasPermission('platform:org-point:adjustment:review')")
    public CommonResult<Long> review(@PathVariable long id, @RequestBody ReviewAdjustment body) {
        return success(service.reviewAdjustment(actor(), id, body.approve(), body.comment()));
    }

    @GetMapping("/{orgId}/adjustments")
    @PreAuthorize("@ss.hasPermission('platform:org-point:query')")
    public CommonResult<List<OrgPointHeadquartersService.Adjustment>> adjustments(@PathVariable long orgId) {
        return success(service.adjustments(actor(), orgId));
    }

    @GetMapping("/prices")
    @PreAuthorize("@ss.hasPermission('platform:org-point:price:query')")
    public CommonResult<List<OrgPointPriceService.CatalogRow>> prices() {
        return success(service.catalog(actor()));
    }

    @PostMapping("/prices")
    @PreAuthorize("@ss.hasPermission('platform:org-point:price:update')")
    public CommonResult<Long> publishPrice(@RequestBody PublishPrice body) {
        return success(service.publishPrice(actor(), body.subject(), body.priceMicros(), body.minCharge(), body.reason()));
    }

    private static long actor() {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null) throw new cn.iocoder.yudao.framework.common.exception.ServiceException(401, "未登录");
        return actor;
    }
}
