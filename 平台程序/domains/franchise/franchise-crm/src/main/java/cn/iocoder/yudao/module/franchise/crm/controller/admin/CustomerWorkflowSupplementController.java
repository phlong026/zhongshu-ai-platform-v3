package cn.iocoder.yudao.module.franchise.crm.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowSupplementService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/franchise/customers/{id}/workflow/steps/{step}/supplements")
public class CustomerWorkflowSupplementController {
    private final CustomerWorkflowSupplementService supplements;
    private final CurrentPost posts;

    public CustomerWorkflowSupplementController(CustomerWorkflowSupplementService supplements, CurrentPost posts) {
        this.supplements = supplements;
        this.posts = posts;
    }

    @GetMapping
    @PreAuthorize("@post.has('franchise:customer:query')")
    public CommonResult<CustomerWorkflowSupplementService.Entries> list(
            @PathVariable long id, @PathVariable String step, @Valid PageParam page) {
        return success(supplements.list(posts.require(), id, step, page.getPageNo(), page.getPageSize()));
    }

    @PostMapping
    @PreAuthorize("@post.has('franchise:customer:write')")
    public CommonResult<CustomerWorkflowSupplementService.Entry> append(
            @PathVariable long id, @PathVariable String step, @RequestBody CustomerWorkflowSupplementService.Input input,
            @RequestHeader("Idempotency-Key") String key) {
        return success(supplements.append(posts.require(), id, step, input, key));
    }
}
