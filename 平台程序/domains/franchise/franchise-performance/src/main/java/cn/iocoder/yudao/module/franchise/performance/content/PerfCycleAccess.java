package cn.iocoder.yudao.module.franchise.performance.content;

import cn.iocoder.yudao.module.franchise.performance.PerformancePermissions;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.performance.enums.FranchisePerformanceErrorCodes.PERFORMANCE_FORBIDDEN;

@Component
public class PerfCycleAccess {
    private final CurrentPost current;
    private final StaffPostService posts;
    private final SystemDirectory directory;

    public PerfCycleAccess(CurrentPost current, StaffPostService posts, SystemDirectory directory) {
        this.current = current;
        this.posts = posts;
        this.directory = directory;
    }

    /** Read the live assignment and grants again after a potentially long review or report. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean require(PostSnapshot expected, boolean management) {
        if (!expected.equals(current.require()) || posts.findActivePost(expected.userId(), expected.postId())
                .filter(expected::equals).isEmpty()
                || !directory.roleHasPermission(expected.roleCode(), PerformancePermissions.QUERY)) {
            throw exception(PERFORMANCE_FORBIDDEN);
        }
        boolean manager = ("boss".equals(expected.roleCode()) || "owner".equals(expected.roleCode()))
                && directory.roleHasPermission(expected.roleCode(), PerformancePermissions.MANAGE);
        if (management && !manager) {
            throw exception(PERFORMANCE_FORBIDDEN);
        }
        return manager;
    }

    public boolean activeMember(long company, long user) {
        return directory.userUsable(user) && posts.listActivePosts(user).stream().anyMatch(p -> p.getOrgId() == company);
    }
}
