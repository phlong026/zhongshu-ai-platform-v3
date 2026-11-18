package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.FranchiseAccountNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.STORE_PROFILE_NAME_TAKEN;

@RestController
@RequestMapping("/platform/org/store-profile")
@PreAuthorize("@post.has('platform:org:manage')")
public class StoreProfileController {
    private final CurrentPost current;
    private final JdbcTemplate jdbc;

    private final FranchiseAccountNaming naming;

    public StoreProfileController(CurrentPost current, JdbcTemplate jdbc, FranchiseAccountNaming naming) {
        this.naming = naming;
        this.current = current;
        this.jdbc = jdbc;
    }

    public record Profile(String orgId, String name, String ownerName, String address, long version, boolean editOwnerName) {}
    public record Update(@NotBlank String orgId, @NotNull Long version, @NotBlank String name,
                         @NotBlank @Size(max=20) String ownerName, @NotNull @Size(max=200) String address) {}

    private PostSnapshot boss() {
        var post = current.require();
        if (!"store".equals(post.orgType()) || !"boss".equals(post.roleCode())) {
            throw new ServiceException(403, "请切换至本门店的老板身份");
        }
        return post;
    }

    private Profile read(PostSnapshot post, long tenant) {
        var rows = jdbc.query("""
                SELECT d.name,a.owner_name,coalesce(p.address,''),coalesce(p.version,0),a.user_id
                FROM system_dept d JOIN pf_franchise_account a ON a.org_id=d.id AND a.tenant_id=d.tenant_id AND a.account_type='BOSS'
                LEFT JOIN pf_org_profile p ON p.org_id=d.id AND p.tenant_id=d.tenant_id
                WHERE d.id=? AND d.tenant_id=? AND d.deleted=0 AND d.status=0
                ORDER BY a.created_at,a.account_id LIMIT 1
                """, (rs,i) -> new Profile(Long.toString(post.orgId()),rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),
                rs.getLong(5)==post.userId()),post.orgId(),tenant);
        if (rows.isEmpty()) throw new ServiceException(404,"当前门店没有可维护的加盟商档案");
        return rows.get(0);
    }

    @GetMapping
    public CommonResult<Profile> get() {
        return success(read(boss(),TenantContextHolder.getRequiredTenantId()));
    }

    @PutMapping
    @Transactional
    public CommonResult<Profile> update(@Valid @RequestBody Update input) {
        var post = boss();
        long tenant = TenantContextHolder.getRequiredTenantId();
        if (!Long.toString(post.orgId()).equals(input.orgId())) throw new ServiceException(409,"工作身份已切换，请重新打开");
        String name = FranchiseAccountNaming.cleanDisplayName(input.name());
        if (name.isEmpty() || name.length() > 30) throw new ServiceException(400,"店名须为 1 至 30 个字符");
        String ownerName = input.ownerName().trim(), address = input.address().trim();
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",tenant + ":ops-org:" + post.orgId());
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",tenant + ":store-name:" + name);
        var before = read(post,tenant);
        if (input.version()!=before.version()) throw new ServiceException(409,"资料已变更，请重新载入");
        if (!before.editOwnerName() && !before.ownerName().equals(ownerName)) throw new ServiceException(403,"仅主账号可修改本人姓名");
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM system_dept d JOIN pf_org_unit o ON o.dept_id=d.id AND o.kind='ORG'
                WHERE d.tenant_id=? AND d.deleted=0 AND btrim(d.name,?)=? AND d.id<>?
                UNION ALL SELECT 1 FROM pf_franchise_account a WHERE a.tenant_id=? AND a.account_type='BOSS'
                AND btrim(a.org_name,?)=? AND a.org_id<>?)
                """,Boolean.class,tenant,FranchiseAccountNaming.DISPLAY_NAME_EDGE_SPACES,name,post.orgId(),
                tenant,FranchiseAccountNaming.DISPLAY_NAME_EDGE_SPACES,name,post.orgId()))) throw exception(STORE_PROFILE_NAME_TAKEN);
        if (!before.name().equals(name)) {
            jdbc.update("UPDATE system_dept SET name=?, update_time=now() WHERE tenant_id=? AND id=?",name,tenant,post.orgId());
            try {
                jdbc.update("UPDATE pf_franchise_account SET org_name=? WHERE tenant_id=? AND org_id=?",name,tenant,post.orgId());
            } catch (DuplicateKeyException e) {
                throw exception(STORE_PROFILE_NAME_TAKEN);
            }
        }
        if (!before.ownerName().equals(ownerName)) {
            FranchiseAccountNaming.Person person;
            try {
                person = naming.person(ownerName);
            } catch (IllegalArgumentException e) {
                throw new ServiceException(400, e.getMessage());
            }
            String accountId = jdbc.queryForObject("""
                    UPDATE pf_franchise_account SET owner_name=?,owner_pinyin=?
                    WHERE tenant_id=? AND org_id=? AND account_type='BOSS' AND user_id=?
                    RETURNING account_id
                    """,String.class,ownerName,person.pinyin(),tenant,post.orgId(),post.userId());
            for (String alias : java.util.List.of(person.pinyin(), person.initials())) {
                jdbc.update("""
                        INSERT INTO pf_franchise_account_alias(tenant_id,account_id,alias)
                        VALUES(?,?,?) ON CONFLICT DO NOTHING
                        """,tenant,accountId,alias);
            }
            jdbc.update("UPDATE system_users SET nickname=?,update_time=now() WHERE tenant_id=? AND id=? AND deleted=0",ownerName,tenant,post.userId());
        }
        jdbc.update("""
                INSERT INTO pf_org_profile(tenant_id,org_id,address,version,updated_by) VALUES(?,?,?,?,?)
                ON CONFLICT(tenant_id,org_id) DO UPDATE SET address=excluded.address,version=excluded.version,
                updated_by=excluded.updated_by,updated_at=now()
                """,tenant,post.orgId(),address,before.version()+1,post.userId());
        return success(read(post,tenant));
    }
}
