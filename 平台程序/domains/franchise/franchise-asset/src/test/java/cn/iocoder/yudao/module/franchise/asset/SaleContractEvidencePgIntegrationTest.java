package cn.iocoder.yudao.module.franchise.asset;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.AssetEvidenceQueryPort;
import cn.iocoder.yudao.module.franchise.api.BusinessReceiptPort;
import cn.iocoder.yudao.module.franchise.api.CommissionDeliverableAccessPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.crm.CrmOrgScope;
import cn.iocoder.yudao.module.franchise.content.content.ContentGrowthService;
import cn.iocoder.yudao.module.franchise.content.controller.admin.ContentPublicationController;
import cn.iocoder.yudao.module.franchise.crm.CustomerSchemeAdapter;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowReadService;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleContractDO;
import cn.iocoder.yudao.module.franchise.sales.SaleContractService;
import cn.iocoder.yudao.module.franchise.sales.SaleRequirementService;
import cn.iocoder.yudao.module.franchise.sales.SalesAccess;
import cn.iocoder.yudao.module.franchise.sales.SalesOrgScope;
import cn.iocoder.yudao.module.franchise.sales.SaleWorkflowFactsAdapter;
import cn.iocoder.yudao.module.franchise.sales.ContractDocumentAccessPolicy;
import cn.iocoder.yudao.module.franchise.test.BusinessReceiptPortTestStub;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.BOSS;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.SALES;
import static org.assertj.core.api.Assertions.*;

/** Real PG, CRM access, command log, scanned uploads and workflow projection; only object storage is in memory. */
@Import({SaleContractService.class, SaleRequirementService.class, SalesAccess.class, SalesOrgScope.class,
        CustomerSchemeAdapter.class, CustomerService.class, CrmOrgScope.class, CustomerWorkflowReadService.class,
        SaleWorkflowFactsAdapter.class, ContentGrowthService.class, ContractDocumentAccessPolicy.class, FranchiseCommands.class, CommandLogImpl.class, SaleContractEvidencePgIntegrationTest.Config.class})
@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/franchise,classpath:db/migration/franchise-asset")
class SaleContractEvidencePgIntegrationTest extends FranchisePgIntegrationTest {
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj << /Type /Page >> endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    @Resource SaleContractService contracts;
    @Resource LocalAssetService assets;
    @Resource AssetEvidenceQueryPort evidence;
    @Resource CustomerWorkflowReadService workflow;
    @Resource SaleWorkflowFactsAdapter sourceFacts;
    @Resource ContentGrowthService growth;
    @Resource JdbcTemplate jdbc;
    @Resource PlatformTransactionManager transactions;
    @Resource MemoryStorage storage;
    private PostSnapshot sales, boss, colleague, other;
    private PostSnapshot otherTeam;
    private long tenant;

    @TestConfiguration
    static class Config {
        @Bean BusinessReceiptPort receipts(JdbcTemplate jdbc) { return new BusinessReceiptPortTestStub(jdbc); }
        @Bean StoreServiceAccountPort accounts() { return org -> org + 1_000_000L; }
        @Bean MemoryStorage storage() { return new MemoryStorage(); }
        @Bean LocalAssetService assets(DataSource data, PlatformTransactionManager transactions, MemoryStorage storage,
                                      CurrentPost posts, StoreServiceAccountPort accounts,
                                      ObjectProvider<CommissionDeliverableAccessPort> deliverables) {
            return new LocalAssetService(data, transactions, storage, new AssetContentScanner(), posts, accounts,
                    deliverables.getIfAvailable());
        }
        @Bean AssetEvidenceQueryPort evidence(JdbcTemplate jdbc) { return new AssetEvidenceQueryAdapter(jdbc); }
    }
    static class MemoryStorage implements ObjectStoragePort {
        final Map<String, byte[]> values = new ConcurrentHashMap<>();
        volatile Runnable beforeRead;
        public String presignUploadUrl(String key, long ttl) { return "local://" + key; }
        public boolean existsWithSize(String key, long size) { return values.containsKey(key) && values.get(key).length == size; }
        public java.io.InputStream getObject(String key) {
            Runnable callback = beforeRead; beforeRead = null; if (callback != null) callback.run();
            return new ByteArrayInputStream(java.util.Objects.requireNonNull(values.get(key)));
        }
        public void putObject(String key, java.nio.file.Path file) {
            try { values.put(key, Files.readAllBytes(file)); } catch (java.io.IOException error) { throw new RuntimeException(error); }
        }
        public String presignDownloadUrl(String key, long ttl) { return "local://" + key; }
    }

