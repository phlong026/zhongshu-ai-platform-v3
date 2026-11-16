package cn.iocoder.yudao.module.franchise.design.remote;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ReferenceMirrorStoreTest {
    @Container
    static final PostgreSQLContainer<?> PG = cn.iocoder.yudao.module.platform.test.TestPostgres.database("postgres:17-alpine");

    SimpleDriverDataSource ds;
    JdbcTemplate jdbc;
    ReferenceMirrorStore store;
    ReferenceMirrorStore.Scope scope =
            new ReferenceMirrorStore.Scope("B", 1, 33, 22, 11, 123, "a".repeat(64));

    @BeforeEach
    void setup() {
        ds =
                new SimpleDriverDataSource(
                        new org.postgresql.Driver(),
                        PG.getJdbcUrl(),
                        PG.getUsername(),
                        PG.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS fr_design_reference_mirror");
        new ResourceDatabasePopulator(
                        new ClassPathResource(
                                "db/migration/franchise-design-remote/V20261115.970__reference_asset_mirror.sql"))
                .execute(ds);
        store = new ReferenceMirrorStore(ds, new DataSourceTransactionManager(ds));
    }

    @Test
    void oneLeaseAndRestartRecoverTheSameAttemptAndAcceptedMapping() {
        var first = store.claim(scope);
        assertThat(first.recovery()).isFalse();
        assertThatThrownBy(() -> store.claim(scope)).hasMessageContaining("正在同步");
        store.release(first);
        var recovered = store.claim(scope);
        assertThat(recovered.recovery()).isTrue();
        assertThat(recovered.attempt()).isEqualTo(first.attempt());
        store.accept(recovered, 901);
        store.release(recovered);
        var reopened = new ReferenceMirrorStore(ds, new DataSourceTransactionManager(ds));
        assertThat(reopened.claim(scope).acceptedId()).isEqualTo(901);
        assertThat(
                        reopened.claim(
                                        new ReferenceMirrorStore.Scope(
                                                "B", 1, 33, 22, 12, 123, "a".repeat(64)))
                                .acceptedId())
                .isNull();
    }

    @Test
    void staleLeaseCannotOverwriteTheWinnerOrReleaseItsLock() {
        var old = store.claim(scope);
        jdbc.update(
                "UPDATE fr_design_reference_mirror SET lease_until=clock_timestamp()-interval '1"
                        + " second'");
        var winner = store.claim(scope);
        store.release(old);
        assertThatThrownBy(() -> store.accept(old, 999)).hasMessageContaining("过期");
        assertThatThrownBy(() -> store.claim(scope)).hasMessageContaining("正在同步");
        store.accept(winner, 901);
        assertThat(store.claim(scope).acceptedId()).isEqualTo(901);
    }

    @Test
    void scopesAreIsolatedAndUndoRefusesAnyPersistedMapping() {
        var one = store.claim(scope);
        store.accept(one, 901);
        for (var other :
                java.util.List.of(
                        new ReferenceMirrorStore.Scope("C", 1, 33, 22, 11, 123, "a".repeat(64)),
                        new ReferenceMirrorStore.Scope("B", 2, 33, 22, 11, 123, "a".repeat(64)),
                        new ReferenceMirrorStore.Scope("B", 1, 34, 22, 11, 123, "a".repeat(64)),
                        new ReferenceMirrorStore.Scope("B", 1, 33, 23, 11, 123, "a".repeat(64)),
                        new ReferenceMirrorStore.Scope("B", 1, 33, 22, 11, 123, "b".repeat(64))))
            assertThat(store.claim(other).acceptedId()).isNull();
        assertThatThrownBy(
                        () ->
                                new ResourceDatabasePopulator(
                                                new ClassPathResource(
                                                        "db/migration-undo/franchise-design-remote/V20261115.970__undo_reference_asset_mirror.sql"))
                                        .execute(ds))
                .hasStackTraceContaining("reference mappings exist");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM fr_design_reference_mirror", Integer.class))
                .isEqualTo(6);
    }
}
