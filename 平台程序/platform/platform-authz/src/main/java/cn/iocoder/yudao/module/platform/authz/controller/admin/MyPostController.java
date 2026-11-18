package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 我的任职：登录后列出本人全部有效岗位供身份切换（对应小程序的岗位选择）。
 * 发生在携带 X-Post-Id 之前，登录即可查看，不挂菜单权限。
 */
@Tag(name = "平台 - 我的任职")
@RestController
@RequestMapping("/platform/posts")
public class MyPostController {

    @Resource
    private StaffPostService staffPostService;
    @Resource
    private CurrentPost currentPost;
    @Resource
    private SystemDirectory systemDirectory;

    public record StaffPost(long postId, long userId, long orgId, String orgName, String orgType, String roleCode,
                            Long teamId, boolean lead, List<String> permissions) {

        public static StaffPost of(PostSnapshot snapshot, String orgName, List<String> permissions) {
            return new StaffPost(snapshot.postId(), snapshot.userId(), snapshot.orgId(), orgName, snapshot.orgType(),
                    snapshot.roleCode(), snapshot.teamId(), snapshot.lead(),permissions);
        }
    }

    // 前端按岗位权限渲染 /fr 菜单与页面的能力清单（逐项经授权真源过滤，不暴露账号全部角色并集）。
    // 消费路由：org:query/manage→/fr/org/staff；franchise:fin:query→/fr/finance（P0 批4）；
    // franchise:performance:operate→/fr/dashboard（P1）；platform:wallet:read→/fr 钱包对账 tab（P1）。
    // 新增写权限码一律不入白名单；既有 org:manage 保留（/fr/org/staff 既有页面能力）。
    private static final List<String> WEB_CAPABILITIES = List.of(
            "platform:org:query",
            "platform:org:manage",
            "franchise:fin:query",
            "franchise:performance:operate",
            "platform:wallet:read");

    public record PostContext(StaffPost post, List<String> permissions, Set<Long> managedTeamIds) {
    }

    @GetMapping("/mine")
    @Operation(summary = "本人全部有效任职；postId 用于后续请求的 X-Post-Id 切换当前岗位")
    public CommonResult<List<StaffPost>> mine() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            return success(List.of());
        }
        List<StaffPostDO> posts = staffPostService.listActivePosts(userId);
        Map<Long, String> names = new HashMap<>();
        return success(posts.stream()
                .flatMap(post -> staffPostService.findActivePost(userId, post.getId()).stream())
                .map(post -> StaffPost.of(post, names.computeIfAbsent(post.orgId(), this::orgName), mapPermissions(post)))
                .toList());
    }

    @GetMapping("/current-context")
    @Operation(summary = "经实时校验的当前任职及组织管理 Web 能力；多岗须带 X-Post-Id")
    public CommonResult<PostContext> currentContext() {
        PostSnapshot post = currentPost.require();
        // 仅暴露本工作区需要的能力，授权真源与 @post.has 相同；不能使用账号的全部角色并集。
        List<String> permissions = WEB_CAPABILITIES.stream()
                .filter(permission -> systemDirectory.roleHasPermission(post.roleCode(), permission))
                .toList();
        return success(new PostContext(StaffPost.of(post, orgName(post.orgId()),mapPermissions(post)), permissions,
                post.managedTeamIds()));
    }

    private String orgName(long orgId) {
        return Objects.requireNonNullElse(systemDirectory.deptName(orgId), "");
    }
    private List<String> mapPermissions(PostSnapshot post) {
        String permission=cn.iocoder.yudao.module.platform.authz.service.FranchiseMapService.READ_ALL;
        return "headquarters".equals(post.orgType()) && systemDirectory.roleHasPermission(post.roleCode(),permission)
                ? List.of(permission) : List.of();
    }
}
