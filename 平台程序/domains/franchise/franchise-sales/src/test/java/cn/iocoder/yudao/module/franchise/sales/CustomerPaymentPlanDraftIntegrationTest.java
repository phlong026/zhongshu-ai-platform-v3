package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@Import({CustomerPaymentPlanDraftService.class,SaleContractService.class,SaleRequirementService.class,SaleQuoteService.class,
        SalesAccess.class,SaleContractQueryAdapter.class,FranchiseCommands.class,CommandLogImpl.class,SaleContractThreeStageIntegrationTest.Config.class})
class CustomerPaymentPlanDraftIntegrationTest extends FranchisePgIntegrationTest {
    @Resource CustomerPaymentPlanDraftService drafts;
    @Resource SaleContractService contracts;
    @Resource JdbcTemplate jdbc;
    @Resource org.springframework.transaction.PlatformTransactionManager transactions;
    @org.junit.jupiter.api.AfterEach void clearTenant(){cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.clear();}
    private void tenant(PostSnapshot actor) {
        jdbc.update("INSERT INTO system_dept(id,name,status,deleted,tenant_id) VALUES(?,'草案测试公司',0,0,?) ON CONFLICT(id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id,status=0,deleted=0",actor.orgId(),actor.orgId());
        cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.setTenantId(actor.orgId());
    }
    @Test void draftRetriesAndVersionsNeverCreateReceivablesAndSignedAgreementWins() {
        var store=fixture.store();var sales=fixture.post(store.orgId(),"sales",store.salesTeamA());
        tenant(sales);
        long customer=customer(sales);String key=key();
        var plan=List.of(new PaymentPlanRules.Input(null,"签约约定",40000L,null,null,"ON_SIGN",null),new PaymentPlanRules.Input(null,"完成约定",60000L,null,null,"ON_FINISH",null));
        assertThat(as(sales,()->drafts.read(sales,customer)).revision()).isZero();
        var cmd=new CustomerPaymentPlanDraftService.Save(0,100000,plan,"客户初步沟通的付款草案");
        assertThat(as(sales,()->drafts.save(sales,customer,cmd,key)).revision()).isEqualTo(1);
        assertThat(as(sales,()->drafts.save(sales,customer,cmd," "+key+" ")).revision()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_customer_payment_plan_draft_version WHERE customer_id=?",Long.class,customer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_contract WHERE customer_id=?",Long.class,customer)).isZero();
        assertThat(as(sales,()->drafts.snapshotForEntrust(sales,customer)).source()).isEqualTo("DRAFT");
        assertThatThrownBy(()->as(sales,()->drafts.save(sales,customer,cmd,key()))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        long requirement=IdWorker.getId();jdbc.update("INSERT INTO fr_sale_requirement(id,company_id,customer_id,author_user_id,version) VALUES(?,?,?,?,1)",requirement,store.orgId(),customer,sales.userId());
        var contract=as(sales,()->contracts.create(sales,new SaleContractService.ContractCommand(customer,120000,0,0,0,null,null,requirement,
                List.of(new PaymentPlanRules.Input(null,"正式约定",120000L,null,null,"MANUAL",null))),key()));
        assertThat(as(sales,()->drafts.snapshotForEntrust(sales,customer)).source()).isEqualTo("DRAFT");
        jdbc.update("UPDATE fr_sale_contract SET status='SIGNED',signed_date=CURRENT_DATE WHERE id=?",contract.getId());
        var formal=as(sales,()->drafts.snapshotForEntrust(sales,customer));
        assertThat(formal.source()).isEqualTo("CONTRACT");assertThat(formal.totalCents()).isEqualTo(120000);assertThat(formal.contractId()).isEqualTo(contract.getId());
        var outsider=fixture.post(fixture.store().orgId(),"boss",null);
        assertThatThrownBy(()->as(outsider,()->drafts.read(outsider,customer))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        var finance=fixture.post(store.orgId(),"finance",null);
        assertThatThrownBy(()->as(finance,()->drafts.save(finance,customer,cmd,key()))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }
    @Test void wrongTenantAndDisabledCompanyCannotReadSaveOrFreezeDrafts() {
        var store=fixture.store();var boss=fixture.post(store.orgId(),"boss",null);tenant(boss);long customer=customer(boss);
        var command=new CustomerPaymentPlanDraftService.Save(0,10000,List.of(new PaymentPlanRules.Input(null,"约定",10000L,null,null,"MANUAL",null)),"客户沟通");
        as(boss,()->drafts.save(boss,customer,command,key()));
        cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.setTenantId(store.orgId()+1);
        assertThatThrownBy(()->as(boss,()->drafts.read(boss,customer))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(()->as(boss,()->drafts.save(boss,customer,command,key()))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(()->as(boss,()->drafts.snapshotForEntrust(boss,customer))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.setTenantId(store.orgId());
        jdbc.update("UPDATE system_dept SET status=1 WHERE id=?",store.orgId());
        assertThatThrownBy(()->as(boss,()->drafts.read(boss,customer))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(()->as(boss,()->drafts.snapshotForEntrust(boss,customer))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }
    @Test void entrustSnapshotLocksTheSameContractAsConcurrentPlanChangesInsideAnExistingTransaction() throws Exception {
        var store=fixture.store();var boss=fixture.post(store.orgId(),"boss",null);tenant(boss);long customer=customer(boss),requirement=IdWorker.getId();
        jdbc.update("INSERT INTO fr_sale_requirement(id,company_id,customer_id,author_user_id,version) VALUES(?,?,?,?,1)",requirement,store.orgId(),customer,boss.userId());
        var contract=as(boss,()->contracts.create(boss,new SaleContractService.ContractCommand(customer,10000,0,0,0,null,null,requirement,
                List.of(new PaymentPlanRules.Input(null,"原计划",10000L,null,null,"MANUAL",null))),key()));
        jdbc.update("UPDATE fr_sale_contract SET status='SIGNED',signed_date=CURRENT_DATE WHERE id=?",contract.getId());
        long stage=jdbc.queryForObject("SELECT id FROM fr_sale_payment_stage WHERE contract_id=?",Long.class,contract.getId());
        var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var writerStarted=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);currentPost.use(boss);
        try {
            var snapshot=pool.submit(()->{
                cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.setTenantId(store.orgId());
                try {return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{
                    var frozen=drafts.snapshotForEntrust(boss,customer);held.countDown();
                    try{if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test lock timeout");}catch(InterruptedException ex){throw new RuntimeException(ex);}
                    return frozen;
                });} finally {cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.clear();}
            });
            assertThat(held.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var write=pool.submit(()->{cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.setTenantId(store.orgId());try{writerStarted.countDown();return contracts.replacePaymentPlan(boss,contract.getId(),
                    new SaleContractService.PaymentPlanCommand(12000,List.of(new PaymentPlanRules.Input(stage,"新计划",12000L,null,null,"MANUAL",null)),jdbc.queryForObject("SELECT revision FROM fr_sale_contract WHERE id=?",Long.class,contract.getId()),"客户改约"),key());}finally{cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.clear();}});
            assertThat(writerStarted.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->write.get(300,java.util.concurrent.TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();var frozen=snapshot.get(10,java.util.concurrent.TimeUnit.SECONDS);write.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(frozen.contractPlanVersion()).isEqualTo(1);assertThat(frozen.totalCents()).isEqualTo(10000);
            assertThat(frozen.stages()).extracting(s->s.amountCents()).containsExactly(10000L);
            var latest=drafts.snapshotForEntrust(boss,customer);assertThat(latest.contractPlanVersion()).isEqualTo(2);assertThat(latest.totalCents()).isEqualTo(12000);
            assertThat(latest.stages()).extracting(s->s.amountCents()).containsExactly(12000L);
        }finally{release.countDown();pool.shutdownNow();currentPost.clear();}
    }
    private long customer(PostSnapshot p){long id=IdWorker.getId();jdbc.update("INSERT INTO fr_customer(id,company_id,owner_user_id,name,phone,source,status,create_time,update_time) VALUES(?,?,?,'草案测试',?,'登记','REGISTERED',now(),now())",id,p.orgId(),p.userId(),String.valueOf(id%10000000000L+10000000000L));return id;}
    private String key(){return "plan-draft-"+IdWorker.getId();}
    private <T>T as(PostSnapshot p,java.util.function.Supplier<T> fn){currentPost.use(p);try{return fn.get();}finally{currentPost.clear();}}
}
