package cn.iocoder.yudao.module.franchise.asset;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.*;
import cn.iocoder.yudao.module.franchise.commission.*;
import cn.iocoder.yudao.module.franchise.commission.ExternalCommissionModel.RequestCommand;
import cn.iocoder.yudao.module.franchise.commission.ExternalCommissionModel.ResultCommand;
import cn.iocoder.yudao.module.franchise.commission.ExternalHandoverModel.*;
import cn.iocoder.yudao.module.franchise.crm.*;
import cn.iocoder.yudao.module.franchise.crm.workflow.*;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel.Attachment;
import cn.iocoder.yudao.module.franchise.engineering.*;
import cn.iocoder.yudao.module.franchise.engineering.content.*;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.sales.*;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleContractDO;
import cn.iocoder.yudao.module.franchise.test.FakeSystemDirectory;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.ApprovalServiceImpl;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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

/** Actual PG workflow, contract, engineering and scanned asset services; storage and external identity mapping only are local doubles. */
@Import({ExternalCommissionService.class, ExternalCommissionDocumentReferences.class,
        ExternalHandoverService.class, ExternalHandoverAccess.class, CommissionDeliverableAccessAdapter.class,
        CommissionSimulatedPayments.class, CommissionService.class, CommissionAccess.class,
        DesignPricingService.class, CommissionManifestService.class, CommissionQueryAdapter.class,
        CommissionOrgScope.class, HandoverService.class, CustomerWorkflowAssetPolicy.class,
        CustomerService.class, CrmOrgScope.class, CustomerSchemeAdapter.class,
        CustomerWorkflowService.class, CustomerWorkflowReadService.class,
        SalesAccess.class, SalesOrgScope.class, SaleContractService.class, SaleRequirementService.class,
        SaleContractQueryAdapter.class, SaleWorkflowFactsAdapter.class, ContractDocumentAccessPolicy.class,
        UnifiedStageAccountAdapter.class, CustomerPaymentPlanDraftService.class,
        EngProjectService.class, EngPlanService.class, EngineeringPortImpl.class,
        EngineeringProjectContextAdapter.class, EngineeringOrgScope.class, EngWorkflowFactsAdapter.class,
        FranchiseCommands.class, CommandLogImpl.class, ApprovalServiceImpl.class,
        SaleContractEvidencePgIntegrationTest.Config.class, ExternalHandoverPgIntegrationTest.Config.class})
