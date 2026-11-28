package cn.iocoder.yudao.module.franchise.asset;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.CommissionQueryPort;
import cn.iocoder.yudao.module.franchise.finance.FinReceiptService;
import cn.iocoder.yudao.module.franchise.finance.FinRefundService;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.annotation.Resource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.VOUCHER_INVALID_FIN;
import static cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Real cash transactions and scanned assets; only object storage is held in memory. */
@Import({FinReceiptService.class,FinRefundService.class,FranchiseCommands.class,CommandLogImpl.class,
        SaleContractEvidencePgIntegrationTest.Config.class,FinRefundVoucherPgIntegrationTest.Config.class})
@TestPropertySource(properties="platform.test.extra-flyway-locations=,classpath:db/migration/franchise,classpath:db/migration/franchise-asset")
class FinRefundVoucherPgIntegrationTest extends FranchisePgIntegrationTest {
    @TestConfiguration static class Config {
        @Bean CommissionQueryPort commissions() { return mock(CommissionQueryPort.class); }
    }
    private static final byte[] PDF="%PDF-1.4\n1 0 obj << /Type /Page >> endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    @Resource FinRefundService refunds;
    @Resource LocalAssetService assets;
    @Resource JdbcTemplate jdbc;
    private PostSnapshot finance,sales,other;
    private long tenant;

    @BeforeEach void prepare() {
        var store=fixture.store(); tenant=store.orgId();
        finance=fixture.post(store.orgId(),"finance",null);
        sales=fixture.post(store.orgId(),"sales",store.salesTeamA());
        other=fixture.post(fixture.store().orgId(),"finance",null);
        TenantContextHolder.setTenantId(tenant);
    }
    @AfterEach void clearTenant() { TenantContextHolder.clear(); }

    @Test void ordinaryRefundAndEngineeringReturnRejectForeignWrongTypePendingDeletedAndMissingEvidence() {
        long receipt=receipt(),payment=engineeringPayment();
        long wrongType=upload(sales,"CONTRACT_ATTACHMENT");
        // A same-id historical file cannot revive a new asset with an invalid type or state.
        historical(wrongType,finance.orgId(),tenant,"application/pdf");
        long pending=as(finance,()->assets.createUploadTicket(actor(finance),"FIN_VOUCHER","application/pdf",PDF.length,sha()).assetId());
        long deleted=upload(finance,"FIN_VOUCHER");
        jdbc.update("UPDATE fr_asset SET deleted=true WHERE id=?",deleted);
        long foreignTenant=upload(finance,"FIN_VOUCHER");
        jdbc.update("UPDATE fr_asset SET tenant_id=? WHERE id=?",tenant+1,foreignTenant);
        long rejected=upload(finance,"FIN_VOUCHER");
        jdbc.update("UPDATE fr_asset SET upload_status='REJECTED' WHERE id=?",rejected);
        String receiptKey=key(),paymentKey=key();
        for(long invalid:List.of(wrongType,pending,deleted,foreignTenant,rejected,upload(other,"FIN_VOUCHER"),IdWorker.getId(),0L,-1L)) {
            assertError(()->register(receipt,invalid,receiptKey),VOUCHER_INVALID_FIN);
            assertError(()->registerExpense(payment,invalid,paymentKey),VOUCHER_INVALID_FIN);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_refund WHERE company_id=?",Long.class,finance.orgId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_txn_no WHERE company_id=?",Long.class,finance.orgId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE org_id=? AND command IN ('fin-refund-register','fin-expense-refund')",Long.class,finance.orgId())).isZero();
        // Failed validation reserves neither the cash amount nor the original request key.
        long valid=upload(finance,"FIN_VOUCHER");
        assertThat(register(receipt,valid,receiptKey)).isPositive();
        assertThat(registerExpense(payment,valid,paymentKey)).isPositive();
    }

    @Test void optionalAndAcceptedEvidenceKeepOriginalCashIdentityAndReplay() {
        long receipt=receipt(),payment=engineeringPayment();
        for(Long proof:new Long[]{null,upload(finance,"FIN_VOUCHER")}) {
            String receiptKey=key(),paymentKey=key();
            long refund=register(receipt,proof,receiptKey),returned=registerExpense(payment,proof,paymentKey);
            if(proof!=null)jdbc.update("UPDATE fr_asset SET deleted=true WHERE id=?",proof);
            assertThat(register(receipt,proof,receiptKey)).isEqualTo(refund);
            assertThat(registerExpense(payment,proof,paymentKey)).isEqualTo(returned);
            assertThat(jdbc.queryForMap("SELECT receipt_id,voucher_file_id,amount_cents FROM fr_fin_refund WHERE id=?",refund))
                    .containsEntry("receipt_id",receipt).containsEntry("voucher_file_id",proof).containsEntry("amount_cents",1000L);
            assertThat(jdbc.queryForMap("SELECT payment_id,voucher_file_id,amount_cents FROM fr_fin_refund WHERE id=?",returned))
                    .containsEntry("payment_id",payment).containsEntry("voucher_file_id",proof).containsEntry("amount_cents",1000L);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_refund WHERE company_id=?",Long.class,finance.orgId())).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT amount_cents FROM fr_fin_receipt WHERE id=?",Long.class,receipt)).isEqualTo(10000);
        assertThat(jdbc.queryForObject("SELECT amount_cents FROM fr_fin_payment WHERE id=?",Long.class,payment)).isEqualTo(10000);
    }

