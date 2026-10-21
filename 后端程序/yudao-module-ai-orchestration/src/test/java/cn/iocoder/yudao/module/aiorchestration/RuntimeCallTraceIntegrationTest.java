package cn.iocoder.yudao.module.aiorchestration;

import cn.iocoder.yudao.module.aiorchestration.job.RuntimeCallTraceService;
import cn.iocoder.yudao.module.aiorchestration.job.RuntimeCallTraceService.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuntimeCallTraceIntegrationTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private RuntimeCallTraceService service;

    @BeforeAll void migrate() {
        var ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class); ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername()); ds.setPassword(PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform", "classpath:db/migration/commerce",
                "classpath:db/migration/ai-orchestration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        service = new RuntimeCallTraceService(ds, new DataSourceTransactionManager(ds));
    }
    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE ai_runtime_call_trace,ai_job,ai_job_attempt");
        jdbc.execute("INSERT INTO ai_job(id,user_id,phase,status,requested_count,output_prefix,fencing_token,claim_expires_at) "
                + "VALUES(1,7,'FLAT','RUNNING',2,'ai-quarantine/1',3,now()+interval '5 minutes')");
        jdbc.execute("INSERT INTO ai_job_attempt(id,job_id,attempt_no,worker_id,fencing_token) VALUES(2,1,1,'fixture',3)");
    }
    private RequestSpec plan(String text, String model) {
        return new RequestSpec("flat-20261012.1", model, "/chat/completions", "系统规则", text, null, 0.0, 1800, null, null, List.of());
    }
    private Event event(String state, RequestSpec request, ResponseSpec response, String error) {
        return new Event(1,3,"plan-1",1,3,"PLAN",0,state,request,response,error);
    }
    private ResponseSpec planResponse(String text) { return new ResponseSpec(text,"a".repeat(64),null,null,null,null); }
    private Event generate(int slot, String state) {
        var request = new RequestSpec("flat-20261012.1","image-fixture","/images/generations",null,
                "用户要求与候选 " + slot,"1536x1024",null,null,1,"high",List.of());
        return new Event(1,3,"image-1-"+slot,1,3,"GENERATE",slot,state,request,
                "SUCCEEDED".equals(state) ? new ResponseSpec(null,"b".repeat(64),13,7,"image/png",80L) : null,null);
    }

    @Test void registrationAndResponseAreIdempotentAndDoNotTouchBillingOrLegacyPlanRecord() {
        var request = plan("一层老人房，二层露台", "text-v1");
        assertThat(service.record(1,event("REQUESTED",request,null,null))).isTrue();
        assertThat(service.record(1,event("DISPATCHED",request,null,null))).isTrue();
        var completed = event("SUCCEEDED",request,planResponse("计划输出"),null);
        assertThat(service.record(1,completed)).isTrue();
        assertThat(service.record(1,completed)).isTrue();
        assertThat(service.record(1,event("REQUESTED",request,null,null))).isTrue();
        var rows=service.list(1);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).state()).isEqualTo("SUCCEEDED");
        assertThat(rows.get(0).request().get("promptText").asText()).isEqualTo("一层老人房，二层露台");
        assertThat(rows.get(0).response().get("promptText").asText()).isEqualTo("计划输出");
        assertThat(jdbc.queryForObject("SELECT prompt_text FROM ai_job_attempt WHERE id=2",String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_charge",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_usage_charge",Long.class)).isZero();
    }

    @Test void immutableRequestAndReceivedResponseCannotBeChangedByDuplicateCallId() {
        var request=plan("原请求", "original-model");
        service.record(1,event("REQUESTED",request,null,null));
        assertThatThrownBy(()->service.record(1,event("REQUESTED",plan("被替换","original-model"),null,null))).hasMessage("RUNTIME_TRACE_CONFLICT");
        assertThatThrownBy(()->service.record(1,event("SUCCEEDED",plan("原请求","different-model"),planResponse("计划"),null))).hasMessage("RUNTIME_TRACE_CONFLICT");
        service.record(1,event("SUCCEEDED",request,planResponse("计划"),null));
        assertThatThrownBy(()->service.record(1,event("SUCCEEDED",request,planResponse("不同响应"),null))).hasMessage("RUNTIME_TRACE_CONFLICT");
        assertThat(service.list(1).get(0).request().get("model").asText()).isEqualTo("original-model");
        assertThat(service.list(1).get(0).response().get("promptText").asText()).isEqualTo("计划");
    }

    @Test void staleExpiredFinishedWrongPhaseAndUnownedSlotsAreRejectedBeforeWriting() {
        var request=event("REQUESTED",plan("需求","model"),null,null);
        assertThatThrownBy(()->service.record(2,request)).hasMessage("RUNTIME_TRACE_INVALID");
        jdbc.execute("UPDATE ai_job SET claim_expires_at=now()-interval '1 second'");
        assertThatThrownBy(()->service.record(1,request)).hasMessage("RUNTIME_TRACE_LEASE");
        jdbc.execute("UPDATE ai_job SET claim_expires_at=now()+interval '5 minutes',fencing_token=4");
        assertThatThrownBy(()->service.record(1,request)).hasMessage("RUNTIME_TRACE_LEASE");
        jdbc.execute("UPDATE ai_job SET fencing_token=3"); jdbc.execute("UPDATE ai_job_attempt SET finished_at=now()");
        assertThatThrownBy(()->service.record(1,request)).hasMessage("RUNTIME_TRACE_LEASE");
        jdbc.execute("UPDATE ai_job_attempt SET finished_at=NULL"); jdbc.execute("UPDATE ai_job SET phase='ELEVATION'");
        assertThatThrownBy(()->service.record(1,request)).hasMessage("RUNTIME_TRACE_LEASE");
        jdbc.execute("UPDATE ai_job SET phase='FLAT'");
        assertThatThrownBy(()->service.record(1,generate(3,"REQUESTED"))).hasMessage("RUNTIME_TRACE_LEASE");
        assertThat(service.list(1)).isEmpty();
    }

    @Test void currentLeaseCanReplayOriginalAttemptTraceButCannotPretendThePaidCallUsedNewSettings() {
        var request=plan("原尝试输入", "original-model");
        service.record(1,event("DISPATCHED",request,null,null));
        jdbc.execute("UPDATE ai_job SET fencing_token=4; UPDATE ai_job_attempt SET finished_at=now(); "
                + "INSERT INTO ai_job_attempt(id,job_id,attempt_no,worker_id,fencing_token) VALUES(3,1,2,'recovery',4)");
        var replay=new Event(2,4,"plan-1",1,3,"PLAN",0,"SUCCEEDED",request,planResponse("原计划"),null);
        assertThat(service.record(1,replay)).isTrue();
        assertThat(service.list(1).get(0).sourceAttemptNo()).isEqualTo(1);
        assertThatThrownBy(()->service.record(1,event("SUCCEEDED",request,planResponse("原计划"),null))).hasMessage("RUNTIME_TRACE_LEASE");
        assertThatThrownBy(()->service.record(1,new Event(2,4,"plan-1",2,4,"PLAN",0,"SUCCEEDED",request,planResponse("原计划"),null)))
                .hasMessage("RUNTIME_TRACE_CONFLICT");
    }

    @Test void concurrentSlotAndDuplicateEventsRemainIndependentAndCannotIncreaseCandidateCount() throws Exception {
        var pool=Executors.newFixedThreadPool(4);
        try {
            List<Future<Boolean>> tasks=new ArrayList<>();
            for(int i=0;i<12;i++) { int slot=i%2+1; tasks.add(pool.submit(()->service.record(1,generate(slot,"SUCCEEDED")))); }
            for(var task:tasks) assertThat(task.get(10,TimeUnit.SECONDS)).isTrue();
        } finally { pool.shutdownNow(); }
        assertThat(service.list(1)).hasSize(2).extracting(Trace::slot).containsExactly(1,2);
        assertThat(service.list(1)).allSatisfy(row -> {
            assertThat(row.request().get("n").asInt()).isEqualTo(1);
            assertThat(row.response().get("width").asInt()).isEqualTo(13);
            assertThat(row.request().get("size").asText()).isEqualTo("1536x1024");
        });
    }

    @Test void unknownOutcomeCanGainReceivedEvidenceButNotSentCannotBecomeASecondPaidCall() {
        var request=plan("需求", "model");
        service.record(1,event("DISPATCHED",request,null,null));
        service.record(1,event("UNKNOWN",request,null,"HTTP_503"));
        assertThat(service.record(1,event("SUCCEEDED",request,planResponse("已收到的原响应"),null))).isTrue();
        jdbc.execute("TRUNCATE ai_runtime_call_trace");
        service.record(1,event("NOT_SENT",request,null,"REQUEST_ABORTED"));
        assertThatThrownBy(()->service.record(1,event("SUCCEEDED",request,planResponse("新调用"),null))).hasMessage("RUNTIME_TRACE_CONFLICT");
    }

    @Test void invalidOversizedOrUncontrolledMetadataNeverPersistsAndDeletedJobsAreNotQueryable() {
        assertThatThrownBy(()->service.record(1,event("REQUESTED",plan("x".repeat(64001),"model"),null,null))).hasMessage("RUNTIME_TRACE_INVALID");
        assertThatThrownBy(()->service.record(1,event("UNKNOWN",plan("需求","model"),null,"Bearer fixture-token"))).hasMessage("RUNTIME_TRACE_INVALID");
        var ref=new Reference("11",-1,"123","CASE_FLOOR_PLAN","a".repeat(64),"a".repeat(64),"image/png",10);
        var request=new RequestSpec("flat-v1","model","/chat/completions","系统","需求",null,0.0,1800,null,null,List.of(ref));
        assertThatThrownBy(()->service.record(1,event("REQUESTED",request,null,null))).hasMessage("RUNTIME_TRACE_INVALID");
        assertThat(service.list(1)).isEmpty();
        service.record(1,event("REQUESTED",plan("需求","model"),null,null));
        jdbc.execute("UPDATE ai_job SET deleted=TRUE");
        assertThat(service.list(1)).isEmpty();
    }

    private void finishSource() {
        jdbc.execute("UPDATE ai_job SET status='SUCCEEDED',accepted_count=2,unit_point_cost=5,total_point_cost=10,claim_expires_at=NULL");
        jdbc.execute("UPDATE ai_job_attempt SET finished_at=now()");
    }

    @Test void terminalJobCanReconcileOnlyItsRegisteredOriginalEvidenceWithoutChangingBillingOrBusinessResult() {
        var request=plan("一层老人房，二层书房", "original-model");
        service.record(1,event("REQUESTED",request,null,null));
        service.record(1,event("DISPATCHED",request,null,null));
        finishSource();
        var before=jdbc.queryForMap("SELECT user_id,status,requested_count,accepted_count,fencing_token,unit_point_cost,total_point_cost FROM ai_job WHERE id=1");
        var completed=event("SUCCEEDED",request,planResponse("已经收到的原计划"),null);
        assertThatThrownBy(()->service.record(1,completed)).hasMessage("RUNTIME_TRACE_LEASE");
        assertThat(service.reconcile(1,event("UNKNOWN",request,null,"HTTP_503"))).isEqualTo("RECORDED");
        assertThat(service.reconcile(1,completed)).isEqualTo("RECORDED");
        assertThat(service.reconcile(1,completed)).isEqualTo("RECORDED");
        assertThat(service.list(1)).singleElement().satisfies(row->{
            assertThat(row.state()).isEqualTo("SUCCEEDED");
            assertThat(row.request().get("model").asText()).isEqualTo("original-model");
            assertThat(row.response().get("promptText").asText()).isEqualTo("已经收到的原计划");
        });
        assertThat(jdbc.queryForMap("SELECT user_id,status,requested_count,accepted_count,fencing_token,unit_point_cost,total_point_cost FROM ai_job WHERE id=1")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT prompt_text FROM ai_job_attempt WHERE id=2",String.class)).isNull();
        for(String table:List.of("ai_task_charge","service_usage_charge","ai_job_result","ai_job_settlement"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class)).as(table+" is not written by reconciliation").isZero();
    }

    @Test void reconciliationCannotBypassALiveLeaseRegisterANewCallOrInventADispatch() {
        var request=plan("原请求", "original-model");
        service.record(1,event("REQUESTED",request,null,null));
        var completed=event("SUCCEEDED",request,planResponse("原计划"),null);
        assertThat(service.reconcile(1,completed)).isEqualTo("RETRY");
        assertThat(service.reconcile(1,generate(2,"SUCCEEDED"))).isEqualTo("RETRY");
        assertThat(service.list(1)).singleElement().satisfies(row->assertThat(row.state()).isEqualTo("REQUESTED"));
        assertThatThrownBy(()->service.reconcile(1,event("REQUESTED",request,null,null))).hasMessage("RUNTIME_TRACE_INVALID");
        assertThatThrownBy(()->service.reconcile(1,event("DISPATCHED",request,null,null))).hasMessage("RUNTIME_TRACE_INVALID");
        jdbc.execute("UPDATE ai_job SET claim_expires_at=now()-interval '1 second'");
        assertThatThrownBy(()->service.record(1,completed)).hasMessage("RUNTIME_TRACE_LEASE");
        assertThat(service.reconcile(1,completed)).isEqualTo("RECORDED");
        finishSource();
        assertThat(service.reconcile(1,generate(2,"SUCCEEDED"))).isEqualTo("DISCARDED");
        assertThat(service.list(1)).hasSize(1);
        assertThat(service.reconcile(1,completed)).isEqualTo("RECORDED");
    }

    @Test void reconciliationRejectsChangedRequestResponseSourceFenceAndCrossJobIdentity() {
        var request=plan("原请求", "original-model");
        service.record(1,event("REQUESTED",request,null,null));finishSource();
        assertThatThrownBy(()->service.reconcile(1,event("SUCCEEDED",plan("伪造请求","original-model"),planResponse("计划"),null))).hasMessage("RUNTIME_TRACE_CONFLICT");
        assertThatThrownBy(()->service.reconcile(1,event("SUCCEEDED",plan("原请求","changed-model"),planResponse("计划"),null))).hasMessage("RUNTIME_TRACE_CONFLICT");
        assertThatThrownBy(()->service.reconcile(1,new Event(1,9,"plan-1",1,9,"PLAN",0,"SUCCEEDED",request,planResponse("计划"),null))).hasMessage("RUNTIME_TRACE_CONFLICT");
        assertThatThrownBy(()->service.reconcile(1,new Event(2,4,"plan-1",1,3,"PLAN",0,"SUCCEEDED",request,planResponse("计划"),null))).hasMessage("RUNTIME_TRACE_INVALID");
        jdbc.execute("INSERT INTO ai_job(id,user_id,phase,status,requested_count,output_prefix,fencing_token) VALUES(9,8,'FLAT','SUCCEEDED',2,'other-owner',3)");
        jdbc.execute("INSERT INTO ai_job_attempt(id,job_id,attempt_no,worker_id,fencing_token,finished_at) VALUES(90,9,1,'other-owner',3,now())");
        var other=new Event(1,3,"plan-9",1,3,"PLAN",0,"SUCCEEDED",request,planResponse("计划"),null);
        assertThat(service.reconcile(9,other)).isEqualTo("DISCARDED");
        assertThat(service.list(9)).isEmpty();
        service.reconcile(1,event("SUCCEEDED",request,planResponse("原响应"),null));
        assertThatThrownBy(()->service.reconcile(1,event("SUCCEEDED",request,planResponse("伪造新响应"),null))).hasMessage("RUNTIME_TRACE_CONFLICT");
        assertThat(service.list(1).get(0).response().get("promptText").asText()).isEqualTo("原响应");
    }

    @Test void reconciliationCannotRestoreDeletedOrErasedEvidenceOrUseForeignOrDeletedSourceAttempts() {
        var request=plan("用户需求", "original-model");
        service.record(1,event("REQUESTED",request,null,null));finishSource();
        var completed=event("SUCCEEDED",request,planResponse("原计划"),null);
        jdbc.execute("UPDATE ai_job SET deleted=TRUE");
        assertThat(service.reconcile(1,completed)).isEqualTo("DISCARDED");
        assertThat(jdbc.queryForObject("SELECT state FROM ai_runtime_call_trace",String.class)).isEqualTo("REQUESTED");
        jdbc.execute("UPDATE ai_job SET deleted=FALSE; UPDATE ai_job_attempt SET deleted=TRUE");
        assertThatThrownBy(()->service.reconcile(1,completed)).hasMessage("RUNTIME_TRACE_SOURCE");
        jdbc.execute("UPDATE ai_job_attempt SET deleted=FALSE,tenant_id=7");
        assertThatThrownBy(()->service.reconcile(1,completed)).hasMessage("RUNTIME_TRACE_SOURCE");
        jdbc.execute("UPDATE ai_job_attempt SET tenant_id=0; UPDATE ai_job SET tenant_id=7");
        assertThat(service.reconcile(1,completed)).isEqualTo("DISCARDED");
        jdbc.execute("UPDATE ai_job SET tenant_id=0");
        // Privacy closure erases the trace while retaining the existing job/financial receipt.
        jdbc.execute("DELETE FROM ai_runtime_call_trace");
        assertThat(service.reconcile(1,completed)).isEqualTo("DISCARDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_runtime_call_trace",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT total_point_cost FROM ai_job WHERE id=1",Long.class)).isEqualTo(10);
    }

    @Test void aLaterAttemptCanNeitherReplaceTheOriginalTraceIdentityNorConvertNotSentToAnotherPaidCall() {
        var request=plan("原请求", "original-model");
        service.record(1,event("NOT_SENT",request,null,"REQUEST_ABORTED"));finishSource();
        jdbc.execute("UPDATE ai_job SET status='RUNNING',fencing_token=4,claim_expires_at=now()+interval '5 minutes'");
        jdbc.execute("INSERT INTO ai_job_attempt(id,job_id,attempt_no,worker_id,fencing_token) VALUES(3,1,2,'recovery',4)");
        assertThat(service.reconcile(1,event("NOT_SENT",request,null,"REQUEST_ABORTED"))).isEqualTo("RECORDED");
        assertThatThrownBy(()->service.reconcile(1,new Event(2,4,"plan-1",2,4,"PLAN",0,"NOT_SENT",request,null,"REQUEST_ABORTED"))).hasMessage("RUNTIME_TRACE_CONFLICT");
        assertThatThrownBy(()->service.reconcile(1,event("SUCCEEDED",request,planResponse("新付费调用"),null))).hasMessage("RUNTIME_TRACE_CONFLICT");
        assertThat(service.list(1)).singleElement().satisfies(row->{
            assertThat(row.sourceAttemptNo()).isEqualTo(1);
            assertThat(row.state()).isEqualTo("NOT_SENT");
            assertThat(row.response()).isNull();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_usage_charge",Long.class)).isZero();
    }
}
