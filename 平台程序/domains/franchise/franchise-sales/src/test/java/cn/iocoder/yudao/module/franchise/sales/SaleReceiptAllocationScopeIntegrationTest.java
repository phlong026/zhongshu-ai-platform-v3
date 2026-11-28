package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleContractDO;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.annotation.Resource;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.REFERENCE_INVALID;
import static cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One original cash receipt may be claimed once, without crossing its project/book identity. */
@Import({SaleContractService.class, SaleRequirementService.class, SaleQuoteService.class,
        SalesAccess.class, FranchiseCommands.class, CommandLogImpl.class,
        SaleContractThreeStageIntegrationTest.Config.class})
class SaleReceiptAllocationScopeIntegrationTest extends FranchisePgIntegrationTest {
    @Resource SaleContractService contracts;
    @Resource JdbcTemplate jdbc;
    @Resource PlatformTransactionManager transactionManager;
    private PostSnapshot sales;
    private PostSnapshot finance;

    @BeforeEach
    void prepare() {
        var store=fixture.store();
        sales=fixture.post(store.orgId(),"sales",store.salesTeamA());
        finance=fixture.post(store.orgId(),"finance",null);
        jdbc.update("INSERT INTO system_dept(id,name,status,deleted,tenant_id) VALUES(?,'收款归属测试',0,0,?) ON CONFLICT(id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id",store.orgId(),store.orgId());
        TenantContextHolder.setTenantId(store.orgId());
        // Cross-domain read contracts only; production tables belong to engineering/commission.
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_project(id bigint primary key,company_id bigint,customer_id bigint,source_commission_id bigint,sale_contract_id bigint)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_commission(id bigint primary key,company_id bigint,customer_id bigint)");
    }

    @AfterEach
    void clearTenant() { TenantContextHolder.clear(); }

    @Test
    void projectReceiptCannotPayAnotherContractEvenForTheSameCustomer() {
        long customer=customer();
        var original=signed(customer);
        long project=project(original,null);
        // Historical project retains its original contract when that customer signs a new one.
        jdbc.update("UPDATE fr_sale_contract SET status='VOIDED' WHERE id=?",original.getId());
        var next=signed(customer);
        project(next,null);
        var other=signed(customer());
        long receipt=receipt(null,project,null);
        for(var target:List.of(next,other)) {
            assertError(()->allocate(target,receipt,key()),REFERENCE_INVALID);
        }
        assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_receipt WHERE id=?",Long.class,receipt)).isEqualTo(project);
        assertUnallocated(receipt);
    }

    @Test
    void bookProjectCustomerAndCommissionAreIndependentScopeGuards() {
        var target=signed(customer());
        long targetCommission=commission(target.getCustomerId());
        project(target,targetCommission);
        var other=signed(customer());
        long otherProject=project(other,null);
        long otherCommission=commission(other.getCustomerId());
        for(long book:List.of(
                book(null,otherProject,null),
                book(other.getCustomerId(),null,null),
                book(null,null,otherCommission))) {
            long receipt=receipt(book,null,null);
            assertError(()->allocate(target,receipt,key()),REFERENCE_INVALID);
            assertUnallocated(receipt);
        }
        long foreignBook=book(null,null,null);
        jdbc.update("UPDATE fr_fin_book SET company_id=? WHERE id=?",fixture.store().orgId(),foreignBook);
        long foreignReceipt=receipt(foreignBook,null,null);
        assertError(()->allocate(target,foreignReceipt,key()),REFERENCE_INVALID);
        assertUnallocated(foreignReceipt);
    }

    @Test
    void commissionBookCannotBorrowANewContractFromTheSameCustomer() {
        long customer=customer();
        var original=signed(customer);
        long commission=commission(customer);
        project(original,commission);
        jdbc.update("UPDATE fr_sale_contract SET status='VOIDED' WHERE id=?",original.getId());
        var target=signed(customer);
        long receipt=receipt(book(customer,null,commission),null,null);
        assertError(()->allocate(target,receipt,key()),REFERENCE_INVALID);
        assertUnallocated(receipt);
    }

