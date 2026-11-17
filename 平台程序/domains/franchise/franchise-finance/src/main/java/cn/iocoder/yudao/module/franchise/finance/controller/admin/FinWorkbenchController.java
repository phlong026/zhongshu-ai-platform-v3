package cn.iocoder.yudao.module.franchise.finance.controller.admin;
import cn.iocoder.yudao.module.franchise.finance.*;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import java.util.*;
@RestController
@RequestMapping("/franchise/finance/workbench")
public class FinWorkbenchController {
    private final FinWorkbenchService service;private final CurrentPost current;
    public FinWorkbenchController(FinWorkbenchService service,CurrentPost current){this.service=service;this.current=current;}
    @GetMapping("/books") @PreAuthorize("@post.has('franchise:fin:query')")
    public CommonResult<?> books(@RequestParam(required=false) String keyword){return success(service.books(current.require().orgId(),keyword));}
    @PostMapping("/books") @PreAuthorize("@post.has('franchise:fin:register')")
    public CommonResult<?> create(@RequestHeader("Idempotency-Key") String key,@RequestBody FinWorkbenchService.BookCommand cmd){return success(Map.of("id",String.valueOf(service.createBook(current.require(),key,cmd))));}
    @PostMapping("/books/{id}/project/{project}") @PreAuthorize("@post.has('franchise:fin:register')")
    public CommonResult<?> link(@PathVariable long id,@PathVariable long project,@RequestHeader("Idempotency-Key") String key){service.linkProject(current.require(),id,project,key);return success(true);}
    @GetMapping("/ledger") @PreAuthorize("@post.has('franchise:fin:query')")
    public CommonResult<?> ledger(@RequestParam String scope){return success(service.ledger(current.require().orgId(),scope));}
    @GetMapping("/reimbursements") @PreAuthorize("@post.has('franchise:fin:query')")
    public CommonResult<?> reimbursements(){return success(service.reimbursements(current.require().orgId()));}
    @PostMapping("/reimbursements/{id}/recover") @PreAuthorize("@post.has('franchise:fin:register')")
    public CommonResult<?> recover(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody FinWorkbenchService.Recovery cmd){return success(Map.of("receiptId",String.valueOf(service.recoverEmployee(current.require(),id,key,cmd))));}
    @PostMapping("/entries/{id}/supplement") @PreAuthorize("@post.has('franchise:fin:register')")
    public CommonResult<?> supplement(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody FinWorkbenchService.Supplement cmd){service.supplement(current.require(),id,key,cmd);return success(true);}
    @GetMapping("/staff") @PreAuthorize("@post.has('franchise:fin:register')")
    public CommonResult<?> staff(){return success(service.staff(current.require().orgId()));}
    @PostMapping("/payables/{id}/adjust") @PreAuthorize("@post.has('franchise:fin:register')")
    public CommonResult<?> adjust(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody FinWorkbenchService.PayableAdjustment cmd){service.adjustPayable(current.require(),id,key,cmd);return success(true);}
    @PostMapping("/payables/{id}/assign") @PreAuthorize("@post.has('franchise:fin:register')")
    public CommonResult<?> assign(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody FinWorkbenchService.Assignment cmd){service.assignPayable(current.require(),id,key,cmd);return success(true);}
    @PostMapping("/entries") @PreAuthorize("@post.has('franchise:fin:register')")
    public CommonResult<?> register(@RequestHeader("Idempotency-Key") String key,@RequestBody FinWorkbenchService.EntryCommand cmd){return success(Map.of("id",String.valueOf(service.register(current.require(),key,cmd))));}
}
