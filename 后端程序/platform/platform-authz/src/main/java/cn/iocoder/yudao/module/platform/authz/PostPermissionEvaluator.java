package cn.iocoder.yudao.module.platform.authz;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 只看当前工作身份的功能权限，在控制器上使用：{@code @PreAuthorize("@post.has('franchise:customer:create')")}。
 *
 * <p>yudao 自带的 {@code @ss.hasPermission} 取用户全部角色的并集，兼岗人员会带上其他岗位的权限；
 * 业务域接口一律用本校验，保证「只能办理当前岗位的事」。
 */
@Component("post")
public class PostPermissionEvaluator {

    private final CurrentPost currentPost;
    private final SystemDirectory systemDirectory;

    public PostPermissionEvaluator(CurrentPost currentPost, SystemDirectory systemDirectory) {
        this.currentPost = currentPost;
        this.systemDirectory = systemDirectory;
    }

    /** 当前岗位拥有任一权限码即通过；没有工作身份时不通过 */
    public boolean has(String... permissions) {
        Optional<PostSnapshot> post = currentPost.find();
        if (post.isEmpty()) {
            return false;
        }
        String role = post.get().roleCode();
        for (String permission : permissions) {
            if (systemDirectory.roleHasPermission(role, permission)) {
                return true;
            }
        }
        return false;
    }

}
