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
    @Resource FranchiseCommands commands;
    @Resource SaleContractQueryAdapter query;
    @Resource JdbcTemplate jdbc;
    private Store store;
    private PostSnapshot sales,boss,finance;

    @BeforeEach void organization() {
        store=fixture.store();
        cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.setTenantId(store.orgId());
        jdbc.update("INSERT INTO system_dept(id,name,status,deleted,tenant_id) VALUES(?,'付款约定测试',0,0,?) ON CONFLICT(id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id",store.orgId(),store.orgId());
        sales=fixture.post(store.orgId(),"sales",store.salesTeamA());
        boss=fixture.post(store.orgId(),"boss",null);
        finance=fixture.post(store.orgId(),"finance",null);
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_project(id bigint primary key,company_id bigint,customer_id bigint,source_commission_id bigint,sale_contract_id bigint)");
    }

    @org.junit.jupiter.api.AfterEach void clearTenant(){cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.clear();}

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

    @Test void draftChangeIsFrozenAtSigningAndItsOriginalRequestCanStillReplay() {
        var c=create(customer(),100000,List.of(input(null,"签约款",40000),input(null,"完工款",60000)));
        var old=as(finance,()->service.stageSummary(finance,c.getId()));
        long first=((Number)old.get(0).get("stageId")).longValue(),last=((Number)old.get(1).get("stageId")).longValue();
        String key=key();
        var cmd=new SaleContractService.PaymentPlanCommand(120000,List.of(input(first,"首笔约定",50000),input(last,"完工约定",50000),input(null,"质保款",20000)),revision(c),"客户确认补充约定");
        var changed=as(finance,()->service.replacePaymentPlan(finance,c.getId(),cmd,key));
        assertThat(as(finance,()->service.replacePaymentPlan(finance,c.getId(),cmd,key)).getRevision()).isEqualTo(changed.getRevision());
        signed(c);
        long signedRevision=revision(c);
        assertThat(as(finance,()->service.replacePaymentPlan(finance,c.getId(),cmd,key)).getRevision()).isEqualTo(signedRevision);
        var receipt=as(finance,()->service.collect(finance,first,new SaleContractService.CollectCommand(20000,"bank",key(),LocalDate.now()),key()));
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
        assertThatThrownBy(()->as(finance,()->{service.changeAmounts(finance,c.getId(),new SaleContractService.ContractChange(100000,0,0,0,null,"旧端误写"),key());return true;})).hasMessageContaining("付款计划已锁定");
    }

    @Test void rejectsDeletingReceiptsReducingBelowCashOrBorrowingAnotherContractNode() {
        var c=create(customer(),100000,List.of(input(null,"节点一",50000),input(null,"节点二",50000)));
        signed(c);
        long id=((Number)as(finance,()->service.stageSummary(finance,c.getId())).get(0).get("stageId")).longValue();
        as(finance,()->service.collect(finance,id,new SaleContractService.CollectCommand(30000,"bank",key(),LocalDate.now()),key()));
        assertThatThrownBy(()->as(finance,()->service.replacePaymentPlan(finance,c.getId(),new SaleContractService.PaymentPlanCommand(100000,List.of(input(id,"原节点",10000),input(null,"新节点",90000)),revision(c),"金额调整"),key()))).hasMessageContaining("付款计划已锁定");
        assertThatThrownBy(()->as(finance,()->service.replacePaymentPlan(finance,c.getId(),new SaleContractService.PaymentPlanCommand(100000,List.of(input(null,"替换节点",100000)),revision(c),"节点调整"),key()))).hasMessageContaining("付款计划已锁定");
        var another=create(customer(),50000,List.of(input(null,"另一客户",50000)));
        long foreign=((Number)as(boss,()->service.stageSummary(boss,another.getId())).get(0).get("stageId")).longValue();
        assertThatThrownBy(()->as(boss,()->service.replacePaymentPlan(boss,c.getId(),new SaleContractService.PaymentPlanCommand(100000,List.of(input(foreign,"外来节点",100000)),revision(c),"测试越权"),key()))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(as(finance,()->service.stageCollected(finance,id))).isEqualTo(30000);
        assertThat(as(boss,()->service.paymentPlanHistory(boss,c.getId()))).hasSize(1);
    }

    @Test void allPositionsCannotAlterSignedTermsAndCrossCompanyReadsFail() {
        var c=create(customer(),100000,List.of(input(null,"付款",100000)));
        signed(c);
        long id=((Number)as(boss,()->service.stageSummary(boss,c.getId())).get(0).get("stageId")).longValue();
        var plan=new SaleContractService.PaymentPlanCommand(100000,List.of(
                new PaymentPlanRules.Input(id,"新日期",50000L,null,LocalDate.now().plusDays(7),"DATE",null),
                new PaymentPlanRules.Input(null,"新工程节点",50000L,null,null,"NODE_COMPLETED",2)),revision(c),"改期数和日期");
        for(var actor:List.of(sales,boss,finance,fixture.post(store.orgId(),"sales-lead",null),fixture.post(store.orgId(),"finance-lead",null))) {
            assertThatThrownBy(()->as(actor,()->service.replacePaymentPlan(actor,c.getId(),plan,key()))).hasMessageContaining("付款计划已锁定");
            assertThat(as(actor,()->service.paymentPlanDetail(actor,c.getId()))).containsEntry("canEdit",false);
        }
        for(var actor:List.of(fixture.post(store.orgId(),"engineer",store.engineeringTeam()),
                fixture.post(store.orgId(),"project-manager",store.engineeringTeam()),fixture.post(fixture.headquarters(),"hq-admin",null))) {
            assertThatThrownBy(()->as(actor,()->service.replacePaymentPlan(actor,c.getId(),plan,key())))
                    .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        }
        var other=fixture.store();var outsider=fixture.post(other.orgId(),"boss",null);
        assertThatThrownBy(()->as(outsider,()->service.paymentPlanHistory(outsider,c.getId()))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(()->as(outsider,()->service.stageCollected(outsider,id))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(as(outsider,()->query.findPaymentPlanById(outsider,c.getId()))).isNull();
        assertThat(as(finance,()->service.paymentPlanDetail(finance,c.getId()))).containsEntry("canEdit",false);
        assertThat(as(sales,()->service.paymentPlanDetail(sales,c.getId()))).containsEntry("canEdit",false);
    }

    @Test void retiredPaymentNodeCannotConsumeAnUnallocatedReceipt() {
        var contract=create(customer(),100000,List.of(input(null,"原付款节点",100000)));
        long retired=((Number)as(finance,()->service.stageSummary(finance,contract.getId())).get(0).get("stageId")).longValue();
        as(finance,()->service.replacePaymentPlan(finance,contract.getId(),
                new SaleContractService.PaymentPlanCommand(100000,List.of(input(null,"新付款节点",100000)),revision(contract),"改用新节点"),key()));
        signed(contract);
        long active=((Number)as(finance,()->service.stageSummary(finance,contract.getId())).get(0).get("stageId")).longValue();
        long receipt=IdWorker.getId();
        jdbc.update("INSERT INTO fr_fin_receipt(id,company_id,source_type,name,amount_cents,status,received_date,revision_version,registrar_user_id) VALUES(?,?,'manual','前期定金',20000,'已登记',CURRENT_DATE,1,?)",
                receipt,finance.orgId(),finance.userId());
        String allocationKey=key();
        assertThatThrownBy(()->as(finance,()->{service.allocateReceipt(finance,retired,receipt,allocationKey);return true;}))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(jdbc.queryForMap("SELECT source_type,source_ref FROM fr_fin_receipt WHERE id=?",receipt))
                .containsEntry("source_type","manual").containsEntry("source_ref",null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_stage_receipt WHERE receipt_id=?",Long.class,receipt)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE aggregate_type='sale-receipt-allocation' AND aggregate_id=?",Long.class,receipt)).isZero();
        as(finance,()->{service.allocateReceipt(finance,active,receipt,allocationKey);return true;});
        as(finance,()->{service.allocateReceipt(finance,active,receipt,allocationKey);return true;});
        assertThat(as(finance,()->service.stageCollected(finance,active))).isEqualTo(20000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_stage_receipt WHERE receipt_id=?",Long.class,receipt)).isEqualTo(1);
    }

    @Test void legacyConfirmedWholeStageKeepsItsReceiptEvidenceWhenTermsAreLocked() {
        long customer=customer();
        var c=as(sales,()->service.create(sales,new SaleContractService.ContractCommand(customer,100000,100000,0,0,null,null,requirement(customer)),key()));
        signed(c);
        long id=((Number)as(finance,()->service.stageSummary(finance,c.getId())).get(0).get("stageId")).longValue();
        var cash=as(finance,()->service.collect(finance,id,new SaleContractService.CollectCommand(100000,"bank",key(),LocalDate.now()),key()));
        long receipt=((Number)cash.get("receiptId")).longValue();
        jdbc.update("UPDATE fr_sale_payment_stage SET paid_receipt_id=?,paid_at=now(),pay_fingerprint='historical-whole-stage' WHERE id=?",receipt,id);
        var legacy=new SaleContractService.ContractChange(120000,0,0,10000,LocalDate.now().plusDays(90),"新增尾款");
        assertThatThrownBy(()->as(finance,()->{service.changeAmounts(finance,c.getId(),legacy,key());return true;}))
                .hasMessageContaining("付款计划已锁定");
        assertThat(jdbc.queryForMap("SELECT paid_receipt_id,pay_fingerprint FROM fr_sale_payment_stage WHERE id=?",id))
                .containsEntry("paid_receipt_id",receipt).containsEntry("pay_fingerprint","historical-whole-stage");
        assertThat(as(finance,()->service.financialStages(finance))).noneMatch(row->"tail".equals(row.get("stage")));
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

    @Test void legacyAmendmentAlreadyInTheLedgerReplaysExactlyAndNeverChangesItsOriginalCash() {
        var c=create(customer(),100000,List.of(input(null,"签约款",100000)));
        signed(c);
        var old=new SaleContractService.ContractChange(100000,0,0,0,null,"原存量约定");
        var payload=new java.util.LinkedHashMap<String,Object>();
        payload.put("change",old); payload.put("contractId",c.getId());
        String sameKey=key();
        as(finance,()->{commands.record(finance,"sale-contract",c.getId(),1,"sale-contract-change",payload,sameKey);return true;});
        long stage=((Number)as(finance,()->service.stageSummary(finance,c.getId())).get(0).get("stageId")).longValue();
        var collection=as(finance,()->service.collect(finance,stage,new SaleContractService.CollectCommand(12345,"bank",key(),LocalDate.now()),key()));
        var version=as(finance,()->service.paymentPlanDetail(finance,c.getId())).get("stages");
        as(finance,()->{service.changeAmounts(finance,c.getId(),old,sameKey);return true;});
        var other=new SaleContractService.ContractChange(90000,0,0,10000,LocalDate.now(),"修改原请求");
        assertThatThrownBy(()->as(finance,()->{service.changeAmounts(finance,c.getId(),other,sameKey);return true;}))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(()->as(finance,()->{service.changeAmounts(finance,c.getId(),other,key());return true;}))
                .hasMessageContaining("付款计划已锁定");
        assertThat(as(finance,()->service.paymentPlanDetail(finance,c.getId()))).containsEntry("stages",version).containsEntry("canEdit",false);
        assertThat(jdbc.queryForObject("SELECT receipt_id FROM fr_sale_stage_receipt WHERE stage_id=?",Long.class,stage))
                .isEqualTo(((Number)collection.get("receiptId")).longValue());
    }

    @Test void editingLegacyPlanPreservesItsMilestonesWithoutInventingNodeNumbers() {
        long customer=customer();
        var c=as(sales,()->service.create(sales,new SaleContractService.ContractCommand(customer,100000,40000,30000,30000,null,null,requirement(customer)),key()));
        var rows=as(boss,()->service.stageSummary(boss,c.getId()));
        var retained=rows.stream().map(r->new PaymentPlanRules.Input(((Number)r.get("stageId")).longValue(),
                (String)r.get("label"),((Number)r.get("amountCents")).longValue(),null,null,(String)r.get("trigger"),null)).toList();
        as(boss,()->service.replacePaymentPlan(boss,c.getId(),new SaleContractService.PaymentPlanCommand(100000,retained,revision(c),"保留原里程碑"),key()));
        assertThat(as(boss,()->service.stageSummary(boss,c.getId()))).extracting(r->r.get("stageId"))
                .containsExactlyElementsOf(rows.stream().map(r->r.get("stageId")).toList());
        var newContract=create(customer(),100000,List.of(input(null,"自定义节点",100000)));
        long newId=((Number)as(boss,()->service.stageSummary(boss,newContract.getId())).get(0).get("stageId")).longValue();
        var invalid=new PaymentPlanRules.Input(newId,"伪历史条件",100000L,null,null,"LEGACY_TOPPING",null);
        assertThatThrownBy(()->as(boss,()->service.replacePaymentPlan(boss,newContract.getId(),
                new SaleContractService.PaymentPlanCommand(100000,List.of(invalid),revision(newContract),"检查旧条件"),key())))
                .hasMessageContaining("原合同节点");
    }

    private SaleContractDO create(long customer,long total,List<PaymentPlanRules.Input> plan){return as(sales,()->service.create(sales,command(customer,total,plan),key()));}
    private SaleContractService.ContractCommand command(long customer,long total,List<PaymentPlanRules.Input> plan){return new SaleContractService.ContractCommand(customer,total,0,0,0,null,null,requirement(customer),plan);}
    private PaymentPlanRules.Input input(Long id,String name,long cents){return new PaymentPlanRules.Input(id,name,cents,null,null,"ON_SIGN",null);}
    private PaymentPlanRules.Input ratio(int bps){return new PaymentPlanRules.Input(null,"比例付款",null,bps,null,"MANUAL",null);}
    private void signed(SaleContractDO c){as(sales,()->service.sign(sales,c.getId(),new SaleContractService.SignCommand(LocalDate.now(),9001L),revision(c),key()));}
    private long revision(SaleContractDO c){return jdbc.queryForObject("SELECT revision FROM fr_sale_contract WHERE id=?",Long.class,c.getId());}
    private long customer(){long id=IdWorker.getId();jdbc.update("INSERT INTO fr_customer(id,company_id,owner_user_id,name,phone,source,status,create_time,update_time) VALUES(?,?,?,'计划测试',?,'登记','REGISTERED',now(),now())",id,store.orgId(),sales.userId(),String.valueOf(id%10000000000L+10000000000L));return id;}
    private long requirement(long customer){var ids=jdbc.queryForList("SELECT id FROM fr_sale_requirement WHERE customer_id=?",Long.class,customer);if(!ids.isEmpty())return ids.get(0);long id=IdWorker.getId();jdbc.update("INSERT INTO fr_sale_requirement(id,company_id,customer_id,author_user_id,version) VALUES(?,?,?,?,1)",id,store.orgId(),customer,sales.userId());return id;}
    private String key(){return "payment-plan-"+IdWorker.getId();}
    private <T>T as(PostSnapshot p,java.util.function.Supplier<T> action){currentPost.use(p);try{return action.get();}finally{currentPost.clear();}}
}
