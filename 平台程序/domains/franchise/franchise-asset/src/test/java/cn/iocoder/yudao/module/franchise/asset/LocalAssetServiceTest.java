package cn.iocoder.yudao.module.franchise.asset;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort.Actor;
import cn.iocoder.yudao.module.franchise.api.CommissionDeliverableAccessPort;
import cn.iocoder.yudao.module.franchise.api.CommissionDeliverableAccessPort.Slot;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

class LocalAssetServiceTest {
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj << /Type /Page >> endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static final PostSnapshot POST = new PostSnapshot(11L, 22L, 33L, "store", "finance", null, false, Set.of());
    private static final Actor ACTOR = new Actor(33, 777, 22, 11);
    private PostSnapshot current;
    private JdbcTemplate jdbc;
    private MemoryStorage storage;
    private LocalAssetService service;
    private CommissionDeliverableAccessPort.DeliverableRef scopedRef;
    private boolean scopeAllowed;
    private boolean ownerMappingActive;

    @Test
    void contentVisibilityPolicyIsEnforcedByEveryPublicAssetRead() throws Exception {
        current = new PostSnapshot(11, 22, 33, "store", "sales", null, false, Set.of());
        byte[] png = png();
        var ticket = service.createUploadTicket(ACTOR, "CONTENT_ASSET", "image/png", png.length, sha(png));
        String token = ticket.uploadUrl().substring(ticket.uploadUrl().indexOf("token=") + 6);
        service.receiveUpload(ACTOR, ticket.assetId(), token, new ByteArrayInputStream(png));
        service.completeUpload(ACTOR, ticket.assetId());
        service.setVisibilityPolicies(java.util.List.of((tenant, actor, id) -> { throw new IllegalStateException("private material"); }));
        assertThatThrownBy(() -> service.createDownloadUrl(ACTOR,ticket.assetId())).hasMessageContaining("private material");
        assertThatThrownBy(() -> service.validateReference(ACTOR,ticket.assetId(),Set.of("CONTENT_ASSET"))).hasMessageContaining("private material");
        assertThatThrownBy(() -> service.assetTypeOf(ACTOR,ticket.assetId())).hasMessageContaining("private material");
        assertThatThrownBy(() -> service.contentMetadata(ACTOR,ticket.assetId())).hasMessageContaining("private material");
    }

    @Test
    void designReferenceStaysInBStorageAndUsesTheDesignRoleChannel() throws Exception {
        current = new PostSnapshot(11L, 22L, 33L, "store", "hq-design-admin", null, false, Set.of());
        byte[] png = png();
        var ticket = service.createUploadTicket(ACTOR, "DESIGN_REFERENCE", "image/png", png.length, sha(png));
        assertThat(ticket.uploadUrl()).startsWith("/admin-api/franchise/assets/");
        String token = ticket.uploadUrl().substring(ticket.uploadUrl().indexOf("token=") + 6);
        service.receiveUpload(ACTOR, ticket.assetId(), token, new ByteArrayInputStream(png));
        assertThat(service.completeUpload(ACTOR, ticket.assetId()).uploadStatus()).isEqualTo("ACCEPTED");
        assertThat(service.validateReference(ACTOR, ticket.assetId(), Set.of("DESIGN_REFERENCE"))).isTrue();
        var reference = service.readDesignReference(ACTOR,ticket.assetId());
        assertThat(reference.mimeType()).isEqualTo("image/png");
        assertThat(reference.sha256()).isEqualTo(sha(reference.bytes()));
        assertThat(reference.bytes()).isNotEmpty();
        jdbc.update("UPDATE fr_asset SET accepted_sha256=? WHERE id=?", "0".repeat(64), ticket.assetId());
        assertThatThrownBy(() -> service.readDesignReference(ACTOR,ticket.assetId())).hasMessageContaining("审核摘要不一致");
        assertThatThrownBy(() -> service.readDesignReference(new Actor(34,777,22,11),ticket.assetId())).isInstanceOf(RuntimeException.class);
        current = POST;
        assertThatThrownBy(() -> service.readDesignReference(ACTOR,ticket.assetId())).isInstanceOf(RuntimeException.class);
        assertThat(storage.values.keySet()).hasSize(2).allMatch(key -> key.startsWith("franchise/1/33/"));
        assertThat(jdbc.queryForObject("SELECT asset_type FROM fr_asset WHERE id=?", String.class, ticket.assetId()))
                .isEqualTo("DESIGN_REFERENCE");
    }

