package cn.iocoder.yudao.module.platform.operations;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import cn.iocoder.yudao.module.franchise.content.content.*;
import cn.iocoder.yudao.module.franchise.content.provider.ContentProviderProperties;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import cn.iocoder.yudao.module.platform.authz.service.OrgDirectoryService;
import cn.iocoder.yudao.module.platform.org.*;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import cn.iocoder.yudao.module.platform.wallet.orgpoint.*;
import cn.iocoder.yudao.module.system.dal.redis.oauth2.OAuth2AccessTokenRedisDAO;
import cn.iocoder.yudao.module.system.service.oauth2.OAuth2TokenService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

@TestPropertySource(properties={"platform.test.extra-flyway-locations=,classpath:db/migration/platform-wallet,classpath:db/migration/franchise","zhongshu.org-point.headquarters-tenant-id=1","platform.legacy-design-bridge.enabled=false"})
@Import({OperationsIntegrationTest.Config.class,OperationsGuard.class,OperationsAudit.class,OrganizationProfiles.class,OrganizationCapabilities.class,
 OrganizationCapabilityGate.class,OrganizationPointSummaryAspect.class,TaskOperationsQuery.class,TaskReconciliation.class,OrgDirectoryService.class,OrgServiceImpl.class,
 StaffPostServiceImpl.class,OrgPointService.class,OrgPointPriceService.class,ContentTaskStore.class})