    @BeforeEach void organization() {
        var store = fixture.store(); tenant = store.orgId();
        sales = fixture.post(store.orgId(), SALES, store.salesTeamA());
        colleague = fixture.post(store.orgId(), SALES, store.salesTeamA());
        otherTeam = fixture.post(store.orgId(), SALES, store.salesTeamB());
        boss = fixture.post(store.orgId(), BOSS, null);
        var second = fixture.store(); other = fixture.post(second.orgId(), SALES, second.salesTeamA());
        for (long company : List.of(sales.orgId(), other.orgId()))
            jdbc.update("INSERT INTO system_dept(id,name,status,deleted,tenant_id) VALUES(?,'签约证据测试',0,0,?) ON CONFLICT(id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id", company, tenant);
        TenantContextHolder.setTenantId(tenant); currentPost.use(sales); storage.beforeRead = null;
    }
    @AfterEach void clearTenant() { storage.beforeRead = null; TenantContextHolder.clear(); }

    @Test void contentGrowthCountsEachCustomerOnceAcrossTouchesAndUsesRealSigningAndNetReceipts() throws Exception {
        long first = publication(sales, "published"), second = publication(colleague, "published");
        long failed = publication(sales, "failed"), foreign = publication(other, "published");
        long buyer = customer(sales), legacy = customer(sales), pending = customer(sales);
        String a = ContentGrowthService.SOURCE_PREFIX + first, b = ContentGrowthService.SOURCE_PREFIX + second;
        inquiry(sales, a, buyer, "CONVERTED");
        inquiry(sales, a, buyer, "CONVERTED");
        inquiry(colleague, b, buyer, "CONVERTED");
        inquiry(sales, b, legacy, "CONVERTED");
        inquiry(sales, b, pending, "CONVERTED");
        inquiry(sales, b, null, "LINK_REQUIRED");
        inquiry(sales, a, null, "INVALID");
        for (String ignored : List.of("转介绍", a + "x", ContentGrowthService.SOURCE_PREFIX + failed, ContentGrowthService.SOURCE_PREFIX + foreign))
            inquiry(sales, ignored, buyer, "CONVERTED");
        inquiry(other, a, customer(other), "CONVERTED");

        var signed = draft(sales, buyer);
        long file = upload(sales, "CONTRACT_ATTACHMENT");
        sign(signed, today(), file, revision(signed.getId()), key());
        var missingEvidence = draft(sales, legacy);
        jdbc.update("UPDATE fr_sale_contract SET status='SIGNED',signed_date=CURRENT_DATE WHERE id=?", missingEvidence.getId());
        draft(sales, pending);
        Map<String, Object> summary = growthSummary(boss);
        assertCounts(summary, 2, 6, 3, 1, 0);
        assertThat(number(summary, "converted")).isEqualTo(3);
        @SuppressWarnings("unchecked") var rows = (List<Map<String,Object>>) summary.get("perPublication");
        assertThat(rows).extracting(r -> r.get("publicationId")).containsExactly(Long.toString(first), Long.toString(second));
        assertThat(rows.stream().mapToLong(row -> number(row, "linkedCustomers")).sum()).isEqualTo(4);

        long stage = ((Number) plan(signed.getId()).get(0).get("id")).longValue(), receipt = IdWorker.getId();
        jdbc.update("INSERT INTO fr_sale_stage_receipt(id,company_id,contract_id,stage_id,receipt_id,amount_cents) VALUES(?,?,?,?,?,100)",
                IdWorker.getId(), sales.orgId(), signed.getId(), stage, receipt);
        assertCounts(growthSummary(boss), 2, 6, 3, 1, 0);
        jdbc.update("INSERT INTO fr_fin_receipt(id,company_id,customer_id,source_type,name,amount_cents,status,registrar_user_id) VALUES(?,?,?,'business','首笔收款',120,'待登记',?)",
                receipt, sales.orgId(), buyer, sales.userId());
        assertCounts(growthSummary(boss), 2, 6, 3, 1, 0);
        jdbc.update("UPDATE fr_fin_receipt SET status='已登记' WHERE id=?", receipt);
        assertCounts(growthSummary(boss), 2, 6, 3, 1, 1);
        jdbc.update("UPDATE fr_fin_receipt SET customer_id=? WHERE id=?", legacy, receipt);
        assertCounts(growthSummary(boss), 2, 6, 3, 1, 0);
        jdbc.update("UPDATE fr_fin_receipt SET customer_id=? WHERE id=?", buyer, receipt);
        jdbc.update("UPDATE fr_fin_receipt SET status='已撤销' WHERE id=?", receipt);
        assertCounts(growthSummary(boss), 2, 6, 3, 1, 0);
        jdbc.update("UPDATE fr_fin_receipt SET status='已登记' WHERE id=?", receipt);
        for (int part : List.of(100, 20)) {
            jdbc.update("INSERT INTO fr_fin_refund(id,receipt_id,company_id,amount_cents,reason,operator_user_id) VALUES(?,?,?,?,?,?)",
                    IdWorker.getId(), receipt, sales.orgId(), part, "退款", sales.userId());
            assertThat(number(growthSummary(boss), "netPaidCustomers")).isEqualTo(part == 100 ? 1 : 0);
        }
        jdbc.update("UPDATE fr_sale_contract SET status='VOIDED' WHERE id=?", signed.getId());
        var replacement = draft(sales, buyer);
        assertCounts(growthSummary(boss), 2, 6, 3, 0, 0);
        sign(replacement, today(), file, revision(replacement.getId()), key());
        assertCounts(growthSummary(boss), 2, 6, 3, 1, 0);
        assertCounts(growthSummary(other), 1, 0, 0, 0, 0);
    }

