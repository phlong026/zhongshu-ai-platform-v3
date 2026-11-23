package cn.iocoder.yudao.module.platform.wallet.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.platform.wallet.PaymentModeService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
@RequestMapping("/admin-api/platform/payment-mode")
public class PaymentModeController {
    private final PaymentModeService service;
    public PaymentModeController(PaymentModeService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("@ss.hasPermission('platform:payment:mode:query')")
    public CommonResult<PaymentModeService.Snapshot> get() {
        return success(service.get());
    }

    @PutMapping
    @PreAuthorize("@ss.hasPermission('platform:payment:mode:update')")
    public CommonResult<PaymentModeService.Snapshot> change(@RequestBody JsonNode body) {
        String mode = body.path("mode").asText("");
        String reason = body.path("reason").asText("");
        long version = body.path("expectedVersion").asLong(-1);
        return success(service.change(mode, reason, version));
    }
}