class OperationsIntegrationTest extends PlatformPgIntegrationTest {
    @TestConfiguration @EnableAspectJAutoProxy(proxyTargetClass=true)
    static class Config {
        @Bean AuditPort auditPort(){return Mockito.mock(AuditPort.class);}
        @Bean OAuth2TokenService tokens(){return Mockito.mock(OAuth2TokenService.class);}
        @Bean OAuth2AccessTokenRedisDAO tokenCache(){return Mockito.mock(OAuth2AccessTokenRedisDAO.class);}
        @Bean ContentTaskService tasks(ContentTaskStore store,OrgPointPort points){return new ContentTaskService(store,null,null,null,null,null,null,null,null,new ContentProviderProperties(),points);}
        @Bean SystemDirectory directory(JdbcTemplate jdbc) {
            return Mockito.mock(SystemDirectory.class,call->{
                String name=call.getMethod().getName();Object[] args=call.getArguments();
                if(name.equals("userExists")||name.equals("userUsable"))return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM system_users WHERE id=? AND deleted=0 AND status=0)",Boolean.class,args[0]));
                if(name.equals("roleIdByCode"))return jdbc.queryForList("SELECT id FROM system_role WHERE code=? ORDER BY id LIMIT 1",Long.class,args[0]).stream().findFirst().orElse(null);
                if(name.equals("userRoleIds"))return new HashSet<>(jdbc.queryForList("SELECT role_id FROM system_user_role WHERE user_id=? AND deleted=0",Long.class,args[0]));
                if(name.equals("replaceUserRoles")){
                    jdbc.update("DELETE FROM system_user_role WHERE user_id=?",args[0]);
                    for(Object role:(Set<?>)args[1])jdbc.update("INSERT INTO system_user_role(user_id,role_id,tenant_id,deleted) VALUES(?,?,1,0)",args[0],role);return null;
                }
                if(name.equals("roleHasPermission"))return false;
                if(name.equals("deptUnavailable"))return false;
                return Mockito.RETURNS_DEFAULTS.answer(call);
            });
        }
    }
    @Resource JdbcTemplate jdbc;
    @Resource OrganizationProfiles profiles;
    @Resource OrgDirectoryService orgDirectory;
    @Resource OrganizationCapabilities capabilities;
    @Resource OrgPointPort points;
    @Resource OrgPointService pointService;
    @Resource TaskOperationsQuery query;
    @Resource TaskReconciliation reconciliation;
    @Resource PlatformTransactionManager transactionManager;
    @Resource OAuth2TokenService tokens;
    private static final AtomicLong IDS=new AtomicLong(700000);
    long org,owner,staff,admin;
    String login;
    @BeforeEach void setup() {
        jdbc.execute("ALTER TABLE system_dept ADD COLUMN IF NOT EXISTS update_time timestamptz DEFAULT now()");
        jdbc.execute("ALTER TABLE system_role ADD COLUMN IF NOT EXISTS deleted int2 DEFAULT 0");
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_users(id bigint PRIMARY KEY,tenant_id bigint,nickname text,mobile text,username text,password text,status int DEFAULT 0,deleted int2 DEFAULT 0,update_time timestamptz)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_user_role(id bigserial PRIMARY KEY,user_id bigint,role_id bigint,tenant_id bigint,deleted int2 DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_oauth2_refresh_token(id bigserial PRIMARY KEY,tenant_id bigint,user_id bigint,user_type int)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS pf_wechat_binding(id bigint PRIMARY KEY,appid text,openid text,user_id bigint,status text)");
        jdbc.update("INSERT INTO system_role(id,code,name,status,tenant_id,deleted) VALUES(9100001,'super_admin','平台管理员',0,1,0),(9100002,'boss','老板',0,1,0),(9100003,'sales','业务员',0,1,0) ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO pf_org_type(code,domain,name) VALUES('store','test','测试门店') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO pf_post_template(role_code,domain,org_types,lead) VALUES('boss','test','store',false),('sales','test','store',false) ON CONFLICT DO NOTHING");
        org=IDS.incrementAndGet();owner=IDS.incrementAndGet();staff=IDS.incrementAndGet();admin=IDS.incrementAndGet();login="ops-"+org;
        jdbc.update("INSERT INTO system_dept(id,name,tenant_id,status,deleted) VALUES(?,?,1,0,0)",org,"联调门店"+org);
        jdbc.update("INSERT INTO pf_org_unit(dept_id,kind,org_id,org_type) VALUES(?,'ORG',?,'store')",org,org);
        for(long user:List.of(owner,staff,admin))jdbc.update("INSERT INTO system_users(id,tenant_id,nickname,mobile,username) VALUES(?,1,?,?,?)",user,"人员"+user,"18"+String.format("%09d",user),"user"+user);
        jdbc.update("INSERT INTO system_user_role(user_id,role_id,tenant_id) VALUES(?,9100001,1)",admin);
        jdbc.update("INSERT INTO pf_staff_post(id,user_id,org_id,role_code) VALUES(?,?,?,'boss'),(?,?,?,'sales')",IDS.incrementAndGet(),owner,org,IDS.incrementAndGet(),staff,org);
        jdbc.update("INSERT INTO pf_franchise_account(account_id,tenant_id,org_id,user_id,login_name,org_name,owner_name,brand,province,city,area,area_pinyin,owner_pinyin,created_by) VALUES(?,1,?,?,?,?,?,'合家美宅','江西','南昌','青山湖','qingshanhu','boss',?)",UUID.randomUUID().toString(),org,owner,login,"联调门店"+org,"老板甲",admin);
        Mockito.reset(tokens);Mockito.when(tokens.listAccessTokenByUser(Mockito.anyLong(),Mockito.anyInt())).thenReturn(List.of());
        actor(admin,1);
    }
    @AfterEach void clear(){TenantContextHolder.clear();SecurityContextHolder.clearContext();}
    private void actor(long user,long tenant){TenantContextHolder.setTenantId(tenant);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser().setId(user).setUserType(2).setTenantId(tenant),null,List.of()));}
    private String key(){return UUID.randomUUID().toString();}
    private OrganizationProfiles.Update edit(long version,String name,String mobile,String request){return new OrganizationProfiles.Update(version,name,"品牌甲","江西","南昌","青山湖","老板乙",mobile,"联系人","18800000001","测试路1号","资料修正","更正门店资料",request);}
    private void enable(String...subjects){var policy=capabilities.get(org);capabilities.update(org,new OrganizationCapabilities.Update(policy.version(),List.of(subjects),"测试开通",key()));}
    private long pool(long amount){pointService.ensurePoolAccount(org);pointService.adjustBalance(org,amount,"fixture:"+key(),"测试资金",admin);return org;}
    private OrgPointPort.ReserveCommand reserve(String biz){return new OrgPointPort.ReserveCommand(biz,org,owner,null,"digital",30,"content_task",IDS.incrementAndGet());}
    @Test void profilePersistsWithoutChangingLoginAndReplaysOnlyIdenticalRequest() {
        var original=profiles.get(org);String request=key();var input=edit(0,"修正门店"+org,original.organization().mobile(),request);
        var changed=profiles.update(org,input);assertThat(changed.version()).isEqualTo(1);assertThat(changed.organization().loginName()).isEqualTo(login);
        assertThat(changed.contactName()).isEqualTo("联系人");assertThat(changed.organization().ownerName()).isEqualTo("老板乙");
        assertThat(profiles.update(org,input).version()).isEqualTo(1);
        assertThatThrownBy(()->profiles.update(org,edit(1,"另一个名称",original.organization().mobile(),request))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->profiles.update(org,edit(0,"过期资料",original.organization().mobile(),key()))).isInstanceOf(ServiceException.class);
    }
    @Test void phoneCorrectionRevokesSessionsAndRejectsDuplicateOrSharedAccounts() {
        profiles.update(org,edit(0,"手机号修正"+org,"18800000009",key()));
        Mockito.verify(tokens).removeAccessToken(owner,2);
        assertThatThrownBy(()->profiles.update(org,edit(1,"重复手机号"+org,"18"+String.format("%09d",staff),key()))).isInstanceOf(ServiceException.class);
        jdbc.update("INSERT INTO system_user_role(user_id,role_id,tenant_id) VALUES(?,9100001,1)",owner);
        assertThat(profiles.get(org).mobileBlockedReason()).isNotEmpty();
        assertThatThrownBy(()->profiles.update(org,edit(1,"管理员账号"+org,"18800000008",key()))).isInstanceOf(ServiceException.class);
    }
    @Test void ownerTransferMovesOnlyOrganizationRoleAndKeepsLoginAndPersonalWechatBindings() {
        jdbc.update("INSERT INTO pf_wechat_binding(id,appid,openid,user_id,status) VALUES(?,'test-app','old-person',?,'ACTIVE'),(?,'test-app','new-person',?,'ACTIVE')",IDS.incrementAndGet(),owner,IDS.incrementAndGet(),staff);
        var bindings=jdbc.queryForList("SELECT user_id,openid FROM pf_wechat_binding WHERE user_id IN (?,?) ORDER BY user_id",owner,staff);
        var original=profiles.get(org);
        var changed=profiles.transfer(org,new OrganizationProfiles.Transfer(0,String.valueOf(staff),original.organization().orgName(),"老板交接",key()));
        assertThat(changed.ownerUserId()).isEqualTo(String.valueOf(staff));assertThat(changed.organization().loginName()).isEqualTo(login);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_staff_post WHERE org_id=? AND user_id=? AND role_code='boss' AND status='ACTIVE'",Integer.class,org,owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_staff_post WHERE org_id=? AND user_id=? AND role_code='boss' AND status='ACTIVE'",Integer.class,org,staff)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_staff_post WHERE org_id=? AND user_id=? AND role_code='sales' AND status='ACTIVE'",Integer.class,org,staff)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT user_id,openid FROM pf_wechat_binding WHERE user_id IN (?,?) ORDER BY user_id",owner,staff)).isEqualTo(bindings);
    }
    @Test void roleAndTenantChecksCannotBeBypassedByCallingServicesDirectly() {
        actor(owner,1);assertThatThrownBy(()->profiles.get(org)).isInstanceOf(ServiceException.class);
        actor(admin,2);assertThatThrownBy(()->capabilities.get(org)).isInstanceOf(ServiceException.class);
        actor(admin,1);jdbc.update("UPDATE system_users SET status=1 WHERE id=?",admin);
        assertThatThrownBy(()->query.page(null,null,null,true,null,1,20)).isInstanceOf(ServiceException.class);
    }
    @Test void unconfiguredAndNewCapabilitiesAreDeniedAndDisablingDoesNotBlockSettlement() {
        pool(100);var command=reserve("reserve:"+key());
        assertThatThrownBy(()->points.reserve(command)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->points.assertSubjectAllowed(org,owner,"digital")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->capabilities.update(org,new OrganizationCapabilities.Update(0,List.of("avatar_clone"),"未定价能力",key()))).isInstanceOf(ServiceException.class);
        enable("digital");long operation=points.reserve(command);enable();
        assertThat(points.reserve(command)).isEqualTo(operation);
        assertThatThrownBy(()->points.reserve(reserve("new:"+key()))).isInstanceOf(ServiceException.class);
        assertThat(points.releaseByBizKey(command.bizKey()).refunded()).isEqualTo(2);
        assertThat(pointService.summary(org).balance()).isEqualTo(100);
    }
    @Test void capabilityUpdatesUseVersionsAndDoNotLeakAcrossOrganizations() {
        enable("digital");var policy=capabilities.get(org);
        assertThatThrownBy(()->capabilities.update(org,new OrganizationCapabilities.Update(0,List.of(),"过期保存",key()))).isInstanceOf(ServiceException.class);
        TenantContextHolder.setTenantId(2L);assertThatThrownBy(()->points.assertSubjectAllowed(org,owner,"digital")).isInstanceOf(ServiceException.class);
        TenantContextHolder.setTenantId(1L);assertThat(policy.items().stream().filter(OrganizationCapabilities.Item::enabled).count()).isEqualTo(1);
    }
    private String task(String state) {
        enable("digital");pool(100);long id=IDS.incrementAndGet();String biz="content:"+id;
        points.reserve(new OrgPointPort.ReserveCommand(biz,org,owner,null,"digital",30,"content_task",id));
        jdbc.update("INSERT INTO fr_content_task(id,company_id,author_user_id,kind,input_snapshot,input_sha256,provider,status,pool_biz_key,pool_reserved_units,pool_terminal_intent,create_time) VALUES(?,?,?,'digital','{}',?,'fake',?,?,30,?,now()-interval '20 minutes')",id,org,owner,"a".repeat(64),state,biz,"SUCCEEDED".equals(state)?"SETTLE":"RELEASE");
        return "operation:"+points.findOperationByBizKey(biz);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> taskView(String key){return (Map<String,Object>)query.detail(key).get("task");}
    @Test void uncertainTasksRequireEvidenceAndFinancialTerminalIsIdempotent() {
        String task=task("SUBMISSION_UNCERTAIN");assertThat(taskView(task).get("can_verify")).isEqualTo(true);
        assertThatThrownBy(()->reconciliation.review(task,new TaskReconciliation.Review(0,"NOT_DELIVERED","原因","",key()))).isInstanceOf(ServiceException.class);
        String request=key();var review=new TaskReconciliation.Review(0,"NOT_DELIVERED","供应商已确认未交付","工单TEST-01已确认",request);
        reconciliation.review(task,review);assertThat(taskView(task).get("billing_state")).isEqualTo("RELEASED");
        reconciliation.review(task,review);assertThat(pointService.summary(org).balance()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT manual_verify_operator_post_id IS NULL FROM fr_content_task WHERE company_id=?",Boolean.class,org)).isTrue();
        assertThatThrownBy(()->reconciliation.review(task,new TaskReconciliation.Review(1,"DELIVERED","不同结论","证据",key()))).isInstanceOf(ServiceException.class);
    }
    @Test void deliveredVerificationUsesFrozenServerQuantityAndDisablingDoesNotPreventClosure() {
        String task=task("SUBMISSION_UNCERTAIN");enable();
        reconciliation.review(task,new TaskReconciliation.Review(0,"DELIVERED","确认已交付","工单TEST-02确认30秒",key()));
        assertThat(taskView(task).get("billing_state")).isEqualTo("SETTLED");assertThat(taskView(task).get("charged")).isEqualTo("2");
        assertThat(pointService.summary(org).balance()).isEqualTo(98);
    }
    @Test void terminalSynchronizationUsesExistingDomainReconciliationWithoutGeneratingAgain() {
        String task=task("FAILED");assertThat(taskView(task).get("anomaly")).isEqualTo("FAILED_PENDING");
        reconciliation.review(task,new TaskReconciliation.Review(0,"SYNC_TERMINAL","补齐失败退回","已有任务失败终态",key()));
        assertThat(taskView(task).get("billing_state")).isEqualTo("RELEASED");assertThat(pointService.summary(org).balance()).isEqualTo(100);
    }
    @Test void waitingExternalRecordsCannotPretendToResolvePendingCredits() {
        String task=task("RUNNING");
        reconciliation.review(task,new TaskReconciliation.Review(0,"WAITING_EXTERNAL","已联系供应商","工单TEST-03待答复",key()));
        assertThat(taskView(task).get("case_status")).isEqualTo("WAITING_EXTERNAL");
        assertThatThrownBy(()->reconciliation.review(task,new TaskReconciliation.Review(1,"REVIEWED","仍在途","尚无交付结论",key()))).isInstanceOf(ServiceException.class);
        assertThat(pointService.summary(org).balance()).isEqualTo(98);
    }
    @Test void queryIsTenantScopedAndReturnsSensitiveErrorTokensRedacted() {
        String task=task("SUBMISSION_UNCERTAIN");jdbc.update("UPDATE fr_content_task SET failed_reason='api_key=SECRET&access_token=HIDDEN' WHERE company_id=?",org);
        var visible=taskView(task);assertThat(String.valueOf(visible.get("task_error"))).doesNotContain("SECRET","HIDDEN");
        assertThat(query.page(org,null,null,true,null,1,20).getTotal()).isEqualTo(1);
        assertThatThrownBy(()->query.detail("operation:999999999999999999")).isInstanceOf(ServiceException.class);
    }
    @Test void accountingFailureRollsBackVerificationDecisionAndAudit() {
        String key=task("SUBMISSION_UNCERTAIN"),request=key(),name="ops_reject_"+org;
        jdbc.execute("CREATE FUNCTION "+name+"() RETURNS trigger AS $$ BEGIN IF NEW.org_id="+org+" AND NEW.type='RELEASE' THEN RAISE EXCEPTION 'simulated_write_failure'; END IF; RETURN NEW; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER "+name+" BEFORE INSERT ON org_point_ledger FOR EACH ROW EXECUTE FUNCTION "+name+"()");
        try { assertThatThrownBy(()->reconciliation.review(key,new TaskReconciliation.Review(0,"NOT_DELIVERED","已核对失败","供应商工单确认",request))).hasStackTraceContaining("simulated_write_failure"); }
        finally { jdbc.execute("DROP TRIGGER "+name+" ON org_point_ledger");jdbc.execute("DROP FUNCTION "+name+"()"); }
        assertThat(pointService.summary(org).balance()).isEqualTo(98);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_ops_action_audit WHERE request_key=?",Integer.class,request)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM fr_content_task WHERE company_id=?",String.class,org)).isEqualTo("SUBMISSION_UNCERTAIN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_task_reconciliation WHERE org_id=?",Integer.class,org)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM org_point_ledger WHERE org_id=? AND type IN ('SETTLE','RELEASE')",Integer.class,org)).isZero();
    }
    @Test void oneRequestKeyCannotAcknowledgeAnotherTaskInSameOrganization() {
        String first=task("RUNNING"),second=task("RUNNING"),request=key();
        var review=new TaskReconciliation.Review(0,"WAITING_EXTERNAL","正在核对","供应商工单处理中",request);
        reconciliation.review(first,review);
        assertThatThrownBy(()->reconciliation.review(second,review)).isInstanceOf(ServiceException.class);
        assertThat(taskView(second).get("case_version")).isEqualTo(0L);
        assertThat(((Map<?,?>)((List<?>)query.detail(first).get("history")).get(0)).get("after_data")).isInstanceOf(String.class);
    }
    @Test void concurrentOppositeDecisionsHaveOnlyOneFinancialTerminal() throws Exception {
        String task=task("SUBMISSION_UNCERTAIN");var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var results=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(String decision:List.of("DELIVERED","NOT_DELIVERED"))results.add(executor.submit(()->{
                actor(admin,1);ready.countDown();start.await();
                try { reconciliation.review(task,new TaskReconciliation.Review(0,decision,"并发核验","测试工单",key()));return true; }
                catch(ServiceException conflict){assertThat(conflict.getCode()).isEqualTo(409);return false;}
                finally {clear();}
            }));
            assertThat(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();start.countDown();
            int success=0;for(var result:results)if(result.get(60,java.util.concurrent.TimeUnit.SECONDS))success++;
            assertThat(success).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM org_point_ledger WHERE org_id=? AND type IN ('SETTLE','RELEASE')",Integer.class,org)).isEqualTo(1);
            assertThat(taskView(task).get("case_version")).isEqualTo(1L);
        } finally {start.countDown();executor.shutdownNow();}
    }
    @Test void missingBillingCanBeInvestigatedWithoutInventingAChargeOrRefund() {
        long id=IDS.incrementAndGet();String task="content:"+id;
        jdbc.update("INSERT INTO fr_content_task(id,company_id,author_user_id,kind,input_snapshot,input_sha256,provider,status,pool_biz_key,pool_reserved_units) VALUES(?,?,?,'digital','{}',?,'fake','SUBMISSION_UNCERTAIN',?,30)",id,org,owner,"b".repeat(64),"missing:"+id);
        assertThat(taskView(task).get("billing_state")).isEqualTo("MISSING");
        assertThat(taskView(task).get("can_review")).isEqualTo(true);
        assertThat(taskView(task).get("can_verify")).isEqualTo(false);
        reconciliation.review(task,new TaskReconciliation.Review(0,"WAITING_EXTERNAL","待查预扣来源","测试工单",key()));
        assertThatThrownBy(()->reconciliation.review(task,new TaskReconciliation.Review(1,"NOT_DELIVERED","不可凭空退回","测试工单",key()))).isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM org_point_ledger WHERE org_id=?",Integer.class,org)).isZero();
    }
    @Test void organizationConsumptionExcludesPendingReservesAndRefunds() {
        String delivered=task("SUBMISSION_UNCERTAIN");
        assertThat(orgDirectory.pointSummary(org)).containsEntry("month_used","0").containsEntry("total_used","0").containsEntry("in_flight","2");
        reconciliation.review(delivered,new TaskReconciliation.Review(0,"DELIVERED","交付已核验","测试工单",key()));
        assertThat(orgDirectory.pointSummary(org)).containsEntry("month_used","2").containsEntry("total_used","2").containsEntry("in_flight","0");
        String failed=task("SUBMISSION_UNCERTAIN");
        assertThat(orgDirectory.pointSummary(org)).containsEntry("month_used","2").containsEntry("in_flight","2");
        reconciliation.review(failed,new TaskReconciliation.Review(0,"NOT_DELIVERED","未交付已核验","测试工单",key()));
        assertThat(orgDirectory.pointSummary(org)).containsEntry("month_used","2").containsEntry("total_used","2").containsEntry("in_flight","0");
    }
    @Test void migrationPreservesExistingAbilitiesAndLeavesNewOrganizationsClosedByDefault() throws Exception {
        var source=new org.springframework.core.io.ClassPathResource("db/migration/pf/V20261116.730__platform_operations.sql");
        try(var connection=jdbc.getDataSource().getConnection();var statement=connection.createStatement()){
            connection.setAutoCommit(false);
            try {
                String schema="ops_upgrade_"+org;statement.execute("CREATE SCHEMA "+schema);statement.execute("SET LOCAL search_path TO "+schema);
                for(String table:List.of("system_dept","pf_org_unit","system_menu","system_role","system_role_menu"))statement.execute("CREATE TABLE "+table+" (LIKE public."+table+" INCLUDING ALL)");
                statement.execute("INSERT INTO system_dept(id,name,tenant_id,status,deleted) VALUES(1,'既有组织',1,0,0),(2,'关闭组织',1,1,0)");
                statement.execute("INSERT INTO pf_org_unit(dept_id,kind,org_id,org_type) VALUES(1,'ORG',1,'store'),(2,'ORG',2,'store')");
                statement.execute(source.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
                try(var rows=statement.executeQuery("SELECT COUNT(*),MIN(jsonb_array_length(allowed_subjects)) FROM pf_org_capability_policy")){rows.next();assertThat(rows.getInt(1)).isEqualTo(2);assertThat(rows.getInt(2)).isEqualTo(11);}
                statement.execute("INSERT INTO system_dept(id,name,tenant_id,status,deleted) VALUES(3,'新增组织',1,0,0)");
                statement.execute("INSERT INTO pf_org_unit(dept_id,kind,org_id,org_type) VALUES(3,'ORG',3,'store')");
                try(var rows=statement.executeQuery("SELECT COUNT(*) FROM pf_org_capability_policy WHERE org_id=3")){rows.next();assertThat(rows.getInt(1)).isZero();}
            } finally {connection.rollback();}
        }
    }
}