    @Test
    void maximumSizeVideoIsPublishedFromDiskWithItsOriginalDigest(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var blobs=new LocalObjectStorageAdapter(dir.resolve("objects").toString());
        configureStorage(blobs);
        current = new PostSnapshot(11,22,33,"store","sales",null,false,Set.of());
        var file=dir.resolve("video.mp4");
        int size=50<<20;
        try(var source=new java.io.RandomAccessFile(file.toFile(),"rw")) {
            source.setLength(size);
            source.seek(4); source.writeBytes("ftyp");
        }
        String hash;
        var digest=MessageDigest.getInstance("SHA-256");
        try(var input=new java.security.DigestInputStream(java.nio.file.Files.newInputStream(file),digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream()); hash=HexFormat.of().formatHex(digest.digest());
        }
        var ticket=service.createUploadTicket(ACTOR,"CONTENT_ASSET","video/mp4",size,hash);
        try(var input=java.nio.file.Files.newInputStream(file)) {
            service.receiveUpload(ACTOR,ticket.assetId(),ticket.uploadUrl().split("token=")[1],input);
        }
        assertThat(service.completeUpload(ACTOR,ticket.assetId()).uploadStatus()).isEqualTo("ACCEPTED");
        assertThat(service.completeUpload(ACTOR,ticket.assetId()).uploadStatus()).isEqualTo("ACCEPTED");
        var key=jdbc.queryForObject("SELECT object_key FROM fr_asset WHERE id=?",String.class,ticket.assetId());
        assertThat(java.nio.file.Files.mismatch(file,dir.resolve("objects").resolve(key))).isEqualTo(-1);
    }

