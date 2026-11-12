package cn.iocoder.yudao.module.platform.wallet.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
// 独立 admin 包不匹配框架 **.controller.admin.**，因此显式声明 ADMIN 前缀。
@RequestMapping("/admin-api/platform/ops")
public class WalletOperationsController {
    private final WalletOperationsService service;
    public WalletOperationsController(WalletOperationsService service) { this.service = service; }

    @GetMapping("/wallets")
    @PreAuthorize("@ss.hasPermission('platform:wallet:query')")
    public CommonResult<PageResult<WalletOperationsService.Wallet>> wallets(
            @RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String orgId) {
        return success(service.wallets(pageNo, pageSize, orgId));
    }
    @GetMapping("/recharge-plans")
    @PreAuthorize("@ss.hasPermission('platform:wallet:plan:query')")
    public CommonResult<PageResult<WalletOperationsService.Plan>> plans(
            @RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize) {
        return success(service.plans(pageNo, pageSize));
    }
    @GetMapping("/recharge-orders")
    @PreAuthorize("@ss.hasPermission('platform:wallet:order:query')")
    public CommonResult<PageResult<WalletOperationsService.Order>> orders(
            @RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String orgId) {
        return success(service.orders(pageNo, pageSize, orgId));
    }
    @GetMapping("/ledger")
    @PreAuthorize("@ss.hasPermission('platform:wallet:ledger:query')")
    public CommonResult<PageResult<WalletOperationsService.Ledger>> ledger(
            @RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String orgId) {
        return success(service.ledger(pageNo, pageSize, orgId));
    }
    @GetMapping("/anomalies")
    @PreAuthorize("@ss.hasPermission('platform:wallet:anomaly:query')")
    public CommonResult<PageResult<WalletOperationsService.Anomaly>> anomalies(
            @RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String orgId) {
        return success(service.anomalies(pageNo, pageSize, orgId));
    }
}
