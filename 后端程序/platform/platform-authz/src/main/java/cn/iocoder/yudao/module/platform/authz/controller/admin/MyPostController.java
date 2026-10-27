package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    public record StaffPost(long postId, long userId, long orgId, String orgType, String roleCode,
                            Long teamId, boolean lead) {

        public static StaffPost of(PostSnapshot snapshot) {
            return new StaffPost(snapshot.postId(), snapshot.userId(), snapshot.orgId(), snapshot.orgType(),
                    snapshot.roleCode(), snapshot.teamId(), snapshot.lead());
        }
    }

    @GetMapping("/mine")
    @Operation(summary = "本人全部有效任职；postId 用于后续请求的 X-Post-Id 切换当前岗位")
    public CommonResult<List<StaffPost>> mine() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            return success(List.of());
        }
        List<StaffPostDO> posts = staffPostService.listActivePosts(userId);
        return success(posts.stream().map(post -> staffPostService.findActivePost(userId, post.getId())
                .map(StaffPost::of).orElse(null)).toList());
    }

}
