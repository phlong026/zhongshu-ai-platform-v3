package cn.iocoder.yudao.module.franchise.commission.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.commission.ExternalCommissionService;
import cn.iocoder.yudao.module.franchise.commission.ExternalCommissionModel.*;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/franchise/customers/{customerId}/external-entrust")
public class ExternalCommissionController {
    private final ExternalCommissionService service;
    private final CurrentPost posts;
    public ExternalCommissionController(ExternalCommissionService service, CurrentPost posts) { this.service=service; this.posts=posts; }
    @GetMapping
    @PreAuthorize("@post.has('franchise:commission:query')")
    public CommonResult<View> get(@PathVariable long customerId) { return success(service.get(posts.require(), customerId)); }
    @PostMapping
    @PreAuthorize("@post.has('franchise:commission:submit')")
    public CommonResult<View> register(@PathVariable long customerId, @RequestBody RequestCommand input, @RequestHeader("Idempotency-Key") String key) {
        return success(service.register(posts.require(),customerId,input,key));
    }
    @PostMapping("/convert-legacy")
    @PreAuthorize("@post.has('franchise:commission:submit')")
    public CommonResult<View> convert(@PathVariable long customerId, @RequestBody RequestCommand input, @RequestHeader("Idempotency-Key") String key) {
        return success(service.convertLegacy(posts.require(),customerId,input,key));
    }
    @PostMapping("/results")
    @PreAuthorize("@post.has('franchise:commission:submit')")
    public CommonResult<View> results(@PathVariable long customerId, @RequestBody ResultCommand input, @RequestHeader("Idempotency-Key") String key) {
        return success(service.results(posts.require(),customerId,input,key));
    }
    @PostMapping("/{kind:requests|results}/{version}/assets/{assetId}/download-tickets")
    @PreAuthorize("@post.has('franchise:commission:query')")
    public CommonResult<AssetAccessPort.DownloadUrlView> download(@PathVariable long customerId, @PathVariable String kind,
            @PathVariable int version, @PathVariable long assetId) {
        return success(service.download(posts.require(),customerId,kind,version,assetId));
    }
}
