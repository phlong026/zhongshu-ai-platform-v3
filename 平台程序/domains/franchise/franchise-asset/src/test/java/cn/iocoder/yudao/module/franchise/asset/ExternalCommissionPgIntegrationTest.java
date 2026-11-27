package cn.iocoder.yudao.module.franchise.asset;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.*;
import cn.iocoder.yudao.module.franchise.commission.*;
import cn.iocoder.yudao.module.franchise.commission.ExternalCommissionModel.*;
import cn.iocoder.yudao.module.franchise.crm.*;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowAssetPolicy;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel.Attachment;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.sales.*;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.ApprovalService;
import cn.iocoder.yudao.module.platform.workflow.ApprovalServiceImpl;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PG, customer ACL, scanned uploads, one-use downloads, plan snapshots and append-only histories. */
@Import({ExternalCommissionService.class, ExternalCommissionDocumentReferences.class, CommissionSimulatedPayments.class,
        CommissionService.class, CommissionAccess.class, DesignPricingService.class, CommissionManifestService.class,
        CommissionOrgScope.class, CustomerWorkflowAssetPolicy.class, CustomerService.class, CrmOrgScope.class,
        CustomerSchemeAdapter.class, SalesAccess.class, SalesOrgScope.class, SaleContractQueryAdapter.class,
        CustomerPaymentPlanDraftService.class, FranchiseCommands.class, CommandLogImpl.class, ApprovalServiceImpl.class,
        SaleContractEvidencePgIntegrationTest.Config.class, ExternalCommissionPgIntegrationTest.Config.class})
@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/franchise,classpath:db/migration/franchise-asset")
class ExternalCommissionPgIntegrationTest extends FranchisePgIntegrationTest {
    @TestConfiguration static class Config {
        @Bean DesignFeeSettlementPort settlement() { return mock(DesignFeeSettlementPort.class); }
    }
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj << /Type /Page >> endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    @Resource ExternalCommissionService service;
    @Resource CommissionService legacyService;
    @Resource CustomerPaymentPlanDraftService plans;
    @Resource LocalAssetService assets;
    @Resource DesignFeeSettlementPort settlement;
    @Resource JdbcTemplate jdbc;
    @Resource FranchiseCommands commands;
    @Resource ApprovalService approvals;
    @Resource PlatformTransactionManager transactions;
    @Resource SaleContractEvidencePgIntegrationTest.MemoryStorage storage;
    private PostSnapshot sales, colleague, lead, foreignLead, boss, other;
    private long tenant;

    @BeforeEach void scope() {
        reset(settlement);
        when(settlement.read(anyLong(),anyInt())).thenReturn(settled(false));
        when(settlement.lockAndRead(anyLong(),anyInt(),any())).thenReturn(settled(false));
        var store=fixture.store(); tenant=store.orgId();
        sales=fixture.post(store.orgId(),"sales",store.salesTeamA());
        colleague=fixture.post(store.orgId(),"sales",store.salesTeamA());
        lead=fixture.post(store.orgId(),"sales-lead",store.salesTeamA());
        foreignLead=fixture.post(store.orgId(),"sales-lead",store.salesTeamB());
        boss=fixture.post(store.orgId(),"boss",null);
        var second=fixture.store(); other=fixture.post(second.orgId(),"sales",second.salesTeamA());
        for(long company:List.of(store.orgId(),second.orgId())) jdbc.update(
                "INSERT INTO system_dept(id,name,status,deleted,tenant_id) VALUES(?,'外部委托测试',0,0,?) ON CONFLICT(id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id,status=0,deleted=0",company,tenant);
        TenantContextHolder.setTenantId(tenant); currentPost.use(sales); storage.beforeRead=null;
    }
    @AfterEach void clear() { storage.beforeRead=null; TenantContextHolder.clear(); }

