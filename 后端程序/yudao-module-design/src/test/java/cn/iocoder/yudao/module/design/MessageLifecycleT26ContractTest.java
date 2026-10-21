package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobSettlementService;
import cn.iocoder.yudao.module.commerce.points.PointAccountService;
import cn.iocoder.yudao.module.commerce.points.PointLedgerPortAdapter;
import cn.iocoder.yudao.module.design.controller.app.AppMessageController;
import cn.iocoder.yudao.module.design.controller.app.AppSubmissionController;
import cn.iocoder.yudao.module.design.notification.MessageFactResolver;
import cn.iocoder.yudao.module.design.notification.MessageService;
import cn.iocoder.yudao.module.design.submission.SubmissionReviewService;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxEventRecord;
import cn.iocoder.yudao.module.infra.zhongshu.event.JdbcReliableEventPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** Committed facts + real PostgreSQL and ledger; no live provider, payment or production data. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageLifecycleT26ContractTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private SimpleDriverDataSource ds;
    private DataSourceTransactionManager manager;
    private MessageService messages;
    private SubmissionReviewService submissions;
    private PointAccountService points;
    private AiJobSettlementService settlements;

    @BeforeAll void migrate() {
        ds = new SimpleDriverDataSource(new org.postgresql.Driver(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform", "classpath:db/migration/commerce",
                "classpath:db/migration/ai-orchestration", "classpath:db/migration/design").load().migrate();
        jdbc = new JdbcTemplate(ds); manager = new DataSourceTransactionManager(ds);
        messages = new MessageService(ds); var events = new JdbcReliableEventPort(ds);
        submissions = new SubmissionReviewService(ds, manager, null, events);
        points = new PointAccountService(ds, manager);
        settlements = new AiJobSettlementService(ds, manager, null, new PointLedgerPortAdapter(points), null, events);
    }
    @BeforeEach void seed() {
        jdbc.execute("TRUNCATE user_message,message_receipt,outbox_event,ai_job_result,ai_job_settlement,ai_job,design_result_version,design_selection,design_candidate,design_project,review_decision,review_task,submission_revision,case_submission,case_publication,design_case_version,design_case,recharge_credit,refund_order,recharge_order,design_point_ledger,design_point_account");
        jdbc.execute("INSERT INTO design_project(id,user_id,source_type) VALUES(91,1,'SELF_UPLOAD'),(92,2,'SELF_UPLOAD')");
    }
    private OutboxEventRecord event(long eventId,String type,String biz,String id,Map<String,Object> payload) {
        return new OutboxEventRecord(eventId,type,biz,id,MessageFactResolver.json(payload),"fixture");
    }
    private OutboxEventRecord persisted(String type) {
        return jdbc.queryForObject("SELECT id,event_type,biz_type,biz_id,payload::text FROM outbox_event WHERE event_type=? ORDER BY id DESC LIMIT 1",
                (rs,n)->new OutboxEventRecord(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),"fixture"),type);
    }
    private OutboxEventRecord copy(OutboxEventRecord source,long eventId) {
        return new OutboxEventRecord(eventId,source.getEventType(),source.getBizType(),source.getBizId(),source.getPayload(),"fixture");
    }
    private long messageId() { return ((Number)messages.list(1,100).get(0).get("id")).longValue(); }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    private OutboxEventRecord settled(long job,String phase) {
        jdbc.update("INSERT INTO ai_job(id,user_id,project_ref,phase,status,requested_count,accepted_count,output_prefix,unit_point_cost,total_point_cost) VALUES(?,1,'91',?,'PARTIALLY_SUCCEEDED',2,1,'fixture',5,10)",job,phase);
        jdbc.update("INSERT INTO ai_job_settlement(id,job_id,settlement_version,requested_count,accepted_billable_count,unit_point_cost,original_debit,refunded_points,reason) VALUES(?,?,1,2,1,5,10,5,'SETTLE')",job+1000,job);
        return event(job+2000,"AI_JOB_SETTLED","ai_job",String.valueOf(job),Map.of("userId",1,"status","PARTIALLY_SUCCEEDED","settlementVersion",1,"settlementReason","SETTLE","refund",999));
    }
    private void selection(long job,long candidate,long selection,long version,int versionNo,String phase) {
        jdbc.update("UPDATE design_selection SET active=FALSE WHERE project_id=91 AND stage=?",phase);
        jdbc.update("INSERT INTO design_candidate(id,project_id,job_id,slot_no,asset_id,ai_result_id) VALUES(?,91,?,1,?,?)",candidate,job,candidate+10000,candidate+20000);
        jdbc.update("INSERT INTO design_selection(id,project_id,stage,candidate_id,selected_by) VALUES(?,91,?,?,1)",selection,phase,candidate);
        if("FLAT".equals(phase)) jdbc.update("INSERT INTO design_result_version(id,project_id,version,flat_selection_id,flat_candidate_ids,result_line,superseded) VALUES(?,91,?, ?,CAST(? AS jsonb),'FLAT',TRUE)",version,versionNo,selection,"["+candidate+"]");
        else jdbc.update("INSERT INTO design_result_version(id,project_id,version,flat_selection_id,elevation_selection_id,flat_candidate_ids,elevation_candidate_id,result_line) VALUES(?,91,?,NULL,?,'[]',?,'ELEVATION')",version,versionNo,selection,candidate);
    }
    private void submission() {
        jdbc.execute("INSERT INTO design_result_version(id,project_id,version,flat_selection_id,flat_candidate_ids,result_line) VALUES(81,91,1,NULL,'[]','ELEVATION')");
        jdbc.execute("INSERT INTO case_submission(id,project_id,user_id,result_version_id,current_round,status) VALUES(31,91,1,81,1,'SUBMITTED')");
        jdbc.execute("INSERT INTO submission_revision(id,submission_id,round_no,result_version_id,rights_snapshot) VALUES(301,31,1,81,'{}')");
        jdbc.execute("INSERT INTO review_task(id,submission_id,round_no) VALUES(311,31,1)");
    }
    private void publishedCase() {
        jdbc.execute("INSERT INTO design_case(id,source_type,creator_user_id,current_version_id,publication_status) VALUES(51,'AI',1,62,'PUBLISHED')");
        jdbc.execute("INSERT INTO design_case_version(id,case_id,version,title,style_code,floor_count,building_area) VALUES(62,51,1,'虚构发布案例','MODERN',2,200)");
        jdbc.execute("INSERT INTO case_publication(id,case_id,action,published_version_id,operator_id) VALUES(61,51,'PUBLISH',62,'fixture')");
    }
    private void creditedOrder() {
        jdbc.execute("INSERT INTO recharge_order(id,order_no,user_id,plan_id,plan_snapshot,amount_cents,base_points,bonus_points) VALUES(21,'R21',1,1,'{}',1000,10,2)");
        jdbc.execute("INSERT INTO recharge_credit(id,order_id,base_points,bonus_points,ledger_base_id) VALUES(22,21,10,2,100)");
        jdbc.execute("INSERT INTO refund_order(id,order_id,refund_request_key,amount_cents,operator_id,channel_refund_id,channel_state,point_reversal_state) VALUES(23,21,'refund23',1000,'fixture','refund-23','SUCCEEDED','REVERSED')");
    }

    @Test void semanticReplayConcurrentTransportIdsAndReadReceiptNeverDuplicateOrCompleteAction() throws Exception {
        var source=settled(71,"FLAT"); var pool=Executors.newFixedThreadPool(4);
        try {
            var futures=new ArrayList<Future<?>>();
            for(int n=0;n<8;n++) { long id=5000+n; futures.add(pool.submit(()->messages.deliver(copy(source,id)))); }
            for(var future:futures)future.get(20,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(count("user_message")).isEqualTo(1);
        assertThat(messages.counts(1).pending()).isEqualTo(1);
        long id=messageId(); messages.markRead(1,id); messages.deliver(copy(source,6000));
        assertThat(messages.counts(1)).isEqualTo(new MessageService.MessageCounts(0,1));
        var row=messages.list(1,10).get(0);
        assertThat(row.get("action_state")).isEqualTo("PENDING");
        assertThat(row.get("content").toString()).contains("5设计点").doesNotContain("999");
        assertThat(messages.target(1,id)).containsEntry("type","JOB").containsEntry("jobId","71");
        assertThatThrownBy(()->messages.target(2,id)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->messages.markRead(2,id)).isInstanceOf(AccessDeniedException.class);
        assertThat(count("design_point_ledger")).isZero();
    }

    @Test void actualSettlementProducerAndReceiptDoNotRecalculateOrAlterLedgerAndFailedJobsHaveNoRetryTodo() {
        points.credit(1,"MANUAL_CREDIT",100,"test","seed","seed","fixture","fixture");
        points.debit(1,"FLAT_GENERATION_DEBIT",10,"ai_job","71","debit","fixture","fixture");
        jdbc.execute("INSERT INTO ai_job(id,user_id,project_ref,phase,status,requested_count,output_prefix,unit_point_cost,total_point_cost) VALUES(71,1,'91','FLAT','QUEUED',2,'fixture',5,10)");
        var outcome=settlements.settle(71,"SETTLE");
        assertThat(outcome.status()).isEqualTo("FAILED"); assertThat(outcome.refundedNow()).isEqualTo(10);
        var source=persisted("AI_JOB_SETTLED");
        assertThat(MessageFactResolver.json(source.getPayload())).containsEntry("settlementVersion",1).containsEntry("settlementReason","SETTLE");
        var ledger=jdbc.queryForList("SELECT id,type,delta,available_after,reserved_after FROM design_point_ledger ORDER BY id");
        messages.deliver(source);messages.deliver(copy(source,6000));messages.markRead(1,messageId());
        assertThat(messages.list(1,10).get(0)).containsEntry("action_state","NONE").containsEntry("title","设计方案生成失败");
        assertThat(messages.counts(1).pending()).isZero();
        assertThat(jdbc.queryForList("SELECT id,type,delta,available_after,reserved_after FROM design_point_ledger ORDER BY id")).isEqualTo(ledger);
        assertThat(points.findAccount(1).orElseThrow().availablePoints()).isEqualTo(100);
    }

    @Test void exactSelectionCompletesWithoutReadingAndLateDeliveryCannotReopenOrNavigateToLatestJob() {
        var old=settled(71,"FLAT"); messages.deliver(old);
        long id=messageId(); selection(71,701,711,81,1,"FLAT");
        settled(72,"FLAT");selection(72,702,712,82,2,"FLAT");
        assertThat(messages.counts(1)).isEqualTo(new MessageService.MessageCounts(1,0));
        assertThat(messages.listPage(1,null,50,"DONE").list()).hasSize(1);
        assertThat(messages.target(1,id)).containsEntry("type","RESULT").containsEntry("resultVersionId","81").containsEntry("jobId","71");
        messages.deliver(copy(old,9001));
        assertThat(messages.counts(1).pending()).isZero();assertThat(count("user_message")).isEqualTo(1);
        // First delivery is also completed if the business action predates delivery.
        var later=settled(73,"ELEVATION");selection(73,703,713,83,3,"ELEVATION");messages.deliver(later);
        assertThat(messages.counts(1).pending()).isZero();
        assertThat(messages.target(1,messageId())).containsEntry("resultVersionId","83");
        jdbc.update("UPDATE design_project SET deleted=TRUE WHERE id=91");
        messages.deliver(settled(74,"FLAT")); // initial late delivery after deletion is unavailable, never reopened
        assertThat(messages.listPage(1,null,10,"DONE").list()).allSatisfy(row->assertThat(row.get("action_state")).isEqualTo("UNAVAILABLE"));
        assertThatThrownBy(()->messages.target(1,id)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void settlementVersionIsPartOfIdentityAndChangedFactIsNeverSilentlyOverwritten() {
        var one=settled(71,"FLAT");messages.deliver(one);
        jdbc.execute("INSERT INTO ai_job_settlement(id,job_id,settlement_version,requested_count,accepted_billable_count,unit_point_cost,original_debit,refunded_points,reason) VALUES(2001,71,2,2,1,5,10,0,'CORRECTION')");
        var two=event(9002,"AI_JOB_SETTLED","ai_job","71",Map.of("userId",1,"status","PARTIALLY_SUCCEEDED","settlementVersion",2,"settlementReason","CORRECTION"));
        messages.deliver(two);assertThat(count("user_message")).isEqualTo(2);
        var tampered=event(9003,"AI_JOB_SETTLED","ai_job","71",Map.of("userId",1,"status","FAILED","settlementVersion",1,"settlementReason","SETTLE"));
        assertThatThrownBy(()->messages.deliver(tampered)).isInstanceOf(AccessDeniedException.class);
        jdbc.execute("UPDATE ai_job_settlement SET refunded_points=4 WHERE job_id=71 AND settlement_version=1");
        assertThatThrownBy(()->messages.deliver(copy(one,9004))).hasMessage("MESSAGE_FACT_CONFLICT");
        assertThat(count("user_message")).isEqualTo(2);
    }

    @Test void actualReviewRoundCompletionLateEventsAndNewRoundRemainDistinct() {
        submission(); submissions.decide(31,99,"CHANGES_REQUESTED","请补充尺寸"); var first=persisted("SUBMISSION_REVIEWED");
        assertThat(MessageFactResolver.json(first.getPayload())).containsEntry("reviewTaskId",311).containsEntry("reviewRound",1);
        messages.deliver(first);long oldId=messageId();messages.markRead(1,oldId);
        assertThat(messages.counts(1).pending()).isEqualTo(1);
        var response=submissions.resubmit(1,31,"已补尺寸","resubmit-1",1);
        assertThat(response.currentRound()).isEqualTo(2);
        assertThat(submissions.resubmit(1,31,"已补尺寸","resubmit-1",1)).isEqualTo(response);
        messages.deliver(copy(first,9004));assertThat(messages.counts(1).pending()).isZero();
        submissions.decide(31,99,"CHANGES_REQUESTED","请再补标高");var second=persisted("SUBMISSION_REVIEWED"); messages.deliver(second);
        assertThat(messages.counts(1).pending()).isEqualTo(1); assertThat(count("user_message")).isEqualTo(2);
        assertThat(messages.target(1,oldId)).containsEntry("reviewRound",1).containsEntry("historical",true);
        assertThatThrownBy(()->submissions.resubmit(1,31,"迟到旧页修改","wrong-round",1)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->submissions.resubmit(1,31,"已补尺寸","resubmit-1",2)).isInstanceOf(ServiceException.class);
        assertThat(submissions.getMySubmission(1,31).orElseThrow().currentRound()).isEqualTo(2);
        var round=submissions.publicReviewRound(1,31,1);assertThat(round).containsEntry("comment","请补充尺寸");
        assertThat(round).doesNotContainKeys("reviewer_user_id","reviewer_id");
        assertThatThrownBy(()->submissions.publicReviewRound(2,31,1)).isInstanceOf(ServiceException.class);
    }

    @Test void approvedReviewDoesNotClaimPublishedAndPublicationRequiresItsCommittedRecord() {
        submission();submissions.decide(31,99,"APPROVE","审核通过");messages.deliver(persisted("SUBMISSION_REVIEWED"));
        assertThat(messages.list(1,10).get(0).get("title")).isEqualTo("投稿审核已通过");
        assertThat(messages.list(1,10).get(0).get("content").toString()).contains("独立发布");assertThat(messages.counts(1).pending()).isZero();
        jdbc.execute("UPDATE case_submission SET published_case_id=51 WHERE id=31");
        var publication=event(9005,"SUBMISSION_PUBLISHED","case_submission","31",Map.of("publicationId",61,"userId",999));
        assertThatThrownBy(()->messages.deliver(publication)).isInstanceOf(AccessDeniedException.class);
        publishedCase();
        messages.deliver(publication);messages.deliver(copy(publication,9006));
        assertThat(messages.list(1,10)).hasSize(2);assertThat(messages.list(999,10)).isEmpty();
        assertThat(messages.list(1,10).get(0).get("fact_version")).isEqualTo("publication:61");
    }

    @Test void creditsAndRefundsUseRealRecordsAndLegacyIncompleteEventsStayGeneric() {
        jdbc.execute("INSERT INTO recharge_order(id,order_no,user_id,plan_id,plan_snapshot,amount_cents,base_points,bonus_points) VALUES(21,'R21',1,1,'{}',1000,10,2)");
        var credit=event(9010,"ORDER_CREDITED","recharge_order","21",Map.of("userId",1));
        messages.deliver(credit); assertThat(messages.list(1,10).get(0).get("title")).isEqualTo("充值记录状态已更新");
        jdbc.execute("INSERT INTO recharge_credit(id,order_id,base_points,bonus_points,ledger_base_id) VALUES(22,21,10,2,100)");
        messages.deliver(copy(credit,9011));messages.deliver(copy(credit,9012));
        messages.deliver(event(9016,"ORDER_CREDITED","recharge_order","21",Map.of("userId",999)));
        assertThat(messages.list(999,10)).isEmpty();
        assertThat(messages.list(1,10).get(0).get("content").toString()).contains("基础10设计点","赠送2设计点");
        jdbc.execute("INSERT INTO refund_order(id,order_id,refund_request_key,amount_cents,operator_id,channel_refund_id) VALUES(23,21,'refund23',1000,'fixture','refund-23')");
        var refund=event(9013,"ORDER_REFUND_REVERSED","refund_order","23",Map.of("refundId",23));
        messages.deliver(refund);assertThat(messages.list(1,10).get(0).get("title")).isEqualTo("退款记录状态已更新");
        jdbc.execute("UPDATE refund_order SET channel_state='SUCCEEDED',point_reversal_state='REVERSED' WHERE id=23");
        messages.deliver(copy(refund,9014));messages.deliver(copy(refund,9015));
        assertThat(messages.list(1,10).get(0).get("title")).isEqualTo("退款已完成");
        assertThat(messages.target(1,messageId())).containsEntry("type","ORDER").containsEntry("orderId","21");
        assertThat(messages.counts(1).pending()).isZero();assertThat(count("design_point_ledger")).isZero();
    }

    @Test void reviewFactsExactRoundsAndNavigationRejectForeignRowsEvenWithSameNumericOwner() {
        submission();submissions.decide(31,99,"CHANGES_REQUESTED","请补充尺寸");var source=persisted("SUBMISSION_REVIEWED");
        var resolver=new MessageFactResolver(ds);
        Map<String,Object> target=Map.of("type","SUBMISSION","submissionId","31","reviewRound",1);
        for(String table:List.of("case_submission","review_task","review_decision","design_project")) {
            jdbc.execute("UPDATE "+table+" SET tenant_id=7");
            try {
                assertThatThrownBy(()->messages.deliver(source)).as(table+" cannot supply a domain-0 fact").isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(()->resolver.resolveTarget(1,target)).as(table+" cannot supply an exact target").isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(()->submissions.publicReviewRound(1,31,1)).as(table+" cannot supply a public review").isInstanceOf(ServiceException.class);
                assertThat(count("user_message")).isZero();
            } finally { jdbc.execute("UPDATE "+table+" SET tenant_id=0"); }
        }
        jdbc.execute("UPDATE design_project SET user_id=2 WHERE id=91");
        assertThatThrownBy(()->messages.deliver(source)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->resolver.resolveTarget(1,target)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->submissions.publicReviewRound(1,31,1)).isInstanceOf(ServiceException.class);
        assertThat(count("user_message")).isZero();
        jdbc.execute("UPDATE design_project SET user_id=1 WHERE id=91");
        messages.deliver(source);assertThat(messages.counts(1).pending()).isEqualTo(1);
    }

    @Test void deletedSubmissionProjectMakesExistingAndInitiallyLateReviewTodosUnavailable() {
        submission();submissions.decide(31,99,"CHANGES_REQUESTED","请补充尺寸");var source=persisted("SUBMISSION_REVIEWED");
        messages.deliver(source);long id=messageId();
        jdbc.execute("UPDATE design_project SET deleted=TRUE WHERE id=91");
        messages.deliver(copy(source,9031));
        assertThat(messages.counts(1).pending()).isZero();
        assertThat(messages.listPage(1,null,10,"DONE").list()).singleElement().satisfies(row->assertThat(row.get("action_state")).isEqualTo("UNAVAILABLE"));
        assertThatThrownBy(()->messages.target(1,id)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->submissions.publicReviewRound(1,31,1)).isInstanceOf(ServiceException.class);
        jdbc.execute("TRUNCATE user_message");messages.deliver(copy(source,9032));
        assertThat(messages.list(1,10)).singleElement().satisfies(row->assertThat(row.get("action_state")).isEqualTo("UNAVAILABLE"));
        assertThat(messages.counts(1).pending()).isZero();
    }

    @Test void publicationRequiresDomainZeroSubmissionProjectPublicationAndCase() {
        submission();publishedCase();jdbc.execute("UPDATE case_submission SET published_case_id=51 WHERE id=31");
        var source=event(9041,"SUBMISSION_PUBLISHED","case_submission","31",Map.of("publicationId",61,"userId",1));
        for(String table:List.of("case_submission","design_project","case_publication","design_case")) {
            jdbc.execute("UPDATE "+table+" SET tenant_id=7");
            try {
                assertThatThrownBy(()->messages.deliver(source)).as(table+" is outside the business domain").isInstanceOfAny(AccessDeniedException.class,IllegalStateException.class);
                assertThat(count("user_message")).isZero();
            } finally { jdbc.execute("UPDATE "+table+" SET tenant_id=0"); }
        }
        messages.deliver(source);assertThat(messages.list(1,10)).singleElement().satisfies(row->assertThat(row.get("fact_version")).isEqualTo("publication:61"));
    }

    @Test void creditRefundAndLegacyExactTargetsCannotUseKnownForeignBusinessRows() {
        creditedOrder();var resolver=new MessageFactResolver(ds);
        var credit=event(9051,"ORDER_CREDITED","recharge_order","21",Map.of("userId",1));
        var refund=event(9052,"ORDER_REFUND_REVERSED","refund_order","23",Map.of("userId",1));
        Map<String,Object> orderTarget=Map.of("type","ORDER","orderId","21");
        Map<String,Object> projectTarget=Map.of("type","PROJECT","projectId","91");
        for(String table:List.of("recharge_order","recharge_credit")) {
            jdbc.execute("UPDATE "+table+" SET tenant_id=7");
            try {
                assertThatThrownBy(()->messages.deliver(credit)).as(table+" must not fall back to legacy delivery").isInstanceOf(AccessDeniedException.class);
                assertThat(count("user_message")).isZero();
                if("recharge_order".equals(table))assertThatThrownBy(()->resolver.resolveTarget(1,orderTarget)).isInstanceOf(AccessDeniedException.class);
            } finally { jdbc.execute("UPDATE "+table+" SET tenant_id=0"); }
        }
        for(String table:List.of("recharge_order","refund_order")) {
            jdbc.execute("UPDATE "+table+" SET tenant_id=7");
            try {
                assertThatThrownBy(()->messages.deliver(refund)).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(()->resolver.fact(refund,1)).isInstanceOf(AccessDeniedException.class);
                assertThat(count("user_message")).isZero();
            } finally { jdbc.execute("UPDATE "+table+" SET tenant_id=0"); }
        }
        jdbc.execute("UPDATE design_project SET tenant_id=7 WHERE id=91");
        assertThatThrownBy(()->resolver.resolveTarget(1,projectTarget)).isInstanceOf(AccessDeniedException.class);
        jdbc.execute("UPDATE design_project SET tenant_id=0 WHERE id=91");
        assertThat(resolver.resolveTarget(1,projectTarget)).containsEntry("projectId","91");
        messages.deliver(credit);messages.deliver(refund);
        assertThat(messages.list(1,10)).hasSize(2);assertThat(count("design_point_ledger")).isZero();
    }

    @Test void missingHistoricMetadataStaysGenericButKnownForeignIdsDoNot() {
        submission();jdbc.execute("UPDATE case_submission SET tenant_id=7 WHERE id=31");
        assertThatThrownBy(()->messages.deliver(event(9061,"SUBMISSION_REVIEWED","case_submission","31",Map.of("ownerUserId",1)))).isInstanceOf(AccessDeniedException.class);
        assertThat(count("user_message")).isZero();
        messages.deliver(event(9062,"ORDER_CREDITED","recharge_order","missing-legacy-id",Map.of("userId",1)));
        assertThat(messages.list(1,10)).singleElement().satisfies(row->assertThat(row.get("title")).isEqualTo("充值记录状态已更新"));
    }

    @Test void foreignSettlementSelectionAndResultRowsNeverProveDomainZeroCompletion() {
        var source=settled(71,"FLAT");var resolver=new MessageFactResolver(ds);
        jdbc.execute("UPDATE ai_job_settlement SET tenant_id=7");
        assertThatThrownBy(()->messages.deliver(source)).isInstanceOf(AccessDeniedException.class);
        assertThat(count("user_message")).isZero();jdbc.execute("UPDATE ai_job_settlement SET tenant_id=0");
        messages.deliver(source);long id=messageId();selection(71,701,711,81,1,"FLAT");
        for(String table:List.of("design_selection","design_candidate")) {
            jdbc.execute("UPDATE "+table+" SET tenant_id=7");
            try {
                assertThat(messages.target(1,id)).containsEntry("type","JOB").doesNotContainKey("resultVersionId");
                assertThat(messages.counts(1).pending()).isEqualTo(1);
            } finally { jdbc.execute("UPDATE "+table+" SET tenant_id=0"); }
        }
        jdbc.execute("UPDATE design_result_version SET tenant_id=7");
        assertThat(messages.target(1,id)).containsEntry("type","JOB").doesNotContainKey("resultVersionId");
        jdbc.execute("UPDATE design_result_version SET tenant_id=0");
        assertThat(messages.target(1,id)).containsEntry("resultVersionId","81");
        jdbc.execute("UPDATE ai_job SET tenant_id=7 WHERE id=71");
        assertThatThrownBy(()->resolver.fact(event(9071,"AI_JOB_SETTLED","ai_job","71",Map.of("userId",1)),1)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->messages.target(1,id)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void filteredCursorCanScanCompletedRowsWithoutSkippingPendingFactsBeyondFifty() {
        var source=settled(71,"FLAT");Map<String,Object> target=Map.of("type","JOB","jobId","71","projectId","91","phase","FLAT");
        for(long id=1;id<=125;id++)jdbc.update("INSERT INTO user_message(id,user_id,message_type,title,action_type,target_snapshot) VALUES(?,1,'AI_JOB_SETTLED','fixture','SELECT_FLAT',CAST(? AS jsonb))",id,MessageFactResolver.json(target));
        settled(72,"FLAT");selection(72,702,712,82,1,"FLAT");
        Map<String,Object> completed=Map.of("type","JOB","jobId","72","projectId","91","phase","FLAT");
        for(long id=126;id<=250;id++)jdbc.update("INSERT INTO user_message(id,user_id,message_type,title,action_type,target_snapshot) VALUES(?,1,'AI_JOB_SETTLED','completed','SELECT_FLAT',CAST(? AS jsonb))",id,MessageFactResolver.json(completed));
        for(long id=251;id<=275;id++)jdbc.update("INSERT INTO user_message(id,user_id,message_type,title) VALUES(?,1,'AI_JOB_SETTLED','legacy')",id);
        assertThat(messages.counts(1)).isEqualTo(new MessageService.MessageCounts(275,125));
        var first=messages.listPage(1,null,50,"TODO");assertThat(first.list()).hasSize(50);assertThat(first.nextCursor()).isEqualTo("76");
        var second=messages.listPage(1,first.nextCursor(),50,"TODO");var third=messages.listPage(1,second.nextCursor(),50,"TODO");
        assertThat(second.list()).hasSize(50);assertThat(third.list()).hasSize(25);assertThat(third.nextCursor()).isNull();
        assertThat(messages.listPage(1,null,50,"NOTICE").list()).hasSize(25);
        assertThatThrownBy(()->messages.listPage(1,null,50,"unknown")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->messages.listPage(1,"9223372036854775808",50)).isInstanceOf(ServiceException.class);
        assertThat(messages.supports("AI_JOB_PROGRESS")).isFalse();
    }

    @Test void apiRequiresOwnerAndUnrestrictedSessionAndPublishesSeparateCounts() {
        submission();submissions.decide(31,99,"CHANGES_REQUESTED","请修改");messages.deliver(persisted("SUBMISSION_REVIEWED"));
        IdentitySessionPort sessions=token->switch(token==null?"":token) {
            case "owner"->Optional.of(new IdentitySessionPort.SessionContext(1,"fixture","owner",false));
            case "other"->Optional.of(new IdentitySessionPort.SessionContext(2,"fixture","other",false));
            case "restricted"->Optional.of(new IdentitySessionPort.SessionContext(1,"fixture","owner",true));
            default->Optional.empty();
        };
        var controller=new AppMessageController();ReflectionTestUtils.setField(controller,"messageService",messages);ReflectionTestUtils.setField(controller,"identitySessionPort",sessions);
        assertThat(controller.counts("Bearer owner").getData()).isEqualTo(new MessageService.MessageCounts(1,1));
        var page=controller.getMessagePage(null,20,"TODO","Bearer owner").getData();
        assertThat(page.getList()).hasSize(1);assertThat(page.getList().get(0).getCategory()).isEqualTo("TODO");
        assertThat(page.getList().get(0).getActionState()).isEqualTo("PENDING");
        assertThat(controller.target(String.valueOf(messageId()),"Bearer owner").getData()).containsEntry("reviewRound",1);
        assertThatThrownBy(()->controller.target(String.valueOf(messageId()),"Bearer other")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->controller.counts("Bearer restricted")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->controller.markRead("bad","Bearer owner")).isInstanceOf(ServiceException.class);
        var app=new AppSubmissionController();ReflectionTestUtils.setField(app,"submissionReviewService",submissions);ReflectionTestUtils.setField(app,"identitySessionPort",sessions);
        var dto=app.getSubmission("31",1,"Bearer owner").getData();assertThat(dto.getViewedReviewRound()).isEqualTo(1);assertThat(dto.getReviewComment()).isEqualTo("请修改");
    }

    @Test void migrationUndoPreservesLegacyReceiptsAndRefusesToEraseDeliveredVersionedFacts() throws Exception {
        String undo=new ClassPathResource("db/migration-undo/design/V20261012.511__undo_message_lifecycle.sql").getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute("INSERT INTO user_message(id,user_id,message_type,title) VALUES(1,1,'AI_JOB_SETTLED','legacy')");messages.markRead(1,1);
        new TransactionTemplate(manager).execute(status->{jdbc.execute(undo);assertThat(count("message_receipt")).isEqualTo(1);status.setRollbackOnly();return null;});
        assertThat(messages.unreadCount(1)).isZero(); messages.deliver(settled(71,"FLAT"));
        assertThatThrownBy(()->jdbc.execute(undo)).hasMessageContaining("T26-10 messages exist");
        assertThat(count("user_message")).isEqualTo(2);assertThat(count("message_receipt")).isEqualTo(1);
        assertThat(messages.counts(1).pending()).isEqualTo(1);
    }
}
