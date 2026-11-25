package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleContractDO;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.franchise.test.FranchiseFixture.Store;
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
import static org.assertj.core.api.Assertions.*;

@Import({SaleContractService.class,SaleRequirementService.class,SaleQuoteService.class,SalesAccess.class,
        FranchiseCommands.class,CommandLogImpl.class,SaleContractQueryAdapter.class,
        SaleContractThreeStageIntegrationTest.Config.class})
class FlexiblePaymentPlanIntegrationTest extends FranchisePgIntegrationTest {
    @Resource SaleContractService service;
    @Resource SaleContractQueryAdapter query;
    @Resource JdbcTemplate jdbc;
    private Store store;
    private PostSnapshot sales,boss,finance;

    @BeforeEach void organization() {
        store=fixture.store();
        sales=fixture.post(store.orgId(),"sales",store.salesTeamA());
        boss=fixture.post(store.orgId(),"boss",null);
        finance=fixture.post(store.orgId(),"finance",null);
    }

    @Test void arbitraryPlansStayDraftUntilTheFormalContractIsSigned() {
        for(int count:List.of(2,3,5)) {
            long customer=customer();
            var plan=java.util.stream.IntStream.range(0,count).mapToObj(i -> input(null,"付款"+(i+1),10000)).toList();
            var contract=create(customer,10000L*count,plan);
            var rows=as(boss,()->service.stageSummary(boss,contract.getId()));
            assertThat(rows).hasSize(count);
            assertThat(rows).allSatisfy(r -> assertThat(String.valueOf(r.get("stage"))).startsWith("STAGE_"));
            assertThat(as(boss,()->query.findPaymentPlan(boss,customer))).isNull();
            assertThat(as(finance,()->service.financialStages(finance))).noneMatch(r -> String.valueOf(contract.getId()).equals(r.get("contract_id")));
            signed(contract);
            var inherited=as(boss,()->query.findPaymentPlan(boss,customer));
            assertThat(inherited.version()).isEqualTo(1);
            assertThat(inherited.stages()).hasSize(count);
            assertThat(inherited.stages()).extracting(s->s.id()).containsExactlyElementsOf(rows.stream().map(r->((Number)r.get("stageId")).longValue()).toList());
        }
    }

    @Test void ratioPlanUsesExactCentsAndReplayDoesNotCreateAnotherPlan() {
        long customer=customer(); String key=key();
        var plan=List.of(ratio(3333),ratio(3333),ratio(3334));
        var cmd=command(customer,10001,plan);
        var first=as(sales,()->service.create(sales,cmd,key));
        var again=as(sales,()->service.create(sales,cmd,key));
        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(as(sales,()->service.stageSummary(sales,first.getId()))).extracting(r->r.get("amountCents")).containsExactly(3333L,3333L,3335L);
        assertThat(as(boss,()->service.paymentPlanHistory(boss,first.getId()))).hasSize(1);
    }

