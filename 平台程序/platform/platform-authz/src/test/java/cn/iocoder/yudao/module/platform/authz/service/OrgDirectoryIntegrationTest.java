package cn.iocoder.yudao.module.platform.authz.service;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.authz.FakeSystemDirectoryForOnboard;
import cn.iocoder.yudao.module.platform.org.OrgServiceImpl;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

@Import({OrgDirectoryService.class,OrgAccountAdminService.class,OrgServiceImpl.class,OrgDirectoryIntegrationTest.Config.class})
class OrgDirectoryIntegrationTest extends PlatformPgIntegrationTest {
    @TestConfiguration static class Config {
        @Bean FakeSystemDirectoryForOnboard directory() { return new FakeSystemDirectoryForOnboard(); }
        @Bean cn.iocoder.yudao.module.system.service.user.AdminUserService users() { return Mockito.mock(cn.iocoder.yudao.module.system.service.user.AdminUserService.class); }
        @Bean cn.iocoder.yudao.module.system.service.oauth2.OAuth2TokenService tokens() { return Mockito.mock(cn.iocoder.yudao.module.system.service.oauth2.OAuth2TokenService.class); }
        @Bean cn.iocoder.yudao.module.system.dal.redis.oauth2.OAuth2AccessTokenRedisDAO cache() { return Mockito.mock(cn.iocoder.yudao.module.system.dal.redis.oauth2.OAuth2AccessTokenRedisDAO.class); }
        @Bean org.springframework.data.redis.core.StringRedisTemplate redisTemplate() { return Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class); }
    }
    @Resource private OrgDirectoryService service;
    @Resource private JdbcTemplate jdbc;
    @Resource private OrgAccountAdminService accounts;
    @Resource private cn.iocoder.yudao.module.system.service.user.AdminUserService users;
    @Resource private cn.iocoder.yudao.module.system.service.oauth2.OAuth2TokenService tokens;
    private static final AtomicLong IDS=new AtomicLong(50000);
    private long tenant,org;

    @BeforeEach void setup() {
        Mockito.reset(users,tokens);
        tenant=IDS.incrementAndGet();org=tenant*100;
        TenantContextHolder.setTenantId(tenant);
        jdbc.execute("ALTER TABLE system_dept ADD COLUMN IF NOT EXISTS update_time timestamptz DEFAULT now()");
        jdbc.execute("ALTER TABLE system_role ADD COLUMN IF NOT EXISTS deleted int2 DEFAULT 0");
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_user_role(id bigserial PRIMARY KEY,user_id bigint,role_id bigint,deleted int2 DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_oauth2_refresh_token(id bigserial PRIMARY KEY,tenant_id bigint,user_id bigint,user_type int)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_users(id bigint PRIMARY KEY,nickname text,mobile text,username text,status int DEFAULT 0,deleted int2 DEFAULT 0,tenant_id bigint)");
        jdbc.execute("ALTER TABLE system_users ADD COLUMN IF NOT EXISTS password text");
        jdbc.execute("ALTER TABLE system_users ADD COLUMN IF NOT EXISTS update_time timestamptz");
        jdbc.execute("CREATE TABLE IF NOT EXISTS org_point_account(org_id bigint PRIMARY KEY,balance bigint,status text)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS org_point_ledger(id bigint,org_id bigint,operation_id text,type text,available_delta bigint,billing_month text,subject text,actor_user_id bigint,note text,create_time timestamptz DEFAULT now())");
        jdbc.execute("CREATE TABLE IF NOT EXISTS org_point_hq_adjustment(id bigint,org_id bigint,status text)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_content_task(id bigint,company_id bigint,author_user_id bigint,kind text,status text,failed_reason text,create_time timestamptz DEFAULT now(),complete_time timestamptz,pool_reserved_units bigint)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_design_remote_command(command_id bigint,org_id bigint,tenant_id bigint,operation text,state text,error_message text,actor_json jsonb DEFAULT '{}',created_at timestamptz DEFAULT now(),updated_at timestamptz)");
        jdbc.update("INSERT INTO pf_org_type(code,domain,name) VALUES('store','test','门店') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO pf_post_template(role_code,domain,org_types,lead) VALUES('boss','test','store',false) ON CONFLICT DO NOTHING");
        addOrg(tenant,org,"验收门店","老板甲",0);
    }
    @AfterEach void clear(){TenantContextHolder.clear();}
    private void addOrg(long t,long id,String name,String owner,int deleted){
        jdbc.update("INSERT INTO system_dept(id,name,tenant_id,status,deleted) VALUES(?,?,?,0,?)",id,name,t,deleted);
        jdbc.update("INSERT INTO pf_org_unit(dept_id,kind,org_id,org_type) VALUES(?,'ORG',?,'store')",id,id);
        jdbc.update("INSERT INTO system_users(id,nickname,mobile,username,tenant_id) VALUES(?,?,'18897911681',?,?)",id+1,"历史昵称","user"+id,t);
        jdbc.update("INSERT INTO pf_staff_post(id,user_id,org_id,role_code) VALUES(?,?,?,'boss')",id+2,id+1,id);
        jdbc.update("INSERT INTO pf_franchise_account(account_id,tenant_id,org_id,user_id,login_name,org_name,owner_name,brand,province,city,area,area_pinyin,owner_pinyin,created_by) VALUES(?,?,?,?,?,?,?,'合家美宅','江西','南昌','高新','gaoxin','laoban',1)",UUID.randomUUID().toString(),t,id,id+1,"hjmz-"+id,name,owner);
    }
    @Test void filtersTenantAndDeletedOrgsAndSearchesRealOwnerAndMobile(){
        addOrg(tenant+1,org+10,"其他租户","老板乙",0);
        addOrg(tenant,org+20,"已删除门店","老板丙",1);
        var page=service.page("老板甲",null,1,20);
        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getList().get(0).ownerName()).isEqualTo("老板甲");
        assertThat(page.getList().get(0).loginName()).isEqualTo("hjmz-"+org);
        assertThat(service.page("18897911681",null,1,20).getTotal()).isEqualTo(1);
        assertThat(service.page(null,null,2,20).getList()).isEmpty();
        assertThat(service.page(null,null,1,20,true).getTotal()).isEqualTo(2);
        assertThatThrownBy(()->service.detail(org+10)).isInstanceOf(ServiceException.class);
    }
    @Test void treatsSearchWildcardsLiterallyAndRejectsInvalidPagination(){
        assertThat(service.page("%",null,1,20).getTotal()).isZero();
        assertThat(service.page("_",null,1,20).getTotal()).isZero();
        assertThatThrownBy(()->service.page(null,null,0,20)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->service.members(org,1,101)).isInstanceOf(ServiceException.class);
    }
    @Test void memberPointsAndGenerationSectionsUseActualOrganization(){
        assertThat(service.members(org,1,20).getTotal()).isEqualTo(1);
        assertThat(service.members(org,1,20).getList().get(0).get("name")).isEqualTo("历史昵称");
        assertThat(service.pointSummary(org).get("balance")).isNull();
        assertThat(service.pointSummary(org).get("month_used")).isEqualTo("0");
        assertThat(service.ledger(org,1,20).getTotal()).isZero();
        jdbc.update("INSERT INTO fr_content_task(id,company_id,author_user_id,kind,status,failed_reason) VALUES(?,?,?,'VIDEO','FAILED','测试失败原因')",org+5,org,org+1);
        jdbc.update("INSERT INTO fr_design_remote_command(command_id,org_id,tenant_id,operation,state) VALUES(?,?,?,'EXTERIOR','SUCCEEDED')",org+6,org,tenant);
        assertThat(service.generations(org,1,20).getTotal()).isEqualTo(2);
    }
    @Test void lifecycleIsRecoverableAuditedAndIdempotent(){
        assertThatThrownBy(()->service.lifecycle(org,"delete","验收门店","测试","direct-delete",1)).isInstanceOf(ServiceException.class);
        var closed=service.lifecycle(org,"close","验收门店","测试关闭","close",1);
        assertThat(closed.organization().status()).isEqualTo("DISABLED");
        service.lifecycle(org,"close","验收门店","测试关闭","close",1);
        assertThat(service.audits(org,1,20).getTotal()).isEqualTo(1);
        assertThatThrownBy(()->service.lifecycle(org,"close","验收门店","不同原因","close",1)).isInstanceOf(ServiceException.class);
        assertThat(service.lifecycle(org,"delete","验收门店","测试删除","delete",1).organization().status()).isEqualTo("DELETED");
        assertThat(service.page(null,null,1,20).getTotal()).isZero();
        assertThat(service.lifecycle(org,"restore","验收门店","测试恢复","restore",1).organization().status()).isEqualTo("DISABLED");
        assertThat(service.lifecycle(org,"reopen","验收门店","测试启用","reopen",1).organization().status()).isEqualTo("ACTIVE");
        assertThat(service.audits(org,1,20).getTotal()).isEqualTo(4);
    }
    @Test void refusesCloseWhileChargeIsPendingAndDeleteWithRemainingBalance(){
        jdbc.update("INSERT INTO org_point_ledger(id,org_id,operation_id,type,available_delta) VALUES(?,?,'pending','RESERVE',-10)",org+8,org);
        assertThatThrownBy(()->service.lifecycle(org,"close","验收门店","测试","close-pending",1)).isInstanceOf(ServiceException.class);
        jdbc.update("INSERT INTO org_point_ledger(id,org_id,operation_id,type,available_delta) VALUES(?,?,'pending','RELEASE',10)",org+9,org);
        service.lifecycle(org,"close","验收门店","测试","closed",1);
        jdbc.update("INSERT INTO org_point_account(org_id,balance,status) VALUES(?,10,'ACTIVE')",org);
        assertThatThrownBy(()->service.lifecycle(org,"delete","验收门店","测试","delete-balance",1)).isInstanceOf(ServiceException.class);
    }
    @Test void resetsOnlyOrganizationOwnerAndAuditsWithoutPasswordAndRetriesOnce(){
        jdbc.update("INSERT INTO system_oauth2_refresh_token(tenant_id,user_id,user_type) VALUES(?,?,2)",tenant,org+1);
        accounts.reset(org,1,"TestOwner321!","验收门店","加盟商申请","reset-once");
        String hash=jdbc.queryForObject("SELECT password FROM system_users WHERE id=?",String.class,org+1);
        accounts.reset(org,1,"TestOwner321!","验收门店","加盟商申请","reset-once");
        assertThat(hash).startsWith("$2a$10$");
        assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().matches("TestOwner321!",hash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT password FROM system_users WHERE id=?",String.class,org+1)).isEqualTo(hash);
        Mockito.verify(tokens,Mockito.times(1)).removeAccessToken(org+1,2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_oauth2_refresh_token WHERE user_id=?",Long.class,org+1)).isZero();
        assertThat(service.audits(org,1,20).getTotal()).isEqualTo(1);
        assertThat(service.audits(org,1,20).getList().get(0).get("reason")).isEqualTo("加盟商申请");
        assertThatThrownBy(()->accounts.reset(org,2,"TestOwner321!","验收门店","加盟商申请","reset-once")).isInstanceOf(ServiceException.class);
    }
    @Test void refusesSelfCrossTenantDeletedOrganizationsAndInvalidPasswords(){
        assertThat(accounts.account(org,org+1).resetAllowed()).isFalse();
        assertThatThrownBy(()->accounts.reset(org,org+1,"TestOwner321!","验收门店","测试","self")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->accounts.reset(org,1,"12345678","验收门店","测试","weak")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->accounts.reset(org,1,"TestOwner321!","错误名称","测试","wrong")).isInstanceOf(ServiceException.class);
        addOrg(tenant+1,org+10,"其他租户","老板乙",0);
        assertThatThrownBy(()->accounts.account(org+10,1)).isInstanceOf(ServiceException.class);
        jdbc.update("UPDATE system_dept SET deleted=1 WHERE id=?",org);
        assertThatThrownBy(()->accounts.account(org,1)).isInstanceOf(ServiceException.class);
        Mockito.verifyNoInteractions(users,tokens);
    }
    @Test void reportsSharedAccountImpactAndProtectsSuperAdministrator(){
        addOrg(tenant,org+20,"第二门店","老板甲",0);
        jdbc.update("UPDATE pf_franchise_account SET user_id=? WHERE org_id=?",org+1,org+20);
        assertThat(accounts.account(org,1).relatedOrganizations()).containsExactlyInAnyOrder("验收门店","第二门店");
        jdbc.update("INSERT INTO system_role(id,code,name,tenant_id,status,deleted) VALUES(?,'super_admin','超级管理员',?,0,0)",org+40,tenant);
        jdbc.update("INSERT INTO system_user_role(user_id,role_id) VALUES(?,?)",org+1,org+40);
        assertThat(accounts.account(org,1).resetAllowed()).isFalse();
        assertThatThrownBy(()->accounts.reset(org,1,"TestOwner321!","验收门店","测试","protected")).isInstanceOf(ServiceException.class);
        Mockito.verifyNoInteractions(users,tokens);
    }
}
