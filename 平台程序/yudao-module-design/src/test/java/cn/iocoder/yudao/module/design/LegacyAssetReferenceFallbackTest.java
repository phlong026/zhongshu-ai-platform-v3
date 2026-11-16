package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LegacyAssetReferenceFallbackTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = cn.iocoder.yudao.module.platform.test.TestPostgres.database("postgres:17-alpine");
    private static LegacyAssetAccessAdapter bridge;

    @BeforeAll
    static void setUp() {
        var dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE asset (id bigint PRIMARY KEY, owner_user_id bigint NOT NULL, "
                + "asset_type text NOT NULL, upload_status text NOT NULL, deleted boolean NOT NULL)");
        jdbc.execute("INSERT INTO asset VALUES "
                + "(101, 7, 'CONTRACT', 'ACCEPTED', false), "
                + "(102, 8, 'CONTRACT', 'ACCEPTED', false), "
                + "(103, 7, 'CONTRACT', 'ACCEPTED', true), "
                + "(104, 7, 'CONTRACT', 'PENDING', false), "
                + "(105, 7, 'CONTRACT', 'REJECTED', false)");
        var design = new FranchiseDesignAdapter(null, null, null, null, null, null, null,
                new StaticListableBeanFactory().getBeanProvider(UsagePricingPort.class),
                new DataSourceTransactionManager(dataSource), dataSource);
        bridge = new LegacyAssetAccessAdapter(design, orgId -> 7L);
    }

    @ParameterizedTest
    @ValueSource(longs = {101, 102, 103, 104, 105})
    void anyGlobalAssetRecordBlocksLegacyFileFallback(long assetId) {
        assertThat(bridge.legacyReferenceAllowed(new AssetAccessPort.Actor(1, 7, 11, 12), assetId)).isFalse();
    }

    @Test
    void onlyGloballyMissingAssetAllowsLegacyFileLookup() {
        assertThat(bridge.legacyReferenceAllowed(new AssetAccessPort.Actor(1, 7, 11, 12), 999)).isTrue();
    }
}
