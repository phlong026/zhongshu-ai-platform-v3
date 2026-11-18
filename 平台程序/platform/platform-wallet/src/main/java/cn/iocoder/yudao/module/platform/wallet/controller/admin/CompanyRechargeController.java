package cn.iocoder.yudao.module.platform.wallet.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.platform.wallet.CompanyRechargeCatalog;
import cn.iocoder.yudao.module.platform.wallet.RechargePaymentService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/franchise/company-points/recharge")
@ConditionalOnProperty(name="platform.legacy-design-bridge.enabled",havingValue="false")
public class CompanyRechargeController {
    private final CompanyRechargeCatalog catalog;
    private final RechargePaymentService payment;
    public CompanyRechargeController(CompanyRechargeCatalog catalog, RechargePaymentService payment) {
        this.catalog=catalog;
        this.payment=payment;
    }
    public record Selection(@NotBlank String planId,@Positive long planVersion) {}
    public record Code(@NotBlank String code) { @Override public String toString() { return "Code[redacted]"; } }

    @GetMapping("/catalog")
    public CommonResult<CompanyRechargeCatalog.Catalog> catalog(HttpServletResponse response) {
        response.setHeader("Cache-Control","private, no-store");
        return CommonResult.success(catalog.read());
    }
    @PostMapping("/orders")
    @PreAuthorize("@post.has('platform:wallet:recharge')")
    public CommonResult<CompanyRechargeCatalog.Order> create(@Valid @RequestBody Selection selection,
            @RequestHeader("Idempotency-Key") String key) {
        return CommonResult.success(CompanyRechargeCatalog.Order.from(payment.createCompanyOrder(selection.planId(),key,selection.planVersion())));
    }
    @PostMapping("/orders/{id}/pay-params")
    @PreAuthorize("@post.has('platform:wallet:recharge')")
    public CommonResult<Map<String,String>> pay(@PathVariable String id,@Valid @RequestBody Code body) {
        return CommonResult.success(payment.payParams(id,body.code(),UUID.randomUUID().toString(),true));
    }
}
