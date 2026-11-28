package cn.iocoder.yudao.module.franchise.sales.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.sales.SaleReceiptCorrectionService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/franchise/sales/receipts")
public class SaleReceiptCorrectionController {
    private final SaleReceiptCorrectionService service;
    private final CurrentPost current;
    public SaleReceiptCorrectionController(SaleReceiptCorrectionService service, CurrentPost current) {
        this.service=service; this.current=current;
    }

    @GetMapping("/{id}/correction")
    @PreAuthorize("@post.has('franchise:fin:register')")
    public CommonResult<?> detail(@PathVariable long id) {
        return success(service.detail(current.require(),id));
    }

    @PostMapping("/{id}/correction")
    @PreAuthorize("@post.has('franchise:fin:register')")
    public CommonResult<?> correct(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
                                  @RequestBody SaleReceiptCorrectionService.Correction change) {
        return success(service.correct(current.require(),id,change,key));
    }

    @GetMapping("/{id}/correction/registration-result")
    @PreAuthorize("@post.has('franchise:fin:register')")
    public CommonResult<?> result(@PathVariable long id, @RequestParam String key) {
        return success(service.registrationResult(current.require(),id,key));
    }
}
