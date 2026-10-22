package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;

import java.util.List;
import java.util.Optional;

/**
 * 员工任职（工作身份）。任职变化会同步 yudao 用户角色，保证后台菜单权限与任职一致。
 * 调用方需自行完成权限校验（谁能给谁分配岗位），本服务只保证数据规则。
 */
public interface StaffPostService {

    /** 分配岗位，返回任职编号 */
    long assignPost(long userId, long orgId, String roleCode, Long teamId, long operatorUserId);

    /** 撤销任职；已撤销或不存在抛 POST_NOT_FOUND */
    void revokePost(long postId, long operatorUserId);

    List<StaffPostDO> listActivePosts(long userId);

    /** 任职属于该用户、仍有效且所在组织启用时返回快照，否则为空 */
    Optional<PostSnapshot> findActivePost(long userId, long postId);

    List<StaffPostDO> listActivePostsInOrg(long orgId, String roleCode);

    /** 用户任职过的全部组织（含已撤销的任职），按编号升序 */
    List<Long> listOrgIdsEverHeld(long userId);

    /** 岗位是否具备 wanted 岗位的身份：相同，或负责人继承了该专员岗位 */
    boolean roleHas(String roleCode, String wanted);

}