    @Test void legacyVoucherStillRequiresTheSameOrganizationTenantAndAllowedMime() {
        long receipt=receipt(),payment=engineeringPayment();
        for(long invalid:List.of(historical(IdWorker.getId(),other.orgId(),tenant,"application/pdf"),
                historical(IdWorker.getId(),finance.orgId(),tenant+1,"application/pdf"),
                historical(IdWorker.getId(),finance.orgId(),tenant,"video/mp4"))) {
            assertError(()->register(receipt,invalid,key()),VOUCHER_INVALID_FIN);
            assertError(()->registerExpense(payment,invalid,key()),VOUCHER_INVALID_FIN);
        }
        long valid=historical(IdWorker.getId(),finance.orgId(),tenant,"application/pdf");
        assertThat(register(receipt,valid,key())).isPositive();
        assertThat(registerExpense(payment,valid,key())).isPositive();
    }

    private long register(long receipt,Long proof,String key) {
        return as(finance,()->refunds.register(finance,key,new FinRefundService.RegisterCommand(
                receipt,1000,"客户实际退款",today(),key,proof,null)));
    }
    private long registerExpense(long payment,Long proof,String key) {
        return as(finance,()->refunds.registerExpense(finance,key,new FinRefundService.ExpenseRefundCommand(
                payment,1000,"工程供应商实际退回",today(),key,proof,null)));
    }
    private long receipt() {
        long id=IdWorker.getId();
        jdbc.update("INSERT INTO fr_fin_receipt(id,company_id,source_type,name,amount_cents,status,registrar_user_id,received_date) VALUES(?,?,'manual','客户实收',10000,'已登记',?,CURRENT_DATE)",id,finance.orgId(),finance.userId());
        return id;
    }
    private long engineeringPayment() {
        long project=IdWorker.getId(),payable=IdWorker.getId(),payment=IdWorker.getId();
        jdbc.update("INSERT INTO fr_eng_project(id,company_id,source_type,name,manager_user_id,author_user_id) VALUES(?,?,'MANUAL',?,?,?)",project,finance.orgId(),"退款测试工程"+project,finance.userId(),finance.userId());
        jdbc.update("INSERT INTO fr_fin_payable(id,company_id,project_id,total_cents,stage,recipient,status) VALUES(?,?,?,10000,'engineering_expense','工程供应商','进行中')",payable,finance.orgId(),project);
        jdbc.update("INSERT INTO fr_eng_expense(id,company_id,project_id,name,purpose,amount_cents,payable_id,author_user_id) VALUES(?,?,?,'工程劳务','OTHER',10000,?,?)",IdWorker.getId(),finance.orgId(),project,payable,finance.userId());
        jdbc.update("INSERT INTO fr_fin_payment(id,payable_id,company_id,project_id,amount_cents,status,operator_user_id,pay_date) VALUES(?,?,?,?,10000,'已核实',?,CURRENT_DATE)",payment,payable,finance.orgId(),project,finance.userId());
        return payment;
    }
    private long historical(long id,long org,long tenant,String mime) {
        jdbc.update("INSERT INTO fr_fin_file(id,company_id,tenant_id,file_name,storage_key,content_type,uploaded_by_user_id) VALUES(?,?,?,'历史凭证.pdf',?,?,?)",id,org,tenant,"legacy/"+id,mime,finance.userId());
        return id;
    }
    private long upload(PostSnapshot post,String type) {
        return as(post,()->{
            var ticket=assets.createUploadTicket(actor(post),type,"application/pdf",PDF.length,sha());
            assets.receiveUpload(actor(post),ticket.assetId(),ticket.uploadUrl().substring(ticket.uploadUrl().indexOf("token=")+6),new ByteArrayInputStream(PDF));
            assertThat(assets.completeUpload(actor(post),ticket.assetId()).uploadStatus()).isEqualTo("ACCEPTED");
            return ticket.assetId();
        });
    }
    private AssetAccessPort.Actor actor(PostSnapshot post) { return new AssetAccessPort.Actor(post.orgId(),post.orgId()+1_000_000L,post.userId(),post.postId()); }
    private static String sha() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PDF)); }
        catch(Exception error) { throw new IllegalStateException(error); }
    }
    private <T>T as(PostSnapshot post,Supplier<T> work) { currentPost.use(post);try{return work.get();}finally{currentPost.clear();} }
    private static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }
    private static String key() { return UUID.randomUUID().toString(); }
}
