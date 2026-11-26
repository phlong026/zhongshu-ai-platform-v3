package cn.iocoder.yudao.module.franchise.content.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.content.ContentPermissions;
import cn.iocoder.yudao.module.franchise.content.content.ContentCoverService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.COVER_GENERATION_RETIRED;

@RestController
@RequestMapping("/franchise/content/covers")
public class ContentCoverController {
    private final CurrentPost posts;
    private final ContentCoverService covers;
    public ContentCoverController(CurrentPost posts, ContentCoverService covers) { this.posts = posts; this.covers = covers; }

    @GetMapping("/templates")
    @PreAuthorize("@post.has('" + ContentPermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> templates() {
        posts.require();
        throw exception(COVER_GENERATION_RETIRED);
    }

    @PostMapping
    @PreAuthorize("@post.has('" + ContentPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> recover(@RequestHeader("Idempotency-Key") String key,
                                                    @RequestBody Map<String, Object> body) {
        return success(covers.recover(posts.require(), key, body));
    }
}
