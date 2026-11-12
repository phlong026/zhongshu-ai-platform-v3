package cn.iocoder.yudao.module.franchise.design.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
@RequestMapping("/admin-api/platform/ops/design-prices")
public class DesignPriceAdminController {
    private final DesignPriceAdminService service;
    public DesignPriceAdminController(DesignPriceAdminService service) { this.service=service; }
    @PostMapping
    @PreAuthorize("@ss.hasPermission('platform:design:price:update')")
    public CommonResult<DesignOperationsService.Price> create(@RequestBody JsonNode body) {
        return success(service.create(DesignPriceAdminService.Change.from(body)));
    }
    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermission('platform:design:price:update')")
    public CommonResult<DesignOperationsService.Price> update(@PathVariable String id,@RequestBody JsonNode body) {
        return success(service.update(id,DesignPriceAdminService.Change.from(body)));
    }
}
