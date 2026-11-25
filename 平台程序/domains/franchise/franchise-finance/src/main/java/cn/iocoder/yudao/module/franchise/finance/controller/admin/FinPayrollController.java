package cn.iocoder.yudao.module.franchise.finance.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.finance.FinPayrollService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/franchise/finance/payroll")
public class FinPayrollController {
    private final CurrentPost current;private final FinPayrollService service;
    public FinPayrollController(CurrentPost current,FinPayrollService service){this.current=current;this.service=service;}
    @GetMapping("/context") @PreAuthorize("@post.has('franchise:fin:query')")
    public CommonResult<?> context(@RequestParam String month){return success(service.context(current.require(),month));}
    @GetMapping("/rules") @PreAuthorize("@post.has('franchise:fin:query')")
    public CommonResult<?> rules(@RequestParam(required=false)String family,@RequestParam(defaultValue="1")int pageNo,@RequestParam(defaultValue="20")int pageSize){return success(service.rules(current.require(),family,pageNo,pageSize));}
    @PostMapping("/rules") @PreAuthorize("@post.has('franchise:fin:register')")
    public CommonResult<?> saveRule(@RequestHeader("Idempotency-Key")String key,@RequestBody FinPayrollService.RuleCommand command){return success(service.saveRule(current.require(),key,command));}
    @GetMapping("/records") @PreAuthorize("@post.has('franchise:fin:query')")
    public CommonResult<?> records(@RequestParam(required=false)String month,@RequestParam(required=false)String family,@RequestParam(defaultValue="1")int pageNo,@RequestParam(defaultValue="20")int pageSize){return success(service.records(current.require(),month,family,pageNo,pageSize));}
    @PostMapping("/records") @PreAuthorize("@post.has('franchise:fin:register')")
    public CommonResult<?> saveRecord(@RequestHeader("Idempotency-Key")String key,@RequestBody FinPayrollService.RecordCommand command){return success(service.saveRecord(current.require(),key,command));}
    @GetMapping("/records/{id}") @PreAuthorize("@post.has('franchise:fin:query')")
    public CommonResult<?> record(@PathVariable long id){return success(service.get(current.require(),id));}
    @GetMapping("/latest") @PreAuthorize("@post.has('franchise:fin:query')")
    public CommonResult<?> latest(@RequestParam long userId,@RequestParam String family,@RequestParam String month){return success(service.latest(current.require(),userId,family,month));}
    @GetMapping("/records/{id}/versions") @PreAuthorize("@post.has('franchise:fin:query')")
    public CommonResult<?> history(@PathVariable long id,@RequestParam(defaultValue="1")int pageNo,@RequestParam(defaultValue="20")int pageSize){return success(service.history(current.require(),id,pageNo,pageSize));}
    @GetMapping("/projects/{id}/stages") @PreAuthorize("@post.has('franchise:fin:query')")
    public CommonResult<?> project(@PathVariable long id){return success(service.project(current.require(),id));}
}
