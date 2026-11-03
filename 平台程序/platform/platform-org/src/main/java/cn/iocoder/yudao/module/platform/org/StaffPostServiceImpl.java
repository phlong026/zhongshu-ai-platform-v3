package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.module.platform.org.dal.*;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.*;

@Service
public class StaffPostServiceImpl implements StaffPostService {

    /** 系统发起的撤销操作者编号（M01-T01 用户删除回收；非人工操作） */
    private static final long REVOKED_BY_SYSTEM = 0L;

    @Resource
    private StaffPostMapper staffPostMapper;
    @Resource
    private PostTemplateMapper postTemplateMapper;
    @Resource
    private OrgUnitMapper orgUnitMapper;
    @Resource
    private SystemDirectory systemDirectory;
    /** 钱包会话撤权回调（franchise-org 实现；组装裁剪时静默跳过） */
    @Resource
    private ObjectProvider<WalletSessionRevocation> walletSessionRevocation;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long assignPost(long userId, long orgId, String roleCode, Long teamId, long operatorUserId) {
        PostTemplateDO template = postTemplateMapper.selectById(roleCode);
        if (template == null) {
            throw exception(POST_TEMPLATE_NOT_FOUND);
        }
        // 09 钱包会话桥接撤权协议：锁序=用户→组织（岗位新增/发卡与成员停用/组织停用经行锁互斥）
        // M01-T01：advisory 用户锁先行——零历史岗位用户的行锁为空集，与删除回收并发须靠本锁串行化
        staffPostMapper.lockUserAdvisoryForWrite(userId);
        staffPostMapper.lockUserPostsForShare(userId);
        orgUnitMapper.lockOrgForShare(orgId);
        OrgUnitDO org = orgUnitMapper.selectById(orgId);
        if (org == null || !org.isOrg()) {
            throw exception(ORG_NOT_FOUND);
        }
        if (!org.isActive()) {
            throw exception(ORG_DISABLED);
        }
        if (!template.allows(org.getOrgType())) {
            throw exception(POST_NOT_ALLOWED_FOR_ORG);
        }
        if (teamId != null) {
            OrgUnitDO team = orgUnitMapper.selectById(teamId);
            if (team == null || team.isOrg() || team.getOrgId() != orgId) {
                throw exception(TEAM_NOT_IN_ORG);
            }
        }
        if (!systemDirectory.userExists(userId)) {
            throw exception(USER_NOT_FOUND);
        }
        if (systemDirectory.roleIdByCode(roleCode) == null) {
            throw exception(ROLE_NOT_CONFIGURED);
        }
        if (staffPostMapper.existsActive(userId, orgId, roleCode)) {
            throw exception(POST_ALREADY_ACTIVE);
        }
        StaffPostDO post = new StaffPostDO().setUserId(userId).setOrgId(orgId).setRoleCode(roleCode)
                .setTeamId(teamId).setStatus(StaffPostDO.STATUS_ACTIVE).setCreatedBy(operatorUserId);
        try {
            staffPostMapper.insert(post);
        } catch (DuplicateKeyException e) { // 并发重复分配，被部分唯一索引拦下
            throw exception(POST_ALREADY_ACTIVE);
        }
        syncUserRoles(userId);
        return post.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokePost(long postId, long operatorUserId) {
        // 撤权协议：岗位行排他锁后再改状态；状态修改与钱包会话吊销同事务提交/回滚
        staffPostMapper.lockPostForUpdate(postId);
        StaffPostDO post = staffPostMapper.selectById(postId);
        if (post == null || !StaffPostDO.STATUS_ACTIVE.equals(post.getStatus())) {
            throw exception(POST_NOT_FOUND);
        }
        staffPostMapper.updateById(new StaffPostDO().setId(postId).setStatus(StaffPostDO.STATUS_REVOKED)
                .setRevokedAt(OffsetDateTime.now()).setRevokedBy(operatorUserId));
        syncUserRoles(post.getUserId());
        walletSessionRevocation.ifAvailable(cb -> cb.revokeForUserOrgs(post.getUserId(), List.of(post.getOrgId())));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeAllForDeletedUser(long userId) {
        // 09 撤权协议：先取用户全部岗位行排他锁（覆盖其全部组织，堵跨组织窗口）
        // M01-T01：advisory 用户锁先行，堵「删除回收与首次分配并发」的零岗位窗口（Codex R1 P2-4）
        staffPostMapper.lockUserAdvisoryForWrite(userId);
        staffPostMapper.lockUserPostsForUpdate(userId);
        List<StaffPostDO> actives = staffPostMapper.selectActiveByUser(userId);
        if (actives.isEmpty()) {
            return; // 无有效任职：幂等返回，不触发钱包调用
        }
        for (StaffPostDO post : actives) {
            staffPostMapper.updateById(new StaffPostDO().setId(post.getId()).setStatus(StaffPostDO.STATUS_REVOKED)
                    .setRevokedAt(OffsetDateTime.now()).setRevokedBy(REVOKED_BY_SYSTEM));
        }
        syncUserRoles(userId);
        // 按曾任职组织吊销钱包会话（含此前已撤销组织，覆盖孤儿会话）；与状态修改同事务
        List<Long> orgIds = staffPostMapper.selectOrgIdsEverHeld(userId);
        walletSessionRevocation.ifAvailable(cb -> cb.revokeForUserOrgs(userId, orgIds));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revalidateActivePost(long userId, long postId) {
        // M01-T01：锁用户全部岗位行至调用方（发卡）事务提交，堵外呼暂停窗口（Codex R1 P1-3）
        staffPostMapper.lockUserPostsForUpdate(userId);
        if (findActivePost(userId, postId).isEmpty()) {
            throw exception(POST_NOT_FOUND);
        }
    }

    @Override
    public List<StaffPostDO> listActivePosts(long userId) {
        return staffPostMapper.selectActiveByUser(userId);
    }

    @Override
    public Optional<PostSnapshot> findActivePost(long userId, long postId) {
        StaffPostDO post = staffPostMapper.selectById(postId);
        if (post == null || post.getUserId() != userId || !StaffPostDO.STATUS_ACTIVE.equals(post.getStatus())) {
            return Optional.empty();
        }
        // M01-T01 纵深防线：删除/停用账号的残留任职不得形成有效快照（旧令牌即使漏清也办理不了业务）
        if (!systemDirectory.userUsable(userId)) {
            return Optional.empty();
        }
        OrgUnitDO org = orgUnitMapper.selectById(post.getOrgId());
        PostTemplateDO template = postTemplateMapper.selectById(post.getRoleCode());
        if (org == null || !org.isActive() || template == null) {
            return Optional.empty();
        }
        boolean lead = Boolean.TRUE.equals(template.getLead());
        Set<Long> managed = lead && post.getTeamId() != null
                ? Set.copyOf(systemDirectory.deptAndDescendants(post.getTeamId()))
                : Set.of();
        return Optional.of(new PostSnapshot(post.getId(), userId, org.getDeptId(), org.getOrgType(),
                post.getRoleCode(), post.getTeamId(), lead, managed));
    }

    @Override
    public Optional<StaffPostDO> getPost(long postId) {
        StaffPostDO post = staffPostMapper.selectById(postId);
        return post != null && StaffPostDO.STATUS_ACTIVE.equals(post.getStatus()) ? Optional.of(post) : Optional.empty();
    }

    @Override
    public List<StaffPostDO> listOrgPosts(long orgId) {
        return staffPostMapper.selectActiveByOrg(orgId);
    }

    @Override
    public List<StaffPostDO> listActivePostsInOrg(long orgId, String roleCode) {
        return staffPostMapper.selectActiveByOrgAndRole(orgId, roleCode);
    }

    @Override
    public List<Long> listOrgIdsEverHeld(long userId) {
        return staffPostMapper.selectOrgIdsEverHeld(userId);
    }

    @Override
    public boolean roleHas(String roleCode, String wanted) {
        if (Objects.equals(roleCode, wanted)) {
            return true;
        }
        PostTemplateDO template = postTemplateMapper.selectById(roleCode);
        return template != null && Objects.equals(template.getInheritsRoleCode(), wanted);
    }

    /**
     * 用户角色 = 平台岗位之外的原有角色（如超级管理员，不受平台管理）+ 全部有效任职对应的角色。
     */
    private void syncUserRoles(long userId) {
        Set<Long> platformRoleIds = postTemplateMapper.selectList().stream()
                .map(t -> systemDirectory.roleIdByCode(t.getRoleCode()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> roles = new HashSet<>(systemDirectory.userRoleIds(userId));
        roles.removeAll(platformRoleIds);
        for (StaffPostDO post : staffPostMapper.selectActiveByUser(userId)) {
            roles.add(systemDirectory.roleIdByCode(post.getRoleCode()));
        }
        systemDirectory.replaceUserRoles(userId, roles);
    }

}
