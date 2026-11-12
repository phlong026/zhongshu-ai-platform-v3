package cn.iocoder.yudao.module.franchise.design.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
@RequestMapping("/admin-api/platform/ops")
public class DesignOperationsController {
    private final DesignOperationsService service;
    public DesignOperationsController(DesignOperationsService service) { this.service = service; }
    @GetMapping("/design-prices")
    @PreAuthorize("@ss.hasPermission('platform:design:price:query')")
    public CommonResult<PageResult<DesignOperationsService.Price>> prices(
            @RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize) {
        return success(service.prices(pageNo, pageSize));
    }
    @GetMapping("/design-costs")
    @PreAuthorize("@ss.hasPermission('platform:design:cost:query')")
    public CommonResult<PageResult<DesignOperationsService.Cost>> costs(
            @RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String orgId) {
        return success(service.costs(pageNo, pageSize, orgId));
    }
}
