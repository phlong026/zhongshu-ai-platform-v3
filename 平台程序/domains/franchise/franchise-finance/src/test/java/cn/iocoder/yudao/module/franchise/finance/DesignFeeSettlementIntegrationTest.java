package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.module.franchise.api.CommissionQueryPort;
import cn.iocoder.yudao.module.franchise.api.DesignFeeSettlementPort;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

@Import({DesignFeeSettlementAdapter.class,DesignFinanceLocks.class,FranchiseCommands.class,CommandLogImpl.class,
        FinReceiptService.class,FinPaymentService.class,FinRefundService.class,DesignFeeActionService.class,FinProjectLedgerService.class,EngineeringLedgerQueryAdapter.class,FinBankService.class,FinAttemptRecorder.class,
        DesignFeeSettlementIntegrationTest.Config.class})
class DesignFeeSettlementIntegrationTest extends FranchisePgIntegrationTest {
    static long storeOrg,headquartersOrg;
    static final java.util.Map<Long,CommissionQueryPort.CommissionFeeView> OVERRIDES=new java.util.HashMap<>();
    @TestConfiguration static class Config {
        @Bean CommissionQueryPort commissions() {
            return id -> OVERRIDES.getOrDefault(id,new CommissionQueryPort.CommissionFeeView(id,storeOrg,headquartersOrg,1,"DESIGNING",10000,0,
                    DesignFeeSettlementPort.Mode.AREA_FULL,321,1,1,new java.math.BigDecimal("100"),100L,"总部设计"));
        }
        @Bean cn.iocoder.yudao.module.franchise.api.AssetAccessPort assets() {return org.mockito.Mockito.mock(cn.iocoder.yudao.module.franchise.api.AssetAccessPort.class);}
        @Bean cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort accounts() {return org->1;}
    }
    @Resource DesignFeeSettlementPort settlement;
    @Resource PlatformTransactionManager transactionManager;
    @Resource JdbcTemplate jdbc;
    @Resource FinPaymentService payments;
    @Resource FinReceiptService receipts;
    @Resource FinRefundService refunds;
    @Resource DesignFeeActionService actions;
    @Resource FinProjectLedgerService ledger;
    @Resource EngineeringLedgerQueryAdapter engineeringLedger;
    @Resource FinBankService bank;
    @org.junit.jupiter.api.BeforeEach void organizations() { storeOrg=fixture.store().orgId();headquartersOrg=fixture.headquarters(); }

    @Test void mandatoryRefusesUnscopedTransactionAndOuterFailureRollsBack() {
        var actor=fixture.post(storeOrg,"finance",null);
        assertThatThrownBy(()->settlement.ensureInitialPayable(5001,1,actor))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        var tx=new TransactionTemplate(transactionManager);
        assertThatThrownBy(()->tx.execute(status->{settlement.ensureInitialPayable(5001,1,actor);throw new IllegalStateException("rollback");}))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payable WHERE source_ref='5001:1:FULL'",Long.class)).isZero();
    }

