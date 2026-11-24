package cn.iocoder.yudao.module.franchise.content.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.content.ContentPermissions;
import cn.iocoder.yudao.module.franchise.content.content.ContentCoverService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/franchise/content/covers")
public class ContentCoverController {
    private final CurrentPost posts;
    private final ContentCoverService covers;
    public ContentCoverController(CurrentPost posts, ContentCoverService covers) { this.posts = posts; this.covers = covers; }

    @GetMapping("/templates")
    @PreAuthorize("@post.has('" + ContentPermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> templates(@RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return success(covers.templates(posts.require(), page, size));
    }

    @PostMapping
    @PreAuthorize("@post.has('" + ContentPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> create(@RequestHeader("Idempotency-Key") String key,
                                                   @RequestBody Map<String, Object> body) {
        return success(covers.create(posts.require(), key, body));
    }
}
