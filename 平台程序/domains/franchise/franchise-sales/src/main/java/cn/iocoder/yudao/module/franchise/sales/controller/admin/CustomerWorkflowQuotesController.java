package cn.iocoder.yudao.module.franchise.sales.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.sales.SaleQuoteService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/franchise/customers/{customerId}/workflow/quotes")
@PreAuthorize("@post.has('franchise:customer:query') and @post.has('franchise:sale:query')")
public class CustomerWorkflowQuotesController {
    private final SaleQuoteService quotes;
    private final CurrentPost posts;

    public CustomerWorkflowQuotesController(SaleQuoteService quotes, CurrentPost posts) {
        this.quotes = quotes;
        this.posts = posts;
    }

    @GetMapping
    public CommonResult<List<SaleQuoteService.WorkflowOption>> options(@PathVariable long customerId) {
        return success(quotes.workflowOptions(posts.require(), customerId));
    }

    @GetMapping("/{quoteId}/export-sheet")
    public CommonResult<Map<String, Object>> sheet(@PathVariable long customerId, @PathVariable long quoteId) {
        return success(quotes.workflowSheet(posts.require(), customerId, quoteId));
    }
}
