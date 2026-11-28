package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleContractDO;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Import({SaleReceiptCorrectionService.class,SaleContractService.class,SaleRequirementService.class,
        SaleQuoteService.class,SalesAccess.class,FranchiseCommands.class,CommandLogImpl.class,
        SaleContractThreeStageIntegrationTest.Config.class})
class SaleReceiptCorrectionIntegrationTest extends FranchisePgIntegrationTest {
    @Resource SaleContractService contracts;
    @Resource SaleReceiptCorrectionService corrections;
    @Resource JdbcTemplate jdbc;
    private PostSnapshot finance,sales;
    private long company;

    @BeforeEach void organization() {
        var store=fixture.store();company=store.orgId();
        finance=fixture.post(company,"finance",null);sales=fixture.post(company,"sales",store.salesTeamA());
        cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.setTenantId(company);
        jdbc.update("INSERT INTO system_dept(id,name,status,deleted,tenant_id) VALUES(?,'原单纠错测试',0,0,?) ON CONFLICT(id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id",company,company);
    }
    @org.junit.jupiter.api.AfterEach void tenant(){cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.clear();}

    @Test void correctsAmountDateAndStageOnTheOriginalReceiptAndPreservesSignedTermsAndReplay() {
        var c=contract();var stages=stageIds(c);String collectKey=key();
        var input=new SaleContractService.CollectCommand(40000,"bank",key(),LocalDate.now());
        long receipt=((Number)as(finance,()->contracts.collect(finance,stages.get(0),input,collectKey)).get("receiptId")).longValue();
        var plan=jdbc.queryForMap("SELECT total_cents,payment_plan_version,revision FROM fr_sale_contract WHERE id=?",c.getId());
        var source=jdbc.queryForMap("SELECT source_ref,transaction_no FROM fr_fin_receipt WHERE id=?",receipt);
        String key=key();var correction=change(1,30000,stages.get(1));
        assertThat(as(finance,()->corrections.registrationResult(finance,receipt,key))).containsEntry("state","NOT_REGISTERED");
        assertThat(as(finance,()->corrections.correct(finance,receipt,correction,key))).containsEntry("revisionVersion",2).containsEntry("replayed",false);
        assertThat(as(finance,()->corrections.registrationResult(finance,receipt,key))).containsEntry("receiptId",Long.toString(receipt)).containsEntry("revisionVersion",2);
        assertThat(as(finance,()->corrections.correct(finance,receipt,correction,key))).containsEntry("revisionVersion",2).containsEntry("replayed",true);
        assertThat(jdbc.queryForMap("SELECT amount_cents,received_date,revision_version,source_ref,transaction_no FROM fr_fin_receipt WHERE id=?",receipt))
                .containsEntry("amount_cents",30000L).containsEntry("received_date",java.sql.Date.valueOf(LocalDate.now().minusDays(1)))
                .containsEntry("revision_version",2).containsAllEntriesOf(source);
        assertThat(jdbc.queryForMap("SELECT stage_id,amount_cents FROM fr_sale_stage_receipt WHERE receipt_id=?",receipt))
                .containsEntry("stage_id",stages.get(1)).containsEntry("amount_cents",30000L);
        assertThat(as(finance,()->contracts.stageCollected(finance,stages.get(0)))).isZero();
        assertThat(as(finance,()->contracts.stageCollected(finance,stages.get(1)))).isEqualTo(30000);
        assertThat(jdbc.queryForMap("SELECT total_cents,payment_plan_version,revision FROM fr_sale_contract WHERE id=?",c.getId())).isEqualTo(plan);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_refund WHERE receipt_id=?",Long.class,receipt)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_receipt_revision WHERE receipt_id=?",Long.class,receipt)).isEqualTo(1);
        long originalTenant=jdbc.queryForObject("SELECT tenant_id FROM fr_fin_receipt WHERE id=?",Long.class,receipt);
        assertThat(originalTenant).isNotEqualTo(company);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM fr_fin_receipt_revision WHERE receipt_id=?",Long.class,receipt)).isEqualTo(originalTenant);
        assertThat(as(finance,()->contracts.collect(finance,stages.get(0),input,collectKey))).containsEntry("receiptId",receipt).containsEntry("replayed",true);
        var detail=as(finance,()->corrections.detail(finance,receipt));
        assertThat(detail).containsEntry("stageId",String.valueOf(stages.get(1))).containsEntry("revisionVersion",2);
        var history=(List<Map<String,Object>>)detail.get("history");
        assertThat(history).hasSize(1);
        assertThat((Map<String,Object>)history.get(0).get("before")).containsEntry("stageId",String.valueOf(stages.get(0))).containsEntry("amountCents",40000);
        assertThat((Map<String,Object>)history.get(0).get("after")).containsEntry("stageId",String.valueOf(stages.get(1))).containsEntry("amountCents",30000);
        assertThatThrownBy(()->as(finance,()->corrections.correct(finance,receipt,change(1,30001,stages.get(1)),key))).isInstanceOf(RuntimeException.class);
    }

    @Test void refundsFollowTheOriginalReceiptAndConstrainCorrectionsWithoutChangingThePlan() {
        var c=contract();var stages=stageIds(c);long receipt=collect(stages.get(0),40000);
        jdbc.update("INSERT INTO fr_fin_refund(id,company_id,receipt_id,amount_cents,status,reason,operator_user_id,date) VALUES(?,?,?,10000,'已核实','原单退款',?,CURRENT_DATE)",IdWorker.getId(),company,receipt,finance.userId());
        assertThatThrownBy(()->as(finance,()->corrections.correct(finance,receipt,change(1,9000,stages.get(1)),key()))).hasMessageContaining("已经退回");
        as(finance,()->corrections.correct(finance,receipt,change(1,30000,stages.get(1)),key()));
        assertThat(as(finance,()->contracts.stageCollected(finance,stages.get(0)))).isZero();
        assertThat(as(finance,()->contracts.stageCollected(finance,stages.get(1)))).isEqualTo(20000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_refund WHERE receipt_id=?",Long.class,receipt)).isEqualTo(1);
    }

    @Test void confirmedDeclarationRemainsOriginalEvidenceAndReplaysTheSameCorrectedReceipt() {
        var stages=stageIds(contract());
        var claim=new SaleContractService.CollectCommand(40000,"bank",key(),LocalDate.now());
        long declaration=((Number)as(sales,()->contracts.declareCollection(sales,stages.get(0),claim,key())).get("declarationId")).longValue();
        String confirmationKey=key();var confirmation=new SaleContractService.DeclarationConfirmation("原实际到账账户","bank");
        long receipt=((Number)as(finance,()->contracts.confirmDeclaration(finance,declaration,confirmation,confirmationKey)).get("receiptId")).longValue();
        as(finance,()->corrections.correct(finance,receipt,change(1,30000,stages.get(1)),key()));
        assertThat(jdbc.queryForMap("SELECT stage_id,amount_cents,received_date,status FROM fr_sale_collection_declaration WHERE id=?",declaration))
                .containsEntry("stage_id",stages.get(0)).containsEntry("amount_cents",40000L).containsEntry("status","CONFIRMED")
                .containsEntry("received_date",java.sql.Date.valueOf(LocalDate.now()));
        assertThat(as(finance,()->contracts.confirmDeclaration(finance,declaration,confirmation,confirmationKey)))
                .containsEntry("receiptId",receipt).containsEntry("replayed",true);
        assertThat(jdbc.queryForObject("SELECT amount_cents FROM fr_fin_receipt WHERE id=?",Long.class,receipt)).isEqualTo(30000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_stage_receipt WHERE contract_id=(SELECT contract_id FROM fr_sale_stage_receipt WHERE receipt_id=?)",Long.class,receipt)).isEqualTo(1);
    }

    @Test void rejectsForeignContractsOrganizationsRolesRetiredStagesStaleVersionsAndOvercollection() {
        var c=contract();var stages=stageIds(c);long receipt=collect(stages.get(0),40000);
        collect(stages.get(1),40000);
        long foreign=stageIds(contract()).get(0);
        assertThatThrownBy(()->as(finance,()->corrections.correct(finance,receipt,change(1,20000,foreign),key()))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->as(finance,()->corrections.correct(finance,receipt,change(1,20000,stages.get(1)),key()))).hasMessageContaining("超过");
        assertThatThrownBy(()->as(finance,()->corrections.correct(finance,receipt,change(2,20000,stages.get(0)),key()))).hasMessageContaining("已被修改");
        for(var actor:List.of(sales,fixture.post(company,"boss",null),fixture.post(fixture.store().orgId(),"finance",null))) {
            assertThatThrownBy(()->as(actor,()->corrections.correct(actor,receipt,change(1,20000,stages.get(0)),key()))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->as(actor,()->corrections.detail(actor,receipt))).isInstanceOf(RuntimeException.class);
        }
        jdbc.update("UPDATE fr_sale_payment_stage SET deleted=true WHERE id=?",stages.get(1));
        assertThatThrownBy(()->as(finance,()->corrections.correct(finance,receipt,change(1,1000,stages.get(1)),key()))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT amount_cents FROM fr_fin_receipt WHERE id=?",Long.class,receipt)).isEqualTo(40000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_receipt_revision WHERE receipt_id=?",Long.class,receipt)).isZero();
    }

    @Test void rejectsInvalidInputAndManualReceipts() {
        long stage=stageIds(contract()).get(0),receipt=collect(stage,40000);
        for(var bad:List.of(new SaleReceiptCorrectionService.Correction(1,0L,LocalDate.now(),stage,"纠错"),
                new SaleReceiptCorrectionService.Correction(1,20000L,LocalDate.now().plusDays(1),stage,"纠错"),
                new SaleReceiptCorrectionService.Correction(1,20000L,LocalDate.now(),stage," "))) {
            assertThatThrownBy(()->as(finance,()->corrections.correct(finance,receipt,bad,key()))).isInstanceOf(RuntimeException.class);
        }
        jdbc.update("UPDATE fr_fin_receipt SET source_type='manual',source_ref=NULL WHERE id=?",receipt);
        assertThatThrownBy(()->as(finance,()->corrections.correct(finance,receipt,change(1,20000,stage),key()))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->as(finance,()->corrections.detail(finance,receipt))).isInstanceOf(RuntimeException.class);
    }

    @Test void concurrentCorrectionsCannotOverwriteEachOtherOrDuplicateCash() throws Exception {
        long stage=stageIds(contract()).get(0),receipt=collect(stage,40000);var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);currentPost.use(finance);
        try {
            Callable<Boolean> a=()->{start.await();try{corrections.correct(finance,receipt,change(1,30000,stage),key());return true;}catch(cn.iocoder.yudao.framework.common.exception.ServiceException conflict){return false;}};
            var first=pool.submit(a);var second=pool.submit(a);start.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_receipt_revision WHERE receipt_id=?",Long.class,receipt)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_stage_receipt WHERE receipt_id=?",Long.class,receipt)).isEqualTo(1);
        } finally {pool.shutdownNow();currentPost.clear();}
    }

    private SaleReceiptCorrectionService.Correction change(int version,long cents,long stage) {return new SaleReceiptCorrectionService.Correction(version,cents,LocalDate.now().minusDays(1),stage,"核对银行回单后纠正原登记");}
    private long collect(long stage,long cents){return ((Number)as(finance,()->contracts.collect(finance,stage,new SaleContractService.CollectCommand(cents,"bank",key(),LocalDate.now()),key())).get("receiptId")).longValue();}
    private List<Long> stageIds(SaleContractDO c){return jdbc.queryForList("SELECT id FROM fr_sale_payment_stage WHERE contract_id=? ORDER BY sequence,id",Long.class,c.getId());}
    private SaleContractDO contract(){
        long customer=IdWorker.getId(),requirement=IdWorker.getId();
        jdbc.update("INSERT INTO fr_customer(id,company_id,owner_user_id,name,phone,source,status,create_time,update_time) VALUES(?,?,?,'纠错测试',?,'登记','REGISTERED',now(),now())",customer,company,sales.userId(),String.valueOf(customer%10000000000L+10000000000L));
        jdbc.update("INSERT INTO fr_sale_requirement(id,company_id,customer_id,author_user_id,version) VALUES(?,?,?,?,1)",requirement,company,customer,sales.userId());
        var plan=List.of(new PaymentPlanRules.Input(null,"首期",50000L,null,null,"ON_SIGN",null),new PaymentPlanRules.Input(null,"第二期",50000L,null,null,"ON_FINISH",null));
        var c=as(sales,()->contracts.create(sales,new SaleContractService.ContractCommand(customer,100000,0,0,0,null,null,requirement,plan),key()));
        as(sales,()->contracts.sign(sales,c.getId(),new SaleContractService.SignCommand(LocalDate.now(),9001L),jdbc.queryForObject("SELECT revision FROM fr_sale_contract WHERE id=?",Long.class,c.getId()),key()));
        return c;
    }
    private String key(){return "receipt-correct-"+IdWorker.getId();}
    private <T>T as(PostSnapshot actor,java.util.function.Supplier<T> action){currentPost.use(actor);try{return action.get();}finally{currentPost.clear();}}
}
