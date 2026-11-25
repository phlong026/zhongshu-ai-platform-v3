package cn.iocoder.yudao.module.franchise.sales.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.sales.CustomerPaymentPlanDraftService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/franchise/sales/customers/{customerId}/payment-plan-draft")
public class CustomerPaymentPlanDraftController {
    private final CurrentPost posts;
    private final CustomerPaymentPlanDraftService service;
    public CustomerPaymentPlanDraftController(CurrentPost posts,CustomerPaymentPlanDraftService service){this.posts=posts;this.service=service;}
    @GetMapping @PreAuthorize("@post.has('franchise:sale:query')")
    public CommonResult<?> read(@PathVariable long customerId){return success(service.read(posts.require(),customerId));}
    @PutMapping @PreAuthorize("@post.has('franchise:sale:write')")
    public CommonResult<?> save(@PathVariable long customerId,@RequestHeader("Idempotency-Key")String key,@RequestBody CustomerPaymentPlanDraftService.Save command){return success(service.save(posts.require(),customerId,command,key));}
}
