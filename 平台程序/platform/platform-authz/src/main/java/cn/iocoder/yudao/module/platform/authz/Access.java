package cn.iocoder.yudao.module.platform.authz;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;


import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 对象级规则的通用判断，业务域组合使用（对应小程序 authority-v2.js 中的 sameCompany、isSelf、teamAccess 等）。
 * 「不能自批」由审批中心保证，「不能自核」等业务专属规则由业务域实现。
 */
public final class Access {

    private Access() {
    }

    public static boolean sameOrg(PostSnapshot post, Long orgId) {
        return orgId != null && post.orgId() == orgId;
    }

    /** 是否本人（按实际人员，兼岗切换身份仍是同一人） */
    public static boolean isSelf(PostSnapshot post, Long userId) {
        return userId != null && post.userId() == userId;
    }

    /** 负责人是否管理该团队（所在团队及其下级团队） */
    public static boolean managesTeam(PostSnapshot post, Long teamId) {
        return teamId != null && post.managedTeamIds().contains(teamId);
    }

    /** 不满足时抛「无权访问该资源」 */
    public static void require(boolean allowed) {
        if (!allowed) {
            throw exception(RESOURCE_FORBIDDEN);
        }
    }

}
