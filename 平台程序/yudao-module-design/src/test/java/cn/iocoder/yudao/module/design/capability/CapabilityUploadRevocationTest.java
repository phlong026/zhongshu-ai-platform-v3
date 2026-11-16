package cn.iocoder.yudao.module.design.capability;

import cn.iocoder.yudao.module.design.asset.*;
import cn.iocoder.yudao.module.design.capability.security.ServicePrincipal;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Interleave committed mapping revocation between real PG short transactions; storage/moderation are simulated. */
@Testcontainers
class CapabilityUploadRevocationTest {
    @Container static final PostgreSQLContainer<?> PG = cn.iocoder.yudao.module.platform.test.TestPostgres.database("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private AssetService assets;
    private CapabilityReceiptStore receipts;
    private DesignCapabilityProvider provider;
    private ServiceDesignBillingPort billing;
    private ObjectStoragePort storage;
    private ContentModerationPort moderation;
    private long owner;
    private byte[] image;

    @BeforeEach void setup() throws Exception {
        var source = new SimpleDriverDataSource(new org.postgresql.Driver(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        var flyway = Flyway.configure().dataSource(source).cleanDisabled(false)
                .locations("classpath:db/migration/platform", "classpath:db/migration/design", "classpath:db/migration/design-capability").load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(source);
        owner = jdbc.queryForObject("INSERT INTO design_capability_organization(caller_app_id,org_id,tenant_id,active) VALUES('platform',10,1,true) RETURNING service_account_id", Long.class);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(new ServicePrincipal("platform", 10, 1, owner), null, "SERVICE"));
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", bytes);
        image = bytes.toByteArray();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        jdbc.update("INSERT INTO asset(id,object_key,owner_user_id,asset_type,source_type,sha256,declared_mime,size_bytes) VALUES(100,'test/upload',?,'USER_SKETCH','USER_UPLOAD',?,'image/png',?)", owner, hash, image.length);
        storage = mock(ObjectStoragePort.class);
        when(storage.existsWithSize(anyString(), anyLong())).thenReturn(true);
        when(storage.getObject(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(image));
        moderation = mock(ContentModerationPort.class);
        when(moderation.review(anyString(), any())).thenReturn(new ContentModerationPort.Decision(true, "fixture", "fixture-request", "pass", "fixture"));
        var transactions = new DataSourceTransactionManager(source);
        receipts = new CapabilityReceiptStore(source, transactions, new com.fasterxml.jackson.databind.ObjectMapper());
        assets = new AssetService(source, transactions, storage, new AssetContentScanner(), moderation,
                null, null, new CapabilityServiceAccountGuard(source));
        billing = mock(ServiceDesignBillingPort.class);
        provider = new DesignCapabilityProvider(mock(DesignServicePort.class), new CapabilityOrganizationMapping(source),
                receipts, billing, source, assets);
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private void revoke() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        jdbc.update("UPDATE design_capability_organization SET active=false WHERE org_id=10");
    }
    private String state() { return jdbc.queryForObject("SELECT upload_status FROM asset WHERE id=100", String.class); }

    @Test void queryingTheOriginalReceiptActuallyRetriesModerationAndCompletesTheSameAsset() {
        var principal = new ServicePrincipal("platform", 10, 1, owner);
        var actor = new DesignServicePort.Actor(1, 2, "DESIGNER");
        var original = new Request<>("platform", 10, actor, Operation.COMPLETE_UPLOAD, "upload-recovery", "first-nonce", new Asset(100));
        when(moderation.review(anyString(), any())).thenThrow(new ModerationUnavailableException())
                .thenReturn(new ContentModerationPort.Decision(true, "fixture", "recovered", "pass", "fixture"));
        var first = provider.invoke(principal, Operation.COMPLETE_UPLOAD, original);
        assertThat(first.code()).isEqualTo(1_112_100_002);
        assertThat(first.receipt().status()).isEqualTo(ReceiptStatus.UNKNOWN);
        assertThat(state()).isEqualTo("PENDING");
        var query = new Request<>("platform", 10, actor, Operation.QUERY_RECEIPT, "lookup", "query-nonce",
                new ReceiptQuery(Operation.COMPLETE_UPLOAD, original.requestKey()));
        // Expire only the receipt lease: the asset's original moderation backoff must still apply.
        jdbc.update("UPDATE design_capability_receipt SET lease_until=clock_timestamp()-interval '1 second'");
        assertThat(((Receipt) provider.invoke(principal, Operation.QUERY_RECEIPT, query).result()).status()).isEqualTo(ReceiptStatus.UNKNOWN);
        verify(moderation, times(1)).review(anyString(), any());
        jdbc.update("UPDATE design_capability_receipt SET lease_until=clock_timestamp()-interval '1 second'");
        jdbc.update("UPDATE asset SET moderation_retry_at=clock_timestamp()-interval '1 second' WHERE id=100");
        Receipt recovered = (Receipt) provider.invoke(principal, Operation.QUERY_RECEIPT, query).result();
        assertThat(recovered.status()).isEqualTo(ReceiptStatus.SUCCEEDED);
        assertThat(recovered.receiptId()).isEqualTo(first.receipt().receiptId());
        assertThat(recovered.requestKey()).isEqualTo(original.requestKey());
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().convertValue(recovered.result(), DesignServicePort.AssetCompleted.class).uploadStatus())
                .isEqualTo("ACCEPTED");
        assertThat(state()).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_capability_receipt", Integer.class)).isEqualTo(1);
        verify(moderation, times(2)).review(anyString(), any());
        verifyNoInteractions(billing);
    }

    @Test void revocationAfterIngressPreventsClaimingValidation() {
        revoke();
        assertThatThrownBy(() -> assets.completeUpload(owner, 100)).isInstanceOf(SecurityException.class);
        assertThat(state()).isEqualTo("PENDING");
        verifyNoInteractions(storage, moderation);
    }

    @Test void revocationDuringModerationCannotCommitAcceptedAndSameAssetCanRecoverAfterReenable() {
        when(moderation.review(anyString(), any())).thenAnswer(invocation -> {
            revoke();
            return new ContentModerationPort.Decision(true, "fixture", "fixture-request", "pass", "fixture");
        });
        assertThatThrownBy(() -> assets.completeUpload(owner, 100)).isInstanceOf(SecurityException.class);
        assertThat(state()).isEqualTo("VALIDATING");
        String oldLease = jdbc.queryForObject("SELECT validation_token FROM asset WHERE id=100", String.class);
        jdbc.update("UPDATE design_capability_organization SET active=true");
        jdbc.update("UPDATE asset SET update_time=now()-interval '11 minutes' WHERE id=100");
        doReturn(new ContentModerationPort.Decision(true, "fixture", "recovered", "pass", "fixture"))
                .when(moderation).review(anyString(), any());
        assertThat(assets.completeUpload(owner, 100)).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT validation_token FROM asset WHERE id=100", String.class)).isNotEqualTo(oldLease);
    }

    @Test void revocationBeforeFinalCompareAndSetCannotCommitAccepted() {
        doAnswer(invocation -> { revoke(); return null; }).when(storage).putObject(startsWith("accepted/"), any());
        assertThatThrownBy(() -> assets.completeUpload(owner, 100)).isInstanceOf(SecurityException.class);
        assertThat(state()).isEqualTo("VALIDATING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_scan_result", Integer.class)).isZero();
    }

    @Test void receiptCallbackFailureRollsBackTheAssetTerminalStateAndReceiptTogether() {
        var action = cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Operation.COMPLETE_UPLOAD;
        var request = new cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Request<>("platform", 10,
                new cn.iocoder.yudao.module.franchise.api.DesignServicePort.Actor(1, 2, "DESIGNER"), action, "upload", "nonce",
                new cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Asset(100));
        var lease = receipts.claim(request);
        assertThatThrownBy(() -> assets.completeUploadWithCompletion(owner, 100, status -> {
            receipts.completeInCurrentTransaction(lease, ignored -> new cn.iocoder.yudao.module.franchise.api.DesignServicePort.AssetCompleted(100, status, null));
            throw new IllegalStateException("injected failure after receipt write");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(state()).isEqualTo("VALIDATING");
        assertThat(jdbc.queryForObject("SELECT state FROM design_capability_receipt", String.class)).isEqualTo("PROCESSING");
        jdbc.update("UPDATE asset SET update_time=now()-interval '11 minutes' WHERE id=100");
        assets.completeUploadWithCompletion(owner, 100, status -> receipts.completeInCurrentTransaction(lease,
                ignored -> new cn.iocoder.yudao.module.franchise.api.DesignServicePort.AssetCompleted(100, status, null)));
        assertThat(state()).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT state FROM design_capability_receipt", String.class)).isEqualTo("SUCCEEDED");
    }

    @Test void revocationAlsoPreventsModerationBackoffWrites() {
        when(moderation.review(anyString(), any())).thenAnswer(invocation -> { revoke(); throw new ModerationUnavailableException(); });
        assertThatThrownBy(() -> assets.completeUpload(owner, 100)).isInstanceOf(SecurityException.class);
        assertThat(state()).isEqualTo("VALIDATING");
        assertThat(jdbc.queryForObject("SELECT moderation_attempts FROM asset WHERE id=100", Integer.class)).isZero();
    }
}
