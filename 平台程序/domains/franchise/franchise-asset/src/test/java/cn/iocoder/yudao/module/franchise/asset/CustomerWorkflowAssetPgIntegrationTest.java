package cn.iocoder.yudao.module.franchise.asset;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.crm.CrmOrgScope;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.LeadService;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowAssetPolicy;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowReadService;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowService;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.*;
import static org.assertj.core.api.Assertions.*;

/** Real PG, upload scanning, accepted bytes and permission rechecks. Storage alone is an in-memory fixture. */
@Import({LeadService.class, CustomerService.class, CrmOrgScope.class, FranchiseCommands.class, CommandLogImpl.class,
        CustomerWorkflowService.class, CustomerWorkflowReadService.class, CustomerWorkflowAssetPolicy.class,
        AssetAccessService.class, CustomerWorkflowAssetPgIntegrationTest.Config.class})
@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/franchise,classpath:db/migration/franchise-asset")
class CustomerWorkflowAssetPgIntegrationTest extends FranchisePgIntegrationTest {
    private static final AtomicLong PHONES = new AtomicLong(17_000_000_000L + System.nanoTime() % 800_000_000L);
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj << /Type /Page >> endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    @Resource JdbcTemplate jdbc;
    @Resource LocalAssetService assets;
    @Resource AssetAccessService channel;
    @Resource CustomerWorkflowService workflow;
    @Resource LeadService leads;
    @Resource MemoryStorage storage;
    private PostSnapshot sales, colleague, lead, boss, other;
    private long tenant;

    @TestConfiguration
    static class Config {
        @Bean StoreServiceAccountPort accounts() { return org -> org + 1_000_000L; }
        @Bean MemoryStorage storage() { return new MemoryStorage(); }
        @Bean LocalAssetService assets(DataSource data, PlatformTransactionManager transactions, MemoryStorage storage,
                                       CurrentPost posts, StoreServiceAccountPort accounts, CustomerWorkflowAssetPolicy policy) {
            LocalAssetService assets = new LocalAssetService(data, transactions, storage, new AssetContentScanner(), posts, accounts);
            assets.setVisibilityPolicies(List.of(policy)); assets.setWorkflowScopes(policy); return assets;
        }
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
        lead = fixture.post(store.orgId(), SALES_LEAD, store.salesTeamA());
        boss = fixture.post(store.orgId(), BOSS, null);
        var otherStore = fixture.store(); other = fixture.post(otherStore.orgId(), SALES, otherStore.salesTeamA());
        for (long company : List.of(store.orgId(), otherStore.orgId()))
            jdbc.update("INSERT INTO system_dept(id,name,status,deleted,tenant_id) VALUES(?,'资料测试门店',0,0,?) ON CONFLICT(id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id", company, tenant);
        TenantContextHolder.setTenantId(tenant); currentPost.use(sales); storage.beforeRead = null;
    }
    @AfterEach void clearTenant() { storage.beforeRead = null; TenantContextHolder.clear(); }

    @Test void acceptedDocumentIsPrivateBeforeAttachmentAndFollowsCustomerVisibilityAfterward() throws Exception {
        long id = upload(PDF, "application/pdf"), customer = customer();
        denied(() -> as(colleague, () -> channel.createDownloadUrl(colleague, id)));
        denied(() -> as(boss, () -> channel.createDownloadUrl(boss, id)));
        as(sales, () -> workflow.save(sales, customer, "QUOTATION", quote(0, id), "link"));
        assertThat(as(lead, () -> channel.createDownloadUrl(lead, id)).downloadUrl()).contains("/" + id + "/content");
        assertThat(as(boss, () -> workflow.download(boss, customer, "QUOTATION", id)).downloadUrl()).isNotBlank();
        denied(() -> as(colleague, () -> channel.createDownloadUrl(colleague, id)));
        denied(() -> as(colleague, () -> assets.validateReference(actor(colleague), id, Set.of("BUSINESS_DOCUMENT"))));
        denied(() -> as(other, () -> channel.createDownloadUrl(other, id)));
        denied(() -> as(sales, () -> workflow.download(sales, customer, "MEASUREMENT", id)));
        TenantContextHolder.setTenantId(tenant + 100);
        denied(() -> as(sales, () -> channel.createDownloadUrl(sales, id)));
    }

