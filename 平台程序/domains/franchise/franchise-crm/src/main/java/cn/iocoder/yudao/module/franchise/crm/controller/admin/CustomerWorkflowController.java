package cn.iocoder.yudao.module.franchise.crm.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel.*;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/franchise/customers")
public class CustomerWorkflowController {
    private final CustomerWorkflowService workflows;
    private final CurrentPost posts;
    public CustomerWorkflowController(CustomerWorkflowService workflows, CurrentPost posts) { this.workflows = workflows; this.posts = posts; }

    @GetMapping("/{id}/workflow")
    @PreAuthorize("@post.has('franchise:customer:query')")
    public CommonResult<View> get(@PathVariable long id) { return success(workflows.get(posts.require(), id)); }

    @GetMapping("/workflow-summaries")
    @PreAuthorize("@post.has('franchise:customer:query')")
    public CommonResult<Map<Long, Summary>> summaries(@RequestParam List<Long> customerIds) {
        return success(workflows.summaries(posts.require(), customerIds));
    }

    @GetMapping("/{id}/workflow/records")
    @PreAuthorize("@post.has('franchise:customer:query')")
    public CommonResult<PageResult<RecordView>> records(@PathVariable long id, @Valid PageParam page) {
        return success(workflows.records(posts.require(), id, page.getPageNo(), page.getPageSize()));
    }

    @GetMapping("/{id}/workflow/timeline")
    @PreAuthorize("@post.has('franchise:customer:query')")
    public CommonResult<PageResult<RecordView>> timeline(@PathVariable long id, @Valid PageParam page) {
        return success(workflows.timeline(posts.require(), id, page.getPageNo(), page.getPageSize()));
    }

    @PostMapping("/{id}/workflow/steps/{step}/assets/{assetId}/download-tickets")
    @PreAuthorize("@post.has('franchise:customer:query')")
    public CommonResult<cn.iocoder.yudao.module.franchise.api.AssetAccessPort.DownloadUrlView> download(
            @PathVariable long id, @PathVariable String step, @PathVariable long assetId) {
        return success(workflows.download(posts.require(), id, step, assetId));
    }

    @PutMapping("/{id}/workflow/steps/{step}")
    @PreAuthorize("@post.has('franchise:customer:write')")
    public CommonResult<View> save(@PathVariable long id, @PathVariable String step, @RequestBody SaveCommand body,
                                 @RequestHeader("Idempotency-Key") String key) {
        return success(workflows.save(posts.require(), id, step, body, key));
    }

    @PostMapping("/{id}/workflow/steps/{step}/complete")
    @PreAuthorize("@post.has('franchise:customer:write')")
    public CommonResult<View> complete(@PathVariable long id, @PathVariable String step, @RequestBody RevisionCommand body,
                                     @RequestHeader("Idempotency-Key") String key) {
        return success(workflows.complete(posts.require(), id, step, body.revision(), key));
    }

    @PostMapping("/{id}/workflow/steps/{step}/reopen")
    @PreAuthorize("@post.has('franchise:customer:write')")
    public CommonResult<View> reopen(@PathVariable long id, @PathVariable String step, @RequestBody ReopenCommand body,
                                   @RequestHeader("Idempotency-Key") String key) {
        return success(workflows.reopen(posts.require(), id, step, body, key));
    }

    @PostMapping("/{id}/workflow/steps/SITE_VISIT/skip")
    @PreAuthorize("@post.has('franchise:customer:write')")
    public CommonResult<View> skipVisit(@PathVariable long id, @RequestBody SkipVisitCommand body,
                                        @RequestHeader("Idempotency-Key") String key) {
        return success(workflows.skipVisit(posts.require(), id, body, key));
    }

    @PostMapping("/{id}/workflow/status")
    @PreAuthorize("@post.has('franchise:customer:write')")
    public CommonResult<View> status(@PathVariable long id, @RequestBody StatusCommand body,
                                   @RequestHeader("Idempotency-Key") String key) {
        return success(workflows.status(posts.require(), id, body, key));
    }
}
