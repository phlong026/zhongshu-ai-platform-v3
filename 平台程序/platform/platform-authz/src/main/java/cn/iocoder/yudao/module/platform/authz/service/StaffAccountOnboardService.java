package cn.iocoder.yudao.module.platform.authz.service;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.org.FranchiseAccountNaming;
import cn.iocoder.yudao.module.platform.org.AccountDisplay;
import cn.iocoder.yudao.module.platform.org.InitialAccountPassword;
import cn.iocoder.yudao.module.platform.org.MemberRolePolicy;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import cn.iocoder.yudao.module.platform.org.dal.OrgUnitMapper;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.authz.enums.PlatformAuthzErrorCodes.ORG_SCOPE_FORBIDDEN;
import static cn.iocoder.yudao.module.platform.authz.enums.PlatformAuthzErrorCodes.POST_NOT_USABLE;
import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.*;

/** 门店负责人为员工配置独立账号；不重置旧用户密码，不复制老板身份。 */
@Service
public class StaffAccountOnboardService {
    @Resource private StaffPostService posts;
    @Resource private SystemDirectory directory;
    @Resource private OrgUnitMapper units;
    @Resource private JdbcTemplate jdbc;
    @Resource private FranchiseAccountNaming naming;

    public record Result(long postId, long userId, String name, String mobile, boolean active,
                         String roleCode, Long teamId, String teamName, String createTime,
                         String accountId, String orgName, String loginName, List<String> aliases,
                         String initialPassword, boolean reusedUser, String roleName, String shortOrgName) {
        @Override public String toString() {
            return "StaffAccountResult[postId=" + postId + ", userId=" + userId + ", loginName=" + loginName + "]";
        }
    }
    private record Parent(String loginName, String orgName, String brand, String province, String city, String area, String areaPinyin) { }
    private record Existing(String accountId, String loginName, String orgName) { }

