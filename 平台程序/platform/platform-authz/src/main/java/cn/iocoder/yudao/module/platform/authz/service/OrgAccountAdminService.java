package cn.iocoder.yudao.module.platform.authz.service;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.system.service.oauth2.OAuth2TokenService;
import cn.iocoder.yudao.module.system.dal.redis.oauth2.OAuth2AccessTokenRedisDAO;
import cn.iocoder.yudao.module.platform.org.WalletSessionRevocation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import java.util.List;
import java.util.UUID;

/** 加盟商账号管理；不接受客户端传入用户编号，不返回或记录密码。 */
@Service
@Transactional(readOnly=true)
public class OrgAccountAdminService {
    private final JdbcTemplate jdbc;
    private final OrgDirectoryService orgDirectoryService;
    private static final org.springframework.security.crypto.password.PasswordEncoder PASSWORDS=
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(10);
    private final OAuth2TokenService tokens;
    private final OAuth2AccessTokenRedisDAO tokenCache;
    private final ApplicationEventPublisher events;
    private final ObjectProvider<WalletSessionRevocation> walletSessions;
    public OrgAccountAdminService(JdbcTemplate jdbc,OrgDirectoryService orgDirectoryService,
            OAuth2TokenService tokens,OAuth2AccessTokenRedisDAO tokenCache,
            ApplicationEventPublisher events,ObjectProvider<WalletSessionRevocation> walletSessions) {
        this.jdbc=jdbc;this.orgDirectoryService=orgDirectoryService;this.tokens=tokens;
        this.tokenCache=tokenCache;this.events=events;this.walletSessions=walletSessions;
    }
    public record PasswordResetTokenKeys(List<String> keys) { }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void cleanCommittedTokens(PasswordResetTokenKeys event) { event.keys().forEach(tokenCache::delete); }

    public record Account(String loginName,String ownerName,List<String> relatedOrganizations,
                          boolean resetAllowed,String blockedReason) { }
    private record Owner(long userId,String loginName,String ownerName) { }