    @Test void readDoesNotCreateAndRegisterNeedsNoBudgetHeadquartersPolicyOrPayment() {
        long customer=customer(sales); var before=as(sales,()->service.get(sales,customer));
        assertThat(before.revision()).isZero(); assertThat(before.currentPaymentPlan()).isNull();
        assertThat(count("fr_commission","customer_id",customer)).isZero();
        long headquarters=jdbc.queryForObject("SELECT count(*) FROM pf_org_unit WHERE org_type='headquarters'",Long.class);
        var view=as(sales,()->service.register(sales,customer,request(before,"首次委托",List.of()),key()));
        assertThat(view.mode()).isEqualTo("EXTERNAL"); assertThat(view.status()).isEqualTo("EXTERNAL_PENDING");
        assertThat(view.revision()).isEqualTo(1); assertThat(view.currentRequest().version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT design_org_id FROM fr_commission WHERE id=?",Long.class,Long.valueOf(view.commissionId()))).isEqualTo(sales.orgId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_org_unit WHERE org_type='headquarters'",Long.class)).isEqualTo(headquarters);
        assertThat(count("fr_customer_scheme","customer_id",customer)).isZero();
        assertThat(count("fr_commission_submission","commission_id",Long.parseLong(view.commissionId()))).isZero();
        assertThat(count("fr_commission_simulated_order","commission_id",Long.parseLong(view.commissionId()))).isZero();
        assertThat(count("fr_sale_contract","customer_id",customer)).isZero();
        verify(settlement,never()).ensureInitialPayable(anyLong(),anyInt(),any());
        verify(settlement,never()).ensureTailPayable(anyLong(),anyInt(),any());
    }

    @Test void freezesServerPlanAndRejectsAStaleReferenceWithoutChangingEarlierVersions() {
        long customer=customer(sales); savePlan(customer,0,100000);
        var context=as(sales,()->service.get(sales,customer));
        var registered=as(sales,()->service.register(sales,customer,request(context,"初次付款约定",List.of()),key()));
        assertThat(registered.currentRequest().paymentPlan().source()).isEqualTo("DRAFT");
        assertThat(registered.currentRequest().paymentPlan().totalCents()).isEqualTo(100000);
        savePlan(customer,1,120000);
        var stale=request(registered,"更新委托说明",List.of());
        assertError(1099000001,()->as(sales,()->service.register(sales,customer,stale,key())));
        var latest=as(sales,()->service.get(sales,customer));
        assertThat(latest.currentPaymentPlan().revision()).isEqualTo(2);
        assertThat(latest.currentRequest().paymentPlan().revision()).isEqualTo(1);
        var updated=as(sales,()->service.register(sales,customer,request(latest,"客户更新付款沟通",List.of()),key()));
        assertThat(updated.requests()).hasSize(2);
        assertThat(updated.requests().get(1).paymentPlan().totalCents()).isEqualTo(100000);
        assertThat(updated.currentRequest().paymentPlan().totalCents()).isEqualTo(120000);
        assertThat(count("fr_sale_contract","customer_id",customer)).isZero();
        assertThatThrownBy(()->jdbc.update("UPDATE fr_commission_external_version SET data='{}'::jsonb WHERE commission_id=?",Long.parseLong(updated.commissionId())))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void signedPlanTakesPriorityAndFreezesItsRealContractAndVersion() {
        long customer=customer(sales); savePlan(customer,0,100000); long contract=IdWorker.getId(),stage=IdWorker.getId();
        jdbc.update("INSERT INTO fr_sale_contract(id,company_id,customer_id,author_user_id,total_cents,first_cents,status,signed_date,revision,payment_plan_version,payment_plan_mode) VALUES(?,?,?,?,130000,0,'SIGNED',CURRENT_DATE,4,2,'CUSTOM')",contract,sales.orgId(),customer,sales.userId());
        jdbc.update("INSERT INTO fr_sale_payment_stage(id,company_id,customer_id,contract_id,stage,label,sequence,amount_cents,status,trigger_type,plan_version) VALUES(?,?,?,?,'STAGE_1','正式合同约定',1,130000,'PENDING','MANUAL',2)",stage,sales.orgId(),customer,contract);
        var context=as(sales,()->service.get(sales,customer));
        assertThat(context.currentPaymentPlanRef()).isEqualTo(new PlanRef("CONTRACT",4,Long.toString(contract),2));
        var view=as(sales,()->service.register(sales,customer,request(context,"按已签合同继续委托",List.of()),key()));
        assertThat(view.currentRequest().paymentPlan().contractId()).isEqualTo(contract);
        assertThat(view.currentRequest().paymentPlan().stages()).hasSize(1);
        assertThat(count("fr_sale_payment_stage","contract_id",contract)).isEqualTo(1);
    }

    @Test void actualDeliveredFilesProduceVersionedResultsAndNewRequestRequiresNewResults() throws Exception {
        long customer=customer(sales); var created=register(customer); long one=upload(sales,"BUSINESS_DOCUMENT"),two=upload(sales,"BUSINESS_DOCUMENT"),three=upload(sales,"BUSINESS_DOCUMENT");
        var input=result(created,one,two,three,"初次收到外部成果");
        var ready=as(sales,()->service.results(sales,customer,input,key()));
        assertThat(ready.status()).isEqualTo("EXTERNAL_READY"); assertThat(ready.currentResult().attachments()).hasSize(3);
        var corrected=as(sales,()->service.results(sales,customer,result(ready,one,two,three,"重新核对图纸版本"),key()));
        assertThat(corrected.currentResult().version()).isEqualTo(2); assertThat(corrected.results()).hasSize(2);
        var revised=as(sales,()->service.register(sales,customer,request(corrected,"客户增加需求，重新委托",List.of()),key()));
        assertThat(revised.status()).isEqualTo("EXTERNAL_PENDING"); assertThat(revised.currentResult()).isNull();
        assertThat(revised.results()).hasSize(2);
        assertError(1099000001,()->as(sales,()->service.results(sales,customer,
                new ResultCommand(revised.revision(),1,today(),"V1",Long.toString(one),Long.toString(two),Long.toString(three),"旧需求成果"),key())));
        assertThat(count("fr_customer_workflow","customer_id",customer)).isZero();
        assertThatThrownBy(()->jdbc.update("DELETE FROM fr_commission_external_result WHERE commission_id=?",Long.parseLong(created.commissionId())))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void invalidFutureWrongTypeUnacceptedForeignAndSharedCustomerAssetsAreRejected() throws Exception {
        long customer=customer(sales); var created=register(customer); long valid=upload(sales,"BUSINESS_DOCUMENT"), wrong=upload(sales,"CONTRACT_ATTACHMENT");
        var pending=as(sales,()->assets.createUploadTicket(actor(sales),"BUSINESS_DOCUMENT","application/pdf",PDF.length,sha(PDF)));
        long foreign=upload(other,"BUSINESS_DOCUMENT");
        for(long invalid:List.of(wrong,pending.assetId(),foreign)) assertThatThrownBy(()->as(sales,()->service.results(sales,customer,result(created,valid,valid,invalid,"不完整资料"),key())))
                .isInstanceOf(ServiceException.class);
        var future=new ResultCommand(created.revision(),1,today().plusDays(1),"V1",Long.toString(valid),Long.toString(valid),Long.toString(valid),"未来事实");
        assertError(400,()->as(sales,()->service.results(sales,customer,future,key())));
        var saved=as(sales,()->service.register(sales,customer,request(created,"补充测量资料",List.of(file(valid))),key()));
        long second=customer(sales); var empty=as(sales,()->service.get(sales,second));
        assertError(400,()->as(sales,()->service.register(sales,second,request(empty,"不得把其他客户资料重复绑定",List.of(file(valid))),key())));
        assertThat(saved.currentRequest().data().attachments()).containsExactly(file(valid));
        assertThat(count("fr_commission_external_result","commission_id",Long.parseLong(created.commissionId()))).isZero();
    }

    @Test void currentAndHistoricalDownloadsFollowCustomerTransferAndRecheckAfterIo() throws Exception {
        long customer=customer(sales), file=upload(sales,"BUSINESS_DOCUMENT"); var empty=as(sales,()->service.get(sales,customer));
        var created=as(sales,()->service.register(sales,customer,request(empty,"带资料委托",List.of(file(file))),key()));
        as(sales,()->service.register(sales,customer,request(created,"第二版未引用原附件",List.of()),key()));
        var bossTicket=as(boss,()->service.download(boss,customer,"requests",1,file));
        assertThat(as(boss,()->assets.readDownload(actor(boss),file,token(bossTicket.downloadUrl()))).bytes()).isEqualTo(PDF);
        var pending=as(sales,()->service.download(sales,customer,"requests",1,file));
        jdbc.update("UPDATE fr_customer SET owner_user_id=? WHERE id=?",colleague.userId(),customer);
        assertError(1099000002,()->as(sales,()->assets.readDownload(actor(sales),file,token(pending.downloadUrl()))));
        var newOwner=as(colleague,()->service.download(colleague,customer,"requests",1,file));
        storage.beforeRead=()->jdbc.update("UPDATE fr_customer SET owner_user_id=? WHERE id=?",sales.userId(),customer);
        assertError(1099000002,()->as(colleague,()->assets.readDownload(actor(colleague),file,token(newOwner.downloadUrl()))));
        assertThatThrownBy(()->as(colleague,()->service.get(colleague,customer))).isInstanceOf(ServiceException.class);
        var current=as(sales,()->service.download(sales,customer,"requests",1,file));
        storage.beforeRead=()->jdbc.update("UPDATE system_dept SET status=1 WHERE id=?",sales.orgId());
        assertError(1099000002,()->as(sales,()->assets.readDownload(actor(sales),file,token(current.downloadUrl()))));
    }

    @Test void tenantCompanyOwnerTeamAndBossWriteLimitsApplyBeforeAnyMutation() {
        long customer=customer(sales); var view=register(customer);
        assertThat(as(lead,()->service.get(lead,customer)).canWrite()).isTrue();
        assertThat(as(boss,()->service.get(boss,customer)).canWrite()).isFalse();
        for(PostSnapshot denied:List.of(colleague,foreignLead,other,boss))
            assertThatThrownBy(()->as(denied,()->service.register(denied,customer,request(view,"无权变更",List.of()),key()))).isInstanceOf(ServiceException.class);
        for(PostSnapshot denied:List.of(colleague,foreignLead,other))
            assertThatThrownBy(()->as(denied,()->service.get(denied,customer))).isInstanceOf(ServiceException.class);
        TenantContextHolder.setTenantId(tenant+1);
        assertError(404,()->as(sales,()->service.get(sales,customer)));
        assertError(404,()->as(sales,()->service.register(sales,customer,request(view,"错误租户",List.of()),key())));
        TenantContextHolder.setTenantId(tenant);
        jdbc.update("UPDATE system_dept SET status=1 WHERE id=?",sales.orgId());
        assertError(404,()->as(sales,()->service.get(sales,customer)));
        assertThat(count("fr_commission_external_version","commission_id",Long.parseLong(view.commissionId()))).isEqualTo(1);
    }

    @Test void sameKeyConcurrentRegistrationCommitsOneVersionAndChangedKeyContentCannotReplay() throws Exception {
        long customer=customer(sales); var empty=as(sales,()->service.get(sales,customer)); var input=request(empty,"并发登记",List.of());
        String key=key(); currentPost.use(sales); var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            Callable<View> call=()->{TenantContextHolder.setTenantId(tenant);try{start.await();return service.register(sales,customer,input,key);}finally{TenantContextHolder.clear();}};
            var a=pool.submit(call); var b=pool.submit(call); start.countDown(); var first=a.get(10,TimeUnit.SECONDS); var second=b.get(10,TimeUnit.SECONDS);
            assertThat(first.commissionId()).isEqualTo(second.commissionId()); assertThat(second.revision()).isEqualTo(1);
            assertThat(count("fr_commission_external_version","commission_id",Long.parseLong(first.commissionId()))).isEqualTo(1);
            assertThat(as(sales,()->service.register(sales,customer,input," "+key+" ")).revision()).isEqualTo(1);
            assertError(1099000000,()->as(sales,()->service.register(sales,customer,request(empty,"键内容变更",List.of()),key)));
            assertError(1099000001,()->as(sales,()->service.register(sales,customer,input,key())));
            long another=customer(sales);
            assertError(1099000000,()->as(sales,()->service.register(sales,another,input,key)));
        } finally {start.countDown();pool.shutdownNow();currentPost.clear();}
    }

    @Test void explicitLegacyConversionKeepsVersionsFinanceSourceAndSupersedesOnlyPendingApproval() {
        for(String status:List.of("FEE_PENDING","FEE_RETURNED","DESIGNING","DESIGN_COMPLETED","DISPATCHED","HANDOVER_RETURNED")) {
            long customer=customer(sales), commission=legacy(customer,status); Long approval="FEE_PENDING".equals(status)?pendingApproval(commission):null;
            var before=as(sales,()->service.get(sales,customer)); assertThat(before.canConvert()).isTrue();
            var input=request(before,"改用外部设计服务，保留原设计费用记录",List.of()); String key=key();
            var converted=as(sales,()->service.convertLegacy(sales,customer,input,key));
            assertThat(converted.commissionId()).isEqualTo(Long.toString(commission)); assertThat(converted.currentRequest().previousStatus()).isEqualTo(status);
            assertThat(converted.currentRequest().action()).isEqualTo("CONVERT");
            assertThat(as(sales,()->service.convertLegacy(sales,customer,input,key)).revision()).isEqualTo(converted.revision());
            assertThat(count("fr_commission_submission","commission_id",commission)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT total_cents FROM fr_commission_submission WHERE commission_id=?",Long.class,commission)).isEqualTo(10000);
            if(approval!=null) {
                assertThat(jdbc.queryForObject("SELECT status FROM pf_approval WHERE id=?",String.class,approval)).isEqualTo("SUPERSEDED");
                assertThat(converted.currentRequest().supersededApprovalIds()).containsExactly(approval.toString());
            }
        }
        for(String status:List.of("HANDOVER_SUBMITTED","ACCEPTED")) {
            long customer=customer(sales); legacy(customer,status); var view=as(sales,()->service.get(sales,customer));
            assertError(409,()->as(sales,()->service.convertLegacy(sales,customer,request(view,"不得改动接收事实",List.of()),key())));
        }
        verify(settlement,never()).ensureInitialPayable(anyLong(),anyInt(),any());
    }

    @Test void historicalSimulationStillNeedsTheReviewedRealSettlementAndCannotBecomeCashByConversion() {
        long customer=customer(sales), commission=legacy(customer,"DISPATCHED");
        jdbc.update("INSERT INTO fr_commission_simulated_order(commission_id,submission_version,company_id,design_org_id,order_number,amount_cents,status,channel,paid_by_user_id,paid_at) VALUES(?,1,?,?,?,10000,'PAID','WECHAT',?,now())",commission,sales.orgId(),sales.orgId(),"SIM-OLD-"+commission,sales.userId());
        var before=as(sales,()->service.get(sales,customer)); assertThat(before.canConvert()).isFalse();
        assertError(1111000023,()->as(sales,()->service.convertLegacy(sales,customer,request(before,"旧模拟记录不能作实收",List.of()),key())));
        new TransactionTemplate(transactions).executeWithoutResult(tx->as(sales,()->{
            commands.record(sales,"commission-historical-settlement",commission,1,
                    "commission-historical-settlement-prepare",Map.of("review","真实核收已核对"),key()); return true;
        }));
        assertError(1111000023,()->as(sales,()->service.convertLegacy(sales,customer,request(before,"仅审核未实收也不行",List.of()),key())));
        when(settlement.read(commission,1)).thenReturn(settled(true)); when(settlement.lockAndRead(eq(commission),eq(1),any())).thenReturn(settled(true));
        var converted=as(sales,()->service.convertLegacy(sales,customer,request(before,"正式结算完成后转换",List.of()),key()));
        assertThat(converted.status()).isEqualTo("EXTERNAL_PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM fr_commission_simulated_order WHERE commission_id=?",String.class,commission)).isEqualTo("PAID");
        verify(settlement,never()).ensureInitialPayable(anyLong(),anyInt(),any());
    }

    @Test void conversionRollsBackApprovalVersionAndAuditTogether() {
        long customer=customer(sales),commission=legacy(customer,"FEE_PENDING"),approval=pendingApproval(commission);
        var before=as(sales,()->service.get(sales,customer));
        assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
            as(sales,()->service.convertLegacy(sales,customer,request(before,"事务回滚验证",List.of()),key()));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM pf_approval WHERE id=?",String.class,approval)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT workflow_mode FROM fr_commission WHERE id=?",String.class,commission)).isEqualTo("LEGACY");
        assertThat(count("fr_commission_external_version","commission_id",commission)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE aggregate_type='fr-external-commission' AND aggregate_id=?",Long.class,commission)).isZero();
    }

    @Test void retiredFeeApprovalCannotRaceConversionIntoANewPayable() throws Exception {
        long customer=customer(sales),commission=legacy(customer,"FEE_PENDING"),approval=pendingApproval(commission);
        var before=as(sales,()->service.get(sales,customer));var input=request(before,"原记录转外部流程",List.of());
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);currentPost.use(sales);
        try {
            var old=pool.submit(()->{TenantContextHolder.setTenantId(tenant);try{start.await();return legacyService.feeDecide(boss,commission,new CommissionService.FeeDecisionCommand(true,"迟到旧审批"),key());}finally{TenantContextHolder.clear();}});
            var conversion=pool.submit(()->{TenantContextHolder.setTenantId(tenant);try{start.await();return service.convertLegacy(sales,customer,input,key());}finally{TenantContextHolder.clear();}});
            start.countDown();
            assertThat(conversion.get(10,TimeUnit.SECONDS).commissionId()).isEqualTo(Long.toString(commission));
            assertThatThrownBy(()->old.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .satisfies(e->assertThat(((ServiceException)e.getCause()).getCode()).isEqualTo(1111000024));
            assertThat(jdbc.queryForObject("SELECT status FROM pf_approval WHERE id=?",String.class,approval)).isEqualTo("SUPERSEDED");
            assertThat(count("fr_commission_external_version","commission_id",commission)).isEqualTo(1);
            verify(settlement,never()).ensureInitialPayable(anyLong(),anyInt(),any());
        } finally {start.countDown();pool.shutdownNow();currentPost.clear();}
    }

    @Test void retiredSubmissionAndExternalRegistrationOnlyCreateOneExternalAggregate() throws Exception {
        long customer=customer(sales);var before=as(sales,()->service.get(sales,customer));
        var input=request(before,"实际外部委托",List.of());
        var legacyInput=new CommissionService.SubmitCommand("旧客户端完整请求","方案图纸",null,new BigDecimal("100"),new BigDecimal("50"),"旧收款方",1,null,null,true);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);currentPost.use(sales);
        try {
            var old=pool.submit(()->{TenantContextHolder.setTenantId(tenant);try{start.await();return legacyService.submit(sales,customer,legacyInput,key());}finally{TenantContextHolder.clear();}});
            var external=pool.submit(()->{TenantContextHolder.setTenantId(tenant);try{start.await();return service.register(sales,customer,input,key());}finally{TenantContextHolder.clear();}});
            start.countDown();var registered=external.get(10,TimeUnit.SECONDS);
            assertThatThrownBy(()->old.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .satisfies(e->assertThat(((ServiceException)e.getCause()).getCode()).isEqualTo(1111000024));
            long id=Long.parseLong(registered.commissionId());
            assertThat(registered.mode()).isEqualTo("EXTERNAL");
            assertThat(count("fr_commission","customer_id",customer)).isEqualTo(1);
            assertThat(count("fr_commission_submission","commission_id",id)).isZero();
            assertThat(count("fr_commission_external_version","commission_id",id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_approval WHERE subject_type='commission-fee' AND subject_id=?",Long.class,id)).isZero();
            verify(settlement,never()).ensureInitialPayable(anyLong(),anyInt(),any());
        } finally {start.countDown();pool.shutdownNow();currentPost.clear();}
    }

    @Test void alreadyApprovedHistoricalFeeAndItsSourceRemainWhenConverted() {
        long customer=customer(sales),commission=legacy(customer,"DESIGNING"),approval=pendingApproval(commission);
        jdbc.update("UPDATE pf_approval SET status='APPROVED',decided_at=now(),approver_user_id=?,approver_post_id=? WHERE id=?",boss.userId(),boss.postId(),approval);
        var before=as(sales,()->service.get(sales,customer));
        var converted=as(sales,()->service.convertLegacy(sales,customer,request(before,"保留真实历史费用",List.of()),key()));
        assertThat(converted.commissionId()).isEqualTo(Long.toString(commission));
        assertThat(converted.currentRequest().supersededApprovalIds()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM pf_approval WHERE id=?",String.class,approval)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT total_cents FROM fr_commission_submission WHERE commission_id=?",Long.class,commission)).isEqualTo(10000);
        verify(settlement,never()).ensureInitialPayable(anyLong(),anyInt(),any());
    }

    private long customer(PostSnapshot post) { long id=IdWorker.getId();jdbc.update("INSERT INTO fr_customer(id,company_id,owner_user_id,team_id,name,phone,source,status) VALUES(?,?,?,?,'外部委托客户',?,'到店','REGISTERED')",id,post.orgId(),post.userId(),post.teamId(),Long.toString(10000000000L+Math.floorMod(id,10000000000L)));return id; }
    private View register(long customer) { var empty=as(sales,()->service.get(sales,customer));return as(sales,()->service.register(sales,customer,request(empty,"外部委托需求",List.of()),key())); }
    private RequestCommand request(View view,String reason,List<Attachment> files) { return new RequestCommand(view.revision(),today(),"外部服务方","EXT-2026","客户实际需求","方案与施工资料",new BigDecimal("260.50"),2,files,view.currentPaymentPlanRef(),reason); }
    private ResultCommand result(View view,long result,long drawing,long review,String note) { return new ResultCommand(view.revision(),view.currentRequest().version(),today(),"图纸V1",Long.toString(result),Long.toString(drawing),Long.toString(review),note); }
    private static Attachment file(long id) { return new Attachment(Long.toString(id),"委托资料"); }
    private void savePlan(long customer,long revision,long total) { as(sales,()->plans.save(sales,customer,new CustomerPaymentPlanDraftService.Save(revision,total,List.of(new PaymentPlanRules.Input(null,"客户约定",total,null,null,"MANUAL",null)),"沟通后记录"),key())); }
    private long legacy(long customer,String status) { long id=IdWorker.getId();jdbc.update("INSERT INTO fr_commission(id,company_id,design_org_id,customer_id,customer_name,status,submission_version,revision) VALUES(?,?,?,?,'旧委托',?,1,1)",id,sales.orgId(),sales.orgId(),customer,status);jdbc.update("INSERT INTO fr_commission_submission(id,company_id,design_org_id,commission_id,version,text,scope,total_cents,first_cents,recipient,auth_kind,submitted_by_user_id,submitted_by_post_id) VALUES(?,?,?,?,1,'旧需求','旧范围',10000,5000,'旧收款方','STANDARD',?,?)",IdWorker.getId(),sales.orgId(),sales.orgId(),id,sales.userId(),sales.postId());return id; }
    private long pendingApproval(long commission) { long id=IdWorker.getId();jdbc.update("INSERT INTO pf_approval(id,domain,org_id,subject_type,subject_id,subject_version,signature,amount_cents,status,approver_role_code,submitter_user_id,submitter_post_id,create_time) VALUES(?,'franchise',?,'commission-fee',?,1,?,10000,'PENDING','boss',?,?,now())",id,sales.orgId(),commission,"legacy-"+commission,sales.userId(),sales.postId());return id; }
    private long upload(PostSnapshot post,String type) throws Exception { currentPost.use(post);var ticket=assets.createUploadTicket(actor(post),type,"application/pdf",PDF.length,sha(PDF));assets.receiveUpload(actor(post),ticket.assetId(),token(ticket.uploadUrl()),new ByteArrayInputStream(PDF));assertThat(assets.completeUpload(actor(post),ticket.assetId()).uploadStatus()).isEqualTo("ACCEPTED");return ticket.assetId(); }
    private AssetAccessPort.Actor actor(PostSnapshot post) { return new AssetAccessPort.Actor(post.orgId(),post.orgId()+1000000L,post.userId(),post.postId()); }
    private static String sha(byte[] data) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}catch(Exception e){throw new IllegalStateException(e);} }
    private static String token(String url) { return url.substring(url.indexOf("token=")+6); }
    private long count(String table,String column,long id) { return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE "+column+"=?",Long.class,id); }
    private static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }
    private static String key() { return UUID.randomUUID().toString(); }
    private <T>T as(PostSnapshot post,Supplier<T> work) { currentPost.use(post);try{return work.get();}finally{currentPost.clear();} }
    private static void assertError(int code,Runnable work) { assertThatThrownBy(work::run).isInstanceOf(ServiceException.class).satisfies(error->assertThat(((ServiceException)error).getCode()).isEqualTo(code)); }
    private static DesignFeeSettlementPort.Settlement settled(boolean ready) { return new DesignFeeSettlementPort.Settlement(DesignFeeSettlementPort.Mode.AREA_FULL,1,List.of(new DesignFeeSettlementPort.StageState(DesignFeeSettlementPort.Stage.FULL,10000,ready?10000:0,0,ready?10000:0,ready?10000:0,0,ready?10000:0,ready,ready,ready))); }
}