    @Test void changesKeepReceiptAndNodeIdentityAndRejectStaleRevision() {
        var c=create(customer(),100000,List.of(input(null,"签约款",40000),input(null,"完工款",60000)));
        signed(c);
        var old=as(finance,()->service.stageSummary(finance,c.getId()));
        long first=((Number)old.get(0).get("stageId")).longValue(),last=((Number)old.get(1).get("stageId")).longValue();
        var receipt=as(finance,()->service.collect(finance,first,new SaleContractService.CollectCommand(20000,"bank",key(),LocalDate.now()),key()));
        String key=key();
        var cmd=new SaleContractService.PaymentPlanCommand(120000,List.of(input(first,"首笔约定",50000),input(last,"完工约定",50000),input(null,"质保款",20000)),revision(c),"客户确认补充约定");
        var changed=as(finance,()->service.replacePaymentPlan(finance,c.getId(),cmd,key));
        assertThat(as(finance,()->service.replacePaymentPlan(finance,c.getId(),cmd,key)).getRevision()).isEqualTo(changed.getRevision());
        assertThat(as(boss,()->service.paymentPlanHistory(boss,c.getId()))).hasSize(2);
        var rows=as(finance,()->service.stageSummary(finance,c.getId()));
        assertThat(rows.get(0)).containsEntry("stageId",first).containsEntry("stage",old.get(0).get("stage"))
                .containsEntry("amountCents",50000L).containsEntry("collectedCents",20000L).containsEntry("planVersion",2);
        assertThat(jdbc.queryForObject("SELECT receipt_id FROM fr_sale_stage_receipt WHERE stage_id=?",Long.class,first))
                .isEqualTo(((Number)receipt.get("receiptId")).longValue());
        assertThat(jdbc.queryForObject("SELECT amount_cents FROM fr_sale_stage_before_change WHERE stage_id=? AND contract_revision=?",Long.class,first,changed.getRevision())).isEqualTo(40000);
        long added=((Number)rows.get(2).get("stageId")).longValue();
        assertThat(jdbc.queryForMap("SELECT amount_cents,deleted FROM fr_sale_stage_before_change WHERE stage_id=? AND contract_revision=?",added,changed.getRevision()))
                .containsEntry("amount_cents",0L).containsEntry("deleted",true);
        assertThatThrownBy(()->as(finance,()->service.replacePaymentPlan(finance,c.getId(),cmd,key()))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(()->as(finance,()->{service.changeAmounts(finance,c.getId(),new SaleContractService.ContractChange(100000,0,0,0,null,"旧端误写"),key());return true;})).hasMessageContaining("自定义付款计划");
    }

    @Test void rejectsDeletingReceiptsReducingBelowCashOrBorrowingAnotherContractNode() {
        var c=create(customer(),100000,List.of(input(null,"节点一",50000),input(null,"节点二",50000)));
        signed(c);
        long id=((Number)as(finance,()->service.stageSummary(finance,c.getId())).get(0).get("stageId")).longValue();
        as(finance,()->service.collect(finance,id,new SaleContractService.CollectCommand(30000,"bank",key(),LocalDate.now()),key()));
        assertThatThrownBy(()->as(finance,()->service.replacePaymentPlan(finance,c.getId(),new SaleContractService.PaymentPlanCommand(100000,List.of(input(id,"原节点",10000),input(null,"新节点",90000)),revision(c),"金额调整"),key()))).hasMessageContaining("净收款");
        assertThatThrownBy(()->as(finance,()->service.replacePaymentPlan(finance,c.getId(),new SaleContractService.PaymentPlanCommand(100000,List.of(input(null,"替换节点",100000)),revision(c),"节点调整"),key()))).hasMessageContaining("不能删除");
        var another=create(customer(),50000,List.of(input(null,"另一客户",50000)));
        long foreign=((Number)as(boss,()->service.stageSummary(boss,another.getId())).get(0).get("stageId")).longValue();
        assertThatThrownBy(()->as(boss,()->service.replacePaymentPlan(boss,c.getId(),new SaleContractService.PaymentPlanCommand(100000,List.of(input(foreign,"外来节点",100000)),revision(c),"测试越权"),key()))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(as(finance,()->service.stageCollected(finance,id))).isEqualTo(30000);
        assertThat(as(boss,()->service.paymentPlanHistory(boss,c.getId()))).hasSize(1);
    }

    @Test void signedChangesRequireManagementAndCrossCompanyReadsFail() {
        var c=create(customer(),100000,List.of(input(null,"付款",100000)));
        signed(c);
        long id=((Number)as(boss,()->service.stageSummary(boss,c.getId())).get(0).get("stageId")).longValue();
        assertThatThrownBy(()->as(sales,()->service.replacePaymentPlan(sales,c.getId(),new SaleContractService.PaymentPlanCommand(100000,List.of(input(id,"付款",100000)),revision(c),"变更"),key()))).hasMessageContaining("负责人或财务");
        var other=fixture.store();var outsider=fixture.post(other.orgId(),"boss",null);
        assertThatThrownBy(()->as(outsider,()->service.paymentPlanHistory(outsider,c.getId()))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(()->as(outsider,()->service.stageCollected(outsider,id))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(as(outsider,()->query.findPaymentPlanById(outsider,c.getId()))).isNull();
        assertThat(as(finance,()->service.paymentPlanDetail(finance,c.getId()))).containsEntry("canEdit",true);
        assertThat(as(sales,()->service.paymentPlanDetail(sales,c.getId()))).containsEntry("canEdit",false);
    }

    @Test void oldWholeStageEvidenceSurvivesAnIncreaseAndTailTriggersFollowDateChanges() {
        long customer=customer();
        var c=as(sales,()->service.create(sales,new SaleContractService.ContractCommand(customer,100000,100000,0,0,null,null,requirement(customer)),key()));
        signed(c);
        long id=((Number)as(finance,()->service.stageSummary(finance,c.getId())).get(0).get("stageId")).longValue();
        var cash=as(finance,()->service.collect(finance,id,new SaleContractService.CollectCommand(100000,"bank",key(),LocalDate.now()),key()));
        long receipt=((Number)cash.get("receiptId")).longValue();
        jdbc.update("UPDATE fr_sale_payment_stage SET paid_receipt_id=?,paid_at=now(),pay_fingerprint='historical-whole-stage' WHERE id=?",receipt,id);
        as(finance,()->{service.changeAmounts(finance,c.getId(),new SaleContractService.ContractChange(120000,0,0,10000,LocalDate.now().plusDays(90),"增加约定"),key());return true;});
        assertThat(jdbc.queryForMap("SELECT status,paid_receipt_id,pay_fingerprint FROM fr_sale_payment_stage WHERE id=?",id))
                .containsEntry("status","OPEN").containsEntry("paid_receipt_id",receipt).containsEntry("pay_fingerprint","historical-whole-stage");
        assertThat(jdbc.queryForObject("SELECT trigger_type FROM fr_sale_payment_stage WHERE contract_id=? AND stage='tail'",String.class,c.getId())).isEqualTo("DATE");
        as(finance,()->{service.changeAmounts(finance,c.getId(),new SaleContractService.ContractChange(120000,0,0,10000,null,"改为竣工到期"),key());return true;});
        assertThat(jdbc.queryForObject("SELECT trigger_type FROM fr_sale_payment_stage WHERE contract_id=? AND stage='tail'",String.class,c.getId())).isEqualTo("ON_FINISH");
        assertThatThrownBy(()->as(finance,()->{service.changeAmounts(finance,c.getId(),new SaleContractService.ContractChange(90000,0,0,0,null,"不可少于已收"),key());return true;})).hasMessageContaining("净收款");
        assertThat(as(finance,()->service.stageCollected(finance,id))).isEqualTo(100000);
    }

    @Test void concurrentWhitespaceVariantsShareTheSameCommandLockAndReplay() throws Exception {
        long customer=customer();var command=command(customer,100000,List.of(input(null,"统一付款",100000)));
        String key=key();var start=new java.util.concurrent.CountDownLatch(1);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);currentPost.use(sales);
        try {
            java.util.concurrent.Callable<Long> first=()->{start.await();return service.create(sales,command,key).getId();};
            java.util.concurrent.Callable<Long> padded=()->{start.await();return service.create(sales,command,"  "+key+"  ").getId();};
            var a=executor.submit(first);var b=executor.submit(padded);start.countDown();
            assertThat(a.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(b.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_contract WHERE customer_id=?",Long.class,customer)).isEqualTo(1);
        } finally {executor.shutdownNow();currentPost.clear();}
    }

    @Test void legacyStageIdsAndZeroAmountsRemainReadable() {
        long customer=customer();
        var c=as(sales,()->service.create(sales,new SaleContractService.ContractCommand(customer,100000,100000,0,0,null,null,requirement(customer)),key()));
        var rows=as(boss,()->service.stageSummary(boss,c.getId()));
        assertThat(rows).extracting(r->r.get("stage")).containsExactly("SIGN_AFTER","BASE_DONE","CAP_DONE");
        assertThat(rows).extracting(r->r.get("trigger")).containsExactly("BEFORE_START","LEGACY_BASE_DONE","LEGACY_TOPPING");
        assertThat(rows.get(1)).containsEntry("amountCents",0L);
        assertThat(as(boss,()->service.paymentPlanHistory(boss,c.getId()))).hasSize(1);
    }

    private SaleContractDO create(long customer,long total,List<PaymentPlanRules.Input> plan){return as(sales,()->service.create(sales,command(customer,total,plan),key()));}
    private SaleContractService.ContractCommand command(long customer,long total,List<PaymentPlanRules.Input> plan){return new SaleContractService.ContractCommand(customer,total,0,0,0,null,null,requirement(customer),plan);}
    private PaymentPlanRules.Input input(Long id,String name,long cents){return new PaymentPlanRules.Input(id,name,cents,null,null,"MANUAL",null);}
    private PaymentPlanRules.Input ratio(int bps){return new PaymentPlanRules.Input(null,"比例付款",null,bps,null,"MANUAL",null);}
    private void signed(SaleContractDO c){jdbc.update("UPDATE fr_sale_contract SET status='SIGNED',signed_date=CURRENT_DATE WHERE id=?",c.getId());}
    private long revision(SaleContractDO c){return jdbc.queryForObject("SELECT revision FROM fr_sale_contract WHERE id=?",Long.class,c.getId());}
    private long customer(){long id=IdWorker.getId();jdbc.update("INSERT INTO fr_customer(id,company_id,owner_user_id,name,phone,source,status,create_time,update_time) VALUES(?,?,?,'计划测试',?,'登记','REGISTERED',now(),now())",id,store.orgId(),sales.userId(),String.valueOf(id%10000000000L+10000000000L));return id;}
    private long requirement(long customer){var ids=jdbc.queryForList("SELECT id FROM fr_sale_requirement WHERE customer_id=?",Long.class,customer);if(!ids.isEmpty())return ids.get(0);long id=IdWorker.getId();jdbc.update("INSERT INTO fr_sale_requirement(id,company_id,customer_id,author_user_id,version) VALUES(?,?,?,?,1)",id,store.orgId(),customer,sales.userId());return id;}
    private String key(){return "payment-plan-"+IdWorker.getId();}
    private <T>T as(PostSnapshot p,java.util.function.Supplier<T> action){currentPost.use(p);try{return action.get();}finally{currentPost.clear();}}
}