    private Owner owner(long orgId,boolean lock) {
        var org=orgDirectoryService.detail(orgId).organization();
        if (!"store".equals(org.orgType()) || "DELETED".equals(org.status()))
            throw new ServiceException(400,"请选择未删除的加盟门店");
        var result=jdbc.query("""
                SELECT a.user_id,a.login_name,a.owner_name FROM pf_franchise_account a
                JOIN system_users u ON u.id=a.user_id AND u.tenant_id=a.tenant_id AND u.deleted=0
                WHERE a.tenant_id=? AND a.org_id=? AND a.account_type='BOSS'
                ORDER BY a.created_at,a.account_id LIMIT 1
                """+(lock?" FOR UPDATE OF u,a":""),
                (r,n)->new Owner(r.getLong(1),r.getString(2),r.getString(3)),
                TenantContextHolder.getRequiredTenantId(),orgId);
        if(result.isEmpty())throw new ServiceException(404,"该组织没有可管理的加盟商账号");
        return result.get(0);
    }
    private String blocked(Owner owner,long actor) {
        if(owner.userId()==actor)return "当前账号请通过个人资料修改密码";
        Boolean administrator=jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM system_user_role ur JOIN system_role r ON r.id=ur.role_id
                WHERE ur.user_id=? AND ur.deleted=0 AND r.tenant_id=? AND r.deleted=0
                  AND r.status=0 AND r.code='super_admin')
                """,Boolean.class,owner.userId(),TenantContextHolder.getRequiredTenantId());
        return Boolean.TRUE.equals(administrator)?"该账号同时具有平台超级管理员权限，请通过系统用户管理处理":"";
    }
    public Account account(long orgId,long actor) {
        Owner owner=owner(orgId,false);
        List<String> related=jdbc.queryForList("""
                SELECT DISTINCT d.name FROM pf_franchise_account a JOIN system_dept d ON d.id=a.org_id
                WHERE a.tenant_id=? AND d.tenant_id=a.tenant_id AND d.deleted=0
                  AND a.user_id=? AND a.account_type='BOSS' ORDER BY d.name
                """,String.class,TenantContextHolder.getRequiredTenantId(),owner.userId());
        String reason=blocked(owner,actor);
        return new Account(owner.loginName(),owner.ownerName(),related,reason.isEmpty(),reason);
    }
    @Transactional(rollbackFor=Exception.class)
    public void reset(long orgId,long actor,String password,String confirmedName,String reason,String requestKey) {
        if(password==null || !password.matches("[\\x21-\\x7E]{8,32}")
                || !password.matches(".*[A-Za-z].*") || !password.matches(".*[0-9].*"))
            throw new ServiceException(400,"密码需为8至32位，包含字母和数字，不含空格");
        if(reason==null || reason.isBlank() || reason.length()>500 || requestKey==null
                || requestKey.isBlank() || requestKey.length()>100)
            throw new ServiceException(400,"请填写操作原因");
        var org=orgDirectoryService.detail(orgId).organization();
        if(!org.orgName().equals(confirmedName))throw new ServiceException(400,"组织名称确认不一致");
        long tenant=TenantContextHolder.getRequiredTenantId();
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,tenant+":"+requestKey);
        var prior=jdbc.queryForList("SELECT org_id,actor_id,action,reason FROM pf_org_admin_audit WHERE tenant_id=? AND request_key=?",tenant,requestKey);
        if(!prior.isEmpty()) {
            var p=prior.get(0);
            if(((Number)p.get("org_id")).longValue()!=orgId || ((Number)p.get("actor_id")).longValue()!=actor
                    || !"reset-password".equals(p.get("action")) || !reason.trim().equals(p.get("reason")))
                throw new ServiceException(409,"请求编号已用于其他操作");
            return;
        }
        Owner preview=owner(orgId,false);
        // 与首次改密登录保持相同的锁顺序，避免旧初始密码登录覆盖管理员重置结果。
        jdbc.queryForList("SELECT user_id FROM pf_staff_password_policy WHERE tenant_id=? AND user_id=? FOR UPDATE",tenant,preview.userId());
        Owner owner=owner(orgId,true);
        if(owner.userId()!=preview.userId())throw new ServiceException(409,"账号归属已改变，请重新打开组织详情");
        String denied=blocked(owner,actor);
        if(!denied.isEmpty())throw new ServiceException(403,denied);
        // 加盟账号沿用 cost 10，不能降为底座普通用户重置接口的默认 cost 4。
        int changed=jdbc.update("UPDATE system_users SET password=?,update_time=now() WHERE id=? AND tenant_id=? AND deleted=0",
                PASSWORDS.encode(password),owner.userId(),tenant);
        if(changed!=1)throw new ServiceException(404,"账号已失效");
        jdbc.update("UPDATE pf_staff_password_policy SET require_change=FALSE,initial_password_expires_at=NULL WHERE tenant_id=? AND user_id=?",tenant,owner.userId());
        // 同时撤销访问令牌和刷新令牌；微信绑定保留，所有端均须重新登录。
        var keys=tokens.listAccessTokenByUser(owner.userId(),UserTypeEnum.ADMIN.getValue()).stream()
                .flatMap(t->java.util.stream.Stream.of(t.getAccessToken(),t.getRefreshToken())).distinct().toList();
        tokens.removeAccessToken(owner.userId(),UserTypeEnum.ADMIN.getValue());
        jdbc.update("DELETE FROM system_oauth2_refresh_token WHERE tenant_id=? AND user_id=? AND user_type=?",tenant,owner.userId(),UserTypeEnum.ADMIN.getValue());
        var orgIds=jdbc.queryForList("SELECT DISTINCT p.org_id FROM pf_staff_post p JOIN system_dept d ON d.id=p.org_id WHERE p.user_id=? AND d.tenant_id=?",Long.class,owner.userId(),tenant);
        walletSessions.ifAvailable(s->s.revokeForUserOrgs(owner.userId(),orgIds));
        events.publishEvent(new PasswordResetTokenKeys(keys));
        jdbc.update("""
                INSERT INTO pf_org_admin_audit(id,tenant_id,org_id,actor_id,action,reason,request_key)
                VALUES(?,?,?,?,'reset-password',?,?)
                """,UUID.randomUUID().toString(),tenant,orgId,actor,reason.trim(),requestKey);
    }
}
