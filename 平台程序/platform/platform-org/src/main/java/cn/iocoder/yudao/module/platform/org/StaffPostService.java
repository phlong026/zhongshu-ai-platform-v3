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

    /**
     * 用户删除后的授权回收（M01-T01）：撤销其跨组织全部有效任职（撤销人=系统 0），
     * 回收平台角色并按曾任职组织吊销钱包会话。无任职幂等返回。
     */
    void revokeAllForDeletedUser(long userId);

    /**
     * 组织级停职（M01-T02 / R01：组织管理员仅管理本组织）：撤销该用户在指定组织的全部有效任职
     * （撤销人=操作者），回收平台角色并吊销该组织的钱包会话；其他组织任职不受影响。
     * 无有效任职幂等返回。须与停职操作同事务。
     */
    void revokeAllInOrg(long userId, long orgId, long operatorUserId);

    /**
     * 建员并指派编排（M01-T05：原子化事务）：手机号已存在复用账号，否则新建；
     * 指派失败时新账号随同一事务回滚、已有账号原资料与其他组织任职保留。
     * 同组织同岗位已存在时按 M01-T03 校验 teamId：一致幂等返回，不一致抛 POST_TEAM_CONFLICT。
     */
    long createStaffInOrg(long orgId, String name, String mobile, String roleCode, Long teamId, long operatorUserId);

    /**
     * 给已有员工追加岗位（M01-T03 命令语义）：同组织同岗位已存在时 teamId 一致幂等返回，
     * 不一致抛 POST_TEAM_CONFLICT（不静默忽略部门变更）；不存在则按模板校验新建。
     */
    long assignPostInOrg(long userId, long orgId, String roleCode, Long teamId, long operatorUserId);

    /**
     * 钱包发卡前的岗位复验（M01-T01）：外呼（微信换码）暂停期间岗位可能被撤/停用/账号删除。
     * 锁定用户全部岗位行至调用方事务提交（与发卡同事务），确认快照仍有效；失效抛 POST_NOT_FOUND。
     * 并发删除的回收须等本事务提交后才获锁——会话要么未发出、要么发出即被随后回收覆盖。
     */
    void revalidateActivePost(long userId, long postId);

    List<StaffPostDO> listActivePosts(long userId);

    /** 任职属于该用户、仍有效且所在组织启用时返回快照，否则为空 */
    Optional<PostSnapshot> findActivePost(long userId, long postId);

    List<StaffPostDO> listActivePostsInOrg(long orgId, String roleCode);

    /** 用户任职过的全部组织（含已撤销的任职），按编号升序 */
    List<Long> listOrgIdsEverHeld(long userId);

    /** 岗位是否具备 wanted 岗位的身份：相同，或负责人继承了该专员岗位 */
    boolean roleHas(String roleCode, String wanted);

    /** 按编号取仍有效的任职；已撤销或不存在为空 */
    Optional<cn.iocoder.yudao.module.platform.org.dal.StaffPostDO> getPost(long postId);

    /** 组织内全部有效任职（成员管理列表用，含各部门与岗位） */
    List<cn.iocoder.yudao.module.platform.org.dal.StaffPostDO> listOrgPosts(long orgId);

}