    @Test void contentGrowthScopesConsultationsAndCustomerOutcomesIndependentlyOfThePublishedDirectory() {
        var remote = otherTeam;
        var teamLead = fixture.post(sales.orgId(), "sales-lead", sales.teamId());
        long a = publication(remote, "published"), b = publication(colleague, "published");
        long mine = customer(sales), sameTeam = customer(colleague), hidden = customer(remote);
        String source = ContentGrowthService.SOURCE_PREFIX + a;
        inquiry(sales, source, mine, "CONVERTED");
        inquiry(sales, source, hidden, "CONVERTED");
        inquiry(colleague, source, sameTeam, "CONVERTED");
        inquiry(remote, ContentGrowthService.SOURCE_PREFIX + b, hidden, "CONVERTED");
        assertCounts(growthSummary(sales), 2, 2, 1, 0, 0);
        assertCounts(growthSummary(teamLead), 2, 3, 2, 0, 0);
        assertCounts(growthSummary(boss), 2, 4, 3, 0, 0);
        var finance = fixture.post(sales.orgId(), "finance", null);
        assertThatThrownBy(() -> growthSummary(finance)).isInstanceOf(ServiceException.class);
        TenantContextHolder.setTenantId(tenant + 1);
        assertThatThrownBy(() -> growthSummary(sales)).isInstanceOf(ServiceException.class);
    }

    private long publication(PostSnapshot post, String status) {
        long id = IdWorker.getId();
        jdbc.update("INSERT INTO fr_content_publication(id,company_id,author_user_id,work_id,title,account_id,platform,status,published_url,published_at) VALUES(?,?,?,?,?,'销售号','douyin',?,?,now())",
                id, post.orgId(), post.userId(), id, "作品" + id, status, "https://example.org/" + id);
        return id;
    }

    private void inquiry(PostSnapshot post, String source, Long customer, String status) {
        jdbc.update("INSERT INTO fr_lead(id,company_id,team_id,registrar_user_id,registrar_post_id,name,phone,need,source,status,customer_id,conversion) VALUES(?,?,?,?,?,'咨询','13800000000','户型',?,?,?,?)",
                IdWorker.getId(), post.orgId(), post.teamId(), post.userId(), post.postId(), source, status, customer,
                customer == null ? null : "EXISTING");
    }

