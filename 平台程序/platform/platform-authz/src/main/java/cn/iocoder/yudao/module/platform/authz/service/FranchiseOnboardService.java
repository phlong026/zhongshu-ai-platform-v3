package cn.iocoder.yudao.module.platform.authz.service;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.org.FranchiseAccountNaming;
import cn.iocoder.yudao.module.platform.org.InitialAccountPassword;
import cn.iocoder.yudao.module.platform.org.OrgService;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import cn.iocoder.yudao.module.platform.org.dal.OrgUnitDO;
import cn.iocoder.yudao.module.platform.org.dal.OrgUnitMapper;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 加盟商资料、统一用户和首任老板身份在同一事务开通。 */
@Service
public class FranchiseOnboardService {
    @Resource private FranchiseAccountNaming naming;
    @Resource private OrgService orgService;
    @Resource private StaffPostService posts;
    @Resource private SystemDirectory directory;
    @Resource private OrgUnitMapper units;
    @Resource private JdbcTemplate jdbc;

    public record Preview(String orgName, String loginName, List<String> aliases, String areaPinyin, String ownerPinyin) { }
    public record Result(long orgId, long adminUserId, long postId, boolean reusedOrg, boolean reusedUser,
                         String orgName, String loginName, List<String> aliases, String initialPassword,
                         boolean requiresPasswordChange, String accountId) {
        @Override public String toString() {
            return "FranchiseOnboardResult[orgId=" + orgId + ", userId=" + adminUserId + ", loginName=" + loginName + "]";
        }
    }

    public Preview preview(String brand, String province, String city, String area, String owner,
                           String areaPinyin, String ownerPinyin) {
        var description = naming.describe(brand, province, city, area, owner, areaPinyin, ownerPinyin);
        String login = availableLogin(TenantContextHolder.getRequiredTenantId(), description);
        return new Preview(description.orgName(), login, description.aliases(login), description.areaPinyin(), description.ownerPinyin());
    }