    @Test
    void legacyBookWithoutMetadataCannotMixAlreadyAllocatedContracts() {
        long customer=customer();
        var original=signed(customer);
        jdbc.update("UPDATE fr_sale_contract SET status='VOIDED' WHERE id=?",original.getId());
        var target=signed(customer);
        long book=book(null,null,null);
        long historical=receipt(book,null,customer);
        jdbc.update("UPDATE fr_fin_receipt SET source_type='business',source_ref=? WHERE id=?","sale-allocation:legacy:"+historical,historical);
        jdbc.update("INSERT INTO fr_sale_stage_receipt(id,company_id,contract_id,stage_id,receipt_id,amount_cents) VALUES(?,?,?,?,?,10000)",
                IdWorker.getId(),finance.orgId(),original.getId(),stage(original),historical);
        long fresh=receipt(book,null,null);
        assertError(()->allocate(target,fresh,key()),REFERENCE_INVALID);
        assertUnallocated(fresh);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_stage_receipt WHERE receipt_id=?",Long.class,historical)).isEqualTo(1);
    }

    @Test
    void matchingProjectAndUnassignedPrepaymentsKeepOneCashReceiptAndReplay() {
        var target=signed(customer());
        long commission=commission(target.getCustomerId());
        long project=project(target,commission);
        long linkedBook=book(target.getCustomerId(),project,commission);
        long preliminaryBook=book(target.getCustomerId(),null,commission);
        long legacyBook=book(null,null,null);
        for(long receipt:List.of(receipt(null,project,null),receipt(linkedBook,null,null),
                receipt(preliminaryBook,null,null),receipt(legacyBook,null,null),receipt(null,null,null))) {
            String key=key();
            allocate(target,receipt,key);
            allocate(target,receipt,key);
            assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_receipt WHERE id=?",Long.class,receipt)).isEqualTo(project);
            assertThat(jdbc.queryForObject("SELECT customer_id FROM fr_fin_receipt WHERE id=?",Long.class,receipt)).isEqualTo(target.getCustomerId());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_stage_receipt WHERE receipt_id=?",Long.class,receipt)).isEqualTo(1);
        }
        assertThat(contracts.stageCollected(finance,stage(target))).isEqualTo(50000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_receipt WHERE company_id=?",Long.class,finance.orgId())).isEqualTo(5);
    }

    @Test
    void unassignedPrepaymentBeforeProjectFilingStillWorksButExistingProjectNeedsItsRealContract() {
        var target=signed(customer());
        long commission=commission(target.getCustomerId());
        long book=book(target.getCustomerId(),null,commission);
        long unassigned=receipt(book,null,null);
        allocate(target,unassigned,key());
        assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_receipt WHERE id=?",Long.class,unassigned)).isNull();
        assertThat(contracts.stageCollected(finance,stage(target))).isEqualTo(10000);

        long historicalProject=IdWorker.getId();
        jdbc.update("INSERT INTO fr_eng_project(id,company_id,customer_id) VALUES(?,?,?)",historicalProject,finance.orgId(),target.getCustomerId());
        long scoped=receipt(null,historicalProject,null);
        assertError(()->allocate(target,scoped,key()),REFERENCE_INVALID);
        assertUnallocated(scoped);
    }

    @Test
    void allocationWaitsForWholeBookLinkAndChecksTheCommittedScope() throws Exception {
        var target=signed(customer());
        project(target,null);
        var other=signed(customer());
        long otherProject=project(other,null);
        long book=book(null,null,null);
        long receipt=receipt(book,null,null);
        var bookLocked=new CountDownLatch(1);
        var finishLink=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var linking=pool.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM fr_fin_book WHERE id=? FOR UPDATE",Long.class,book);
                jdbc.update("UPDATE fr_fin_book SET project_id=? WHERE id=?",otherProject,book);
                bookLocked.countDown();
                try {
                    if(!finishLink.await(10,TimeUnit.SECONDS))throw new AssertionError("book link was not released");
                } catch(InterruptedException e) { throw new RuntimeException(e); }
                jdbc.update("UPDATE fr_fin_receipt SET project_id=? WHERE book_id=?",otherProject,book);
            }));
            assertThat(bookLocked.await(10,TimeUnit.SECONDS)).isTrue();
            var allocation=pool.submit(()->{
                TenantContextHolder.setTenantId(finance.orgId());
                try { assertError(()->allocate(target,receipt,key()),REFERENCE_INVALID); }
                finally { TenantContextHolder.clear(); }
            });
            try {
                assertThatThrownBy(()->allocation.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { finishLink.countDown(); }
            linking.get(10,TimeUnit.SECONDS);
            allocation.get(10,TimeUnit.SECONDS);
        } finally { finishLink.countDown(); pool.shutdownNow(); }
        assertUnallocated(receipt);
        assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_receipt WHERE id=?",Long.class,receipt)).isEqualTo(otherProject);
    }

    private long customer() {
        long id=IdWorker.getId();
        jdbc.update("INSERT INTO fr_customer(id,company_id,owner_user_id,name,phone,source,status) VALUES(?,?,?,'陈先生',?,'视频号咨询','REGISTERED')",
                id,finance.orgId(),sales.userId(),"19"+Long.toString(id).substring(9));
        return id;
    }

    private SaleContractDO signed(long customer) {
        long requirement=IdWorker.getId();
        jdbc.update("INSERT INTO fr_sale_requirement(id,company_id,customer_id,author_user_id,version,note) VALUES(?,?,?,?,1,'建房需求') ON CONFLICT DO NOTHING",
                requirement,finance.orgId(),customer,sales.userId());
        requirement=jdbc.queryForObject("SELECT id FROM fr_sale_requirement WHERE customer_id=? ORDER BY id LIMIT 1",Long.class,customer);
        currentPost.use(sales);
        try {
            var contract=contracts.create(sales,new SaleContractService.ContractCommand(customer,100000,100000,0,0,LocalDate.now(),null,requirement),key());
            long revision=jdbc.queryForObject("SELECT revision FROM fr_sale_contract WHERE id=?",Long.class,contract.getId());
            contracts.sign(sales,contract.getId(),new SaleContractService.SignCommand(LocalDate.now(),9001L),revision,key());
            return contract;
        } finally { currentPost.clear(); }
    }

    private long project(SaleContractDO contract,Long commission) {
        long id=IdWorker.getId();
        jdbc.update("INSERT INTO fr_eng_project(id,company_id,customer_id,source_commission_id,sale_contract_id) VALUES(?,?,?,?,?)",
                id,finance.orgId(),contract.getCustomerId(),commission,contract.getId());
        return id;
    }

    private long commission(long customer) {
        long id=IdWorker.getId();
        jdbc.update("INSERT INTO fr_commission(id,company_id,customer_id) VALUES(?,?,?)",id,finance.orgId(),customer);
        return id;
    }

    private long book(Long customer,Long project,Long commission) {
        long id=IdWorker.getId();
        jdbc.update("INSERT INTO fr_fin_book(id,company_id,name,customer_id,project_id,commission_id) VALUES(?,?,'前期账本',?,?,?)",
                id,finance.orgId(),customer,project,commission);
        return id;
    }

    private long receipt(Long book,Long project,Long customer) {
        long id=IdWorker.getId();
        jdbc.update("INSERT INTO fr_fin_receipt(id,company_id,source_type,name,amount_cents,status,received_date,revision_version,registrar_user_id,book_id,project_id,customer_id) VALUES(?,?,'manual','前期定金',10000,'已登记',CURRENT_DATE,1,?,?,?,?)",
                id,finance.orgId(),finance.userId(),book,project,customer);
        return id;
    }

    private long stage(SaleContractDO contract) {
        return jdbc.queryForObject("SELECT id FROM fr_sale_payment_stage WHERE contract_id=? AND stage='SIGN_AFTER'",Long.class,contract.getId());
    }

    private void allocate(SaleContractDO contract,long receipt,String key) {
        currentPost.use(finance);
        try { contracts.allocateReceipt(finance,stage(contract),receipt,key); }
        finally { currentPost.clear(); }
    }

    private void assertUnallocated(long receipt) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_stage_receipt WHERE receipt_id=?",Long.class,receipt)).isZero();
        assertThat(jdbc.queryForObject("SELECT source_type FROM fr_fin_receipt WHERE id=?",String.class,receipt)).isEqualTo("manual");
    }

    private String key() { return "allocation-"+IdWorker.getId(); }
}