    @Transactional(rollbackFor = Exception.class)
    public Result open(long orgId, Long requestedUserId, String name, String mobile, String roleCode, Long teamId,
                       long operatorUserId, long operatorPostId) {
        PostSnapshot operator = posts.findActivePost(operatorUserId, operatorPostId).orElseThrow(() -> exception(POST_NOT_USABLE));
        if (operator.orgId() != orgId) throw exception(ORG_SCOPE_FORBIDDEN).setMessage("只能在本门店开通员工账号");
        if (!"store".equals(operator.orgType()) || !List.of("boss", "owner").contains(operator.roleCode())) {
            throw exception(ORG_SCOPE_FORBIDDEN).setMessage("员工账号只能由门店老板或负责人开通");
        }
        MemberRolePolicy.requireEmployeeRole(roleCode);
        if (mobile == null || !mobile.matches("1\\d{10}")) throw new IllegalArgumentException("请输入11位员工手机号");
        var person = naming.person(name);
        long tenant = TenantContextHolder.getRequiredTenantId();
        units.lockOrgKeyForOnboard("franchise-mobile:" + tenant + ":" + mobile);
        Parent parent = parent(tenant, orgId);
        // 同租户所有门店共用短登录名空间；连带数字姓名与重名序号也必须串行分配。
        units.lockOrgKeyForOnboard("franchise-staff-login:" + tenant);
        posts.revalidateActivePost(operatorUserId, operatorPostId);
        Long userId = requestedUserId == null ? directory.userIdByMobile(mobile) : requestedUserId;
        if (userId != null) {
            SystemDirectory.UserBrief user = directory.usersByIds(List.of(userId)).stream().findFirst()
                    .orElseThrow(() -> exception(USER_NOT_FOUND));
            if (!mobile.equals(user.mobile())) throw exception(STAFF_USER_MOBILE_MISMATCH);
            if (!directory.userUsable(userId)) throw exception(STAFF_GLOBAL_RESUME_FORBIDDEN);
        }
        boolean reusedUser = userId != null;
        List<Existing> existing = userId == null ? List.of() : jdbc.query(
                "SELECT account_id,login_name,org_name FROM pf_franchise_account WHERE tenant_id=? AND org_id=? AND user_id=?",
                (rs, row) -> new Existing(rs.getString(1), rs.getString(2), rs.getString(3)), tenant, orgId, userId);
        String loginName = existing.isEmpty() ? availableLogin(tenant, person.pinyin()) : existing.get(0).loginName();
        String initial = reusedUser ? null : InitialAccountPassword.forStaffLogin(loginName);
        if (userId == null) userId = directory.createUserWithPassword(person.name(), mobile, orgId, initial);
        long postId = posts.assignPostInOrg(userId, orgId, roleCode, teamId, operatorUserId);
        Existing account;
        if (existing.isEmpty()) {
            String login = loginName;
            account = new Existing(UUID.randomUUID().toString(), login, parent.orgName());
            jdbc.update("""
                    INSERT INTO pf_franchise_account(account_id,tenant_id,org_id,user_id,login_name,org_name,owner_name,account_type,
                        brand,province,city,area,area_pinyin,owner_pinyin,created_by)
                    VALUES (?,?,?,?,?,?,?,'STAFF',?,?,?,?,?,?,?)
                    """, account.accountId(), tenant, orgId, userId, login, parent.orgName(), person.name(), parent.brand(),
                    parent.province(), parent.city(), parent.area(), parent.areaPinyin(), person.pinyin(), operatorUserId);
            var aliases = new LinkedHashSet<String>();
            aliases.add(login);
            aliases.add(login.startsWith("hjmz-") ? login.substring(5) : login);
            aliases.add(parent.loginName()); aliases.add(parent.loginName().replaceFirst("^hjmz-", ""));
            aliases.add(parent.orgName());
            aliases.add(person.pinyin()); aliases.add(person.initials()); aliases.add(person.name().toLowerCase(Locale.ROOT));
            for (String alias : aliases) jdbc.update("INSERT INTO pf_franchise_account_alias(tenant_id,account_id,alias) VALUES (?,?,?)",
                    tenant, account.accountId(), alias.toLowerCase(Locale.ROOT));
        } else {
            account = existing.get(0);
        }
        if (initial != null) jdbc.update("INSERT INTO pf_staff_password_policy(tenant_id,user_id,require_change) VALUES (?,?,TRUE)", tenant, userId);
        var post = posts.getPost(postId).orElseThrow(() -> exception(POST_NOT_FOUND));
        var user = directory.usersByIds(List.of(userId)).stream().findFirst().orElseThrow(() -> exception(USER_NOT_FOUND));
        List<String> aliases = jdbc.queryForList("SELECT alias FROM pf_franchise_account_alias WHERE tenant_id=? AND account_id=? ORDER BY alias",
                String.class, tenant, account.accountId());
        AccountDisplay display = AccountDisplay.of(user.name(), roleCode, parent.brand(), parent.province(), parent.city(), parent.area(), account.orgName());
        return new Result(postId, userId, user.name(), mobile, true, roleCode, teamId, directory.deptName(teamId),
                post.getCreateTime() == null ? "" : post.getCreateTime().toString(), account.accountId(), account.orgName(),
                account.loginName(), aliases, initial, reusedUser, display.roleName(), display.shortOrgName());
    }

    private Parent parent(long tenant, long orgId) {
        List<Parent> parents = jdbc.query("SELECT login_name,org_name,brand,province,city,area,area_pinyin FROM pf_franchise_account WHERE tenant_id=? AND org_id=? AND account_type='BOSS' ORDER BY created_at LIMIT 1",
                (rs, row) -> new Parent(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)), tenant, orgId);
        if (!parents.isEmpty()) return parents.get(0);
        // 存量门店没有地区结构化资料时仍能加人；使用稳定组织编号，后续总部补资料不改已交付员工账号。
        return new Parent("hjmz-store" + orgId, directory.deptName(orgId), "合家美宅", "", "", "", "");
    }

    private String availableLogin(long tenant, String base) {
        if (base.length() > 61) throw new IllegalArgumentException("员工账号拼音过长，请联系总部配置简称");
        for (int suffix = 1; suffix <= 10000; suffix++) {
            String candidate = base + (suffix == 1 ? "" : suffix);
            if (candidate.length() > 61) throw new IllegalArgumentException("员工账号拼音过长，请联系总部配置简称");
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM pf_franchise_account WHERE tenant_id=? AND login_name=?",
                    Integer.class, tenant, candidate);
            if (count != null && count == 0) return candidate;
        }
        throw new IllegalArgumentException("同名员工过多，请联系总部配置简称");
    }
}
