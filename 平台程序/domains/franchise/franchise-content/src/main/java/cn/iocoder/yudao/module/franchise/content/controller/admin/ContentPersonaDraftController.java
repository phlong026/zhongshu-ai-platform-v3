package cn.iocoder.yudao.module.franchise.content.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.content.content.ContentPersonaDraftService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/franchise/content/personas/drafts")
public class ContentPersonaDraftController {
    @Resource private CurrentPost currentPost;
    @Resource private ContentPersonaDraftService drafts;
    public record Save(@NotNull @PositiveOrZero Long revision, @NotNull Map<String,Object> form, @PositiveOrZero Long basePersonaVersion, boolean rebase) {}
    public record Publish(@NotNull @PositiveOrZero Long revision) {}
    @GetMapping("{key}")
    @PreAuthorize("@post.has('franchise:content:persona','franchise:content:persona:self')")
    public CommonResult<ContentPersonaDraftService.Draft> get(@PathVariable("key") String key) {
        return success(drafts.get(currentPost.require(),key));
    }
    @PutMapping("{key}")
    @PreAuthorize("@post.has('franchise:content:persona','franchise:content:persona:self')")
    public CommonResult<ContentPersonaDraftService.Draft> save(@PathVariable("key") String key,@Valid @RequestBody Save body) {
        return success(drafts.save(currentPost.require(),key,body.revision(),body.form(),body.basePersonaVersion(),body.rebase()));
    }
    @PostMapping("{key}/publish")
    @PreAuthorize("@post.has('franchise:content:persona','franchise:content:persona:self')")
    public CommonResult<Map<String,Object>> publish(@PathVariable("key") String key,@Valid @RequestBody Publish body) {
        var post=currentPost.require();
        return success(ContentPersonaController.view(drafts.publish(post,key,body.revision()),post));
    }
}
