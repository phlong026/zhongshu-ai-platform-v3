package cn.iocoder.yudao.module.franchise.design;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import cn.iocoder.yudao.module.franchise.design.billing.DesignWalletChargeAdapter;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.wallet.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesignBillingIsolationIntegrationTest {
    PostgreSQLContainer<?> container;
    SimpleDriverDataSource ds;
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    DesignWalletChargeAdapter billing;
    PostSnapshot actor=new PostSnapshot(41,51,21,"store","owner",null,false,Set.of());
    FlatJob payload=new FlatJob(71,new DesignServicePort.FlatJobCreate(2,"2K","PORTRAIT","request"));
    PlatformPriceSnapshot price;

    @BeforeAll void database() throws Exception {
        ds=new SimpleDriverDataSource(); ds.setDriverClass(org.postgresql.Driver.class);
        if (Boolean.getBoolean("prelaunch.nativePg")) {
            ds.setUrl("jdbc:postgresql://127.0.0.1:55445/postgres"); ds.setUsername("dev_fixture");
            var admin=new JdbcTemplate(ds);
            if (admin.queryForObject("SELECT count(*) FROM pg_database WHERE datname='prelaunch_b_design_billing'",Integer.class)==0) admin.execute("CREATE DATABASE prelaunch_b_design_billing");
            ds.setUrl("jdbc:postgresql://127.0.0.1:55445/prelaunch_b_design_billing");
        } else {
            container=new PostgreSQLContainer<>("postgres:17-alpine"); container.start();
            ds.setUrl(container.getJdbcUrl()); ds.setUsername(container.getUsername()); ds.setPassword(container.getPassword());
        }
        jdbc=new JdbcTemplate(ds);
        String schema="billing_"+java.util.UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE SCHEMA "+schema);
        ds.setUrl(ds.getUrl()+(ds.getUrl().contains("?")?"&":"?")+"currentSchema="+schema);
        jdbc.execute("CREATE TABLE system_dept(id BIGINT PRIMARY KEY,tenant_id BIGINT,status INT,deleted INT)");
        jdbc.execute("CREATE TABLE pf_org_unit(dept_id BIGINT PRIMARY KEY,org_id BIGINT,kind TEXT,status TEXT)");
        jdbc.execute("CREATE TABLE system_users(id BIGINT PRIMARY KEY,tenant_id BIGINT NOT NULL,status SMALLINT,deleted SMALLINT)");
        jdbc.execute("CREATE TABLE pf_staff_post(id BIGINT PRIMARY KEY,user_id BIGINT,org_id BIGINT,role_code TEXT,status TEXT)");
        for (String path:java.util.List.of("db/migration/platform-wallet/V20261111.901__pf_wallet_accounting.sql",
                "db/migration/franchise-design-remote/V20261111.945__design_wallet_pricing.sql")) {
            try (var stream=new ClassPathResource(path).getInputStream()) { jdbc.execute(new String(stream.readAllBytes(),StandardCharsets.UTF_8)); }
        }
        var transactions=new DataSourceTransactionManager(ds); tx=new TransactionTemplate(transactions);
        StaffPostService posts=mock(StaffPostService.class);
        when(posts.findActivePost(51,41)).thenReturn(Optional.of(actor));
        CurrentPost current=() -> Optional.of(actor);
        var wallet=new WalletChargeService(ds,transactions,new WalletScope(ds,current,posts),new PointAccountService(ds));
        billing=new DesignWalletChargeAdapter(ds,transactions,wallet);
        jdbc.update("INSERT INTO system_dept VALUES(21,31,0,0)");
        jdbc.update("INSERT INTO pf_org_unit VALUES(21,21,'ORG','ACTIVE')");
        jdbc.update("INSERT INTO system_users VALUES(51,31,0,0)");
        jdbc.update("INSERT INTO pf_staff_post VALUES(41,51,21,'owner','ACTIVE')");
    }
    @AfterAll void stop() { if(container!=null) container.stop(); }
    @BeforeEach void fixture() {
        TenantContextHolder.setTenantId(31L);
        jdbc.execute("TRUNCATE fr_design_billing,fr_design_price,pf_wallet_charge,pf_point_ledger,pf_point_account,pf_wallet");
        jdbc.update("INSERT INTO pf_wallet(id,org_id,tenant_id) VALUES(11,21,31)");
        jdbc.update("INSERT INTO pf_point_account(wallet_id,tenant_id,available_points) VALUES(11,31,1000)");
        jdbc.update("INSERT INTO fr_design_price VALUES(31,1,1,'CREATE_FLAT_JOB','2K','B_WALLET_POINTS',4,9,0,1,8,0,TRUE,now())");
        price=tx.execute(status -> billing.quote(actor,Operation.CREATE_FLAT_JOB,payload));
    }
    @AfterEach void clear() { TenantContextHolder.clear(); }
    void reserve(long id) { tx.executeWithoutResult(status -> billing.reserve(id,actor,price)); }
    void settle(long id,Settlement facts) { tx.executeWithoutResult(status -> billing.settle(id,actor,price,facts)); }
    Settlement facts(long version,boolean terminal,long prompt,long images) {
        return new Settlement(91,version,"PARTIALLY_SUCCEEDED",terminal,2,(int)images,900_000,800_000,100_000,prompt,images,0);
    }
    void balance(long available,long reserved,int ledgers) {
        assertThat(jdbc.queryForObject("SELECT available_points FROM pf_point_account WHERE wallet_id=11",Long.class)).isEqualTo(available);
        assertThat(jdbc.queryForObject("SELECT reserved_points FROM pf_point_account WHERE wallet_id=11",Long.class)).isEqualTo(reserved);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_point_ledger",Integer.class)).isEqualTo(ledgers);
    }

    @Test void partialThenLatePromptUsesOriginalBPriceAndReleasesOnlyAtFinancialTerminal() {
        reserve(101); balance(978,22,0);
        jdbc.update("UPDATE fr_design_price SET active=FALSE");
        TenantContextHolder.clear(); // The task owns a durable wallet reference; recovery does not impersonate a user.
        settle(101,facts(2,false,0,1)); balance(978,13,1);
        settle(101,facts(2,false,0,1)); balance(978,13,1);
        settle(101,facts(3,false,0,1)); balance(978,13,1);
        settle(101,facts(4,true,1,1)); balance(987,0,2);
        settle(101,facts(4,true,1,1)); balance(987,0,2);
    }

    @Test void outOfOrderOrChangedSameVersionCannotDebitOrRelease() {
        reserve(102); settle(102,facts(4,false,1,0));
        assertThatThrownBy(() -> settle(102,facts(3,true,1,1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> settle(102,facts(4,true,1,1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> settle(102,facts(5,false,0,0))).isInstanceOf(IllegalStateException.class);
        balance(978,18,1);
    }

    @Test void cancelWithoutAnyResultReleasesReservationAndDoesNotCreateDebit() {
        reserve(103);
        settle(103,new Settlement(91,4,"CANCELLED",true,2,0,1,0,1,0,0,0));
        balance(1000,0,0);
    }

    @Test void concurrentRepeatedTerminalHasOneDebit() throws Exception {
        reserve(104);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(() -> settle(104,facts(4,true,1,1)));
            var b=pool.submit(() -> settle(104,facts(4,true,1,1)));
            a.get(15,TimeUnit.SECONDS); b.get(15,TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        balance(987,0,1);
    }

    @Test void synchronousReceiptChargesBUsageOnceAndDefiniteRejectionReleases() {
        jdbc.update("INSERT INTO fr_design_price VALUES(31,2,1,'NOTE_POLISH','','B_WALLET_POINTS',0,0,6,0,0,1,TRUE,now())");
        var note=new Note("老人房在一楼","FLAT");
        var quote=tx.execute(status -> billing.quote(actor,Operation.NOTE_POLISH,note));
        tx.executeWithoutResult(status -> billing.reserve(201,actor,quote));
        balance(994,6,0);
        TenantContextHolder.clear();
        tx.executeWithoutResult(status -> billing.completeSynchronous(201,actor,quote,Operation.NOTE_POLISH));
        tx.executeWithoutResult(status -> billing.completeSynchronous(201,actor,quote,Operation.NOTE_POLISH));
        balance(994,0,1);
        TenantContextHolder.setTenantId(31L);
        tx.executeWithoutResult(status -> billing.reserve(202,actor,quote));
        tx.executeWithoutResult(status -> billing.reject(202,actor,quote,429));
        tx.executeWithoutResult(status -> billing.reject(202,actor,quote,429));
        balance(994,0,1);
    }

    @Test void missingBudgetRuleReleasesBReservationWithoutAnyDebit() {
        jdbc.update("INSERT INTO fr_design_price VALUES(31,3,1,'CREATE_BUDGET_ESTIMATE','','B_WALLET_POINTS',0,0,6,0,0,1,TRUE,now())");
        var budget=new Budget(71,new DesignServicePort.BudgetEstimateCreate("NO_RULE","BRICK","A",120,null,"no-budget-rule"));
        var quote=tx.execute(status -> billing.quote(actor,Operation.CREATE_BUDGET_ESTIMATE,budget));
        tx.executeWithoutResult(status -> billing.reserve(203,actor,quote));
        balance(994,6,0);
        TenantContextHolder.clear();
        tx.executeWithoutResult(status -> billing.reject(203,actor,quote,1_071_000_002));
        tx.executeWithoutResult(status -> billing.reject(203,actor,quote,1_071_000_002));
        balance(1000,0,0);
        assertThat(jdbc.queryForObject("SELECT charged_points FROM fr_design_billing WHERE command_id=203",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT refunded_points FROM fr_design_billing WHERE command_id=203",Long.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT terminal FROM fr_design_billing WHERE command_id=203",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT settlement_fingerprint FROM fr_design_billing WHERE command_id=203",String.class))
                .isEqualTo("REJECTED:1071000002");
    }

    @Test void emptyPriceCatalogCanStartHqButPaidQuoteFailsWithoutAnyReservation() {
        jdbc.update("DELETE FROM fr_design_price");
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withPropertyValues("platform.legacy-design-bridge.enabled=false")
                .withBean(DesignWalletChargeAdapter.class, () -> billing, definition -> definition.setInitMethodName("requireReady"))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DesignWalletChargeAdapter.class);
                    assertThatThrownBy(() -> tx.execute(status -> context.getBean(DesignWalletChargeAdapter.class)
                            .quote(actor,Operation.CREATE_FLAT_JOB,payload))).isInstanceOf(IllegalStateException.class);
                });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_design_billing",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_wallet_charge",Integer.class)).isZero();
        balance(1000,0,0);
    }

    @Test void anotherTenantCannotQuoteOrReserveThisTenantsRule() {
        TenantContextHolder.setTenantId(32L);
        assertThatThrownBy(() -> tx.execute(status -> billing.quote(actor,Operation.CREATE_FLAT_JOB,payload)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reserve(106)).isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_design_billing",Integer.class)).isZero();
        balance(1000,0,0);
    }

    @Test void reserveFailureRollsBackBillingAndWalletTogether() {
        jdbc.update("UPDATE pf_point_account SET available_points=1");
        assertThatThrownBy(() -> reserve(105))
                .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(1_120_000_003));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_design_billing",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_wallet_charge",Integer.class)).isZero();
        balance(1,0,0);
    }
}
