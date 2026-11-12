package cn.iocoder.yudao.module.platform.wallet.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
@RequestMapping("/admin-api/platform/ops")
public class WalletConfigurationController {
    private final RechargePlanAdminService plans;
    private final OperationsAuditService audit;
    public WalletConfigurationController(RechargePlanAdminService plans,OperationsAuditService audit) {
        this.plans=plans; this.audit=audit;
    }
    @PostMapping("/recharge-plans")
    @PreAuthorize("@ss.hasPermission('platform:wallet:plan:update')")
    public CommonResult<WalletOperationsService.Plan> create(@RequestBody JsonNode body) {
        return success(plans.create(RechargePlanAdminService.Change.from(body)));
    }
    @PutMapping("/recharge-plans/{id}")
    @PreAuthorize("@ss.hasPermission('platform:wallet:plan:update')")
    public CommonResult<WalletOperationsService.Plan> update(@PathVariable String id,@RequestBody JsonNode body) {
        return success(plans.update(id,RechargePlanAdminService.Change.from(body)));
    }
    @GetMapping("/audit")
    @PreAuthorize("@ss.hasPermission('platform:ops:audit:query')")
    public CommonResult<PageResult<OperationsAuditService.Audit>> audit(
            @RequestParam(defaultValue="1") int pageNo,@RequestParam(defaultValue="20") int pageSize) {
        return success(audit.page(pageNo,pageSize));
    }
    @GetMapping("/anomalies/{id}/reviews")
    @PreAuthorize("@ss.hasPermission('platform:wallet:anomaly:query')")
    public CommonResult<PageResult<OperationsAuditService.Review>> reviews(@PathVariable String id,
            @RequestParam(defaultValue="1") int pageNo,@RequestParam(defaultValue="20") int pageSize) {
        return success(audit.reviews(id,pageNo,pageSize));
    }
    @PostMapping("/anomalies/{id}/reviews")
    @PreAuthorize("@ss.hasPermission('platform:wallet:anomaly:review')")
    public CommonResult<String> review(@PathVariable String id,@RequestBody JsonNode body) {
        return success(audit.review(id,OperationsInput.text(body,"note",512,true)));
    }
}
