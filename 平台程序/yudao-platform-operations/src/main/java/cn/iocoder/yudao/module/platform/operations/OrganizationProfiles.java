package cn.iocoder.yudao.module.platform.operations;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.module.platform.authz.service.OrgAccountAdminService;
import cn.iocoder.yudao.module.platform.authz.service.OrgDirectoryService;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.WalletSessionRevocation;
import cn.iocoder.yudao.module.system.service.oauth2.OAuth2TokenService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 组织资料和人员交接分开执行；不更改密码、不迁移微信身份。 */
@Service
public class OrganizationProfiles {
    private final JdbcTemplate jdbc;
    private final OperationsGuard guard;
    private final OperationsAudit audit;
    private final OrgDirectoryService directory;
    private final StaffPostService posts;
    private final OAuth2TokenService tokens;
    private final ApplicationEventPublisher events;
    private final ObjectProvider<WalletSessionRevocation> sessions;
    public OrganizationProfiles(JdbcTemplate jdbc,OperationsGuard guard,OperationsAudit audit,OrgDirectoryService directory,
            StaffPostService posts,OAuth2TokenService tokens,ApplicationEventPublisher events,ObjectProvider<WalletSessionRevocation> sessions) {
        this.jdbc=jdbc;this.guard=guard;this.audit=audit;this.directory=directory;this.posts=posts;this.tokens=tokens;this.events=events;this.sessions=sessions;
    }
    public record Profile(OrgDirectoryService.Organization organization,long version,String ownerUserId,
          boolean wechatBound,String mobileBlockedReason,String contactName,String contactMobile,String address) { }
    public record Update(long expectedVersion,String orgName,String brand,String province,String city,String area,
          String ownerName,String ownerMobile,String contactName,String contactMobile,String address,String remark,
          String reason,String requestKey) { }
    public record Transfer(long expectedVersion,String newOwnerUserId,String confirmedName,String reason,String requestKey) { }
    private record Owner(String accountId,long userId) { }
    private Owner owner(long tenant,long org) {
        var rows=jdbc.query("SELECT account_id,user_id FROM pf_franchise_account WHERE tenant_id=? AND org_id=? AND account_type='BOSS' ORDER BY created_at,account_id LIMIT 1",
            (r,n)->new Owner(r.getString(1),r.getLong(2)),tenant,org);
        return rows.isEmpty()?null:rows.get(0);
    }
    @Transactional(readOnly=true)
    public Profile get(long org) {
        var actor=guard.require();guard.organization(actor,org,false);return read(actor,org);
    }
    private Profile read(OperationsGuard.Actor actor,long org) {
        var organization=directory.detail(org).organization();Owner owner=owner(actor.tenantId(),org);
        var rows=jdbc.queryForList("SELECT version,contact_name,contact_mobile,address FROM pf_org_profile WHERE tenant_id=? AND org_id=?",actor.tenantId(),org);
        var metadata=rows.isEmpty()?Map.<String,Object>of():rows.get(0);
        boolean bound=owner!=null&&tableExists("pf_wechat_binding")&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pf_wechat_binding WHERE user_id=? AND status='ACTIVE')",Boolean.class,owner.userId()));
        return new Profile(organization,rows.isEmpty()?0:((Number)metadata.get("version")).longValue(),owner==null?null:String.valueOf(owner.userId()),bound,
            owner==null?"该组织尚无加盟商账号":mobileBlocked(actor,owner),string(metadata.get("contact_name")),string(metadata.get("contact_mobile")),string(metadata.get("address")));
    }
    private String mobileBlocked(OperationsGuard.Actor actor,Owner owner) {
        Boolean administrator=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM system_user_role ur JOIN system_role r ON r.id=ur.role_id AND r.tenant_id=ur.tenant_id WHERE ur.user_id=? AND ur.tenant_id=? AND ur.deleted=0 AND r.deleted=0 AND r.code='super_admin')",Boolean.class,owner.userId(),actor.tenantId());
        if(Boolean.TRUE.equals(administrator))return "平台管理员账号手机号请通过系统用户管理维护";
        Integer related=jdbc.queryForObject("SELECT COUNT(DISTINCT org_id) FROM pf_franchise_account WHERE tenant_id=? AND user_id=?",Integer.class,actor.tenantId(),owner.userId());
        return related!=null&&related>1?"账号关联多个组织，请通过系统用户管理修改手机号":"";
    }
    @Transactional(rollbackFor=Exception.class)
    public Profile update(long org,Update input) {
        var actor=guard.require();guard.organization(actor,org,true);validate(input);
        String hash=audit.hash(input);
        if(audit.replay(actor,org,"profile",input.requestKey(),hash))return read(actor,org);
        lock(actor,org,0);
        var before=read(actor,org);
        if(input.expectedVersion()!=before.version())throw new ServiceException(409,"组织资料已被修改，请刷新后重试");
        var same=jdbc.queryForList("SELECT o.dept_id FROM pf_org_unit o JOIN system_dept d ON d.id=o.dept_id WHERE o.kind='ORG' AND d.tenant_id=? AND d.name=? AND o.dept_id<>?",actor.tenantId(),input.orgName().trim(),org);
        if(!same.isEmpty())throw new ServiceException(409,"已有同名组织，请使用不同名称");
        Owner owner=owner(actor.tenantId(),org);
        if(owner!=null) {
            for(String value:List.of(orEmpty(input.brand()),orEmpty(input.province()),orEmpty(input.city()),orEmpty(input.area()),orEmpty(input.ownerName())))OperationsGuard.text(value,40,"品牌、地区及老板姓名");
            if(!Objects.equals(orEmpty(input.ownerMobile()),orEmpty(before.organization().mobile()))) {
                String blocked=mobileBlocked(actor,owner);if(!blocked.isEmpty())throw new ServiceException(409,blocked);
                if(input.ownerMobile()==null||!input.ownerMobile().matches("1\\d{10}"))throw new ServiceException(400,"请输入11位老板手机号");
                jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",actor.tenantId()+":profile-mobile:"+input.ownerMobile());
                Boolean duplicate=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM system_users WHERE tenant_id=? AND mobile=? AND id<>? AND deleted=0)",Boolean.class,actor.tenantId(),input.ownerMobile(),owner.userId());
                if(Boolean.TRUE.equals(duplicate))throw new ServiceException(409,"该手机号已有账号，请核对后处理");
                if(jdbc.update("UPDATE system_users SET mobile=?,update_time=now() WHERE id=? AND tenant_id=? AND deleted=0 AND status=0",input.ownerMobile(),owner.userId(),actor.tenantId())!=1)throw new ServiceException(409,"账号状态已变化，请刷新后重试");
                revokeSessions(actor.tenantId(),owner.userId());
            }
            try {
                jdbc.update("UPDATE pf_franchise_account SET org_name=?,brand=?,province=?,city=?,area=?,owner_name=?,remark=? WHERE account_id=? AND tenant_id=?",
                    input.orgName().trim(),input.brand().trim(),input.province().trim(),input.city().trim(),input.area().trim(),input.ownerName().trim(),orEmpty(input.remark()).trim(),owner.accountId(),actor.tenantId());
            }catch(DuplicateKeyException e){throw new ServiceException(409,"加盟商名称已存在，请核对资料");}
        }
        jdbc.update("UPDATE system_dept SET name=?,update_time=now() WHERE id=? AND tenant_id=?",input.orgName().trim(),org,actor.tenantId());
        jdbc.update("""
          INSERT INTO pf_org_profile(tenant_id,org_id,contact_name,contact_mobile,address,version,updated_by)
          VALUES(?,?,?,?,?,?,?) ON CONFLICT(tenant_id,org_id) DO UPDATE SET contact_name=excluded.contact_name,
          contact_mobile=excluded.contact_mobile,address=excluded.address,version=excluded.version,updated_by=excluded.updated_by,updated_at=now()
          """,actor.tenantId(),org,orEmpty(input.contactName()).trim(),orEmpty(input.contactMobile()).trim(),orEmpty(input.address()).trim(),before.version()+1,actor.userId());
        var after=read(actor,org);audit.append(actor,org,"profile",input.requestKey(),hash,input.reason(),before,after);return after;
    }
    @Transactional(rollbackFor=Exception.class)
    public Profile transfer(long org,Transfer input) {
        var actor=guard.require();guard.organization(actor,org,true);
        OperationsGuard.text(input.reason(),500,"交接原因");
        long target;
        try{target=Long.parseLong(input.newOwnerUserId());if(target<=0)throw new NumberFormatException();}catch(Exception e){throw new ServiceException(400,"请选择接任老板");}
        String hash=audit.hash(input);
        if(audit.replay(actor,org,"owner-transfer",input.requestKey(),hash))return read(actor,org);
        lock(actor,org,target);
        var before=read(actor,org);
        if(input.expectedVersion()!=before.version())throw new ServiceException(409,"组织资料已变化，请重新确认");
        if(!before.organization().orgName().equals(input.confirmedName()))throw new ServiceException(400,"组织名称确认不一致");
        if(!"ACTIVE".equals(before.organization().status()))throw new ServiceException(409,"请先启用组织后交接老板身份");
        Owner owner=owner(actor.tenantId(),org);
        if(owner==null||!"store".equals(before.organization().orgType()))throw new ServiceException(400,"该组织没有可交接的加盟商账号");
        if(owner.userId()==target)throw new ServiceException(400,"接任人已是当前老板");
        var candidates=jdbc.queryForList("""
          SELECT u.id,u.nickname FROM system_users u WHERE u.id=? AND u.tenant_id=? AND u.deleted=0 AND u.status=0
          AND EXISTS(SELECT 1 FROM pf_staff_post p WHERE p.user_id=u.id AND p.org_id=? AND p.status='ACTIVE') FOR UPDATE OF u
          """,target,actor.tenantId(),org);
        if(candidates.isEmpty())throw new ServiceException(400,"接任人必须是本组织的在职成员");
        Boolean administrator=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM system_user_role ur JOIN system_role r ON r.id=ur.role_id WHERE ur.user_id=? AND ur.tenant_id=? AND ur.deleted=0 AND r.deleted=0 AND r.code='super_admin')",Boolean.class,target,actor.tenantId());
        if(Boolean.TRUE.equals(administrator))throw new ServiceException(400,"请选择加盟商成员账号接任，避免与平台管理身份混用");
        var oldPosts=posts.listActivePostsInOrg(org,"boss");
        for(var post:oldPosts)if(post.getUserId()!=target)posts.revokePost(post.getId(),actor.userId());
        posts.assignPostInOrg(target,org,"boss",null,actor.userId());
        jdbc.update("UPDATE pf_franchise_account SET user_id=?,owner_name=? WHERE account_id=? AND tenant_id=?",target,String.valueOf(candidates.get(0).get("nickname")),owner.accountId(),actor.tenantId());
        jdbc.update("INSERT INTO pf_org_profile(tenant_id,org_id,version,updated_by) VALUES(?,?,?,?) ON CONFLICT(tenant_id,org_id) DO UPDATE SET version=excluded.version,updated_by=excluded.updated_by,updated_at=now()",actor.tenantId(),org,before.version()+1,actor.userId());
        sessions.ifAvailable(s->{s.revokeForUserOrgs(owner.userId(),List.of(org));s.revokeForUserOrgs(target,List.of(org));});
        var after=read(actor,org);audit.append(actor,org,"owner-transfer",input.requestKey(),hash,input.reason(),before,after);return after;
    }
    private void lock(OperationsGuard.Actor actor,long org,long target) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",actor.tenantId()+":ops-org:"+org);
        // 遵守任职模块的用户→组织锁序；一次锁齐交接涉及人员，避免跨成员操作死锁。
        var users=new TreeSet<Long>(jdbc.queryForList("SELECT user_id FROM pf_staff_post WHERE org_id=? AND role_code='boss' AND status='ACTIVE'",Long.class,org));
        Owner current=owner(actor.tenantId(),org);if(current!=null)users.add(current.userId());if(target>0)users.add(target);
        for(long user:users) {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(?)",user);
            jdbc.queryForList("SELECT id FROM pf_staff_post WHERE user_id=? FOR UPDATE",user);
        }
        jdbc.queryForList("SELECT dept_id FROM pf_org_unit WHERE dept_id=? FOR UPDATE",org);
        guard.organization(actor,org,true);
    }
    private void revokeSessions(long tenant,long user) {
        var keys=tokens.listAccessTokenByUser(user,UserTypeEnum.ADMIN.getValue()).stream().flatMap(t->java.util.stream.Stream.of(t.getAccessToken(),t.getRefreshToken())).distinct().toList();
        tokens.removeAccessToken(user,UserTypeEnum.ADMIN.getValue());
        jdbc.update("DELETE FROM system_oauth2_refresh_token WHERE tenant_id=? AND user_id=? AND user_type=?",tenant,user,UserTypeEnum.ADMIN.getValue());
        var orgs=jdbc.queryForList("SELECT DISTINCT org_id FROM pf_staff_post WHERE user_id=?",Long.class,user);
        sessions.ifAvailable(s->s.revokeForUserOrgs(user,orgs));
        events.publishEvent(new OrgAccountAdminService.PasswordResetTokenKeys(keys));
    }
    private static void validate(Update input) {
        OperationsGuard.text(input.orgName(),64,"组织名称");OperationsGuard.text(input.reason(),500,"操作原因");
        if(input.expectedVersion()<0||orEmpty(input.remark()).length()>500||orEmpty(input.contactName()).length()>40||orEmpty(input.address()).length()>300
                ||!orEmpty(input.contactMobile()).matches("[+0-9() -]{0,20}"))throw new ServiceException(400,"联系资料格式不正确");
    }
    private boolean tableExists(String table){return jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL",Boolean.class,table);}
    private static String string(Object value){return value==null?"":String.valueOf(value);}
    private static String orEmpty(String value){return value==null?"":value;}
}
