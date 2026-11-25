package cn.iocoder.yudao.module.franchise.commission.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.commission.ExternalHandoverService;
import cn.iocoder.yudao.module.franchise.commission.ExternalHandoverModel.*;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/franchise/commissions/{commissionId}/external-handover")
public class ExternalHandoverController {
    private final ExternalHandoverService service;
    private final CurrentPost posts;
    public ExternalHandoverController(ExternalHandoverService service,CurrentPost posts) { this.service=service; this.posts=posts; }
    @GetMapping
    @PreAuthorize("@post.has('franchise:commission:query') or @post.has('franchise:engineering:query')")
    public CommonResult<View> get(@PathVariable long commissionId) { return success(service.get(posts.require(),commissionId)); }
    @PostMapping
    @PreAuthorize("@post.has('franchise:commission:submit')")
    public CommonResult<View> submit(@PathVariable long commissionId,@RequestBody Submit input,@RequestHeader("Idempotency-Key") String key) {
        return success(service.submit(posts.require(),commissionId,input,key));
    }
    @PostMapping("/return")
    @PreAuthorize("@post.has('franchise:commission:receive')")
    public CommonResult<View> returnPacket(@PathVariable long commissionId,@RequestBody Decision input,@RequestHeader("Idempotency-Key") String key) {
        return success(service.returnPacket(posts.require(),commissionId,input,key));
    }
    @PostMapping("/receive")
    @PreAuthorize("@post.has('franchise:commission:receive')")
    public CommonResult<View> receive(@PathVariable long commissionId,@RequestBody Decision input,@RequestHeader("Idempotency-Key") String key) {
        return success(service.receive(posts.require(),commissionId,input,key));
    }
    @PostMapping("/versions/{version}/assets/{slot}/download-tickets")
    @PreAuthorize("@post.has('franchise:commission:query') or @post.has('franchise:engineering:query')")
    public CommonResult<AssetAccessPort.DownloadUrlView> download(@PathVariable long commissionId,@PathVariable int version,@PathVariable String slot) {
        return success(service.download(posts.require(),commissionId,version,slot));
    }
}