@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/franchise,classpath:db/migration/franchise-asset")
class ExternalHandoverPgIntegrationTest extends FranchisePgIntegrationTest {
    @TestConfiguration static class Config {
        @Bean DesignFeeSettlementPort settlement() { return mock(DesignFeeSettlementPort.class); }
        @Bean @Primary StoreServiceAccountPort scopedAccounts(JdbcTemplate jdbc) {
            return new StoreServiceAccountPort() {
                public long requireServiceAccount(long org) { return org + 1_000_000L; }
                public long requireExistingServiceAccountForTask(long tenant, long org) {
                    Boolean active = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM system_dept WHERE id=? AND tenant_id=? AND status=0 AND deleted=0)",Boolean.class,org,tenant);
                    if (!Boolean.TRUE.equals(active)) throw new ServiceAccountInactiveException(org);
                    return requireServiceAccount(org);
                }
            };
        }
    }
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj << /Type /Page >> endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    @Resource ExternalCommissionService external;
    @Resource ExternalHandoverService handover;
    @Resource HandoverService legacyHandover;
    @Resource CommissionService legacyCommission;
    @Resource CustomerPaymentPlanDraftService drafts;
    @Resource CustomerWorkflowService workflow;
    @Resource CustomerWorkflowReadService reads;
    @Resource SaleContractService contracts;
    @Resource EngProjectService projects;
    @Resource EngPlanService plans;
    @Resource LocalAssetService assets;
    @Resource FranchiseCommands commands;
    @Resource DesignFeeSettlementPort settlement;
    @Resource CommissionQueryPort commissionQuery;
    @Resource JdbcTemplate jdbc;
    @Resource PlatformTransactionManager transactions;
    @Resource SaleContractEvidencePgIntegrationTest.MemoryStorage storage;
    private PostSnapshot sales, colleague, lead, boss, engineer, secondEngineer, finance, other;
    private long tenant;

    @BeforeEach void organization() {
        reset(settlement);
        var store=fixture.store(); tenant=store.orgId();
        sales=fixture.post(store.orgId(),"sales",store.salesTeamA());
        colleague=fixture.post(store.orgId(),"sales",store.salesTeamA());
        lead=fixture.post(store.orgId(),"sales-lead",store.salesTeamA());
        boss=fixture.post(store.orgId(),"boss",null);
        engineer=fixture.post(store.orgId(),"engineer",store.engineeringTeam());
        secondEngineer=fixture.post(store.orgId(),"engineer",store.engineeringTeam());
        finance=fixture.post(store.orgId(),"finance",null);
        var second=fixture.store(); other=fixture.post(second.orgId(),"engineer",second.engineeringTeam());
        for(long company:List.of(store.orgId(),second.orgId())) jdbc.update(
                "INSERT INTO system_dept(id,name,status,deleted,tenant_id) VALUES(?,'外部移交测试',0,0,?) ON CONFLICT(id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id,status=0,deleted=0",company,tenant);
        TenantContextHolder.setTenantId(tenant); storage.beforeRead=null; currentPost.use(sales);
    }
    @AfterEach void clear() { storage.beforeRead=null; TenantContextHolder.clear(); }

    @Test void allEightStepsUseActualExternalEvidenceSignedContractNetCollectionAndEngineeringStart() throws Exception {
        long customer=customer(sales), document=upload(sales,"BUSINESS_DOCUMENT");
        completeManualSix(customer,document);
        assertProgress(customer,6);
        saveDraft(customer,0,100000);
        var ready=ready(customer);
        assertThat(ready.currentRequest().paymentPlan().source()).isEqualTo("DRAFT");
        assertThat(count("fr_sale_contract","customer_id",customer)).isZero();
        var contract=signed(customer);
        assertProgress(customer,7);
        long confirmation=upload(sales,"BUSINESS_DOCUMENT");
        var pending=submit(ready,confirmation,engineer);
        assertThat(pending.projectId()).isNull(); assertThat(pending.currentPacket().paymentPlan().source()).isEqualTo("DRAFT");
        assertProgress(customer,7);
        var accepted=receive(pending,engineer,key()); long project=Long.parseLong(accepted.projectId());
        assertThat(accepted.status()).isEqualTo("ACCEPTED"); assertProgress(customer,7);
        assertThat(jdbc.queryForObject("SELECT actual_start_at FROM fr_eng_project WHERE id=?",LocalDate.class,project)).isNull();
        assertThat(jdbc.queryForObject("SELECT sale_contract_id FROM fr_eng_project WHERE id=?",Long.class,project)).isEqualTo(contract.getId());
        var detail=as(engineer,()->projects.detail(engineer,project));
        assertThat(detail).containsEntry("handoverMode","EXTERNAL").containsEntry("externalHandoverVersion",1).containsEntry("canReadHandover",true);
        assertThat(((CustomerPaymentPlanDraftPort.Snapshot)detail.get("handoverPaymentPlan")).source()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForList("SELECT file_ref FROM fr_eng_document WHERE project_id=?",String.class,project))
                .containsExactlyInAnyOrderElementsOf(accepted.currentPacket().files().stream().map(File::assetId).toList());
        prepare(project,engineer);
        assertThat(jdbc.queryForObject("SELECT payment_plan_snapshot->>'contractId' FROM fr_eng_project WHERE id=?",String.class,project)).isEqualTo(contract.getId().toString());
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(payment_plan_snapshot->'stages') FROM fr_eng_project WHERE id=?",Integer.class,project)).isEqualTo(2);
        assertThat(count("fr_sale_payment_stage","contract_id",contract.getId())).isEqualTo(2);
        long proof=upload(engineer,"ENG_DRAWING"); var start=new EngPlanService.NodeRecordCommand(today(),"真实开工交底",proof,true);
        assertThatThrownBy(()->as(engineer,()->plans.start(engineer,project,start,key()))).isInstanceOf(ServiceException.class);
        assertProgress(customer,7);
        collectBeforeStart(contract.getId()); String startKey=key();
        as(engineer,()->plans.start(engineer,project,start,startKey));
        as(engineer,()->plans.start(engineer,project,start," "+startKey+" "));
        var completed=reads.readAuthorized(tenant,sales.orgId(),customer);
        assertThat(completed.completedCount()).isEqualTo(8); assertThat(completed.progressPercent()).isEqualByComparingTo("100");
        assertThat(completed.steps().get(6).canComplete()).isFalse(); assertThat(completed.steps().get(7).canComplete()).isFalse();
        assertThat(completed.steps().get(7).fields()).containsEntry("projectId",Long.toString(project)).containsEntry("actualStartAt",today().toString());
        assertThat(count("fr_eng_site_record","project_id",project)).isEqualTo(1);
        assertThat(audit(project,"eng-start")).isEqualTo(1);
        long commission=Long.parseLong(ready.commissionId());
        assertThat(count("fr_commission_submission","commission_id",commission)).isZero();
        assertThat(count("fr_commission_dispatch_manifest","commission_id",commission)).isZero();
        assertThat(count("fr_commission_simulated_order","commission_id",commission)).isZero();
        assertThat(count("pf_approval","subject_id",commission)).isZero();
        assertThat(as(sales,()->commissionQuery.customerDesignStatuses(sales.orgId(),List.of(customer))))
                .containsEntry(customer,"ACCEPTED");
        verify(settlement,never()).ensureInitialPayable(anyLong(),anyInt(),any());
        verify(settlement,never()).ensureTailPayable(anyLong(),anyInt(),any());
    }

    @Test void getDoesNotCreateAndSubmitRequiresCurrentResultRealConfirmationAndCurrentReceiver() throws Exception {
        var ready=ready(customer(sales)); long commission=Long.parseLong(ready.commissionId());
        var view=as(sales,()->handover.get(sales,commission));
        assertThat(view.currentPacket()).isNull(); assertThat(view.canSubmit()).isTrue();
        assertThat(count("fr_commission_handover","commission_id",commission)).isZero();
        long file=upload(sales,"BUSINESS_DOCUMENT"); var valid=submitCommand(view,file,engineer);
        assertError(400,()->as(sales,()->handover.submit(sales,commission,new Submit(view.revision(),view.resultVersion(),Long.toString(file),today().plusDays(1),"说明",Long.toString(engineer.userId()),true),key())));
        assertError(400,()->as(sales,()->handover.submit(sales,commission,new Submit(view.revision(),view.resultVersion(),Long.toString(file),today(),"说明",Long.toString(engineer.userId()),false),key())));
        assertError(1099000001,()->as(sales,()->handover.submit(sales,commission,new Submit(view.revision()-1,view.resultVersion(),Long.toString(file),today(),"说明",Long.toString(engineer.userId()),true),key())));
        assertError(1099000001,()->as(sales,()->handover.submit(sales,commission,new Submit(view.revision(),view.resultVersion()+1,Long.toString(file),today(),"说明",Long.toString(engineer.userId()),true),key())));
        for(var target:List.of(sales,other)) assertError(400,()->as(sales,()->handover.submit(sales,commission,submitCommand(view,file,target),key())));
        FakeSystemDirectory.INSTANCE.setUserActive(engineer.userId(),false);
        try { assertError(400,()->as(sales,()->handover.submit(sales,commission,valid,key()))); }
        finally { FakeSystemDirectory.INSTANCE.setUserActive(engineer.userId(),true); }
        long contractFile=upload(sales,"CONTRACT_ATTACHMENT");
        assertThatThrownBy(()->as(sales,()->handover.submit(sales,commission,submitCommand(view,contractFile,engineer),key()))).isInstanceOf(ServiceException.class);
        var unuploaded=as(sales,()->assets.createUploadTicket(actor(sales),"BUSINESS_DOCUMENT","application/pdf",PDF.length,sha(PDF)));
        assertThatThrownBy(()->as(sales,()->handover.submit(sales,commission,submitCommand(view,unuploaded.assetId(),engineer),key()))).isInstanceOf(ServiceException.class);
        assertThat(count("fr_commission_handover","commission_id",commission)).isZero();
        assertThat(audit(commission,"external-packet-submit")).isZero();
    }

    @Test void returnCreatesAnotherFrozenVersionAndHistoricalReceiverLosesVisibility() throws Exception {
        long customer=customer(sales); saveDraft(customer,0,100000); var ready=ready(customer);
        var first=submit(ready,upload(sales,"BUSINESS_DOCUMENT"),engineer); long commission=Long.parseLong(first.commissionId());
        long firstAsset=Long.parseLong(first.currentPacket().files().get(0).assetId());
        var oldTicket=as(engineer,()->handover.download(engineer,commission,1,"RESULT"));
        as(engineer,()->handover.returnPacket(engineer,commission,new Decision(first.revision(),1,"施工图缺少说明",false),key()));
        saveDraft(customer,1,125000);
        var latest=as(sales,()->external.get(sales,customer));
        var revised=as(sales,()->external.register(sales,customer,request(latest,"客户更新委托"),key()));
        var newResult=deliver(customer,revised); var second=submit(newResult,upload(sales,"BUSINESS_DOCUMENT"),secondEngineer);
        assertThat(second.currentPacket().version()).isEqualTo(2); assertThat(second.currentPacket().resultVersion()).isEqualTo(2);
        assertThat(second.currentPacket().paymentPlan().totalCents()).isEqualTo(125000);
        assertThat(second.packets().get(1).paymentPlan().totalCents()).isEqualTo(100000);
        assertThat(second.packets().get(1).status()).isEqualTo("RETURNED");
        assertError(1099000002,()->as(engineer,()->handover.get(engineer,commission)));
        assertError(1099000002,()->as(engineer,()->assets.readDownload(actor(engineer),firstAsset,token(oldTicket.downloadUrl()))));
        assertThat(as(engineer,()->legacyCommission.listVisible(engineer))).noneMatch(c->c.getId()==commission);
        assertThatThrownBy(()->as(engineer,()->legacyCommission.get(engineer,commission))).isInstanceOf(ServiceException.class);
        assertThat(as(secondEngineer,()->handover.get(secondEngineer,commission)).packets()).hasSize(1);
        assertError(1099000002,()->as(secondEngineer,()->handover.download(secondEngineer,commission,1,"RESULT")));
        assertThatThrownBy(()->jdbc.update("UPDATE fr_commission_handover SET note='改写旧交底' WHERE commission_id=? AND version=1",commission)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var history=as(boss,()->handover.get(boss,commission)); assertThat(history.packets()).hasSize(2); assertThat(history.canSubmit()).isFalse();
        assertThat(as(sales,()->assets.readDownload(actor(sales),firstAsset,token(handover.download(sales,commission,1,"RESULT").downloadUrl()))).bytes()).isEqualTo(PDF);
    }

    @Test void scopedEngineerDownloadsOnlyTheFourFrozenSlotsAndTicketsAreOneUse() throws Exception {
        var pending=submit(ready(customer(sales)),upload(sales,"BUSINESS_DOCUMENT"),engineer);
        long commission=Long.parseLong(pending.commissionId());
        for(var file:pending.currentPacket().files()) {
            long id=Long.parseLong(file.assetId()); var ticket=as(engineer,()->handover.download(engineer,commission,1,file.slot()));
            assertThat(as(engineer,()->assets.readDownload(actor(engineer),id,token(ticket.downloadUrl()))).bytes()).isEqualTo(PDF);
            assertError(1099000002,()->as(engineer,()->assets.readDownload(actor(engineer),id,token(ticket.downloadUrl()))));
            assertThatThrownBy(()->as(engineer,()->assets.createDownloadUrl(actor(engineer),id))).isInstanceOf(ServiceException.class);
        }
        assertError(1099000002,()->as(secondEngineer,()->handover.get(secondEngineer,commission)));
        assertError(1099000002,()->as(other,()->handover.download(other,commission,1,"RESULT")));
        assertError(400,()->as(engineer,()->handover.download(engineer,commission,1,"ATTACHMENT")));
        long unrelated=upload(sales,"BUSINESS_DOCUMENT"); var ticket=as(engineer,()->handover.download(engineer,commission,1,"RESULT"));
        assertError(1099000002,()->as(engineer,()->assets.readDownload(actor(engineer),unrelated,token(ticket.downloadUrl()))));
    }

    @Test void transferDuringStorageReadRevokesBusinessAndEngineeringTickets() throws Exception {
        long customer=customer(sales); var pending=submit(ready(customer),upload(sales,"BUSINESS_DOCUMENT"),engineer);
        long commission=Long.parseLong(pending.commissionId()),asset=Long.parseLong(pending.currentPacket().files().get(0).assetId());
        var business=as(sales,()->handover.download(sales,commission,1,"RESULT"));
        storage.beforeRead=()->jdbc.update("UPDATE fr_customer SET owner_user_id=? WHERE id=?",colleague.userId(),customer);
        assertError(1099000002,()->as(sales,()->assets.readDownload(actor(sales),asset,token(business.downloadUrl()))));
        assertError(1099000002,()->as(sales,()->handover.get(sales,commission)));
        assertThat(as(colleague,()->handover.get(colleague,commission)).currentPacket()).isNotNull();
        var accepted=receive(pending,engineer,key()); long project=Long.parseLong(accepted.projectId());
        var engineering=as(engineer,()->handover.download(engineer,commission,1,"RESULT"));
        storage.beforeRead=()->projects.changeManager(engineer,project,new EngProjectService.ManagerCommand(secondEngineer.userId(),"施工负责人交接"),key());
        assertError(1099000002,()->as(engineer,()->assets.readDownload(actor(engineer),asset,token(engineering.downloadUrl()))));
        assertError(1099000002,()->as(engineer,()->handover.get(engineer,commission)));
        assertThat(as(engineer,()->legacyCommission.listVisible(engineer))).noneMatch(c->c.getId()==commission);
        assertThat(as(secondEngineer,()->handover.get(secondEngineer,commission)).currentPacket().status()).isEqualTo("ACCEPTED");
        assertThat(as(secondEngineer,()->legacyCommission.listVisible(secondEngineer))).anyMatch(c->c.getId()==commission);
        assertThat(as(finance,()->handover.get(finance,commission)).canDecide()).isFalse();
        var limited=as(engineer,()->projects.detail(engineer,project));
        assertThat(limited).containsEntry("canReadHandover",false).containsEntry("documents",List.of());
        assertThat(limited.get("handoverPaymentPlan")).isNull();
    }

    @Test void companyTenantAndRealPostAreRecheckedForReadsWritesAndDownloadConsumption() throws Exception {
        var pending=submit(ready(customer(sales)),upload(sales,"BUSINESS_DOCUMENT"),engineer);
        long commission=Long.parseLong(pending.commissionId()),asset=Long.parseLong(pending.currentPacket().files().get(0).assetId());
        assertError(1099000002,()->as(colleague,()->handover.get(colleague,commission)));
        assertThat(as(lead,()->handover.get(lead,commission)).currentPacket()).isNotNull();
        assertError(1099000002,()->as(boss,()->handover.submit(boss,commission,submitCommand(pending,asset,engineer),key())));
        TenantContextHolder.setTenantId(tenant+1);
        try { assertError(1099000002,()->as(engineer,()->handover.get(engineer,commission))); }
        finally { TenantContextHolder.setTenantId(tenant); }
        var ticket=as(engineer,()->handover.download(engineer,commission,1,"RESULT"));
        storage.beforeRead=()->jdbc.update("UPDATE system_dept SET status=1 WHERE id=?",engineer.orgId());
        try { assertError(1099000002,()->as(engineer,()->assets.readDownload(actor(engineer),asset,token(ticket.downloadUrl())))); }
        finally { jdbc.update("UPDATE system_dept SET status=0 WHERE id=?",engineer.orgId()); }
        jdbc.update("UPDATE pf_staff_post SET status='REVOKED',revoked_at=now(),revoked_by=? WHERE id=?",sales.userId(),engineer.postId());
        assertError(1099000002,()->as(engineer,()->handover.get(engineer,commission)));
        assertError(1099000002,()->as(engineer,()->handover.receive(engineer,commission,decision(pending),key())));
    }

    @Test void acceptedMetadataAndStoredBytesMustStillMatchAtReceiveAndDownload() throws Exception {
        var pending=submit(ready(customer(sales)),upload(sales,"BUSINESS_DOCUMENT"),engineer);
        long commission=Long.parseLong(pending.commissionId()),asset=Long.parseLong(pending.currentPacket().files().get(0).assetId());
        jdbc.update("UPDATE fr_asset SET accepted_sha256='bad' WHERE id=?",asset);
        assertError(400,()->as(engineer,()->handover.receive(engineer,commission,decision(pending),key())));
        assertThat(count("fr_eng_project","source_commission_id",commission)).isZero();
        jdbc.update("UPDATE fr_asset SET accepted_sha256=? WHERE id=?",sha(PDF),asset);
        var ticket=as(engineer,()->handover.download(engineer,commission,1,"RESULT"));
        String object=jdbc.queryForObject("SELECT object_key FROM fr_asset WHERE id=?",String.class,asset);
        storage.values.put(object,"tampered".getBytes(StandardCharsets.US_ASCII));
        assertError(1071000001,()->as(engineer,()->assets.readDownload(actor(engineer),asset,token(ticket.downloadUrl()))));
        assertThat(as(engineer,()->handover.get(engineer,commission)).currentPacket().status()).isEqualTo("PENDING");
    }

    @Test void receiveRollbackLeavesPacketCommissionProjectAndCommandLogUnchanged() throws Exception {
        var pending=submit(ready(customer(sales)),upload(sales,"BUSINESS_DOCUMENT"),engineer);
        long commission=Long.parseLong(pending.commissionId()); String acceptKey=key();
        as(engineer,()->new TransactionTemplate(transactions).execute(tx->{
            var accepted=handover.receive(engineer,commission,decision(pending),acceptKey);
            assertThat(accepted.projectId()).isNotNull(); tx.setRollbackOnly(); return null;
        }));
        assertThat(count("fr_eng_project","source_commission_id",commission)).isZero();
        assertThat(audit(commission,"external-packet-receive")).isZero();
        var stillPending=as(engineer,()->handover.get(engineer,commission));
        assertThat(stillPending.revision()).isEqualTo(pending.revision()); assertThat(stillPending.status()).isEqualTo("HANDOVER_SUBMITTED");
        assertThat(stillPending.currentPacket().status()).isEqualTo("PENDING");
        assertThat(receive(pending,engineer,acceptKey).projectId()).isNotNull();
    }

    @Test void concurrentSameKeySubmitAndReceiveCommitOneVersionAndOneProject() throws Exception {
        var ready=ready(customer(sales)); long commission=Long.parseLong(ready.commissionId());
        var context=as(sales,()->handover.get(sales,commission)); var input=submitCommand(context,upload(sales,"BUSINESS_DOCUMENT"),engineer); String submitKey=key();
        var submitted=concurrently(sales,()->handover.submit(sales,commission,input,submitKey),()->handover.submit(sales,commission,input," "+submitKey+" "));
        assertThat(submitted.get(0).revision()).isEqualTo(submitted.get(1).revision());
        assertThat(count("fr_commission_handover","commission_id",commission)).isEqualTo(1);
        assertThat(audit(commission,"external-packet-submit")).isEqualTo(1);
        assertError(1099000000,()->as(sales,()->handover.submit(sales,commission,new Submit(input.revision(),input.resultVersion(),input.confirmationAssetId(),input.confirmationDate(),"改过内容",input.managerUserId(),true),submitKey)));
        var pending=submitted.get(0); String receiveKey=key();
        var received=concurrently(engineer,()->handover.receive(engineer,commission,decision(pending),receiveKey),()->handover.receive(engineer,commission,decision(pending),receiveKey));
        assertThat(received.get(0).projectId()).isEqualTo(received.get(1).projectId());
        assertThat(count("fr_eng_project","source_commission_id",commission)).isEqualTo(1);
        assertThat(audit(commission,"external-packet-receive")).isEqualTo(1);
        assertError(1099000000,()->as(engineer,()->handover.returnPacket(engineer,commission,new Decision(pending.revision(),1,"同键换动作",false),receiveKey)));
    }

    @Test void competingAcceptAndReturnHaveExactlyOneWinnerAndNoOrphanProject() throws Exception {
        var pending=submit(ready(customer(sales)),upload(sales,"BUSINESS_DOCUMENT"),engineer); long commission=Long.parseLong(pending.commissionId());
        var outcomes=concurrently(engineer,()->outcome(()->handover.receive(engineer,commission,decision(pending),key())),
                ()->outcome(()->handover.returnPacket(engineer,commission,new Decision(pending.revision(),1,"需补图纸",false),key())));
        assertThat(outcomes).filteredOn(value->value instanceof View).hasSize(1);
        assertThat(outcomes).filteredOn(value->value instanceof ServiceException).hasSize(1);
        var result=as(sales,()->handover.get(sales,commission));
        assertThat(audit(commission,"external-packet-receive")+audit(commission,"external-packet-return")).isEqualTo(1);
        assertThat(count("fr_eng_project","source_commission_id",commission)).isEqualTo("ACCEPTED".equals(result.status())?1:0);
    }

    @Test void legacyHandoverCommandsCannotActOnExternalPackets() throws Exception {
        var ready=ready(customer(sales)); long commission=Long.parseLong(ready.commissionId()),file=upload(sales,"BUSINESS_DOCUMENT");
        var input=new HandoverService.PacketCommand(0,"a","b","c",Long.toString(file),"V1",today(),"旧入口",engineer.userId(),true,file);
        assertThatThrownBy(()->as(sales,()->legacyHandover.submit(sales,commission,input,key()))).isInstanceOf(ServiceException.class);
        var pending=submit(ready,file,engineer);
        assertThatThrownBy(()->as(engineer,()->legacyHandover.receive(engineer,commission,new HandoverService.DecisionCommand("",true),key()))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->as(engineer,()->legacyHandover.returnPacket(engineer,commission,new HandoverService.DecisionCommand("旧退回",false),key()))).isInstanceOf(ServiceException.class);
        assertThat(as(engineer,()->handover.get(engineer,commission)).revision()).isEqualTo(pending.revision());
        assertThat(count("fr_eng_project","source_commission_id",commission)).isZero();
    }

    @Test void ownerTransferWhileSubmitWaitsForCustomerLockCannotUseItsPreLockAuthority() throws Exception {
        long customer=customer(sales); var ready=ready(customer); long commission=Long.parseLong(ready.commissionId());
        var context=as(sales,()->handover.get(sales,commission)); var input=submitCommand(context,upload(sales,"BUSINESS_DOCUMENT"),engineer);
        var held=new CountDownLatch(1); var release=new CountDownLatch(1); var started=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2); currentPost.use(sales);
        try {
            var transfer=pool.submit(()->new TransactionTemplate(transactions).execute(tx->{
                jdbc.queryForObject("SELECT id FROM fr_customer WHERE id=? FOR UPDATE",Long.class,customer);
                held.countDown(); await(release);
                jdbc.update("UPDATE fr_customer SET owner_user_id=? WHERE id=?",colleague.userId(),customer); return null;
            }));
            assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
            var submit=pool.submit(()->{TenantContextHolder.setTenantId(tenant);try { started.countDown();return handover.submit(sales,commission,input,key()); }finally{TenantContextHolder.clear();}});
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->submit.get(250,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();transfer.get(10,TimeUnit.SECONDS);
            assertThatThrownBy(()->submit.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ServiceException.class);
            assertThat(count("fr_commission_handover","commission_id",commission)).isZero();
            assertThat(audit(commission,"external-packet-submit")).isZero();
        } finally { release.countDown();pool.shutdownNow();currentPost.clear(); }
    }

    @Test void existingManualProjectRequiresItsActualManagerAndFailedReceiveRollsBackPacketDecision() throws Exception {
        long customer=customer(sales); signed(customer); long manual=manualProject(customer,secondEngineer);
        var pending=submit(ready(customer),upload(sales,"BUSINESS_DOCUMENT"),engineer); long commission=Long.parseLong(pending.commissionId());
        assertThatThrownBy(()->receive(pending,engineer,key())).isInstanceOf(ServiceException.class);
        assertThat(as(sales,()->handover.get(sales,commission)).currentPacket().status()).isEqualTo("PENDING");
        assertThat(count("fr_eng_project","source_commission_id",commission)).isZero();
        assertThat(audit(commission,"external-packet-receive")).isZero();
        assertThat(jdbc.queryForObject("SELECT manager_user_id FROM fr_eng_project WHERE id=?",Long.class,manual)).isEqualTo(secondEngineer.userId());
        as(secondEngineer,()->projects.changeManager(secondEngineer,manual,new EngProjectService.ManagerCommand(engineer.userId(),"明确交接后再接收"),key()));
        var accepted=receive(pending,engineer,key()); assertThat(accepted.projectId()).isEqualTo(Long.toString(manual));
        assertThat(count("fr_eng_project","customer_id",customer)).isEqualTo(1);
        assertThat(count("fr_eng_document","project_id",manual)).isEqualTo(4);
    }

    @Test void receiveUsesProjectThenContractLockOrderWhenReusingAnExistingManualProject() throws Exception {
        long customer=customer(sales); var contract=signed(customer); long project=manualProject(customer,engineer);
        var pending=submit(ready(customer),upload(sales,"BUSINESS_DOCUMENT"),engineer); long commission=Long.parseLong(pending.commissionId());
        var held=new CountDownLatch(1); var lockContract=new CountDownLatch(1); var contractHeld=new CountDownLatch(1); var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2); currentPost.use(engineer);
        try {
            var preparing=pool.submit(()->new TransactionTemplate(transactions).execute(tx->{
                jdbc.queryForObject("SELECT id FROM fr_eng_project WHERE id=? FOR UPDATE",Long.class,project);
                held.countDown(); await(lockContract);
                jdbc.queryForObject("SELECT id FROM fr_sale_contract WHERE id=? FOR UPDATE",Long.class,contract.getId());
                contractHeld.countDown(); await(release); return null;
            }));
            assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
            var receiving=pool.submit(()->{TenantContextHolder.setTenantId(tenant);try{return handover.receive(engineer,commission,decision(pending),key());}finally{TenantContextHolder.clear();}});
            assertThatThrownBy(()->receiving.get(250,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            lockContract.countDown(); assertThat(contractHeld.await(5,TimeUnit.SECONDS)).isTrue();
            release.countDown();preparing.get(10,TimeUnit.SECONDS);
            assertThat(receiving.get(10,TimeUnit.SECONDS).projectId()).isEqualTo(Long.toString(project));
            assertThat(count("fr_eng_project","customer_id",customer)).isEqualTo(1);
        } finally {lockContract.countDown();release.countDown();pool.shutdownNow();currentPost.clear();}
    }

    @Test void legacyDateOnlyStartRetryMustMatchTheActualSavedProofAndNote() throws Exception {
        long customer=customer(sales); var contract=signed(customer);
        long project=Long.parseLong(receive(submit(ready(customer),upload(sales,"BUSINESS_DOCUMENT"),engineer),engineer,key()).projectId());
        prepare(project,engineer); collectBeforeStart(contract.getId()); long file=upload(engineer,"ENG_DRAWING");
        var start=new EngPlanService.NodeRecordCommand(today(),"正式开工",file,true); as(engineer,()->plans.start(engineer,project,start,key()));
        String legacyKey=key(); as(engineer,()->{
            commands.record(engineer,"fr-eng-project",project,IdWorker.getId(),"eng-start",Map.of("date",today().toString()),legacyKey);return null;
        });
        as(engineer,()->plans.start(engineer,project,start,legacyKey));
        long replacement=upload(engineer,"ENG_DRAWING");
        assertError(1099000000,()->as(engineer,()->plans.start(engineer,project,new EngPlanService.NodeRecordCommand(today(),"正式开工",replacement,true),legacyKey)));
        assertError(1099000000,()->as(engineer,()->plans.start(engineer,project,new EngPlanService.NodeRecordCommand(today(),"更改说明",file,true),legacyKey)));
        assertThat(count("fr_eng_site_record","project_id",project)).isEqualTo(1);
    }

    @Test void startRequiresExplicitRealDateAndItsIdempotencyBindsEvidenceAndProject() throws Exception {
        long customer=customer(sales); var contract=signed(customer);
        var accepted=receive(submit(ready(customer),upload(sales,"BUSINESS_DOCUMENT"),engineer),engineer,key());
        long project=Long.parseLong(accepted.projectId()); prepare(project,engineer); collectBeforeStart(contract.getId());
        long file=upload(engineer,"ENG_DRAWING");
        assertThatThrownBy(()->as(engineer,()->plans.start(engineer,project,new EngPlanService.NodeRecordCommand(null,"实际开工",file,true),key()))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->as(engineer,()->plans.start(engineer,project,new EngPlanService.NodeRecordCommand(today().plusDays(1),"实际开工",file,true),key()))).isInstanceOf(ServiceException.class);
        String startKey=key(); var input=new EngPlanService.NodeRecordCommand(today(),"真实现场开工",file,true);
        as(engineer,()->plans.start(engineer,project,input,startKey));
        long replacement=upload(engineer,"ENG_DRAWING");
        assertError(1099000000,()->as(engineer,()->plans.start(engineer,project,new EngPlanService.NodeRecordCommand(today(),"真实现场开工",replacement,true),startKey)));
        assertError(1099000000,()->as(engineer,()->plans.start(engineer,project,new EngPlanService.NodeRecordCommand(today(),"改写说明",file,true),startKey)));
        assertThat(count("fr_eng_site_record","project_id",project)).isEqualTo(1); assertProgress(customer,2);
        jdbc.update("UPDATE fr_asset SET accepted_sha256='invalid' WHERE id=?",file);
        assertProgress(customer,1);
        assertThat(jdbc.queryForObject("SELECT actual_start_at FROM fr_eng_project WHERE id=?",LocalDate.class,project)).isEqualTo(today());
    }

    @Test void convertedLegacyDetailExposesOnlyFrozenPacketToReceiverAndKeepsHistoryForCustomerReader() throws Exception {
        long customer=customer(sales), commission=IdWorker.getId(), legacyAsset=upload(sales,"DESIGN_REFERENCE");
        jdbc.update("INSERT INTO fr_commission(id,company_id,design_org_id,customer_id,customer_name,status,submission_version,dispatch_seq,first_ready_at,tail_paid_at,tail_ready_at,revision) VALUES(?,?,?,?,'旧委托客户','DISPATCHED',1,1,now(),now(),now(),1)",
                commission,sales.orgId(),sales.orgId(),customer);
        jdbc.update("INSERT INTO fr_commission_submission(id,company_id,design_org_id,commission_id,version,text,scope,attachment,total_cents,first_cents,recipient,auth_kind,submitted_by_user_id,submitted_by_post_id) VALUES(?,?,?,?,1,'历史私有需求','旧合同范围',?,10000,5000,'旧收款方','STANDARD',?,?)",
                IdWorker.getId(),sales.orgId(),sales.orgId(),commission,Long.toString(legacyAsset),sales.userId(),sales.postId());
        jdbc.update("INSERT INTO fr_commission_progress(id,company_id,design_org_id,commission_id,kind,seq,note,recipient,dispatch_seq,actor_user_id,actor_post_id) VALUES(?,?,?,?,'DISPATCH',1,'历史沟通与费用事项','旧收件人',1,?,?)",
                IdWorker.getId(),sales.orgId(),sales.orgId(),commission,sales.userId(),sales.postId());
        jdbc.update("INSERT INTO fr_commission_dispatch_manifest(id,commission_id,dispatch_seq,submission_version,design_org_id,result_asset_id,drawing_asset_id,review_asset_id,drawing_version,actor_user_id,actor_post_id) VALUES(?,?,1,1,?,?,?,?,'历史V1',?,?)",
                IdWorker.getId(),commission,sales.orgId(),legacyAsset,legacyAsset,legacyAsset,sales.userId(),sales.postId());
        long approval=IdWorker.getId();
        jdbc.update("INSERT INTO pf_approval(id,domain,org_id,subject_type,subject_id,subject_version,signature,amount_cents,status,approver_role_code,submitter_user_id,submitter_post_id,create_time,decided_at) VALUES(?,'franchise',?,'commission-fee',?,1,?,10000,'APPROVED','boss',?,?,now(),now())",
                approval,sales.orgId(),commission,"legacy-"+commission,sales.userId(),sales.postId());
        var before=as(sales,()->external.get(sales,customer));
        var converted=as(sales,()->external.convertLegacy(sales,customer,request(before,"保留旧记录并明确转换外部委托"),key()));
        var pending=submit(deliver(customer,converted),upload(sales,"BUSINESS_DOCUMENT"),engineer);
        var restricted=as(engineer,()->legacyCommission.get(engineer,commission));
        assertThat(restricted.commission().getId()).isEqualTo(commission);
        assertThat(restricted.commission().getWorkflowMode()).isEqualTo("EXTERNAL");
        assertThat(restricted.commission().getSubmissionVersion()).isNull();
        assertThat(restricted.commission().getDesignOrgId()).isNull();
        assertThat(restricted.commission().getFirstReadyAt()).isNull();
        assertThat(restricted.commission().getTailPaidAt()).isNull();
        assertThat(restricted.commission().getTailReadyAt()).isNull();
        assertThat(restricted.submissions()).isEmpty(); assertThat(restricted.progress()).isEmpty();
        assertThat(restricted.dispatches()).isEmpty(); assertThat(restricted.feeApproval()).isNull();
        assertThat(restricted.settlement()).isNull(); assertThat(restricted.simulatedPayment()).isNull();
        assertThat(restricted.allowedActions()).isEmpty();
        assertThat(restricted.handovers()).hasSize(1);
        assertThat(restricted.handovers().get(0).getVersion()).isEqualTo(pending.currentPacket().version());
        assertThat(restricted.handovers().get(0).getResultFile()).isNotEqualTo(Long.toString(legacyAsset));
        var original=as(sales,()->legacyCommission.get(sales,commission));
        assertThat(original.submissions()).hasSize(1); assertThat(original.submissions().get(0).getTotalCents()).isEqualTo(10000);
        assertThat(original.progress()).hasSize(1); assertThat(original.dispatches()).hasSize(1);
        assertThat(original.dispatches().get(0).resultAssetId()).isEqualTo(legacyAsset);
        assertThat(original.feeApproval().getId()).isEqualTo(approval);
        assertThat(count("fr_commission_submission","commission_id",commission)).isEqualTo(1);
        assertThat(count("fr_commission_dispatch_manifest","commission_id",commission)).isEqualTo(1);
    }

    private long customer(PostSnapshot owner) {
        long id=IdWorker.getId(); jdbc.update("INSERT INTO fr_customer(id,company_id,owner_user_id,team_id,name,phone,city,source,status) VALUES(?,?,?,?,'实际八步客户',?,'武汉市','到店','REGISTERED')",
                id,owner.orgId(),owner.userId(),owner.teamId(),Long.toString(10000000000L+Math.floorMod(id,10000000000L))); return id;
    }
    private ExternalCommissionModel.View ready(long customer) throws Exception {
        var initial=as(sales,()->external.get(sales,customer));
        var registered=as(sales,()->external.register(sales,customer,request(initial,"登记实际外部委托"),key()));
        return deliver(customer,registered);
    }
    private RequestCommand request(ExternalCommissionModel.View view,String note) {
        return new RequestCommand(view.revision(),today(),"周工设计服务","EXT-2026","客户两层乡墅需求","方案、施工图及专业审核",new BigDecimal("260.50"),2,List.of(),view.currentPaymentPlanRef(),note);
    }
    private ExternalCommissionModel.View deliver(long customer,ExternalCommissionModel.View view) throws Exception {
        long result=upload(sales,"BUSINESS_DOCUMENT"),drawing=upload(sales,"BUSINESS_DOCUMENT"),review=upload(sales,"BUSINESS_DOCUMENT");
        return as(sales,()->external.results(sales,customer,new ResultCommand(view.revision(),view.currentRequest().version(),today(),"图纸V"+view.currentRequest().version(),Long.toString(result),Long.toString(drawing),Long.toString(review),"收到外部正式成果"),key()));
    }
    private void saveDraft(long customer,long revision,long total) {
        as(sales,()->drafts.save(sales,customer,new CustomerPaymentPlanDraftService.Save(revision,total,List.of(new PaymentPlanRules.Input(null,"需求阶段约定",total,null,null,"MANUAL",null)),"先登记约定，正式合同另行签署"),key()));
    }
    private SaleContractDO signed(long customer) throws Exception {
        var draft=as(sales,()->contracts.create(sales,new SaleContractService.ContractCommand(customer,100000,0,0,0,null,null,null,List.of(
                new PaymentPlanRules.Input(null,"开工前",40000L,null,null,"BEFORE_START",null),
                new PaymentPlanRules.Input(null,"完工",60000L,null,null,"ON_FINISH",null))),key()));
        long file=upload(sales,"CONTRACT_ATTACHMENT");
        return as(sales,()->contracts.sign(sales,draft.getId(),new SaleContractService.SignCommand(today(),file),draft.getRevision(),key()));
    }
    private long manualProject(long customer,PostSnapshot actor) {
        String phone=jdbc.queryForObject("SELECT phone FROM fr_customer WHERE id=?",String.class,customer);
        var project=as(actor,()->projects.create(actor,new EngProjectService.CreateCommand("手工工程"+customer,"实际八步客户","武汉市",phone,new BigDecimal("260.50"),2,null),key()));
        return ((Number)project.get("id")).longValue();
    }
    private View submit(ExternalCommissionModel.View ready,long file,PostSnapshot receiver) {
        long commission=Long.parseLong(ready.commissionId()); var view=as(sales,()->handover.get(sales,commission));
        return as(sales,()->handover.submit(sales,commission,submitCommand(view,file,receiver),key()));
    }
    private Submit submitCommand(View view,long file,PostSnapshot receiver) {
        return new Submit(view.revision(),view.resultVersion(),Long.toString(file),today(),"已核对客户确认、施工图及专业审核记录",Long.toString(receiver.userId()),true);
    }
    private static Decision decision(View view) { return new Decision(view.revision(),view.currentPacket().version(),"核查齐全，接收工程资料",true); }
    private View receive(View pending,PostSnapshot actor,String key) { return as(actor,()->handover.receive(actor,Long.parseLong(pending.commissionId()),decision(pending),key)); }
    private void prepare(long project,PostSnapshot actor) {
        List<EngPlanService.NodeInput> nodes=jdbc.query("SELECT node_index,name FROM fr_eng_plan_node WHERE project_id=? ORDER BY node_index",
                (rs,n)->new EngPlanService.NodeInput(rs.getInt("node_index"),rs.getString("name"),today().plusDays(n),today().plusDays(n)),project);
        as(actor,()->plans.prepare(actor,project,new EngPlanService.PrepareCommand(null,null,null,null,null,"核对正式合同付款计划","施工班组",nodes,1),key()));
    }
    private void collectBeforeStart(long contract) {
        long stage=jdbc.queryForObject("SELECT id FROM fr_sale_payment_stage WHERE contract_id=? AND trigger_type='BEFORE_START'",Long.class,contract);
        as(finance,()->contracts.collect(finance,stage,new SaleContractService.CollectCommand(40000,"bank",key(),today()),key()));
    }
    private void completeManualSix(long customer,long document) {
        List<Map<String,Object>> fields=List.of(
                Map.of("contactMethod","微信","invitationResult","CONFIRMED","appointmentAt",today()+"T10:00:00"),
                Map.of("receptionist","业务经办人","requirements","两层乡墅","feedback","已确认需求"),
                Map.of("selectionResult","两层户型","confirmed",true),
                Map.of("siteName","示范工地","feedback","了解施工情况"),
                Map.of("address","实际宅基地","measurer","测量人员","width","10","depth","12","area","120","checked",true),
                Map.of("amountCents","100000","scope","土建","versionLabel","V1","communicated",true,"result","客户接受"));
        long revision=0;
        for(int n=0;n<6;n++) {
            String code=CustomerWorkflowModel.Step.values()[n].name();
            var input=new CustomerWorkflowModel.SaveCommand(revision,today(),"已完成当次沟通与资料核对",fields.get(n),List.of(new Attachment(Long.toString(document),"外部正式资料")));
            var saved=as(sales,()->workflow.save(sales,customer,code,input,key()));
            revision=as(sales,()->workflow.complete(sales,customer,code,saved.revision(),key())).revision();
        }
    }
    private void assertProgress(long customer,int completed) { assertThat(reads.readAuthorized(tenant,sales.orgId(),customer).completedCount()).isEqualTo(completed); }
    private long upload(PostSnapshot post,String type) throws Exception {
        var image=new java.io.ByteArrayOutputStream();
        boolean reference="DESIGN_REFERENCE".equals(type);
        if (reference) javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",image);
        byte[] bytes=reference?image.toByteArray():PDF;
        return as(post,()->{ var ticket=assets.createUploadTicket(actor(post),type,reference?"image/png":"application/pdf",bytes.length,sha(bytes));
            assets.receiveUpload(actor(post),ticket.assetId(),token(ticket.uploadUrl()),new ByteArrayInputStream(bytes));
            assertThat(assets.completeUpload(actor(post),ticket.assetId()).uploadStatus()).isEqualTo("ACCEPTED"); return ticket.assetId(); });
    }
    private AssetAccessPort.Actor actor(PostSnapshot post) { return new AssetAccessPort.Actor(post.orgId(),post.orgId()+1000000L,post.userId(),post.postId()); }
    private static String sha(byte[] data) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); } catch(Exception error) { throw new IllegalStateException(error); } }
    private static String token(String url) { return url.substring(url.indexOf("token=")+6); }
    private long count(String table,String column,long id) { return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE "+column+"=?",Long.class,id); }
    private long audit(long aggregate,String action) { return jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE aggregate_id=? AND command=?",Long.class,aggregate,action); }
    private static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }
    private static String key() { return UUID.randomUUID().toString(); }
    private <T>T as(PostSnapshot actor,Supplier<T> work) { currentPost.use(actor);try{return work.get();}finally{currentPost.clear();} }
    private static void assertError(int code,Runnable work) { assertThatThrownBy(work::run).isInstanceOf(ServiceException.class).satisfies(error->assertThat(((ServiceException)error).getCode()).isEqualTo(code)); }
    private Object outcome(Supplier<?> work) { try { return work.get(); } catch(ServiceException failure) { return failure; } }
    private <T>List<T> concurrently(PostSnapshot actor,Supplier<T> one,Supplier<T> two) throws Exception {
        currentPost.use(actor); var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try { var first=pool.submit(()->worker(start,one)); var second=pool.submit(()->worker(start,two)); start.countDown();
            return List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); currentPost.clear(); }
    }
    private <T>T worker(CountDownLatch start,Supplier<T> work) throws Exception {
        TenantContextHolder.setTenantId(tenant); try { if(!start.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test timeout"); return work.get(); }
        finally { TenantContextHolder.clear(); }
    }
    private static void await(CountDownLatch latch) {
        try {if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
    }
}