    @Test void stagingCapacityIsReusableAfterFailure(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var pool=new AssetSpool(dir,100);
        try(var first=pool.reserve(75);var file=first.file()){
            assertThatThrownBy(()->pool.reserve(26)).isInstanceOf(AssetSpool.CapacityBusy.class);
            try(var additional=pool.reserve(25)) { assertThatThrownBy(()->pool.reserve(1)).isInstanceOf(AssetSpool.CapacityBusy.class); }
        }
        try(var retry=pool.reserve(100);var file=retry.file()){ assertThat(file.path).exists(); }
        try(var entries=java.nio.file.Files.list(dir)){ assertThat(entries).isEmpty(); }
    }

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(1L);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:asset_" + java.util.UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/franchise-asset/V20261111.910__fr_asset.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        // H2 为未命名 CHECK 生成随机名；生产 PostgreSQL 使用 fr_asset_asset_type_check。
        String assetTypeCheck = jdbc.queryForObject("SELECT cc.constraint_name FROM information_schema.check_constraints cc "
                + "JOIN information_schema.table_constraints tc ON tc.constraint_catalog=cc.constraint_catalog "
                + "AND tc.constraint_schema=cc.constraint_schema AND tc.constraint_name=cc.constraint_name "
                + "WHERE tc.table_name='FR_ASSET' AND UPPER(cc.check_clause) LIKE '%ASSET_TYPE%'", String.class);
        jdbc.execute("ALTER TABLE fr_asset DROP CONSTRAINT " + assetTypeCheck);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/franchise-asset/V20261114.920__design_reference_asset.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/franchise-asset/V20261115.984__design_deliverables_scopes.sql")).execute(ds);
        jdbc.execute("CREATE TABLE pf_org_unit (dept_id BIGINT,org_id BIGINT,kind VARCHAR(8),status VARCHAR(16))");
        jdbc.execute("CREATE TABLE system_dept (id BIGINT,tenant_id BIGINT,status INT,deleted INT)");
        jdbc.update("INSERT INTO pf_org_unit VALUES (33,33,'ORG','ACTIVE')");
        jdbc.update("INSERT INTO system_dept VALUES (33,1,0,0)");
        storage = new MemoryStorage();
        current = POST;
        ownerMappingActive = true;
        configureStorage(storage);
    }

    private void configureStorage(ObjectStoragePort adapter) {
        var ds=java.util.Objects.requireNonNull(jdbc.getDataSource());
        CurrentPost posts = () -> Optional.ofNullable(current);
        service = new LocalAssetService(ds, new DataSourceTransactionManager(ds), adapter,
                new AssetContentScanner(), posts, new cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort() {
                    public long requireServiceAccount(long org) {
                        if (current == null || current.orgId() != org) throw new ServiceAccountInactiveException(org);
                        return org == 33 ? 777 : 888;
                    }
                    public long requireExistingServiceAccountForTask(long tenant, long org) {
                        if (tenant != 1 || org != 33 || !ownerMappingActive) throw new ServiceAccountInactiveException(org);
                        return 777;
                    }
                }, (actor, commission, seq, slot) -> {
                    if (!scopeAllowed || commission != 42 || seq != 1 || scopedRef == null || slot != scopedRef.slot())
                        throw new IllegalArgumentException("无权查看委托文件");
                    return scopedRef;
                });
    }

    @AfterEach
    void clearTenant() { TenantContextHolder.clear(); }

    private long pending(byte[] content) throws Exception {
        var ticket = service.createUploadTicket(ACTOR, "FIN_VOUCHER", "application/pdf", content.length, sha(content));
        String token = ticket.uploadUrl().substring(ticket.uploadUrl().indexOf("token=") + 6);
        service.receiveUpload(ACTOR, ticket.assetId(), token, new ByteArrayInputStream(content));
        return ticket.assetId();
    }

    @Test
    void acceptedBytesAreImmutableAndDownloadTokenIsOneUse() throws Exception {
        long id = pending(PDF);
        assertThat(service.validateReference(ACTOR, id, Set.of("FIN_VOUCHER"))).isFalse();
        assertThat(service.completeUpload(ACTOR, id).uploadStatus()).isEqualTo("ACCEPTED");
        assertThat(service.completeUpload(ACTOR, id).uploadStatus()).isEqualTo("ACCEPTED");
        assertThat(service.validateReference(ACTOR, id, Set.of("FIN_VOUCHER"))).isTrue();
        String rawKey = jdbc.queryForObject("SELECT upload_key FROM fr_asset WHERE id=?", String.class, id);
        storage.putObject(rawKey, "overwritten".getBytes(StandardCharsets.US_ASCII));
        String url = service.createDownloadUrl(ACTOR, id).downloadUrl();
        String token = url.substring(url.indexOf("token=") + 6);
        assertThat(service.readDownload(ACTOR, id, token).bytes()).isEqualTo(PDF);
        assertThatThrownBy(() -> service.readDownload(ACTOR, id, token)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_asset_download_ticket WHERE consumed_at IS NOT NULL", Integer.class)).isEqualTo(1);
    }

    @Test void acceptedFinanceEvidenceCanBeOpenedByAnotherFinancePost() throws Exception {
        long id=pending(PDF);
        service.completeUpload(ACTOR,id);
        current=new PostSnapshot(27,98,33,"store","finance-lead",null,false,Set.of());
        var other=new Actor(33,777,98,27);
        String ticket=service.createDownloadUrl(other,id).downloadUrl();
        assertThat(service.readDownload(other,id,tokenOf(ticket)).bytes()).isEqualTo(PDF);
        current=new PostSnapshot(27,98,33,"store","sales",null,false,Set.of());
        assertThatThrownBy(()->service.createDownloadUrl(other,id)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void backgroundTaskRestoresTenantFromDirectoryAndCannotCrossOrgOrType() throws Exception {
        long finance = pending(PDF);
        service.completeUpload(ACTOR, finance);
        current = new PostSnapshot(11L, 22L, 33L, "store", "sales", null, false, Set.of());
        byte[] audio = new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0, 1};
        var ticket = service.createUploadTicket(ACTOR, "CONTENT_ASSET", "audio/mpeg", audio.length, sha(audio));
        service.receiveUpload(ACTOR, ticket.assetId(), ticket.uploadUrl().substring(ticket.uploadUrl().indexOf("token=") + 6),
                new ByteArrayInputStream(audio));
        service.completeUpload(ACTOR, ticket.assetId());
        current = null;
        TenantContextHolder.clear();
        storage.externalDownloads = true;
        assertThat(service.createTaskDownloadUrl(33, ticket.assetId(), "CONTENT_ASSET").downloadUrl()).startsWith("https://");
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThatThrownBy(() -> service.createTaskDownloadUrl(34, ticket.assetId(), "CONTENT_ASSET")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.createTaskDownloadUrl(33, finance, "CONTENT_ASSET")).isInstanceOf(RuntimeException.class);
        TenantContextHolder.setTenantId(2L);
        assertThatThrownBy(() -> service.createTaskDownloadUrl(33, ticket.assetId(), "CONTENT_ASSET")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void wrongOrgTenantTypeActorAndLegacyAccountCannotRead() throws Exception {
        long id = pending(PDF);
        service.completeUpload(ACTOR, id);
        assertThat(service.validateReference(ACTOR, id, Set.of("CONTRACT_ATTACHMENT"))).isFalse();
        assertThatThrownBy(() -> service.createDownloadUrl(new Actor(34, 888, 11, 22), id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.createDownloadUrl(new Actor(33, 777, 99, 22), id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.createDownloadUrl(777, id)).isInstanceOf(RuntimeException.class);
        TenantContextHolder.setTenantId(2L);
        assertThat(service.validateReference(ACTOR, id, Set.of("FIN_VOUCHER"))).isFalse();
        assertThatThrownBy(() -> service.completeUpload(ACTOR, id)).isInstanceOf(RuntimeException.class);
        TenantContextHolder.clear();
        assertThatThrownBy(() -> service.createDownloadUrl(ACTOR, id)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void integrityAndContentFailuresNeverBecomeAccepted() throws Exception {
        long id = pending(PDF);
        storage.putObject(jdbc.queryForObject("SELECT upload_key FROM fr_asset WHERE id=?", String.class, id), new byte[PDF.length]);
        assertThat(service.completeUpload(ACTOR, id).uploadStatus()).isEqualTo("REJECTED");
        assertThatThrownBy(() -> service.createDownloadUrl(ACTOR, id)).isInstanceOf(RuntimeException.class);
        long invalid = pending("not a pdf".getBytes(StandardCharsets.US_ASCII));
        assertThat(service.completeUpload(ACTOR, invalid).rejectedReason()).contains("MAGIC_NUMBER");
    }

    @Test
    void downloadConsumptionIsAtomicAndExpiredTicketsFailClosed() throws Exception {
        long id = pending(PDF);
        service.completeUpload(ACTOR, id);
        String url = service.createDownloadUrl(ACTOR, id).downloadUrl();
        String token = url.substring(url.indexOf("token=") + 6);
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> read = () -> {
                TenantContextHolder.setTenantId(1L);
                try { service.readDownload(ACTOR, id, token); return true; }
                catch (RuntimeException expected) { return false; }
                finally { TenantContextHolder.clear(); }
            };
            var results = executor.invokeAll(java.util.List.of(read, read));
            assertThat(results.stream().map(result -> {
                try { return result.get(); } catch (Exception e) { throw new AssertionError(e); }
            }).filter(Boolean::booleanValue).count()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
        url = service.createDownloadUrl(ACTOR, id).downloadUrl();
        String expiredToken = url.substring(url.indexOf("token=") + 6);
        jdbc.update("UPDATE fr_asset_download_ticket SET expires_at=TIMESTAMP '2000-01-01 00:00:00'");
        assertThatThrownBy(() -> service.readDownload(ACTOR, id, expiredToken)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void uploadTicketBindsActorExpiresAndCannotBeReused() throws Exception {
        var ticket = service.createUploadTicket(ACTOR, "FIN_VOUCHER", "application/pdf", PDF.length, sha(PDF));
        String token = ticket.uploadUrl().substring(ticket.uploadUrl().indexOf("token=") + 6);
        assertThatThrownBy(() -> service.receiveUpload(new Actor(33, 777, 99, 22), ticket.assetId(), token, new ByteArrayInputStream(PDF)))
                .isInstanceOf(RuntimeException.class);
        service.receiveUpload(ACTOR, ticket.assetId(), token, new ByteArrayInputStream(PDF));
        assertThatThrownBy(() -> service.receiveUpload(ACTOR, ticket.assetId(), token, new ByteArrayInputStream(PDF)))
                .isInstanceOf(RuntimeException.class);
        assertThat(service.completeUpload(ACTOR, ticket.assetId()).uploadStatus()).isEqualTo("ACCEPTED");
    }

    @Test
    void completeCannotRejectOrAcceptWhileProxyUploadHasNotFinished() throws Exception {
        var ticket = service.createUploadTicket(ACTOR, "FIN_VOUCHER", "application/pdf", PDF.length, sha(PDF));
        String token = ticket.uploadUrl().substring(ticket.uploadUrl().indexOf("token=") + 6);
        storage.onPut = () -> {
            assertThatThrownBy(() -> service.completeUpload(ACTOR, ticket.assetId())).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT upload_status FROM fr_asset WHERE id=?", String.class, ticket.assetId()))
                    .isEqualTo("PENDING");
        };
        service.receiveUpload(ACTOR, ticket.assetId(), token, new ByteArrayInputStream(PDF));
        storage.onPut = null;
        assertThat(service.completeUpload(ACTOR, ticket.assetId()).uploadStatus()).isEqualTo("ACCEPTED");
    }

    @Test
    void expiredOrMissingUploadTicketCannotPublishAndRevokedPostCannotDownload() throws Exception {
        long expired = service.createUploadTicket(ACTOR, "FIN_VOUCHER", "application/pdf", PDF.length, sha(PDF)).assetId();
        jdbc.update("UPDATE fr_asset_upload_ticket SET expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE asset_id=?", expired);
        assertThatThrownBy(() -> service.completeUpload(ACTOR, expired)).isInstanceOf(RuntimeException.class);
        long missing = pending(PDF);
        jdbc.update("DELETE FROM fr_asset_upload_ticket WHERE asset_id=?", missing);
        assertThatThrownBy(() -> service.completeUpload(ACTOR, missing)).isInstanceOf(RuntimeException.class);
        long accepted = pending(PDF);
        service.completeUpload(ACTOR, accepted);
        String url = service.createDownloadUrl(ACTOR, accepted).downloadUrl();
        String token = url.substring(url.indexOf("token=") + 6);
        current = null;
        assertThatThrownBy(() -> service.readDownload(ACTOR, accepted, token)).isInstanceOf(RuntimeException.class);
        current = new PostSnapshot(11L, 22L, 33L, "store", "sales", null, false, Set.of());
        assertThatThrownBy(() -> service.readDownload(ACTOR, accepted, token)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void directUploadValidationCanRecoverAfterTicketExpiryOnceClaimed() throws Exception {
        var ticket = service.createUploadTicket(ACTOR, "FIN_VOUCHER", "application/pdf", PDF.length, sha(PDF));
        long id = ticket.assetId();
        jdbc.update("UPDATE fr_asset_upload_ticket SET upload_mode='DIRECT' WHERE asset_id=?", id);
        storage.putObject(jdbc.queryForObject("SELECT upload_key FROM fr_asset WHERE id=?", String.class, id), PDF);
        storage.fail = true;
        assertThatThrownBy(() -> service.completeUpload(ACTOR, id)).isInstanceOf(RuntimeException.class);
        jdbc.update("UPDATE fr_asset_upload_ticket SET expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE asset_id=?", id);
        storage.fail = false;
        assertThat(service.completeUpload(ACTOR, id).uploadStatus()).isEqualTo("ACCEPTED");
    }

    @Test
    void storageFailureIsRetryableAndStaleValidationCannotPublish() throws Exception {
        long id = pending(PDF);
        storage.fail = true;
        assertThatThrownBy(() -> service.completeUpload(ACTOR, id)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT upload_status FROM fr_asset WHERE id=?", String.class, id)).isEqualTo("PENDING");
        storage.fail = false;
        storage.onPut = () -> jdbc.update("UPDATE fr_asset SET validation_token='new-owner' WHERE id=?", id);
        assertThatThrownBy(() -> service.completeUpload(ACTOR, id)).isInstanceOf(RuntimeException.class);
        assertThat(service.validateReference(ACTOR, id, Set.of("FIN_VOUCHER"))).isFalse();
    }

    @Test
    void dispatchScopeReadsHqBytesButGenericDownloadStillCannotCrossOrganization() throws Exception {
        current = new PostSnapshot(11L, 22L, 33L, "headquarters", "hq-design-admin", null, false, Set.of());
        var ticket = service.createUploadTicket(ACTOR, "DESIGN_DELIVERABLE", "application/pdf", PDF.length, sha(PDF));
        service.receiveUpload(ACTOR, ticket.assetId(), tokenOf(ticket.uploadUrl()), new ByteArrayInputStream(PDF));
        service.completeUpload(ACTOR, ticket.assetId());
        current = new PostSnapshot(44L, 55L, 34L, "store", "sales", null, false, Set.of());
        Actor consumer = new Actor(34, 888, 55, 44);
        scopeAllowed = true;
        scopedRef = new CommissionDeliverableAccessPort.DeliverableRef(ticket.assetId(), 33, 42, 1, Slot.RESULT);
        assertThatThrownBy(() -> service.createDownloadUrl(consumer, ticket.assetId())).isInstanceOf(RuntimeException.class);
        var url = service.createCommissionDownloadUrl(consumer, 42, 1, Slot.RESULT).downloadUrl();
        assertThat(service.readDownload(consumer, ticket.assetId(), tokenOf(url)).bytes()).isEqualTo(PDF);
        assertThatThrownBy(() -> service.readDownload(consumer, ticket.assetId(), tokenOf(url))).isInstanceOf(RuntimeException.class);
        var second = service.createCommissionDownloadUrl(consumer, 42, 1, Slot.RESULT).downloadUrl();
        scopeAllowed = false;
        assertThatThrownBy(() -> service.readDownload(consumer, ticket.assetId(), tokenOf(second))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_asset_download_ticket WHERE consumed_at IS NULL", Integer.class)).isEqualTo(1);
        scopeAllowed = true;
        ownerMappingActive = false;
        assertThatThrownBy(() -> service.readDownload(consumer, ticket.assetId(), tokenOf(second)))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        ownerMappingActive = true;
        assertThatThrownBy(() -> service.readDownload(new Actor(34, 888, 99, 44), ticket.assetId(), tokenOf(second))).isInstanceOf(RuntimeException.class);
        TenantContextHolder.setTenantId(2L);
        assertThatThrownBy(() -> service.readDownload(consumer, ticket.assetId(), tokenOf(second))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void confirmationScopeDoesNotBroadenEngineeringGenericContractRights() throws Exception {
        current = new PostSnapshot(11L, 22L, 33L, "store", "sales", null, false, Set.of());
        var ticket = service.createUploadTicket(ACTOR, "CONTRACT_ATTACHMENT", "application/pdf", PDF.length, sha(PDF));
        service.receiveUpload(ACTOR, ticket.assetId(), tokenOf(ticket.uploadUrl()), new ByteArrayInputStream(PDF));
        service.completeUpload(ACTOR, ticket.assetId());
        current = new PostSnapshot(11L, 22L, 33L, "store", "engineer", null, false, Set.of());
        scopeAllowed = true;
        scopedRef = new CommissionDeliverableAccessPort.DeliverableRef(ticket.assetId(), 33, 42, 1, Slot.CONFIRMATION);
        assertThatThrownBy(() -> service.createDownloadUrl(ACTOR, ticket.assetId())).isInstanceOf(RuntimeException.class);
        String url = service.createCommissionDownloadUrl(ACTOR, 42, 1, Slot.CONFIRMATION).downloadUrl();
        assertThat(service.readDownload(ACTOR, ticket.assetId(), tokenOf(url)).bytes()).isEqualTo(PDF);
        scopedRef = new CommissionDeliverableAccessPort.DeliverableRef(ticket.assetId(), 33, 42, 1, Slot.RESULT);
        assertThatThrownBy(() -> service.createCommissionDownloadUrl(ACTOR, 42, 1, Slot.RESULT)).isInstanceOf(RuntimeException.class);
    }

    private static String tokenOf(String url) { return url.substring(url.indexOf("token=") + 6); }

    private static String sha(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static byte[] png() throws Exception {
        var image = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static class MemoryStorage implements ObjectStoragePort {
        final Map<String, byte[]> values = new ConcurrentHashMap<>();
        boolean fail;
        boolean externalDownloads;
        Runnable onPut;
        public String presignUploadUrl(String key, long ttl) { return "local://" + key; }
        public String presignDownloadUrl(String key, long ttl) { return (externalDownloads ? "https://storage.invalid/" : "local://") + key; }
        public boolean existsWithSize(String key, long size) {
            if (fail) throw new IllegalStateException("storage unavailable");
            return values.containsKey(key) && values.get(key).length == size;
        }
        public InputStream getObject(String key) { return new ByteArrayInputStream(values.get(key)); }
        public void putObject(String key, java.nio.file.Path file) {
            try { putObject(key, java.nio.file.Files.readAllBytes(file)); }
            catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
        }
        void putObject(String key, byte[] value) { values.put(key, value.clone()); if (onPut != null) onPut.run(); }
    }
}
