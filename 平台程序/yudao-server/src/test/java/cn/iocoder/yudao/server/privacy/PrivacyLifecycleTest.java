package cn.iocoder.yudao.server.privacy;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.asset.AssetContentScanner;
import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.asset.ContentModerationPort;
import cn.iocoder.yudao.module.design.asset.ObjectStoragePort;
import cn.iocoder.yudao.module.design.rights.RightsGrantService;
import cn.iocoder.yudao.module.identity.account.JdbcAccountStateAdapter;
import cn.iocoder.yudao.module.identity.privacy.PrivacyService;
import cn.iocoder.yudao.module.identity.session.UserSessionService;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrivacyLifecycleTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:17-alpine");
    SimpleDriverDataSource ds;JdbcTemplate jdbc;DataSourceTransactionManager manager;
    PrivacyService requests;PrivacyLifecycleService lifecycle;UserSessionService sessions;
    JdbcDeliveryPort delivery;JdbcAccountStateAdapter guard;Map<String,byte[]> objects;ObjectStoragePort storage;
    @BeforeAll void setup(){
        ds=new SimpleDriverDataSource();ds.setDriverClass(org.postgresql.Driver.class);ds.setUrl(PG.getJdbcUrl());ds.setUsername(PG.getUsername());ds.setPassword(PG.getPassword());
        jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform","classpath:db/migration/identity","classpath:db/migration/design","classpath:db/migration/commerce","classpath:db/migration/ai-orchestration").load().migrate();
        sessions=new UserSessionService(ds,manager);requests=new PrivacyService(ds);delivery=new JdbcDeliveryPort(ds);guard=new JdbcAccountStateAdapter(ds);
    }
    @BeforeEach void reset() throws Exception {
        jdbc.execute("TRUNCATE ai_runtime_call_trace,budget_draft,account,user_session,wechat_identity,design_access_grant,privacy_consent,data_subject_request,design_point_account,design_point_ledger,recharge_order,refund_order,ai_job,design_project,asset,asset_rights_grant,case_submission,design_case,audit_event,one_time_download_ticket");
        jdbc.execute("INSERT INTO account(id,nickname) VALUES(7,'MY_PROFILE'),(8,'OTHER_PROFILE')");
        storage=mock(ObjectStoragePort.class);objects=new ConcurrentHashMap<>();
        doAnswer(call->{objects.put(call.getArgument(0),call.getArgument(1));return null;}).when(storage).putObject(anyString(),any());
        when(storage.getObject(anyString())).thenAnswer(call->new ByteArrayInputStream(objects.get(call.getArgument(0))));
        lifecycle=new PrivacyLifecycleService(ds,manager,storage,delivery,sessions,new cn.iocoder.yudao.module.infra.zhongshu.audit.JdbcAuditPort(ds));ReflectionTestUtils.setField(lifecycle,"policyVersion","reviewed-fixture-v1");
    }
    @Test void exportsOnlyOwnersRecordsAndContextBoundTicketCannotBeStolenOrReplayed() throws Exception {
        requests.acceptCurrentConsents(7);requests.acceptCurrentConsents(8);
        jdbc.execute("INSERT INTO wechat_identity(id,account_id,appid,openid) VALUES(1,7,'wxfixture','own-openid'),(2,8,'wxfixture','other-openid')");
        jdbc.execute("INSERT INTO budget_draft(id,user_id,name,create_key) VALUES(701,7,'OWN_BUDGET_DRAFT','own'),(801,8,'OTHER_BUDGET_DRAFT','other')");
        long id=requests.createSubjectRequest(7,"EXPORT").requestId();lifecycle.exportOne(id);
        assertThat(requests.getSubjectRequest(7,id).orElseThrow().status()).isEqualTo("COMPLETED");
        var ticket=lifecycle.ticket(7,id);
        assertThatThrownBy(()->lifecycle.download(8,id,ticket.getToken())).isInstanceOf(ServiceException.class);
        assertThat(delivery.consumeOwnedDownloadTicket(ticket.getToken(),7,"EXPORT",String.valueOf(id)).getOutcome()).isNotEqualTo(TicketConsumption.Outcome.CONSUMED_NOW);
        String json=new String(lifecycle.download(7,id,ticket.getToken()),StandardCharsets.UTF_8);
        assertThat(json).contains("MY_PROFILE","own-openid","assetInventory","publishedQuotes","OWN_BUDGET_DRAFT").doesNotContain("OTHER_PROFILE","other-openid","token_hash","file_key","OTHER_BUDGET_DRAFT");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get("profile").get(0).get("id").asText()).isEqualTo("7");
        assertThatThrownBy(()->lifecycle.download(7,id,ticket.getToken())).isInstanceOf(ServiceException.class);
        jdbc.update("UPDATE data_subject_request SET file_expires_at=now()-interval '1 second' WHERE id=?",id);
        assertThatThrownBy(()->lifecycle.ticket(7,id)).isInstanceOf(ServiceException.class);
    }
    @Test void expiredExportLeaseRecoversButLateWorkerCannotPublish() throws Exception {
        long id=requests.createSubjectRequest(7,"EXPORT").requestId();
        jdbc.update("UPDATE data_subject_request SET status='PROCESSING',lease_token='crashed',lease_expires_at=now()-interval '1 second' WHERE id=?",id);
        lifecycle.exportOne(id);assertThat(requests.getSubjectRequest(7,id).orElseThrow().status()).isEqualTo("COMPLETED");
        long newer=requests.createSubjectRequest(7,"EXPORT").requestId();
        doAnswer(call->{jdbc.update("UPDATE data_subject_request SET lease_token='new-worker' WHERE id=?",newer);return null;}).when(storage).putObject(anyString(),any());
        lifecycle.exportOne(newer);assertThat(requests.getSubjectRequest(7,newer).orElseThrow().status()).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT file_key FROM data_subject_request WHERE id=?",String.class,newer)).isNull();
    }

    @Test void runtimeCallTracesExportOnlyToOwnerAndClosureDeletesOnlyOwnersPromptDerivatives() throws Exception {
        jdbc.execute("INSERT INTO ai_job(id,user_id,phase,status,requested_count,output_prefix) VALUES(101,7,'FLAT','FAILED',1,'out/101'),(201,8,'FLAT','FAILED',1,'out/201')");
        jdbc.execute("INSERT INTO ai_runtime_call_trace(id,job_id,call_id,source_attempt_no,source_fencing_token,stage,candidate_slot_no,state,request_spec) "
                + "VALUES(11,101,'plan-101',1,1,'PLAN',0,'REQUESTED','{\"promptText\":\"OWN_RUNTIME_TRACE\"}'),"
                + "(22,201,'plan-201',1,1,'PLAN',0,'REQUESTED','{\"promptText\":\"OTHER_RUNTIME_TRACE\"}')");
        long export=requests.createSubjectRequest(7,"EXPORT").requestId();lifecycle.exportOne(export);
        var ticket=lifecycle.ticket(7,export);
        String json=new String(lifecycle.download(7,export,ticket.getToken()),StandardCharsets.UTF_8);
        assertThat(json).contains("generationCallTraces","OWN_RUNTIME_TRACE").doesNotContain("OTHER_RUNTIME_TRACE");
        long close=requests.createSubjectRequest(7,"CLOSE_ACCOUNT").requestId();
        lifecycle.decide(99,close,true,"本人申请，衍生提示词清除");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_runtime_call_trace WHERE job_id=101",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_runtime_call_trace WHERE job_id=201",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE id=101",Integer.class)).isEqualTo(1);
    }
    @Test void concurrentRequestsAndConsentsAreIdempotent() throws Exception {
        var pool=Executors.newFixedThreadPool(4);var ids=new HashSet<Long>();
        try{var tasks=new ArrayList<Future<Long>>();for(int i=0;i<8;i++)tasks.add(pool.submit(()->{requests.acceptCurrentConsents(7);return requests.createSubjectRequest(7,"EXPORT").requestId();}));for(var task:tasks)ids.add(task.get(10,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
        assertThat(ids).hasSize(1);assertThat(requests.listConsents(7)).hasSize(3);
        assertThatThrownBy(()->requests.createSubjectRequest(7,null)).isInstanceOf(ServiceException.class);
    }
    @Test void closureRevokesAllSessionsAndGrantsArchivesProjectsAndKeepsLedgerEvidence() {
        var token=sessions.issue(7,"wxfixture","own",true,null);
        jdbc.execute("INSERT INTO design_access_grant(id,account_id) VALUES(1,7)");
        jdbc.execute("INSERT INTO design_project(id,user_id,source_type) VALUES(1,7,'SELF_UPLOAD')");
        jdbc.execute("INSERT INTO design_point_ledger(id,user_id,type,delta,available_after,reserved_after) VALUES(1,7,'MANUAL_CREDIT',1,0,0)");
        long id=requests.createSubjectRequest(7,"CLOSE_ACCOUNT").requestId();lifecycle.decide(99,id,true,"本人申请，状态核验完成");lifecycle.decide(99,id,true,"重复请求");
        assertThat(jdbc.queryForObject("SELECT status FROM account WHERE id=7",String.class)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT status FROM design_project WHERE id=1",String.class)).isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject("SELECT revoked_by FROM design_access_grant WHERE id=1",String.class)).isEqualTo("99");
        assertThat(sessions.validateAccessToken(token.accessToken())).isEmpty();assertThat(sessions.refresh(token.refreshToken())).isEmpty();
        assertThatThrownBy(()->sessions.issue(7,"wxfixture","own",true,null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->new TransactionTemplate(manager).execute(s->{guard.requireActiveForWrite(7);return null;})).isInstanceOf(ServiceException.class);
        var points=new cn.iocoder.yudao.module.commerce.points.PointAccountService(ds,manager);
        ReflectionTestUtils.setField(points,"accountStatePort",guard);
        assertThatThrownBy(()->points.credit(7,"MANUAL_CREDIT",1,"manual","7","closure-manual","99","test")).isInstanceOf(ServiceException.class);
        var profile=new cn.iocoder.yudao.module.identity.account.AccountProfileService(ds,mock(cn.iocoder.yudao.module.infra.zhongshu.api.ProfileAvatarPort.class));
        assertThat(profile.update(7,"late edit",null)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE event_type='PRIVACY_REQUEST_DECIDED'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM account WHERE id=8",String.class)).isEqualTo("ACTIVE");
    }
    @Test void closureNeedsPolicyAndZeroBalanceAndNoUnfinishedPayments() {
        long id=requests.createSubjectRequest(7,"CLOSE_ACCOUNT").requestId();ReflectionTestUtils.setField(lifecycle,"policyVersion","");
        assertThatThrownBy(()->lifecycle.decide(99,id,true,"checked")).isInstanceOf(ServiceException.class).hasMessageContaining("留存");
        ReflectionTestUtils.setField(lifecycle,"policyVersion","approved-v1");
        jdbc.execute("INSERT INTO design_point_account(id,user_id,available_points,reserved_points) VALUES(1,7,1,0)");
        assertThatThrownBy(()->lifecycle.decide(99,id,true,"checked")).isInstanceOf(ServiceException.class).hasMessageContaining("设计点");
        jdbc.execute("UPDATE design_point_account SET available_points=0 WHERE user_id=7");
        jdbc.execute("INSERT INTO recharge_order(id,order_no,user_id,plan_id,plan_snapshot,amount_cents,base_points,bonus_points) VALUES(1,'ORD1',7,1,'{}',100,1,0)");
        assertThatThrownBy(()->lifecycle.decide(99,id,true,"checked")).isInstanceOf(ServiceException.class).hasMessageContaining("未完成");
        assertThat(requests.getSubjectRequest(7,id).orElseThrow().status()).isEqualTo("PENDING");
        lifecycle.decide(99,id,false,"请先处理待支付订单");assertThat(jdbc.queryForObject("SELECT status FROM account WHERE id=7",String.class)).isEqualTo("ACTIVE");
        assertThatThrownBy(()->lifecycle.decide(99,99999,true,"checked")).isInstanceOf(ServiceException.class);
    }
    @Test void closureWaitsForInFlightChargeAndThenDetectsItsPendingOrder() throws Exception {
        long id=requests.createSubjectRequest(7,"CLOSE_ACCOUNT").requestId();var held=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var write=pool.submit(()->new TransactionTemplate(manager).execute(s->{guard.requireActiveForWrite(7);held.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}jdbc.execute("INSERT INTO recharge_order(id,order_no,user_id,plan_id,plan_snapshot,amount_cents,base_points,bonus_points) VALUES(2,'ORD2',7,1,'{}',100,1,0)");return null;}));
            assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();var close=pool.submit(()->lifecycle.decide(99,id,true,"checked"));
            assertThatThrownBy(()->close.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);release.countDown();write.get(5,TimeUnit.SECONDS);
            assertThatThrownBy(()->close.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(ServiceException.class);
        }finally{release.countDown();pool.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT status FROM account WHERE id=7",String.class)).isEqualTo("ACTIVE");
    }
    @Test void concurrentSessionRefreshAndClosureCannotLeaveUsableSessionOrDeadlock() throws Exception {
        var token=sessions.issue(7,"wxfixture","own",true,null);long id=requests.createSubjectRequest(7,"CLOSE_ACCOUNT").requestId();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->{start.await();return sessions.refresh(token.refreshToken());});var b=pool.submit(()->{start.await();lifecycle.decide(99,id,true,"checked");return true;});start.countDown();var refreshed=a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);refreshed.ifPresent(t->assertThat(sessions.validateAccessToken(t.accessToken())).isEmpty());}finally{pool.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_session WHERE account_id=7 AND revoked_at IS NULL",Integer.class)).isZero();
    }
    /** 立面参考图 DESIGN_REFERENCE 是用户自己的照片：必须走真实上传入口落成 USER_UPLOAD，才会被导出清单圈进来，且只属于本人。 */
    AssetService assets(){
        var moderation=mock(ContentModerationPort.class);when(moderation.supportsUpload(anyString())).thenReturn(true);
        return new AssetService(ds,manager,storage,new AssetContentScanner(),moderation,delivery,new RightsGrantService(ds),guard);
    }
    @Test void exportListsOwnersDesignReferenceUploadsButNotOthersOrCompanyAssets() throws Exception {
        var assets=assets();var sha="a".repeat(64);
        long mine=assets.createUploadTicket(7,"DESIGN_REFERENCE","image/png",2048,sha).assetId();
        long others=assets.createUploadTicket(8,"DESIGN_REFERENCE","image/png",2048,sha).assetId();
        long company=assets.createUploadTicket(7,"CASE_IMAGE","image/png",2048,sha).assetId();
        assertThat(jdbc.queryForObject("SELECT source_type FROM asset WHERE id=?",String.class,mine)).isEqualTo("USER_UPLOAD");
        long id=requests.createSubjectRequest(7,"EXPORT").requestId();lifecycle.exportOne(id);
        var json=new String(lifecycle.download(7,id,lifecycle.ticket(7,id).getToken()),StandardCharsets.UTF_8);
        var inventory=new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get("assetInventory");
        var listed=new HashMap<String,String>();inventory.forEach(row->listed.put(row.get("id").asText(),row.get("asset_type").asText()));
        assertThat(listed).containsEntry(String.valueOf(mine),"DESIGN_REFERENCE")
                .doesNotContainKey(String.valueOf(others)).doesNotContainKey(String.valueOf(company));
    }
    @Test void closedAccountCannotUploadDesignReference() {
        var assets=assets();long id=requests.createSubjectRequest(7,"CLOSE_ACCOUNT").requestId();lifecycle.decide(99,id,true,"本人申请，状态核验完成");
        assertThatThrownBy(()->assets.createUploadTicket(7,"DESIGN_REFERENCE","image/png",2048,"b".repeat(64))).isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset WHERE owner_user_id=7",Integer.class)).isZero();
    }
}