    @Test void replacingAttachmentRetainsHistoricalDownloadAndCustomerOwnership() throws Exception {
        long customer = customer(), first = upload(PDF, "application/pdf"), second = upload(PDF, "application/pdf");
        as(sales, () -> workflow.save(sales, customer, "QUOTATION", quote(0, first), "v1"));
        as(sales, () -> workflow.save(sales, customer, "QUOTATION", quote(1, second), "v2"));
        var result = as(sales, () -> workflow.download(sales, customer, "QUOTATION", first));
        var bytes = as(sales, () -> assets.readDownload(actor(sales), first, token(result.downloadUrl())));
        assertThat(bytes.bytes()).isEqualTo(PDF); assertThat(bytes.mimeType()).isEqualTo("application/pdf");
        assertThat(as(sales, () -> workflow.get(sales, customer)).steps().get(5).attachments().get(0).assetId()).isEqualTo(Long.toString(second));
        assertThat(as(sales, () -> workflow.records(sales, customer, 1, 20)).getList().get(1).attachments().get(0).assetId()).isEqualTo(Long.toString(first));
        long otherCustomer = customer();
        denied(() -> as(sales, () -> workflow.save(sales, otherCustomer, "QUOTATION", quote(0, first), "wrong-owner")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_customer_workflow WHERE customer_id=?", Long.class, otherCustomer)).isZero();
    }

    @Test void bossCanReadOnlyCustomerBoundLegacyDesignReferencesThroughTheWorkflowScope() throws Exception {
        long customer = customer();
        var encoded = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", encoded);
        byte[] png = encoded.toByteArray();
        var upload = channel.uploadTicket(sales, "DESIGN_REFERENCE", "image/png", png.length, sha(png));
        assets.receiveUpload(actor(sales), upload.assetId(), token(upload.uploadUrl()), new ByteArrayInputStream(png));
        assertThat(channel.completeUpload(sales, upload.assetId()).uploadStatus()).isEqualTo("ACCEPTED");
        long file = upload.assetId();
        denied(() -> as(boss, () -> assets.createWorkflowDownloadUrl(actor(boss), customer, "HOUSE_SELECTION", file)));
        as(sales, () -> workflow.save(sales, customer, "HOUSE_SELECTION", new CustomerWorkflowModel.SaveCommand(0,
                LocalDate.now(ZoneId.of("Asia/Shanghai")), "旧外部方案", Map.of("selectionResult", "两层庭院", "confirmed", true),
                List.of(new CustomerWorkflowModel.Attachment(Long.toString(file), "旧方案.png"))), "legacy-reference"));
        var ticket = as(boss, () -> workflow.download(boss, customer, "HOUSE_SELECTION", file));
        assertThat(as(boss, () -> assets.readDownload(actor(boss), file, token(ticket.downloadUrl()))).mimeType()).isEqualTo("image/png");
        // The generic role matrix remains closed; unrelated and invisible customer files do not get this exception.
        denied(() -> as(boss, () -> channel.createDownloadUrl(boss, file)));
        denied(() -> as(colleague, () -> assets.createWorkflowDownloadUrl(actor(colleague), customer, "HOUSE_SELECTION", file)));
        denied(() -> as(other, () -> assets.createWorkflowDownloadUrl(actor(other), customer, "HOUSE_SELECTION", file)));
        long unrelated = customer();
        denied(() -> as(boss, () -> assets.createWorkflowDownloadUrl(actor(boss), unrelated, "HOUSE_SELECTION", file)));
    }

    @Test void customerTransferRevokesPreviouslyIssuedTicketAndTheOriginalUploader() throws Exception {
        long customer = customer(), file = upload(PDF, "application/pdf");
        as(sales, () -> workflow.save(sales, customer, "QUOTATION", quote(0, file), "save"));
        String oldToken = token(as(sales, () -> workflow.download(sales, customer, "QUOTATION", file)).downloadUrl());
        jdbc.update("UPDATE fr_customer SET owner_user_id=? WHERE id=?", colleague.userId(), customer);
        denied(() -> as(sales, () -> assets.readDownload(actor(sales), file, oldToken)));
        denied(() -> as(sales, () -> channel.createDownloadUrl(sales, file)));
        var current = as(colleague, () -> workflow.download(colleague, customer, "QUOTATION", file));
        assertThat(as(colleague, () -> assets.readDownload(actor(colleague), file, token(current.downloadUrl()))).bytes()).isEqualTo(PDF);
    }

    @Test void transferDuringObjectReadIsCheckedAgainBeforeReturningPrivateBytes() throws Exception {
        long customer = customer(), file = upload(PDF, "application/pdf");
        as(sales, () -> workflow.save(sales, customer, "QUOTATION", quote(0, file), "save"));
        String token = token(as(sales, () -> workflow.download(sales, customer, "QUOTATION", file)).downloadUrl());
        storage.beforeRead = () -> jdbc.update("UPDATE fr_customer SET owner_user_id=? WHERE id=?", colleague.userId(), customer);
        denied(() -> as(sales, () -> assets.readDownload(actor(sales), file, token)));
    }

    @Test void businessDocumentSizeMimeAndAcceptedDigestAreEnforced() throws Exception {
        denied(() -> channel.uploadTicket(sales, "BUSINESS_DOCUMENT", "application/pdf", (30L << 20) + 1, "0".repeat(64)));
        denied(() -> channel.uploadTicket(sales, "BUSINESS_DOCUMENT", "image/svg+xml", 10, "0".repeat(64)));
        var bad = channel.uploadTicket(sales, "BUSINESS_DOCUMENT", "image/jpeg", PDF.length, sha(PDF));
        assets.receiveUpload(actor(sales), bad.assetId(), token(bad.uploadUrl()), new ByteArrayInputStream(PDF));
        assertThat(channel.completeUpload(sales, bad.assetId()).uploadStatus()).isEqualTo("REJECTED");
        assertThat(assets.validateReference(actor(sales), bad.assetId(), Set.of("BUSINESS_DOCUMENT"))).isFalse();
        long file = upload(PDF, "application/pdf");
        String ticket = token(channel.createDownloadUrl(sales, file).downloadUrl());
        jdbc.update("UPDATE fr_asset SET accepted_sha256=? WHERE id=?", "0".repeat(64), file);
        denied(() -> assets.readDownload(actor(sales), file, ticket));
    }

    @Test void simultaneousFirstAttachmentCannotBindOnePrivateDocumentToTwoCustomers() throws Exception {
        long first = customer(), second = customer(), file = upload(PDF, "application/pdf");
        currentPost.use(sales); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = List.of(first, second).stream().map(customer -> pool.submit(() -> {
                TenantContextHolder.setTenantId(tenant);
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("requests did not start");
                    workflow.save(sales, customer, "QUOTATION", quote(0, file), "attach-" + customer); return true;
                } catch (ServiceException rejected) { assertThat(rejected.getCode()).isEqualTo(400); return false; }
                finally { TenantContextHolder.clear(); }
            })).toList();
            start.countDown();
            int accepted = 0; for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_customer_workflow WHERE customer_id IN (?,?)", Long.class, first, second)).isEqualTo(1);
    }

    private long customer() {
        return as(sales, () -> leads.capture(sales, new LeadService.CaptureCommand("王先生", Long.toString(PHONES.incrementAndGet()), "自建房", "到店"), null).customerId());
    }
    private long upload(byte[] bytes, String mime) throws Exception {
        currentPost.use(sales);
        var ticket = channel.uploadTicket(sales, "BUSINESS_DOCUMENT", mime, bytes.length, sha(bytes));
        assets.receiveUpload(actor(sales), ticket.assetId(), token(ticket.uploadUrl()), new ByteArrayInputStream(bytes));
        assertThat(channel.completeUpload(sales, ticket.assetId()).uploadStatus()).isEqualTo("ACCEPTED");
        return ticket.assetId();
    }
    private CustomerWorkflowModel.SaveCommand quote(long revision, long asset) {
        return new CustomerWorkflowModel.SaveCommand(revision, LocalDate.now(ZoneId.of("Asia/Shanghai")), "外部报价",
                Map.of("amountCents", "18000000", "scope", "主体土建", "versionLabel", "V1", "result", "已说明", "communicated", true),
                List.of(new CustomerWorkflowModel.Attachment(Long.toString(asset), "正式报价.pdf")));
    }
    private AssetAccessPort.Actor actor(PostSnapshot post) { return new AssetAccessPort.Actor(post.orgId(), post.orgId() + 1_000_000L, post.userId(), post.postId()); }
    private <T> T as(PostSnapshot post, Supplier<T> body) { currentPost.use(post); return body.get(); }
    private static void denied(Runnable body) { assertThatThrownBy(body::run).isInstanceOf(ServiceException.class); }
    private static String token(String url) { return url.substring(url.indexOf("token=") + 6); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
