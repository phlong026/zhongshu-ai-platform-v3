package cn.iocoder.yudao.module.franchise.finance.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.finance.FinProjectAccountingService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/franchise/finance/workbench/projects/{projectId}/accounting")
public class FinProjectAccountingController {
    private final FinProjectAccountingService service;
    private final CurrentPost current;
    public FinProjectAccountingController(FinProjectAccountingService service,CurrentPost current) {this.service=service;this.current=current;}
    @GetMapping @PreAuthorize("@post.has('franchise:fin:query')")
    public CommonResult<?> get(@PathVariable long projectId) {return success(service.get(current.require(),projectId));}
    @GetMapping("/snapshots") @PreAuthorize("@post.has('franchise:fin:query')")
    public CommonResult<?> history(@PathVariable long projectId,@RequestParam(defaultValue="1")int pageNo,@RequestParam(defaultValue="20")int pageSize) {
        return success(service.history(current.require(),projectId,pageNo,pageSize));
    }
    @PostMapping("/confirm") @PreAuthorize("@post.has('franchise:fin:query')")
    public CommonResult<?> confirm(@PathVariable long projectId,@RequestHeader("Idempotency-Key")String key,@RequestBody FinProjectAccountingService.Confirm command) {
        return success(service.confirm(current.require(),projectId,key,command));
    }
}
