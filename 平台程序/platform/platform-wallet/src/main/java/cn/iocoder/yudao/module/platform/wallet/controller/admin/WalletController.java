package cn.iocoder.yudao.module.platform.wallet.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.platform.wallet.RechargePaymentService;
import cn.iocoder.yudao.module.platform.wallet.WalletQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "平台 - 组织钱包")
@RestController
@RequestMapping("/platform/wallet")
@Validated
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class WalletController {

    private final WalletQueryService service;
    private final RechargePaymentService payment;

    public WalletController(WalletQueryService service, RechargePaymentService payment) {
        this.service = service;
        this.payment = payment;
    }

    public record CreateOrderRequest(@NotBlank String planId, @NotBlank @Size(max = 128) String idempotencyKey) {
    }

    public record PayParamsRequest(String code, String idempotencyKey) {
        @Override
        public String toString() {
            return "PayParamsRequest[redacted]";
        }
    }

    @GetMapping("/recharge-orders")
    @PreAuthorize("@post.has('platform:wallet:read')")
    @Operation(summary = "分页读取当前组织充值订单")
    public CommonResult<PageResult<RechargePaymentService.OrderResponse>> orders(
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return success(payment.listOrders(pageNo, pageSize));
    }

    @GetMapping("/recharge-orders/{id}")
    @PreAuthorize("@post.has('platform:wallet:read')")
    @Operation(summary = "读取充值订单的支付与入账状态")
    public CommonResult<RechargePaymentService.OrderResponse> order(@PathVariable String id) {
        return success(payment.getOrder(id));
    }

    @PostMapping("/recharge-orders")
    @PreAuthorize("@post.has('platform:wallet:recharge')")
    @Operation(summary = "按当前员工绑定创建组织充值订单")
    public CommonResult<RechargePaymentService.OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        return success(payment.createOrder(request.planId(), request.idempotencyKey()));
    }

    @PostMapping("/recharge-orders/{id}/pay-params")
    @PreAuthorize("@post.has('platform:wallet:recharge')")
    @Operation(summary = "使用一次性微信登录码获取支付参数")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<Map<String, String>> payParams(@PathVariable String id, @RequestBody PayParamsRequest request) {
        return success(payment.payParams(id, request.code(), request.idempotencyKey()));
    }

    @GetMapping("/account")
    @Operation(summary = "当前岗位所属组织的钱包")
    @PreAuthorize("@post.has('platform:wallet:read')")
    public CommonResult<WalletQueryService.Account> account() {
        return success(service.account());
    }

    @GetMapping("/ledger")
    @Operation(summary = "当前组织的钱包流水")
    @PreAuthorize("@post.has('platform:wallet:read')")
    public CommonResult<PageResult<WalletQueryService.LedgerItem>> ledger(
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return success(service.ledger(pageNo, pageSize));
    }

    @GetMapping("/recharge-plans")
    @Operation(summary = "当前租户可用充值套餐")
    @PreAuthorize("@post.has('platform:wallet:recharge')")
    public CommonResult<List<WalletQueryService.RechargePlan>> rechargePlans() {
        return success(service.rechargePlans());
    }
}
