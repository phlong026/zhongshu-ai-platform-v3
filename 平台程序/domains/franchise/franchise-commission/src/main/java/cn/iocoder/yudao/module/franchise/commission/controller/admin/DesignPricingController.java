package cn.iocoder.yudao.module.franchise.commission.controller.admin;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.commission.DesignPricingService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/franchise/design-pricing-policies")
public class DesignPricingController {
    private final DesignPricingService pricing;
    private final CurrentPost currentPost;
    public DesignPricingController(DesignPricingService pricing,CurrentPost currentPost) { this.pricing=pricing;this.currentPost=currentPost; }
    @GetMapping("/current")
    public CommonResult<DesignPricingService.Policy> current() { return success(pricing.current(currentPost.require())); }
    @PostMapping
    public CommonResult<DesignPricingService.Policy> save(@RequestBody DesignPricingService.Save body,@RequestHeader("Idempotency-Key") String key) {
        return success(pricing.save(currentPost.require(),body,key));
    }
}
