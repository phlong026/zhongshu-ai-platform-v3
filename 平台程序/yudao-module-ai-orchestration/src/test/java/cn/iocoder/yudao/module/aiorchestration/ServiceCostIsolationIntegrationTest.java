package cn.iocoder.yudao.module.aiorchestration;

import cn.iocoder.yudao.module.aiorchestration.billing.PlatformCostService;
import cn.iocoder.yudao.module.aiorchestration.job.*;
import cn.iocoder.yudao.module.commerce.points.*;
import cn.iocoder.yudao.module.commerce.pricing.*;
import cn.iocoder.yudao.module.infra.zhongshu.api.*;
import cn.iocoder.yudao.module.infra.zhongshu.event.JdbcReliableEventPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceCostIsolationIntegrationTest {
    PostgreSQLContainer<?> container;
    SimpleDriverDataSource ds;
    JdbcTemplate jdbc;
    PointAccountService points;
    PlatformCostService costs;
    AiJobOrchestrationService orchestration;
    AiJobSettlementService settlement;
    PricingPort.PriceConfirmation consumerPrice;

    @BeforeAll void database() {
        ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        if (Boolean.getBoolean("prelaunch.nativePg")) {
            ds.setUrl("jdbc:postgresql://127.0.0.1:55445/postgres");
            ds.setUsername("dev_fixture");
            var admin = new JdbcTemplate(ds);
            if (admin.queryForObject("SELECT count(*) FROM pg_database WHERE datname='prelaunch_service_cost'", Integer.class) == 0) {
                admin.execute("CREATE DATABASE prelaunch_service_cost");
            }
            ds.setUrl("jdbc:postgresql://127.0.0.1:55445/prelaunch_service_cost");
        } else {
            container = cn.iocoder.yudao.module.platform.test.TestPostgres.database("postgres:17-alpine");
            container.start();
            ds.setUrl(container.getJdbcUrl());
            ds.setUsername(container.getUsername());
            ds.setPassword(container.getPassword());
        }
        String schema="service_cost_"+java.util.UUID.randomUUID().toString().replace("-","");
        new JdbcTemplate(ds).execute("CREATE SCHEMA "+schema);
        ds.setUrl(ds.getUrl()+(ds.getUrl().contains("?")?"&":"?")+"currentSchema="+schema);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform", "classpath:db/migration/commerce",
                "classpath:db/migration/ai-orchestration").load().migrate();
        jdbc = new JdbcTemplate(ds);
    }

    @AfterAll void stop() { if (container != null) container.stop(); }

    @BeforeEach void clean() {
        jdbc.execute("TRUNCATE platform_cost_ledger,platform_cost_business,platform_cost_price,ai_job,ai_job_attempt,ai_job_result,ai_job_settlement,ai_task_charge,service_usage_charge,service_usage_price_rule,generation_price_rule,design_point_account,design_point_ledger,outbox_event");
        var tx = new DataSourceTransactionManager(ds);
        points = new PointAccountService(ds, tx);
        var ledger = new PointLedgerPortAdapter(points);
        var usage = new UsagePointPriceService(ds, tx, ledger);
        var prices = new PriceRuleService(ds);
        long priceId = prices.createRule("FLAT", 10, 1, 4, Instant.now().minusSeconds(5), null);
        usage.createRule("AI_PROMPT", 7, Instant.now().minusSeconds(5), null, "fixture");
        var quote = usage.quote("AI_PROMPT");
        consumerPrice = new PricingPort.PriceConfirmation(Long.toString(priceId), 1,
                new UsagePricingPort.Confirmation("AI_PROMPT", Long.toString(quote.ruleId()), quote.ruleVersion()));
        points.credit(901, "RECHARGE_BASE_CREDIT", 1000, "fixture", "fixture", "fixture-credit", null, "fixture");
        var events = new JdbcReliableEventPort(ds);
        orchestration = new AiJobOrchestrationService(ds, tx, events, null, null);
        costs = new PlatformCostService(ds, tx);
        costs.setServiceAuthorization(subject -> {
            if (!"platform-b".equals(subject.callerAppId()) || subject.orgId()!=21 || subject.tenantId()!=31 || subject.serviceAccountId()!=901) {
                throw new IllegalStateException("Fixture service mapping does not match");
            }
        });
        settlement = new AiJobSettlementService(ds, tx, new PricingPortAdapter(prices), ledger, orchestration, events, usage);
        settlement.setPlatformCostPort(costs);
        jdbc.update("INSERT INTO platform_cost_price(product,version,unit,prompt_micros,image_micros,usage_micros) VALUES ('FLAT:2K',1,'MICRO_CNY',3,11,0)");
    }

    long serviceJob(String receipt, int count) {
        costs.prepare(new PlatformCostPort.Subject("platform-b", 21, 31, 901, receipt, "request-" + receipt),
                "FLAT:2K", count, "fingerprint-" + receipt);
        return settlement.createJobWithCharge(901, "FLAT", count, "svc:" + receipt, "91", null, "2K");
    }

    void consumerUntouched() {
        assertThat(points.findAccount(901).orElseThrow().availablePoints()).isEqualTo(1000);
        assertThat(points.findAccount(901).orElseThrow().reservedPoints()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_usage_charge", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_charge", Integer.class)).isZero();
    }

    @Test void consumerPathStillChargesAndRefundsConsumerLedger() {
        long id = settlement.createJobWithCharge(901, "FLAT", 1, "consumer", "91", consumerPrice);
        assertThat(points.findAccount(901).orElseThrow().availablePoints()).isEqualTo(990);
        settlement.settle(id, "SETTLE");
        assertThat(points.findAccount(901).orElseThrow().availablePoints()).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_cost_ledger", Integer.class)).isZero();
    }

    @Test void unknownPromptSurvivesTerminalAndLateRecoveryInFreshService() {
        long id = serviceJob("unknown", 2);
        var claim = orchestration.claim("fixture", 1, 60, "fake").get(0);
        settlement.reservePromptCall(id, claim.attemptNo(), claim.fencingToken());
        settlement.finishPromptCall(id, claim.attemptNo(), claim.fencingToken(), "DISPATCHED", "plan-" + id, null);
        settlement.settle(id, "SETTLE");
        var before = costs.facts(id);
        assertThat(before.terminal()).isFalse();
        assertThat(before.refunded()).isEqualTo(22);
        assertThatThrownBy(() -> costs.finishPrompt(id,claim.fencingToken(),"NOT_SENT",null,null,false))
                .isInstanceOf(IllegalStateException.class);
        var recovered = new PlatformCostService(ds, new DataSourceTransactionManager(ds));
        assertThat(recovered.finishPrompt(id, claim.fencingToken(), "SUCCEEDED", "plan-" + id, "a".repeat(64), true)).isTrue();
        assertThat(recovered.finishPrompt(id, claim.fencingToken(), "SUCCEEDED", "plan-" + id, "a".repeat(64), true)).isFalse();
        var after = recovered.facts(id);
        assertThat(after.version()).isGreaterThan(before.version());
        assertThat(after.terminal()).isTrue();
        assertThat(after.charged()).isEqualTo(3);
        assertThat(after.promptUnits()).isEqualTo(1);
        consumerUntouched();
    }

    @Test void concurrentSubmissionAndTerminalReplayHaveOneReservationAndRefund() throws Exception {
        costs.prepare(new PlatformCostPort.Subject("platform-b",21,31,901,"concurrent","request-concurrent"),
                "FLAT:2K", 2, "fingerprint-concurrent");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> settlement.createJobWithCharge(901,"FLAT",2,"svc:concurrent","91",null,"2K"));
            var b = pool.submit(() -> settlement.createJobWithCharge(901,"FLAT",2,"svc:concurrent","91",null,"2K"));
            long id = a.get(20, TimeUnit.SECONDS);
            assertThat(b.get(20, TimeUnit.SECONDS)).isEqualTo(id);
            settlement.settle(id,"SETTLE");
            var version = costs.facts(id).version();
            settlement.settle(id,"SETTLE");
            assertThat(costs.facts(id).version()).isEqualTo(version);
            assertThat(costs.facts(id).refunded()).isEqualTo(25);
        } finally {
            pool.shutdownNow();
        }
        consumerUntouched();
    }

    @Test void partiallySucceededAndCancelledUseOnlyInternalCostLedger() {
        long id=serviceJob("partial",2);
        jdbc.update("INSERT INTO ai_job_result(id,job_id,attempt_no,provider_code,candidate_slot_no,content_sha256,object_key,mime_type,size_bytes,validation_state) "
                + "VALUES(101,?,1,'fixture',1,?,'ai-quarantine/fixture.png','image/png',10,'ACCEPTED')",id,"a".repeat(64));
        settlement.settle(id,"SETTLE");
        assertThat(costs.facts(id).status()).isEqualTo("PARTIALLY_SUCCEEDED");
        assertThat(costs.facts(id).charged()).isEqualTo(11);
        assertThat(costs.facts(id).refunded()).isEqualTo(14);
        long cancel=serviceJob("cancel",1);
        orchestration.requestCancellationForUser(cancel,901);
        settlement.settle(cancel,"CANCEL");
        assertThat(costs.facts(cancel).status()).isEqualTo("CANCELLED");
        assertThat(costs.facts(cancel).refunded()).isEqualTo(14);
        consumerUntouched();
    }

    @Test void unsentRetryAndWorkerTakeoverKeepOneCumulativeReservation() {
        long id=serviceJob("takeover",1);
        var first=orchestration.claim("first",1,60,"fake").get(0);
        settlement.reservePromptCall(id,first.attemptNo(),first.fencingToken());
        settlement.finishPromptCall(id,first.attemptNo(),first.fencingToken(),"NOT_SENT");
        settlement.reservePromptCall(id,first.attemptNo(),first.fencingToken());
        settlement.finishPromptCall(id,first.attemptNo(),first.fencingToken(),"DISPATCHED","plan-"+id,null);
        jdbc.update("UPDATE ai_job SET claim_expires_at=now()-interval '1 second' WHERE id=?",id);
        var next=orchestration.claim("next",1,60,"fake").get(0);
        assertThatThrownBy(() -> settlement.finishPromptCall(id,first.attemptNo(),first.fencingToken(),"SUCCEEDED","plan-"+id,"a".repeat(64)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> settlement.reservePromptCall(id,next.attemptNo(),next.fencingToken())).isInstanceOf(IllegalStateException.class);
        settlement.finishPromptCall(id,next.attemptNo(),next.fencingToken(),"SUCCEEDED","plan-"+id,"a".repeat(64));
        settlement.settle(id,"SETTLE");
        assertThat(costs.facts(id).reserved()).isEqualTo(14);
        assertThat(costs.facts(id).charged()).isEqualTo(3);
        assertThat(costs.facts(id).refunded()).isEqualTo(11);
        consumerUntouched();
    }

    @Test void costLedgerIsAppendOnlyAndSubjectCannotBeRebound() {
        long id=serviceJob("immutable",1);
        assertThatThrownBy(() -> jdbc.update("UPDATE platform_cost_ledger SET charged_micros=1 WHERE business_id='immutable'"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> costs.prepare(new PlatformCostPort.Subject("foreign",21,31,901,"immutable","request-immutable"),
                "FLAT:2K",1,"fingerprint-immutable")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> costs.requireOwner(new PlatformCostPort.Subject("platform-b",22,31,901,null,null),id))
                .isInstanceOf(IllegalStateException.class);
        consumerUntouched();
    }

    @Test void synchronousUsageAndUnknownFailureRecoveryNeverTouchConsumerLedger() {
        jdbc.update("INSERT INTO platform_cost_price(product,version,unit,prompt_micros,image_micros,usage_micros) VALUES ('NOTE_POLISH',1,'MICRO_CNY',0,0,17)");
        var subject=new PlatformCostPort.Subject("platform-b",21,31,901,"note","request-note");
        costs.prepare(subject,"NOTE_POLISH",0,"fingerprint");
        costs.beginExternal("note");
        assertThat(costs.rejectPrepared(subject,"fingerprint")).isFalse();
        assertThatThrownBy(() -> costs.beginExternal("note")).isInstanceOf(IllegalStateException.class);
        costs.saveExternalResult("note","\"polished\"");
        costs.finishUsage("note","\"polished\"");
        costs.finishUsage("note","\"polished\"");
        assertThat(jdbc.queryForObject("SELECT charged_micros FROM platform_cost_business WHERE id='note'",Long.class)).isEqualTo(17);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_cost_ledger WHERE event_type='USAGE_FINAL'",Integer.class)).isEqualTo(1);
        var failure=new PlatformCostPort.Subject("platform-b",21,31,901,"failed-note","request-failed-note");
        costs.prepare(failure,"NOTE_POLISH",0,"fingerprint");
        costs.beginExternal("failed-note");
        costs.recordExternalFailure("failed-note","verified-runtime-receipt:FAILED");
        assertThat(costs.rejectPrepared(failure,"fingerprint")).isTrue();
        assertThat(costs.rejectPrepared(failure,"fingerprint")).isTrue();
        assertThat(jdbc.queryForObject("SELECT refunded_micros FROM platform_cost_business WHERE id='failed-note'",Long.class)).isEqualTo(17);
        consumerUntouched();
    }

    @Test void revokedMappingBetweenPreparationAndDispatchPreventsExternalCall() throws Exception {
        jdbc.execute("CREATE TABLE service_cost_auth_fixture(account_id BIGINT PRIMARY KEY,active BOOLEAN NOT NULL)");
        jdbc.update("INSERT INTO service_cost_auth_fixture VALUES(901,TRUE)");
        costs.setServiceAuthorization(subject -> {
            var active=jdbc.queryForList("SELECT account_id FROM service_cost_auth_fixture WHERE account_id=? AND active=TRUE FOR SHARE",Long.class,subject.serviceAccountId());
            if (active.isEmpty()) throw new IllegalStateException("Service mapping revoked");
        });
        jdbc.update("INSERT INTO platform_cost_price(product,version,unit,prompt_micros,image_micros,usage_micros) VALUES('NOTE_POLISH',1,'MICRO_CNY',0,0,17)");
        costs.prepare(new PlatformCostPort.Subject("platform-b",21,31,901,"revoked-recovery","request-revoked-recovery"),"NOTE_POLISH",0,"fingerprint");
        costs.beginExternal("revoked-recovery");
        var prepared=new CountDownLatch(1);
        var revoked=new CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var pool=Executors.newSingleThreadExecutor();
        try {
            var result=pool.submit(() -> {
                costs.prepare(new PlatformCostPort.Subject("platform-b",21,31,901,"revoked","request-revoked"),"NOTE_POLISH",0,"fingerprint");
                prepared.countDown();
                if (!revoked.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Revoke timeout");
                if (costs.beginExternal("revoked")) calls.incrementAndGet();
                return null;
            });
            assertThat(prepared.await(10,TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE service_cost_auth_fixture SET active=FALSE WHERE account_id=901");
            revoked.countDown();
            assertThatThrownBy(() -> result.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
            // A trusted runtime NOT_FOUND allows only the same receipt key, still behind a fresh mapping lock.
            assertThatThrownBy(() -> {
                costs.authorizeExternalRecovery("revoked-recovery");
                calls.incrementAndGet();
            }).isInstanceOf(IllegalStateException.class);
            assertThat(calls).hasValue(0);
            assertThat(jdbc.queryForObject("SELECT state FROM platform_cost_business WHERE id='revoked-recovery'",String.class)).isEqualTo("UNKNOWN");
            assertThat(jdbc.queryForObject("SELECT state FROM platform_cost_business WHERE id='revoked'",String.class)).isEqualTo("PREPARED");
            consumerUntouched();
        } finally {
            pool.shutdownNow();
            jdbc.execute("DROP TABLE service_cost_auth_fixture");
        }
    }

    @Test void missingPriceAndMissingRoutingFailClosedBeforeConsumerWallet() {
        assertThatThrownBy(() -> costs.prepare(new PlatformCostPort.Subject("platform-b",21,31,901,"no-price","request-no-price"),
                "ELEVATION:4K", 1, "fingerprint")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> settlement.createJobWithCharge(901,"FLAT",1,"svc:missing","91",null,"2K"))
                .isInstanceOf(IllegalStateException.class);
        consumerUntouched();
    }
}
