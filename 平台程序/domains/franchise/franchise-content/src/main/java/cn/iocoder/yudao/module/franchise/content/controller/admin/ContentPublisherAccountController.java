package cn.iocoder.yudao.module.franchise.content.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.content.ContentPermissions;
import cn.iocoder.yudao.module.franchise.content.content.ContentPublisherAccountService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/franchise/content/publisher-accounts")
public class ContentPublisherAccountController {
    private final CurrentPost posts;
    private final ContentPublisherAccountService accounts;

    public ContentPublisherAccountController(CurrentPost posts, ContentPublisherAccountService accounts) {
        this.posts = posts; this.accounts = accounts;
    }

    @GetMapping
    @PreAuthorize("@post.has('" + ContentPermissions.QUERY + "')")
    public CommonResult<List<Map<String, Object>>> list(@RequestParam(required = false) String platform) {
        return success(accounts.list(posts.require(), platform).stream().map(ContentPublisherAccountController::view).toList());
    }

    @PostMapping
    @PreAuthorize("@post.has('" + ContentPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return success(view(accounts.create(posts.require(), body)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@post.has('" + ContentPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return success(view(accounts.update(posts.require(), id, body)));
    }

    private static Map<String, Object> view(ContentPublisherAccountService.Account account) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", Long.toString(account.id())); view.put("platform", account.platform());
        view.put("displayName", account.displayName()); view.put("identifier", account.identifier());
        view.put("enabled", account.enabled()); view.put("ownerUserId", Long.toString(account.ownerUserId()));
        view.put("createTime", account.createTime()); view.put("updateTime", account.updateTime());
        view.put("source", "USER_REGISTERED");
        return view;
    }
}
