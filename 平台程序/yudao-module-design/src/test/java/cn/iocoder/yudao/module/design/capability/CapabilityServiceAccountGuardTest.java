package cn.iocoder.yudao.module.design.capability;

import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.design.capability.security.ServicePrincipal;
import cn.iocoder.yudao.module.infra.zhongshu.api.AccountStatePort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class CapabilityServiceAccountGuardTest {
    @Container static final PostgreSQLContainer<?> PG = cn.iocoder.yudao.module.platform.test.TestPostgres.database("postgres:17-alpine");
    private CapabilityServiceAccountGuard guard;
    private TransactionTemplate tx;
    private JdbcTemplate jdbc;
    private long owner;

    @BeforeEach void setup() {
        var ds = new SimpleDriverDataSource(new org.postgresql.Driver(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        var flyway = Flyway.configure().dataSource(ds).cleanDisabled(false).locations("classpath:db/migration/design-capability").load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(ds);
        owner = jdbc.queryForObject("INSERT INTO design_capability_organization(caller_app_id,org_id,tenant_id,active) VALUES('platform',10,1,true) RETURNING service_account_id", Long.class);
        guard = new CapabilityServiceAccountGuard(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void serviceWriteRequiresMatchingAuthenticatedOrganizationAndTransaction() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> guard.requireActiveForWrite(owner)))
                .isInstanceOf(SecurityException.class);
        authenticate(new ServicePrincipal("platform", 11, 1, owner));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> guard.requireActiveForWrite(owner)))
                .isInstanceOf(SecurityException.class);
        authenticate(new ServicePrincipal("platform", 10, 1, owner));
        assertThatThrownBy(() -> guard.requireActiveForWrite(owner)).isInstanceOf(IllegalStateException.class);
        tx.executeWithoutResult(status -> guard.requireActiveForWrite(owner));
        jdbc.update("UPDATE design_capability_organization SET active=false");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> guard.requireActiveForWrite(owner)))
                .isInstanceOf(SecurityException.class);
    }

    @Test void springCanOnlyChooseTheConstructorWithAnExplicitAccountGuard() {
        for (Class<?> type : new Class<?>[]{AssetService.class, DesignProjectService.class}) {
            var selected = Arrays.stream(type.getConstructors()).filter(c -> c.isAnnotationPresent(Autowired.class)).toList();
            assertThat(selected).hasSize(1);
            assertThat(Arrays.asList(selected.get(0).getParameterTypes())).contains(AccountStatePort.class);
        }
    }

    private void authenticate(ServicePrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal, null, "SERVICE"));
    }
}