    @Transactional(rollbackFor = Exception.class)
    public Result onboard(String brand, String province, String city, String area, String owner, String mobile,
                          String areaPinyin, String ownerPinyin, String remark, long operator) {
        var description = naming.describe(brand, province, city, area, owner, areaPinyin, ownerPinyin);
        if (mobile == null || !mobile.matches("1\\d{10}")) throw new IllegalArgumentException("请输入11位老板手机号");
        if (remark != null && remark.length() > 500) throw new IllegalArgumentException("备注最多500字");
        long tenant = TenantContextHolder.getRequiredTenantId();
        // 同手机号跨门店开通，以及同地区不同老板开通，均先取业务键锁再查重。
        units.lockOrgKeyForOnboard("franchise-mobile:" + tenant + ":" + mobile);
        units.lockOrgKeyForOnboard("franchise-login:" + tenant + ":" + description.baseLogin());
        units.lockOrgKeyForOnboard("org-onboard:" + tenant + ":store:" + description.orgName());
        Long existingUser = directory.userIdByMobile(mobile);
        if (existingUser != null && !directory.userUsable(existingUser)) {
            throw new IllegalArgumentException("该账号已被停用，请联系平台管理员");
        }
        List<Result> existing = jdbc.query("SELECT * FROM pf_franchise_account WHERE tenant_id=? AND org_name=? AND account_type='BOSS'",
                (rs, row) -> new Result(rs.getLong("org_id"), rs.getLong("user_id"), 0, true, true,
                        rs.getString("org_name"), rs.getString("login_name"), List.of(), null, false, rs.getString("account_id")),
                tenant, description.orgName());
        if (!existing.isEmpty()) {
            Result account = existing.get(0);
            if (existingUser == null || existingUser != account.adminUserId()) {
                throw new IllegalArgumentException("该加盟商名称已开通为其他老板账号，请核对手机号");
            }
            requireActiveOrg(account.orgId());
            long postId = posts.assignPostInOrg(existingUser, account.orgId(), "boss", null, operator);
            return result(account.orgId(), existingUser, postId, true, true, description,
                    account.loginName(), null, account.accountId(), tenant);
        }

        List<Long> matchingOrgs = directory.findRootDeptIdsByName(description.orgName());
        Long existingOrg = null;
        for (Long id : matchingOrgs) {
            OrgUnitDO unit = orgService.getUnit(id);
            if (unit == null || !unit.isOrg() || !unit.isActive() || !"store".equals(unit.getOrgType())) {
                throw new IllegalArgumentException("同名组织已存在或已停用，请核对加盟商资料");
            }
            if (existingUser == null || posts.listActivePosts(existingUser).stream().noneMatch(p ->
                    p.getOrgId().equals(id) && "boss".equals(p.getRoleCode()))) {
                throw new IllegalArgumentException("该加盟商名称已开通，请核对已有老板账号");
            }
            existingOrg = id;
        }
        String login = availableLogin(tenant, description);
        long orgId = existingOrg == null ? orgService.createOrg("store", description.orgName(), null) : existingOrg;
        String initial = existingUser == null ? InitialAccountPassword.generate() : null;
        long userId = existingUser == null ? directory.createUserWithPassword(owner.trim(), mobile, orgId, initial) : existingUser;
        long postId = posts.assignPostInOrg(userId, orgId, "boss", null, operator);
        String accountId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO pf_franchise_account(account_id,tenant_id,org_id,user_id,login_name,org_name,owner_name,
                    brand,province,city,area,area_pinyin,owner_pinyin,remark,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, accountId, tenant, orgId, userId, login, description.orgName(), description.ownerName(),
                description.brand(), description.province(), description.city(), description.area(),
                description.areaPinyin(), description.ownerPinyin(), remark, operator);
        for (String alias : description.aliases(login)) {
            jdbc.update("INSERT INTO pf_franchise_account_alias(tenant_id,account_id,alias) VALUES (?,?,?)",
                    tenant, accountId, alias.toLowerCase(java.util.Locale.ROOT));
        }
        if (initial != null) {
            jdbc.update("INSERT INTO pf_staff_password_policy(tenant_id,user_id,require_change) VALUES (?,?,TRUE)", tenant, userId);
        }
        return result(orgId, userId, postId, existingOrg != null, existingUser != null, description, login, initial, accountId, tenant);
    }

    private Result result(long orgId, long userId, long postId, boolean reusedOrg, boolean reusedUser,
                          FranchiseAccountNaming.Description description, String login, String initial, String accountId, long tenant) {
        boolean requiresChange = Boolean.TRUE.equals(jdbc.query("SELECT require_change FROM pf_staff_password_policy WHERE tenant_id=? AND user_id=?",
                rs -> rs.next() ? rs.getBoolean(1) : false, tenant, userId));
        List<String> aliases = jdbc.queryForList("SELECT alias FROM pf_franchise_account_alias WHERE tenant_id=? AND account_id=? ORDER BY alias",
                String.class, tenant, accountId);
        return new Result(orgId, userId, postId, reusedOrg, reusedUser, description.orgName(), login,
                aliases, initial, requiresChange, accountId);
    }

    private void requireActiveOrg(long orgId) {
        OrgUnitDO org = orgService.getUnit(orgId);
        if (org == null || !org.isActive()) throw new IllegalArgumentException("该加盟商已停用，请联系平台管理员");
    }

    private String availableLogin(long tenant, FranchiseAccountNaming.Description description) {
        List<String> existing = jdbc.queryForList("SELECT login_name FROM pf_franchise_account WHERE tenant_id=? AND org_name=? AND account_type='BOSS'",
                String.class, tenant, description.orgName());
        if (!existing.isEmpty()) return existing.get(0);
        String base = description.baseLogin();
        String candidate = base;
        for (int suffix = 1; suffix <= 10000; suffix++) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM pf_franchise_account WHERE tenant_id=? AND login_name=?",
                    Integer.class, tenant, candidate);
            if (count != null && count == 0) return candidate;
            candidate = base + "-" + description.ownerPinyin() + (suffix == 1 ? "" : "-" + suffix);
        }
        throw new IllegalArgumentException("该地区账号过多，请调整门店片区");
    }

}
