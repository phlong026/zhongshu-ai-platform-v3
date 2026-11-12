package cn.iocoder.yudao.module.design.capability;

import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class CapabilityReceiptStoreTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private CapabilityReceiptStore store;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        var ds = new SimpleDriverDataSource(new org.postgresql.Driver(), POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(ds).cleanDisabled(false)
                .locations("classpath:db/migration/design-capability").load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO design_capability_organization(caller_app_id,org_id,tenant_id,active) VALUES('platform',10,1,true),('platform',11,2,true)");
        jdbc.execute("CREATE TABLE capability_test_business(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY)");
        store = new CapabilityReceiptStore(ds, new DataSourceTransactionManager(ds), new ObjectMapper());
    }

    private Request<?> request(long org, String key, String nonce, String source) {
        return new Request<>("platform", org, new DesignServicePort.Actor(1, 2, "DESIGNER"),
                Operation.CREATE_PROJECT, key, nonce,
                new DesignServicePort.DesignProjectCreate(source, null, null, Map.of(), "FLAT", key));
    }

    private Object createBusiness() {
        return new DesignServicePort.ProjectCreated(jdbc.queryForObject(
                "INSERT INTO capability_test_business DEFAULT VALUES RETURNING id", Long.class), false);
    }

    @Test
    void concurrentRequestsCommitExactlyOneBusinessAndReceipt() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            var calls = new ArrayList<Callable<Reply<Object>>>();
            for (int i = 0; i < 8; i++) {
                int nonce = i;
                calls.add(() -> store.executeAtomic(request(10, "stable", "nonce" + nonce, "SELF_UPLOAD"),
                        this::createBusiness));
            }
            var replies = pool.invokeAll(calls, 20, TimeUnit.SECONDS);
            long fresh = 0;
            for (var reply : replies) if (!reply.get().replayed()) fresh++;
            assertThat(fresh).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM capability_test_business", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM design_capability_receipt", Integer.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void changedBusinessOrActorConflictsButTransportNonceDoesNot() {
        store.executeAtomic(request(10, "stable", "n1", "SELF_UPLOAD"), this::createBusiness);
        assertThat(store.executeAtomic(request(10, "stable", "n2", "SELF_UPLOAD"), this::createBusiness).replayed()).isTrue();
        assertThatThrownBy(() -> store.executeAtomic(request(10, "stable", "n3", "REFERENCE"), this::createBusiness))
                .isInstanceOf(CapabilityException.class).extracting("httpStatus").isEqualTo(409);
        var changed = new Request<>("platform", 10, new DesignServicePort.Actor(9, 2, "DESIGNER"),
                Operation.CREATE_PROJECT, "stable", "n4", request(10, "stable", "n1", "SELF_UPLOAD").payload());
        assertThatThrownBy(() -> store.executeAtomic(changed, this::createBusiness)).isInstanceOf(CapabilityException.class);
    }

    @Test
    void receiptAndBusinessRollBackTogetherThenSameKeyCanRetry() {
        assertThatThrownBy(() -> store.executeAtomic(request(10, "stable", "n1", "SELF_UPLOAD"), () -> {
            createBusiness();
            throw new IllegalStateException("injected before receipt commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM capability_test_business", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_capability_receipt", Integer.class)).isZero();
        assertThat(store.executeAtomic(request(10, "stable", "n2", "SELF_UPLOAD"), this::createBusiness).replayed()).isFalse();
    }

    @Test
    void lostResponseIsRecoveredByScopedReceiptAndOtherOrganizationCannotReadIt() {
        var original = store.executeAtomic(request(10, "stable", "n1", "SELF_UPLOAD"), this::createBusiness);
        // The committed HTTP reply is intentionally discarded; no second mutation is submitted.
        var recovered = store.query("platform", 10, new ReceiptQuery(Operation.CREATE_PROJECT, "stable"));
        assertThat(recovered.receiptId()).isEqualTo(original.receipt().receiptId());
        assertThat(recovered.status()).isEqualTo(ReceiptStatus.SUCCEEDED);
        assertThat(store.query("platform", 11, new ReceiptQuery(Operation.CREATE_PROJECT, "stable")).status())
                .isEqualTo(ReceiptStatus.NOT_FOUND);
    }
}