    private Map<String, Object> growthSummary(PostSnapshot post) {
        var controller = new ContentPublicationController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "growth", growth);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "currentPost", currentPost);
        return as(post, () -> controller.growthSummary().getData());
    }

    private static long number(Map<String, Object> row, String key) {
        assertThat(row).containsKey(key);
        return ((Number) row.get(key)).longValue();
    }

    private static void assertCounts(Map<String, Object> reply, long published, long consultations, long linked, long signed, long netPaid) {
        assertThat(List.of(number(reply, "publishedCount"), number(reply, "consultations"), number(reply, "linkedCustomers"),
                number(reply, "signedCustomers"), number(reply, "netPaidCustomers"))).containsExactly(published, consultations, linked, signed, netPaid);
    }

    @Test void createsWithoutInternalDesignBudgetAndOnlyRealSigningCompletesStepSeven() throws Exception {
        long customer = customer(sales); var contract = draft(sales, customer); long file = upload(sales, "CONTRACT_ATTACHMENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_quote WHERE customer_id=?", Long.class, customer)).isZero();
        assertThat(contract.getRequirementId()).isNull();
        assertThat(workflow.readAuthorized(tenant, sales.orgId(), customer).completedCount()).isZero();
        String key = key(); var command = new SaleContractService.SignCommand(today(), file); long version = revision(contract.getId());
        var signed = as(sales, () -> contracts.sign(sales, contract.getId(), command, version, key));
        assertThat(signed.getStatus()).isEqualTo("SIGNED"); assertThat(signed.getRevision()).isEqualTo(version + 1);
        assertThat(as(sales, () -> contracts.sign(sales, contract.getId(), command, version, key)).getRevision()).isEqualTo(version + 1);
        var view = workflow.readAuthorized(tenant, sales.orgId(), customer);
        assertThat(view.completedCount()).isEqualTo(1); assertThat(view.progressPercent()).isEqualByComparingTo("12.5");
        assertThat(view.steps().get(6).fields()).containsEntry("contractId", contract.getId().toString()).containsEntry("assetId", Long.toString(file));
        assertThat(view.steps().get(6).canComplete()).isFalse(); assertThat(view.steps().get(6).attachments()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_customer_workflow WHERE customer_id=?", Long.class, customer)).isZero();
        assertThat(auditCount(contract.getId(), "sale-contract-sign")).isEqualTo(1);
        assertError(409, () -> as(sales, () -> contracts.sign(sales, contract.getId(), command, signed.getRevision(), key())));
    }

    @Test void missingFutureWrongTypeRejectedAndUnscannedEvidenceCannotSign() throws Exception {
        var contract = draft(sales, customer(sales)); long version = revision(contract.getId());
        long file = upload(sales, "CONTRACT_ATTACHMENT");
        assertError(400, () -> sign(contract, null, file, version, key()));
        assertError(400, () -> sign(contract, today(), null, version, key()));
        assertError(400, () -> sign(contract, today().plusDays(1), file, version, key()));
        long wrongType = upload(boss, "ENG_DRAWING");
        assertThatThrownBy(() -> sign(contract, today(), wrongType, version, key())).isInstanceOf(ServiceException.class);
        var pending = as(sales, () -> assets.createUploadTicket(actor(sales), "CONTRACT_ATTACHMENT", "application/pdf", PDF.length, sha(PDF)));
        assertThatThrownBy(() -> sign(contract, today(), pending.assetId(), version, key())).isInstanceOf(ServiceException.class);
        var bad = as(sales, () -> assets.createUploadTicket(actor(sales), "CONTRACT_ATTACHMENT", "image/jpeg", PDF.length, sha(PDF)));
        assets.receiveUpload(actor(sales), bad.assetId(), token(bad.uploadUrl()), new ByteArrayInputStream(PDF));
        assertThat(assets.completeUpload(actor(sales), bad.assetId()).uploadStatus()).isEqualTo("REJECTED");
        assertThatThrownBy(() -> sign(contract, today(), bad.assetId(), version, key())).isInstanceOf(ServiceException.class);
        jdbc.update("UPDATE fr_asset SET accepted_sha256='invalid' WHERE id=?", file);
        assertError(400, () -> sign(contract, today(), file, version, key()));
        assertThat(revision(contract.getId())).isEqualTo(version); assertThat(status(contract.getId())).isEqualTo("WAIT_SIGN");
        assertThat(auditCount(contract.getId(), "sale-contract-sign")).isZero();
    }

    @Test void usesValidDraftEvidenceButNeverResignsVoidedContract() throws Exception {
        long customer = customer(sales), file = upload(sales, "CONTRACT_ATTACHMENT");
        var draft = as(sales, () -> contracts.create(sales, new SaleContractService.ContractCommand(customer,
                100000, 40000, 30000, 30000, today(), file, null), key()));
        var signed = sign(draft, null, null, revision(draft.getId()), key());
        assertThat(signed.getSignedDate()).isEqualTo(today()); assertThat(signed.getAssetId()).isEqualTo(file);
        as(sales, () -> contracts.void_(sales, draft.getId(), "客户重新签约", signed.getRevision(), key()));
        assertError(409, () -> sign(draft, today(), file, revision(draft.getId()), key()));
    }

    @Test void legacySupplementAndCorrectionKeepPaymentRowsAndRecordBothEvidenceVersions() throws Exception {
        long customer = customer(sales); var contract = draft(sales, customer); long file = upload(sales, "CONTRACT_ATTACHMENT");
        jdbc.update("UPDATE fr_sale_contract SET status='SIGNED',signed_date=NULL,asset_id=NULL WHERE id=?", contract.getId());
        List<Map<String,Object>> beforePlan = plan(contract.getId()); long version = revision(contract.getId());
        assertThat(workflow.readAuthorized(tenant, sales.orgId(), customer).completedCount()).isZero();
        String key = key(); var command = new SaleContractService.EvidenceCommand(today(), file, " 补录纸质已签合同 ", version);
        var supplemented = as(sales, () -> contracts.supplementEvidence(sales, contract.getId(), command, key));
        as(sales, () -> contracts.supplementEvidence(sales, contract.getId(), command, " " + key + " "));
        assertThat(supplemented.getStatus()).isEqualTo("SIGNED"); assertThat(supplemented.getRevision()).isEqualTo(version + 1);
        assertThat(plan(contract.getId())).isEqualTo(beforePlan);
        assertThat(workflow.readAuthorized(tenant, sales.orgId(), customer).completedCount()).isEqualTo(1);
        long replacement = upload(sales, "CONTRACT_ATTACHMENT");
        as(sales, () -> contracts.supplementEvidence(sales, contract.getId(),
                new SaleContractService.EvidenceCommand(today().minusDays(1), replacement, "更正签署日期及清晰扫描件", version + 1), key()));
        var history = jdbc.queryForList("SELECT before_evidence::text,after_evidence::text,reason,previous_revision,revision FROM fr_sale_contract_evidence_event WHERE contract_id=? ORDER BY revision", contract.getId());
        assertThat(history).hasSize(2); assertThat(history.get(0).get("reason")).isEqualTo("补录纸质已签合同");
        var emptyEvidence = JsonUtils.parseObject(history.get(0).get("before_evidence").toString(), Map.class);
        assertThat(emptyEvidence.get("signedDate")).isNull(); assertThat(emptyEvidence.get("assetId")).isNull();
        var supplementedEvidence = JsonUtils.parseObject(history.get(0).get("after_evidence").toString(), Map.class);
        var correctionBefore = JsonUtils.parseObject(history.get(1).get("before_evidence").toString(), Map.class);
        var correctionAfter = JsonUtils.parseObject(history.get(1).get("after_evidence").toString(), Map.class);
        assertThat(supplementedEvidence.get("signedDate")).isEqualTo(today().toString());
        assertThat(supplementedEvidence.get("assetId")).isEqualTo(Long.toString(file));
        assertThat(correctionBefore).isEqualTo(supplementedEvidence);
        assertThat(correctionAfter.get("signedDate")).isEqualTo(today().minusDays(1).toString());
        assertThat(correctionAfter.get("assetId")).isEqualTo(Long.toString(replacement));
        assertThat(plan(contract.getId())).isEqualTo(beforePlan); assertThat(auditCount(contract.getId(), "sale-contract-evidence")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_contract WHERE customer_id=?", Long.class, customer)).isEqualTo(1);
        assertError(STATE_VERSION_CONFLICT.getCode(), () -> as(sales, () -> contracts.supplementEvidence(sales, contract.getId(), command, key())));
        assertError(IDEMPOTENCY_KEY_REUSED.getCode(), () -> as(sales, () -> contracts.supplementEvidence(sales, contract.getId(),
                new SaleContractService.EvidenceCommand(today(), replacement, "变更原命令", version), key)));
    }

    @Test void supplementRequiresSignedStateReasonAndCurrentRevision() throws Exception {
        var contract = draft(sales, customer(sales)); long file = upload(sales, "CONTRACT_ATTACHMENT"), version = revision(contract.getId());
        assertError(409, () -> as(sales, () -> contracts.supplementEvidence(sales, contract.getId(),
                new SaleContractService.EvidenceCommand(today(), file, "补录", version), key())));
        sign(contract, today(), file, version, key());
        for (String reason : List.of(" ", "字".repeat(501)))
            assertError(400, () -> as(sales, () -> contracts.supplementEvidence(sales, contract.getId(),
                    new SaleContractService.EvidenceCommand(today(), file, reason, version + 1), key())));
        assertError(STATE_VERSION_CONFLICT.getCode(), () -> as(sales, () -> contracts.supplementEvidence(sales, contract.getId(),
                new SaleContractService.EvidenceCommand(today(), file, "旧版本", version), key())));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_contract_evidence_event WHERE contract_id=?", Long.class, contract.getId())).isZero();
    }

    @Test void otherCompanyAssetsContractsAndWrongTenantStayInaccessible() throws Exception {
        var contract = draft(sales, customer(sales)); long file = upload(sales, "CONTRACT_ATTACHMENT"), foreignFile = upload(other, "CONTRACT_ATTACHMENT");
        assertThatThrownBy(() -> sign(contract, today(), foreignFile, revision(contract.getId()), key())).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> as(other, () -> contracts.sign(other, contract.getId(),
                new SaleContractService.SignCommand(today(), foreignFile), revision(contract.getId()), key()))).isInstanceOf(ServiceException.class);
        TenantContextHolder.setTenantId(tenant + 1);
        assertThatThrownBy(() -> sign(contract, today(), file, revision(contract.getId()), key())).isInstanceOf(ServiceException.class);
        assertThat(evidence.acceptedOwners(tenant, Set.of(file), Set.of("CONTRACT_ATTACHMENT"))).isEmpty();
        TenantContextHolder.setTenantId(tenant);
        assertThat(evidence.acceptedOwners(tenant, Set.of(file), Set.of("CONTRACT_ATTACHMENT"))).containsEntry(file, sales.orgId());
        assertThat(evidence.acceptedOwners(tenant, Set.of(file), Set.of("ENG_DRAWING"))).isEmpty();
        assertThat(revision(contract.getId())).isEqualTo(1); assertThat(status(contract.getId())).isEqualTo("WAIT_SIGN");
    }

    @Test void onlyCurrentContractAttachmentGetsDownloadTicketAndInvalidationRemovesWorkflowCompletion() throws Exception {
        long customer = customer(sales); var contract = draft(sales, customer); long file = upload(sales, "CONTRACT_ATTACHMENT"), replacement = upload(sales, "CONTRACT_ATTACHMENT");
        sign(contract, today(), file, revision(contract.getId()), key());
        assertThatThrownBy(() -> as(sales, () -> contracts.evidenceDownload(sales, contract.getId(), replacement))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> as(other, () -> contracts.evidenceDownload(other, contract.getId(), file))).isInstanceOf(ServiceException.class);
        var ticket = as(boss, () -> contracts.evidenceDownload(boss, contract.getId(), file));
        assertThatThrownBy(() -> jdbc.update("UPDATE fr_asset_download_ticket SET scope_customer_id=?,scope_workflow_step='QUOTATION' WHERE asset_id=? AND scope_contract_id=?", customer, file, contract.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(as(boss, () -> assets.readDownload(actor(boss), file, token(ticket.downloadUrl()))).bytes()).isEqualTo(PDF);
        assertThat(auditCount(contract.getId(), "contract-evidence-download")).isEqualTo(1);
        jdbc.update("UPDATE fr_asset SET deleted=TRUE WHERE id=?", file);
        assertThat(evidence.acceptedOwners(tenant, Set.of(file), Set.of("CONTRACT_ATTACHMENT"))).isEmpty();
        assertThat(workflow.readAuthorized(tenant, sales.orgId(), customer).completedCount()).isZero();
        assertThatThrownBy(() -> as(sales, () -> contracts.evidenceDownload(sales, contract.getId(), file))).isInstanceOf(ServiceException.class);
    }

    @Test void sharedInvalidAssetIdCannotMakeAnotherCompanyCompleteInABulkProjection() throws Exception {
        long ownCustomer = customer(sales), otherCustomer = customer(other), file = upload(sales, "CONTRACT_ATTACHMENT");
        var own = draft(sales, ownCustomer); var foreign = draft(other, otherCustomer);
        sign(own, today(), file, revision(own.getId()), key());
        // Simulates a corrupt legacy reference. The foreign company must not borrow the valid row in the same batch.
        jdbc.update("UPDATE fr_sale_contract SET status='SIGNED',signed_date=?,asset_id=? WHERE id=?", today(), file, foreign.getId());
        var facts = sourceFacts.findFacts(tenant, Map.of(ownCustomer, sales.orgId(), otherCustomer, other.orgId()));
        assertThat(facts.stream().filter(fact -> fact.customerId() == ownCustomer)).singleElement()
                .satisfies(fact -> assertThat(fact.complete()).isTrue());
        assertThat(facts.stream().filter(fact -> fact.customerId() == otherCustomer)).singleElement()
                .satisfies(fact -> assertThat(fact.complete()).isFalse());
        jdbc.update("UPDATE fr_sale_contract SET deleted=TRUE WHERE id=?", own.getId());
        assertThatThrownBy(() -> as(sales, () -> contracts.supplementEvidence(sales, own.getId(),
                new SaleContractService.EvidenceCommand(today(), file, "不可修改已删除合同", revision(own.getId())), key()))).isInstanceOf(ServiceException.class);
    }

    @Test void transferRevokesBothScopedAndGenericUnconsumedContractTickets() throws Exception {
        long customer = customer(sales); var contract = draft(sales, customer); long file = upload(sales, "CONTRACT_ATTACHMENT");
        sign(contract, today(), file, revision(contract.getId()), key());
        String scoped = token(as(sales, () -> contracts.evidenceDownload(sales, contract.getId(), file)).downloadUrl());
        String generic = token(as(sales, () -> assets.createDownloadUrl(actor(sales), file)).downloadUrl());
        jdbc.update("UPDATE fr_customer SET owner_user_id=? WHERE id=?", colleague.userId(), customer);
        assertThatThrownBy(() -> as(sales, () -> assets.readDownload(actor(sales), file, scoped))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> as(sales, () -> assets.readDownload(actor(sales), file, generic))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> as(sales, () -> assets.createDownloadUrl(actor(sales), file))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> as(sales, () -> contracts.evidenceDownload(sales, contract.getId(), file))).isInstanceOf(ServiceException.class);
        var next = as(colleague, () -> contracts.evidenceDownload(colleague, contract.getId(), file));
        assertThat(as(colleague, () -> assets.readDownload(actor(colleague), file, token(next.downloadUrl()))).bytes()).isEqualTo(PDF);
    }

    @Test void replacingEvidenceRevokesOldContractTicketAndHistoricalFileStillFollowsCustomerAcl() throws Exception {
        long customer = customer(sales); var contract = draft(sales, customer); long file = upload(sales, "CONTRACT_ATTACHMENT"), replacement = upload(sales, "CONTRACT_ATTACHMENT");
        sign(contract, today(), file, revision(contract.getId()), key());
        String scoped = token(as(sales, () -> contracts.evidenceDownload(sales, contract.getId(), file)).downloadUrl());
        as(sales, () -> contracts.supplementEvidence(sales, contract.getId(),
                new SaleContractService.EvidenceCommand(today(), replacement, "替换清晰扫描件", revision(contract.getId())), key()));
        assertThatThrownBy(() -> as(sales, () -> assets.readDownload(actor(sales), file, scoped))).isInstanceOf(ServiceException.class);
        String history = token(as(sales, () -> assets.createDownloadUrl(actor(sales), file)).downloadUrl());
        jdbc.update("UPDATE fr_customer SET owner_user_id=? WHERE id=?", colleague.userId(), customer);
        assertThatThrownBy(() -> as(sales, () -> assets.readDownload(actor(sales), file, history))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> as(sales, () -> assets.createDownloadUrl(actor(sales), file))).isInstanceOf(ServiceException.class);
    }

    @Test void scopedTicketsPreserveAllCurrentAndHistoricalCustomerReferencesToSharedBytes() throws Exception {
        for (boolean historicalReference : List.of(false, true)) {
            long firstCustomer = customer(sales), secondCustomer = customer(sales);
            var first = draft(sales, firstCustomer); var second = draft(sales, secondCustomer);
            long file = upload(sales, "CONTRACT_ATTACHMENT");
            sign(first, today(), file, revision(first.getId()), key());
            sign(second, today(), file, revision(second.getId()), key());
            if (historicalReference) {
                long replacement = upload(sales, "CONTRACT_ATTACHMENT");
                as(sales, () -> contracts.supplementEvidence(sales, second.getId(),
                        new SaleContractService.EvidenceCommand(today(), replacement, "保留原文件历史", revision(second.getId())), key()));
            }
            String scoped = token(as(sales, () -> contracts.evidenceDownload(sales, first.getId(), file)).downloadUrl());
            String generic = token(as(sales, () -> assets.createDownloadUrl(actor(sales), file)).downloadUrl());
            jdbc.update("UPDATE fr_customer SET owner_user_id=? WHERE id=?", colleague.userId(), secondCustomer);
            assertThatThrownBy(() -> as(sales, () -> contracts.evidenceDownload(sales, first.getId(), file))).isInstanceOf(ServiceException.class);
            assertThatThrownBy(() -> as(sales, () -> assets.createDownloadUrl(actor(sales), file))).isInstanceOf(ServiceException.class);
            assertThatThrownBy(() -> as(sales, () -> assets.readDownload(actor(sales), file, scoped))).isInstanceOf(ServiceException.class);
            assertThatThrownBy(() -> as(sales, () -> assets.readDownload(actor(sales), file, generic))).isInstanceOf(ServiceException.class);
            var authorized = as(boss, () -> contracts.evidenceDownload(boss, first.getId(), file));
            assertThat(as(boss, () -> assets.readDownload(actor(boss), file, token(authorized.downloadUrl()))).bytes()).isEqualTo(PDF);
            jdbc.update("UPDATE fr_customer SET owner_user_id=? WHERE id=?", sales.userId(), secondCustomer);
            String duringRead = token(as(sales, () -> contracts.evidenceDownload(sales, first.getId(), file)).downloadUrl());
            storage.beforeRead = () -> jdbc.update("UPDATE fr_customer SET owner_user_id=? WHERE id=?", colleague.userId(), secondCustomer);
            assertThatThrownBy(() -> as(sales, () -> assets.readDownload(actor(sales), file, duringRead))).isInstanceOf(ServiceException.class);
        }
    }

    @Test void storageReadRechecksCustomerAndCurrentEvidenceAndAcceptedBytesBeforeReturning() throws Exception {
        long customer = customer(sales); var contract = draft(sales, customer); long file = upload(sales, "CONTRACT_ATTACHMENT"), replacement = upload(sales, "CONTRACT_ATTACHMENT");
        sign(contract, today(), file, revision(contract.getId()), key());
        String first = token(as(sales, () -> contracts.evidenceDownload(sales, contract.getId(), file)).downloadUrl());
        storage.beforeRead = () -> jdbc.update("UPDATE fr_customer SET owner_user_id=? WHERE id=?", colleague.userId(), customer);
        assertThatThrownBy(() -> as(sales, () -> assets.readDownload(actor(sales), file, first))).isInstanceOf(ServiceException.class);
        jdbc.update("UPDATE fr_customer SET owner_user_id=? WHERE id=?", sales.userId(), customer);
        String second = token(as(sales, () -> contracts.evidenceDownload(sales, contract.getId(), file)).downloadUrl());
        storage.beforeRead = () -> jdbc.update("UPDATE fr_sale_contract SET asset_id=? WHERE id=?", replacement, contract.getId());
        assertThatThrownBy(() -> as(sales, () -> assets.readDownload(actor(sales), file, second))).isInstanceOf(ServiceException.class);
        var current = as(sales, () -> contracts.evidenceDownload(sales, contract.getId(), replacement));
        String objectKey = jdbc.queryForObject("SELECT object_key FROM fr_asset WHERE id=?", String.class, replacement);
        storage.values.put(objectKey, "tampered".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> as(sales, () -> assets.readDownload(actor(sales), replacement, token(current.downloadUrl()))))
                .isInstanceOf(ServiceException.class).hasMessageContaining("已校验内容");
    }

    @Test void sameKeyAndWhitespaceVariantSignOnceEvenWhenConcurrent() throws Exception {
        var contract = draft(sales, customer(sales)); long file = upload(sales, "CONTRACT_ATTACHMENT"), version = revision(contract.getId());
        String key = key(); currentPost.use(sales); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = List.of(key, " " + key + " ").stream().map(requestKey -> pool.submit(() -> {
                TenantContextHolder.setTenantId(tenant);
                try { assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return contracts.sign(sales, contract.getId(), new SaleContractService.SignCommand(today(), file), version, requestKey).getRevision(); }
                finally { TenantContextHolder.clear(); }
            })).toList();
            start.countDown();
            for (var future : futures) assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo(version + 1);
        } finally { pool.shutdownNow(); }
        assertThat(auditCount(contract.getId(), "sale-contract-sign")).isEqualTo(1);
        var another = draft(sales, customer(sales));
        assertError(IDEMPOTENCY_KEY_REUSED.getCode(), () -> sign(another, today(), file, revision(another.getId()), key));
        assertThat(status(another.getId())).isEqualTo("WAIT_SIGN");
    }

    @Test void failedOuterTransactionRollsBackEvidenceRevisionAndAuditTogether() throws Exception {
        var contract = draft(sales, customer(sales)); long file = upload(sales, "CONTRACT_ATTACHMENT");
        sign(contract, today(), file, revision(contract.getId()), key()); long version = revision(contract.getId());
        as(sales, () -> new TransactionTemplate(transactions).execute(status -> {
            contracts.supplementEvidence(sales, contract.getId(), new SaleContractService.EvidenceCommand(today().minusDays(1), file, "取消更正", version), key());
            status.setRollbackOnly(); return null;
        }));
        assertThat(revision(contract.getId())).isEqualTo(version);
        assertThat(jdbc.queryForObject("SELECT signed_date FROM fr_sale_contract WHERE id=?", LocalDate.class, contract.getId())).isEqualTo(today());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_contract_evidence_event WHERE contract_id=?", Long.class, contract.getId())).isZero();
        assertThat(auditCount(contract.getId(), "sale-contract-evidence")).isZero();
    }

    private SaleContractDO draft(PostSnapshot post, long customer) {
        return as(post, () -> contracts.create(post, new SaleContractService.ContractCommand(customer, 100000, 40000, 30000, 30000, null, null, null), key()));
    }
    private SaleContractDO sign(SaleContractDO contract, LocalDate date, Long file, long revision, String key) {
        return as(sales, () -> contracts.sign(sales, contract.getId(), new SaleContractService.SignCommand(date, file), revision, key));
    }
    private long customer(PostSnapshot post) {
        long id = IdWorker.getId();
        jdbc.update("INSERT INTO fr_customer(id,company_id,owner_user_id,team_id,name,phone,source,status) VALUES(?,?,?,?,'实际签约客户',?,'到店','REGISTERED')", id, post.orgId(), post.userId(), post.teamId(), Long.toString(10000000000L + Math.floorMod(id, 10000000000L))); return id;
    }
    private long upload(PostSnapshot post, String type) throws Exception {
        currentPost.use(post); var ticket = assets.createUploadTicket(actor(post), type, "application/pdf", PDF.length, sha(PDF));
        assets.receiveUpload(actor(post), ticket.assetId(), token(ticket.uploadUrl()), new ByteArrayInputStream(PDF));
        assertThat(assets.completeUpload(actor(post), ticket.assetId()).uploadStatus()).isEqualTo("ACCEPTED");
        return ticket.assetId();
    }
    private long revision(long contract) { return jdbc.queryForObject("SELECT revision FROM fr_sale_contract WHERE id=?", Long.class, contract); }
    private String status(long contract) { return jdbc.queryForObject("SELECT status FROM fr_sale_contract WHERE id=?", String.class, contract); }
    private List<Map<String,Object>> plan(long contract) { return jdbc.queryForList("SELECT id,stage,amount_cents,status FROM fr_sale_payment_stage WHERE contract_id=? ORDER BY id", contract); }
    private long auditCount(long contract, String command) { return jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE aggregate_id=? AND command=?", Long.class, contract, command); }
    private <T> T as(PostSnapshot post, Supplier<T> action) { currentPost.use(post); return action.get(); }
    private static AssetAccessPort.Actor actor(PostSnapshot post) { return new AssetAccessPort.Actor(post.orgId(), post.orgId() + 1_000_000L, post.userId(), post.postId()); }
    private static void assertError(int code, Runnable action) { assertThatThrownBy(action::run).isInstanceOf(ServiceException.class).extracting(error -> ((ServiceException) error).getCode()).isEqualTo(code); }
    private static String token(String url) { return url.substring(url.indexOf("token=") + 6); }
    private static String sha(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception error) { throw new AssertionError(error); } }
    private static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }
    private static String key() { return UUID.randomUUID().toString(); }
}