    @Test void fullPayableIsUniqueAndBothLiveNetsAreRequired() {
        var actor=fixture.post(storeOrg,"finance",null);
        new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            settlement.ensureInitialPayable(5002,1,actor);
            settlement.ensureInitialPayable(5002,1,actor);
        });
        long payable=jdbc.queryForObject("SELECT id FROM fr_fin_payable WHERE source_ref='5002:1:FULL'",Long.class);
        assertThat(settlement.read(5002,1).stages()).hasSize(1);
        assertThat(settlement.read(5002,1).readyToComplete()).isFalse();
        jdbc.update("INSERT INTO fr_fin_payment(id,payable_id,company_id,amount_cents,status,operator_user_id) VALUES(7002,?,?,10000,'已核实',1)",payable,storeOrg);
        assertThat(settlement.read(5002,1).readyToComplete()).isFalse();
        jdbc.update("INSERT INTO fr_fin_receipt(id,company_id,source_type,source_ref,origin_payment_id,name,amount_cents,status,registrar_user_id) VALUES(8002,?,'design','5002:1:FULL',7002,'test',10000,'已登记',1)",headquartersOrg);
        assertThat(settlement.read(5002,1).readyToDispatch()).isTrue();
        jdbc.update("INSERT INTO fr_fin_refund(id,receipt_id,company_id,amount_cents,status,reason,operator_user_id) VALUES(9002,8002,?,1,'已核实','test',1)",headquartersOrg);
        assertThat(settlement.read(5002,1).readyToDispatch()).isFalse();
    }

    @Test void realPartialReceiptsAreBoundToOriginAndNetRefundsReopenGate() {
        var store=fixture.post(storeOrg,"finance",null); var hq=fixture.post(headquartersOrg,"finance",null);
        long payable=payable(5010,store);
        long payment=payments.register(store,"payment-5010",new FinPaymentService.PaymentCommand(payable,10000,java.time.LocalDate.now(),"store5010",null));
        long first=receipts.register(hq,"receipt5010a",receipt(5010,payment,4000));
        long second=receipts.register(hq,"receipt5010b",receipt(5010,payment,6000));
        assertThat(settlement.read(5010,1).readyToComplete()).isTrue();
        assertThatThrownBy(()->receipts.register(hq,"excess5010",receipt(5010,payment,1))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(()->receipts.register(hq,"forged5010",receipt(5011,payment,1))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(()->receipts.revision(hq,first,1,4001,"超收","rev5010")).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        long refund=refunds.register(hq,"refund5010",new FinRefundService.RegisterCommand(second,100,"测试退款",java.time.LocalDate.now(),null,null,null));
        assertThat(settlement.read(5010,1).readyToDispatch()).isFalse();
        assertThatThrownBy(()->refunds.void_(hq,refund,"恢复",null)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        refunds.void_(hq,refund,"恢复","voidRefund5010");
        assertThat(settlement.read(5010,1).readyToDispatch()).isTrue();
        OVERRIDES.put(5010L,new CommissionQueryPort.CommissionFeeView(5010,storeOrg,headquartersOrg,1,"DISPATCHED",10000,0,
                DesignFeeSettlementPort.Mode.AREA_FULL,321,1,1,new java.math.BigDecimal("100"),100L,"总部设计"));
        long expense=refunds.registerExpense(store,"expense5010",new FinRefundService.ExpenseRefundCommand(payment,200,"设计退回",java.time.LocalDate.now(),null,null,null));
        assertThat(settlement.read(5010,1).stages().get(0).storePaidNetCents()).isEqualTo(9800);
        assertThat(settlement.read(5010,1).readyToComplete()).isFalse();
        assertThat(engineeringLedger.paidCents(store,payable)).isEqualTo(9800);
        assertThat(engineeringLedger.paidCents(hq,payable)).isZero();
        assertThat(actions.page(headquartersOrg,1,100).getList().stream().filter(i->i.commissionId()==5010).findFirst().orElseThrow().payments().get(0).receivableCents()).isZero();
        var exception=actions.page(headquartersOrg,1,100).getList().stream().filter(i->i.commissionId()==5010).findFirst().orElseThrow();
        assertThat(exception.originPaymentOverReceivedCents()).isEqualTo(200);assertThat(exception.financialException()).isTrue();
        refunds.register(hq,"refund5010correction",new FinRefundService.RegisterCommand(second,200,"核收更正",java.time.LocalDate.now(),null,null,null));
        long replacement=payments.register(store,"payment5010replacement",new FinPaymentService.PaymentCommand(payable,200,java.time.LocalDate.now(),null,null));
        receipts.register(hq,"receipt5010replacement",receipt(5010,replacement,200));
        var corrected=actions.page(headquartersOrg,1,100).getList().stream().filter(i->i.commissionId()==5010).findFirst().orElseThrow();
        assertThat(corrected.financialException()).isFalse();assertThat(corrected.originPaymentOverReceivedCents()).isZero();
        assertThat(settlement.read(5010,1).readyToDispatch()).isTrue();
        assertThatThrownBy(()->refunds.void_(store,expense,"补付后不能恢复超额付款","voidExpense5010")).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE fr_fin_payable SET customer_id=99 WHERE id=?",payable)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void legacyFirstCompletesButTailAndBothSidesAreRequiredForDispatch() {
        OVERRIDES.put(5020L,new CommissionQueryPort.CommissionFeeView(5020,storeOrg,headquartersOrg,1,"DESIGNING",10000,4000));
        var store=fixture.post(storeOrg,"finance",null);
        long first=payable(5020,store);
        payments.register(store,"legacy5020",new FinPaymentService.PaymentCommand(first,4000,java.time.LocalDate.now(),null,null));
        assertThat(settlement.read(5020,1).readyToComplete()).isTrue();
        assertThat(settlement.read(5020,1).readyToDispatch()).isFalse();
        new TransactionTemplate(transactionManager).executeWithoutResult(s->settlement.ensureTailPayable(5020,1,store));
        assertThat(jdbc.queryForObject("SELECT total_cents FROM fr_fin_payable WHERE source_ref='5020:1:TAIL'",Long.class)).isEqualTo(6000);
    }

    @Test void sameKeyConcurrentReceiptReplaysOneActualPartialAmount() throws Exception {
        var store=fixture.post(storeOrg,"finance",null); var hq=fixture.post(headquartersOrg,"finance",null);
        long payment=payments.register(store,"payment5030",new FinPaymentService.PaymentCommand(payable(5030,store),10000,java.time.LocalDate.now(),null,null));
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2); var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var left=executor.submit(()->{start.await();return receipts.register(hq,"same5030",receipt(5030,payment,6000));});
            var right=executor.submit(()->{start.await();return receipts.register(hq,"same5030",receipt(5030,payment,6000));});
            start.countDown(); assertThat(left.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(right.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(jdbc.queryForObject("SELECT sum(amount_cents) FROM fr_fin_receipt WHERE origin_payment_id=?",Long.class,payment)).isEqualTo(6000);
        } finally {executor.shutdownNow();}
    }

    @Test void pendingApprovalIsReadableButNeverReady() {
        OVERRIDES.put(5040L,new CommissionQueryPort.CommissionFeeView(5040,storeOrg,headquartersOrg,1,"FEE_PENDING",10000,4000));
        assertThat(settlement.read(5040,1).readyToComplete()).isFalse();
        assertThatThrownBy(()->payable(5040,fixture.post(storeOrg,"finance",null))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }

    @Test void concurrentDistinctReceiptsCannotOverCollectOriginPayment() throws Exception {
        var store=fixture.post(storeOrg,"finance",null); var hq=fixture.post(headquartersOrg,"finance",null);
        long payment=payments.register(store,"payment5031",new FinPaymentService.PaymentCommand(payable(5031,store),10000,java.time.LocalDate.now(),null,null));
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2); var start=new java.util.concurrent.CountDownLatch(1);
        var outcomes=new java.util.ArrayList<java.util.concurrent.Future<Object>>();
        try {
            for(int i=0;i<2;i++) {final int index=i;outcomes.add(executor.submit(()->{start.await();try{return receipts.register(hq,"different5031-"+index,receipt(5031,payment,6000));}catch(cn.iocoder.yudao.framework.common.exception.ServiceException e){return e;}}));}
            start.countDown();int failures=0;
            for(var future:outcomes) if(future.get(10,java.util.concurrent.TimeUnit.SECONDS) instanceof cn.iocoder.yudao.framework.common.exception.ServiceException) failures++;
            assertThat(failures).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT sum(amount_cents) FROM fr_fin_receipt WHERE origin_payment_id=?",Long.class,payment)).isEqualTo(6000);
        } finally {executor.shutdownNow();}
    }

    @Test void ledgerIncludesPreProjectDesignCostsAndPagesSameScopedSummary() {
        var store=fixture.post(storeOrg,"finance",null);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx->{
            // 财务模块不依赖工程实现；事务内临时表只提供工程Port既有持久化列，测试查询范围而非工程迁移。
            jdbc.execute("CREATE TEMP TABLE fr_eng_project(id bigint,company_id bigint,source_commission_id bigint,sale_contract_id bigint,name text,status text,create_time timestamptz DEFAULT now()) ON COMMIT DROP");
            jdbc.execute("CREATE TEMP TABLE fr_sale_stage_receipt(company_id bigint,contract_id bigint,receipt_id bigint,stage_id bigint) ON COMMIT DROP");
            jdbc.execute("CREATE TEMP TABLE fr_eng_collection(company_id bigint,project_id bigint,receipt_id bigint) ON COMMIT DROP");
            jdbc.execute("CREATE TEMP TABLE fr_eng_expense(company_id bigint,project_id bigint,amount_cents bigint,payable_id bigint,status varchar(16)) ON COMMIT DROP");
            jdbc.execute("CREATE TEMP TABLE fr_sale_contract(id bigint,company_id bigint,status text,deleted boolean DEFAULT false,signed_date date) ON COMMIT DROP");
            jdbc.execute("CREATE TEMP TABLE fr_sale_payment_stage(id bigint,company_id bigint,contract_id bigint,stage text,amount_cents bigint,status text,deleted boolean DEFAULT false) ON COMMIT DROP");
            jdbc.update("INSERT INTO fr_eng_project(id,company_id,source_commission_id,sale_contract_id,name,status) VALUES(50501,?,5050,888,'test','IN_PROGRESS')",storeOrg);
            jdbc.update("INSERT INTO fr_sale_contract(id,company_id,status,signed_date) VALUES(888,?,'SIGNED',CURRENT_DATE)",storeOrg);
            jdbc.update("INSERT INTO fr_sale_payment_stage(id,company_id,contract_id,stage,amount_cents,status) VALUES(50511,?,888,'SIGN_AFTER',50000,'SETTLED'),(50512,?,888,'BASE_DONE',30000,'OPEN'),(50513,?,888,'CAP_DONE',20000,'PENDING'),(50514,?,888,'CAP_DONE',0,'PENDING'),(50515,?,888,'BASE_DONE',999999,'PENDING')",storeOrg,storeOrg,storeOrg,storeOrg,storeOrg+1);
            long payment=payments.register(store,"payment5050",new FinPaymentService.PaymentCommand(payable(5050,store),10000,java.time.LocalDate.now(),null,null));
            refunds.registerExpense(store,"expense5050",new FinRefundService.ExpenseRefundCommand(payment,200,"退回",java.time.LocalDate.now(),null,null,null));
            long income=receipts.register(store,"income5050",new FinReceiptService.RegisterCommand("manual",null,"工程回款",50000,java.time.LocalDate.now(),"bank",null,null,null,null,null,null));
            jdbc.update("INSERT INTO fr_sale_stage_receipt VALUES(?,888,?,50511)",storeOrg,income);
            jdbc.update("INSERT INTO fr_eng_collection VALUES(?,50501,?)",storeOrg,income);
            long prior=receipts.register(store,"prior5050",new FinReceiptService.RegisterCommand("manual",null,"工程建立前首期",10000,java.time.LocalDate.now(),"bank",null,null,null,null,null,null));
            jdbc.update("INSERT INTO fr_sale_stage_receipt VALUES(?,888,?,50512)",storeOrg,prior);
            long unrelated=receipts.register(store,"unrelated5050",new FinReceiptService.RegisterCommand("manual",null,"同组织另一合同",90000,java.time.LocalDate.now(),"bank",null,null,null,null,null,null));
            jdbc.update("INSERT INTO fr_sale_stage_receipt VALUES(?,999,?,0)",storeOrg,unrelated);
            jdbc.update("INSERT INTO fr_fin_receipt(id,company_id,project_id,name,source_type,amount_cents,status,registrar_user_id) VALUES(50599,?,50501,'同源预期应收','intercompany',50000,'待登记',1)",storeOrg);
            jdbc.update("INSERT INTO fr_sale_stage_receipt VALUES(?,888,50599,50511)",storeOrg);
            refunds.register(store,"refund5050",new FinRefundService.RegisterCommand(income,1000,"退还",java.time.LocalDate.now(),null,null,null));
            var first=ledger.page(storeOrg,50501,1,3);var second=ledger.page(storeOrg,50501,2,3);var third=ledger.page(storeOrg,50501,3,3);
            assertThat(first.summary()).isEqualTo(new FinProjectLedgerService.Summary(100000,60000,1000,10000,200,10000));
            assertThat(second.summary()).isEqualTo(first.summary());assertThat(third.summary()).isEqualTo(first.summary());
            var ids=new java.util.HashSet<Long>();for(var page:java.util.List.of(first,second,third)) for(var row:page.entries().getList()) assertThat(ids.add(row.id())).isTrue();
            assertThat(ids).hasSize(9);assertThat(first.entries().getTotal()).isEqualTo(9);
            assertThat(jdbc.queryForObject("SELECT status FROM fr_sale_contract WHERE id=888",String.class)).isEqualTo("SIGNED");
            jdbc.update("UPDATE fr_sale_contract SET status='VOIDED' WHERE id=888");
            assertThat(ledger.page(storeOrg,50501,1,20).summary().receivableCents()).isZero();
            assertThat(ledger.projects(storeOrg,1,10,"test").getTotal()).isEqualTo(1);
            assertThat(ledger.projects(storeOrg,1,10,"missing").getTotal()).isZero();
            assertThatThrownBy(()->new FinProjectLedgerService(jdbc).page(storeOrg+1,50501,1,2)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        });
    }

    @Test void mandatoryLockingReadRetainsStageLockUntilOuterCommit() throws Exception {
        var store=fixture.post(storeOrg,"finance",null);var hq=fixture.post(headquartersOrg,"finance",null);
        long payment=payments.register(store,"payment5060",new FinPaymentService.PaymentCommand(payable(5060,store),10000,java.time.LocalDate.now(),null,null));
        long receipt=receipts.register(hq,"receipt5060",receipt(5060,payment,10000));
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try {
            var gate=executor.submit(()->new TransactionTemplate(transactionManager).execute(tx->{
                boolean ready=settlement.lockAndRead(5060,1,hq).readyToDispatch();locked.countDown();
                try {if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test gate timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return ready;
            }));
            assertThat(locked.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var refund=executor.submit(()->refunds.register(hq,"refund5060",new FinRefundService.RegisterCommand(receipt,1,"并发退款",java.time.LocalDate.now(),null,null,null)));
            boolean waiting=false;
            for(int i=0;i<100;i++) {
                if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND NOT granted)",Boolean.class)){waiting=true;break;}
                Thread.sleep(20);
            }
            assertThat(waiting).as("退款应等待外层业务事务持有的阶段锁").isTrue();
            assertThat(refund.isDone()).isFalse();release.countDown();assertThat(gate.get(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(refund.get(5,java.util.concurrent.TimeUnit.SECONDS)).isPositive();
            assertThat(settlement.read(5060,1).readyToDispatch()).isFalse();
        } finally {release.countDown();executor.shutdownNow();}
    }

    @Test void nonPositiveRefundsCannotInflateNetSettlementOrPersistCash() {
        var store=fixture.post(storeOrg,"finance",null);var hq=fixture.post(headquartersOrg,"finance",null);
        long payment=payments.register(store,"payment5070",new FinPaymentService.PaymentCommand(payable(5070,store),10000,java.time.LocalDate.now(),null,null));
        long receipt=receipts.register(hq,"receipt5070",receipt(5070,payment,5000));
        var before=settlement.read(5070,1);
        assertThat(before.readyToComplete()).isFalse();
        for(long amount:new long[]{0,-1,-5000}) {
            assertThatThrownBy(()->refunds.register(hq,"invalidClient5070-"+amount,new FinRefundService.RegisterCommand(receipt,amount,"拒绝负款",java.time.LocalDate.now(),null,null,null)))
                    .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
            assertThatThrownBy(()->refunds.registerExpense(store,"invalidExpense5070-"+amount,new FinRefundService.ExpenseRefundCommand(payment,amount,"拒绝负款",java.time.LocalDate.now(),null,null,null)))
                    .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        }
        assertThat(settlement.read(5070,1)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_refund WHERE receipt_id=? OR payment_id=?",Long.class,receipt,payment)).isZero();
    }

    @Test void replacementPaymentCannotReuseOrphanedReceiptAndConcurrentCorrectionsRestoreReadiness() throws Exception {
        var store=fixture.post(storeOrg,"finance",null);var hq=fixture.post(headquartersOrg,"finance",null);
        long payable=payable(5080,store);
        long original=payments.register(store,"payment5080original",new FinPaymentService.PaymentCommand(payable,10000,java.time.LocalDate.now(),null,null));
        long receipt=receipts.register(hq,"receipt5080original",receipt(5080,original,10000));
        refunds.registerExpense(store,"expense5080",new FinRefundService.ExpenseRefundCommand(original,10000,"真实支出退回",java.time.LocalDate.now(),null,null,null));
        long replacement=payments.register(store,"payment5080replacement",new FinPaymentService.PaymentCommand(payable,10000,java.time.LocalDate.now(),null,null));
        var stale=settlement.read(5080,1).stages().get(0);
        assertThat(stale.storePaidNetCents()).isEqualTo(10000);assertThat(stale.headquartersNetCents()).isEqualTo(10000);
        assertThat(stale.storeReady()).isTrue();assertThat(stale.headquartersReady()).isFalse();
        assertThat(settlement.read(5080,1).readyToComplete()).isFalse();assertThat(settlement.read(5080,1).readyToDispatch()).isFalse();
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var correction=executor.submit(()->{start.await();return refunds.register(hq,"refund5080original",new FinRefundService.RegisterCommand(receipt,10000,"原核收更正退款",java.time.LocalDate.now(),null,null,null));});
            var newReceipt=executor.submit(()->{start.await();return receipts.register(hq,"receipt5080replacement",receipt(5080,replacement,10000));});
            start.countDown();assertThat(correction.get(10,java.util.concurrent.TimeUnit.SECONDS)).isPositive();assertThat(newReceipt.get(10,java.util.concurrent.TimeUnit.SECONDS)).isPositive();
        } finally {executor.shutdownNow();}
        assertThat(settlement.read(5080,1).readyToDispatch()).isTrue();
        assertThat(jdbc.queryForObject("SELECT amount_cents FROM fr_fin_receipt WHERE id=?",Long.class,receipt)).isEqualTo(10000);
        assertThatThrownBy(()->payments.void_(store,replacement,"不能留下孤儿核收","void5080"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class)
                .extracting(e->((cn.iocoder.yudao.framework.common.exception.ServiceException)e).getCode())
                .isEqualTo(cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.PAYMENT_HAS_DESIGN_RECEIPT.getCode());
    }

    @Test void paymentTransactionIdentityReplaysAndSurvivesVoid() {
        var store=fixture.post(storeOrg,"finance",null);long payable=payable(5090,store);
        var command=new FinPaymentService.PaymentCommand(payable,5000,java.time.LocalDate.now(),"bank5090",null);
        long payment=payments.register(store,"payment5090",command);
        assertThat(payments.register(store,"payment5090",command)).isEqualTo(payment);
        assertThatThrownBy(()->payments.register(store,"duplicate5090",command))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        payments.void_(store,payment,"原单更正","void5090");
        assertThatThrownBy(()->payments.register(store,"reused5090",command))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_txn_no WHERE company_id=? AND txn_no='bank5090' AND owner_type='payment'",Long.class,storeOrg)).isEqualTo(1);
        var other=fixture.post(fixture.store().orgId(),"finance",null);
        long otherPayable=payments.registerPayable(other,"other5090",new FinPaymentService.PayableCommand(other.orgId(),null,"材料",10000,"供应商",null));
        assertThat(payments.register(other,"otherBank5090",new FinPaymentService.PaymentCommand(otherPayable,5000,java.time.LocalDate.now(),"bank5090",null))).isPositive();
        long blankPayable=payments.registerPayable(store,"blank5090",new FinPaymentService.PayableCommand(store.orgId(),null,"劳务",10000,"施工班组",null));
        assertThat(payments.register(store,"blank5090a",new FinPaymentService.PaymentCommand(blankPayable,5000,java.time.LocalDate.now(),null,null))).isPositive();
        assertThat(payments.register(store,"blank5090b",new FinPaymentService.PaymentCommand(blankPayable,5000,java.time.LocalDate.now()," ",null))).isPositive();
        long legacyPayable=payments.registerPayable(store,"legacyTxn5090",new FinPaymentService.PayableCommand(store.orgId(),null,"材料",5000,"历史供应商",null));
        jdbc.update("INSERT INTO fr_fin_payment(id,payable_id,company_id,amount_cents,status,operator_user_id,transaction_no) VALUES(50909,?,?,1000,'已核实',?,'legacy5090')",legacyPayable,storeOrg,store.userId());
        assertThatThrownBy(()->payments.register(store,"legacyDuplicate5090",new FinPaymentService.PaymentCommand(legacyPayable,1000,java.time.LocalDate.now(),"legacy5090",null)))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }

    @Test void concurrentPaymentsCannotClaimSameOrganizationBankTransaction() throws Exception {
        var store=fixture.post(storeOrg,"finance",null);long leftPayable=payable(5091,store),rightPayable=payable(5092,store);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        var outcomes=new java.util.ArrayList<java.util.concurrent.Future<Object>>();
        try {
            for(long payable:java.util.List.of(leftPayable,rightPayable)) outcomes.add(executor.submit(()->{
                start.await();try{return payments.register(store,"bank5091-"+payable,new FinPaymentService.PaymentCommand(payable,5000,java.time.LocalDate.now(),"bank5091",null));}
                catch(cn.iocoder.yudao.framework.common.exception.ServiceException e){return e;}
            }));
            start.countDown();int rejected=0;
            for(var future:outcomes) {var outcome=future.get(10,java.util.concurrent.TimeUnit.SECONDS);if(outcome instanceof cn.iocoder.yudao.framework.common.exception.ServiceException error){assertThat(error.getCode()).isEqualTo(cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.TXN_NO_OCCUPIED.getCode());rejected++;}}
            assertThat(rejected).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payment WHERE company_id=? AND transaction_no='bank5091'",Long.class,storeOrg)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_txn_no WHERE company_id=? AND txn_no='bank5091'",Long.class,storeOrg)).isEqualTo(1);
        } finally {executor.shutdownNow();}
    }

    @Test void legacyPaymentWithoutIdentityBlocksAllReceiptAndRefundReuseEvenAfterVoid() {
        var store=fixture.post(storeOrg,"finance",null);
        long payable=payments.registerPayable(store,"legacy5100",new FinPaymentService.PayableCommand(store.orgId(),null,"材料",10000,"历史供应商",null));
        jdbc.update("INSERT INTO fr_fin_payment(id,payable_id,company_id,amount_cents,status,operator_user_id,transaction_no) VALUES(51009,?,?,5000,'已核实',?,'legacy5100')",payable,storeOrg,store.userId());
        long payment=payments.register(store,"validPayment5100",new FinPaymentService.PaymentCommand(payable,5000,java.time.LocalDate.now(),null,null));
        long receipt=receipts.register(store,"validReceipt5100",new FinReceiptService.RegisterCommand("manual",null,"可退款原单",10000,java.time.LocalDate.now(),"bank",null,null,null,null,null,null));
        long flow=bank.injectFlow(store,storeOrg," legacy5100 ",1000,java.time.LocalDate.now(),"e2e");
        long pending=bank.injectPendingReceipt(storeOrg,1000,"跨端预期");
        for(int state=0;state<2;state++) {
            final int pass=state;
            var tx=new TransactionTemplate(transactionManager);
            Throwable receiptError=tx.execute(status->{var error=catchThrowable(()->receipts.register(store,"legacyReceipt5100-"+pass,new FinReceiptService.RegisterCommand("manual",null,"不得复用",1000,java.time.LocalDate.now(),"bank"," legacy5100 ",null,null,null,null,null)));status.setRollbackOnly();return error;});
            Throwable refundError=tx.execute(status->{var error=catchThrowable(()->refunds.register(store,"legacyRefund5100-"+pass,new FinRefundService.RegisterCommand(receipt,1000,"不得复用",java.time.LocalDate.now()," legacy5100 ",null,null)));status.setRollbackOnly();return error;});
            Throwable expenseError=tx.execute(status->{var error=catchThrowable(()->refunds.registerExpense(store,"legacyExpense5100-"+pass,new FinRefundService.ExpenseRefundCommand(payment,1000,"不得复用",java.time.LocalDate.now()," legacy5100 ",null,null)));status.setRollbackOnly();return error;});
            Throwable bankError=tx.execute(status->{var error=catchThrowable(()->bank.confirm(store,"legacyBank5100-"+pass,flow,pending));status.setRollbackOnly();return error;});
            org.assertj.core.api.SoftAssertions.assertSoftly(soft->{
                for(Throwable error:new Throwable[]{receiptError,refundError,expenseError,bankError}) {
                    soft.assertThat(error).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
                    if(error instanceof cn.iocoder.yudao.framework.common.exception.ServiceException serviceError)
                        soft.assertThat(serviceError.getCode()).isEqualTo(cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.TXN_NO_OCCUPIED.getCode());
                }
            });
            if(state==0) payments.void_(store,51009,"旧付款作废也永久占号");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_txn_no WHERE company_id=? AND txn_no='legacy5100'",Long.class,storeOrg)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_refund WHERE company_id=?",Long.class,storeOrg)).isZero();
    }

    @Test void confirmedBankReceiptAlsoReservesPermanentCrossTypeIdentity() {
        var store=fixture.post(storeOrg,"finance",null);
        long flow=bank.injectFlow(store,storeOrg,"bank5110",1000,java.time.LocalDate.now(),"e2e");
        long pending=bank.injectPendingReceipt(storeOrg,1000,"跨端预期");
        assertThat(bank.confirm(store,"bankConfirm5110",flow,pending)).isPositive();
        assertThat(jdbc.queryForObject("SELECT owner_id FROM fr_fin_txn_no WHERE company_id=? AND txn_no='bank5110'",Long.class,storeOrg)).isEqualTo(pending);
        long payable=payments.registerPayable(store,"bankPayable5110",new FinPaymentService.PayableCommand(storeOrg,null,"材料",10000,"供应商",null));
        assertThatThrownBy(()->payments.register(store,"bankPayment5110",new FinPaymentService.PaymentCommand(payable,1000,java.time.LocalDate.now(),"bank5110",null)))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(()->refunds.register(store,"bankRefund5110",new FinRefundService.RegisterCommand(pending,100,"不得复用",java.time.LocalDate.now(),"bank5110",null,null)))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        long payment=payments.register(store,"validBankPayment5110",new FinPaymentService.PaymentCommand(payable,1000,java.time.LocalDate.now(),null,null));
        assertThatThrownBy(()->refunds.registerExpense(store,"bankExpense5110",new FinRefundService.ExpenseRefundCommand(payment,100,"不得复用",java.time.LocalDate.now(),"bank5110",null,null)))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_txn_no WHERE company_id=? AND txn_no='bank5110'",Long.class,storeOrg)).isEqualTo(1);
    }

    private long payable(long id,cn.iocoder.yudao.module.platform.org.PostSnapshot actor) {
        new TransactionTemplate(transactionManager).executeWithoutResult(s->settlement.ensureInitialPayable(id,1,actor));
        return jdbc.queryForObject("SELECT id FROM fr_fin_payable WHERE source_ref LIKE ?",Long.class,id+":1:%");
    }
    private FinReceiptService.RegisterCommand receipt(long id,long payment,long amount) {
        return new FinReceiptService.RegisterCommand("design",null,"实收",amount,java.time.LocalDate.now(),"bank",null,null,null,null,id,"FULL",payment);
    }
}
