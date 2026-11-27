package cn.iocoder.yudao.module.franchise.design.remote;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.command.CommandLog;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

/** Real PG transactions and real FranchiseCommands; the CommandLog fixture persists audit rows in PG. */
@Testcontainers
class RemoteDesignCommandStoreTest {
    @Container
    static final PostgreSQLContainer<?> PG = cn.iocoder.yudao.module.platform.test.TestPostgres.database("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private RemoteDesignCommandStore store;
    private RemoteDesignChargePort charges;
    private final AtomicBoolean failAudit = new AtomicBoolean();
    private final PostSnapshot post = new PostSnapshot(2, 1, 10, "STORE", "DESIGNER", null, false, Set.of());

    @BeforeEach
    void setup() {
        var ds = new SimpleDriverDataSource(new org.postgresql.Driver(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        var flyway = Flyway.configure().dataSource(ds).cleanDisabled(false).baselineOnMigrate(true)
                .locations("classpath:db/migration/franchise-design-remote").load();
        flyway.clean();
        jdbc = new JdbcTemplate(ds);
        // Same menu schema shim as PlatformPgIntegrationTest, used by the HQ migration tests.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS system_menu (
                    id int8 PRIMARY KEY, name varchar(64), permission varchar(128), type int4, sort int4,
                    parent_id int8, path varchar(256), icon varchar(128), component varchar(256),
                    component_name varchar(128), status int4, visible bool, keep_alive bool, always_show bool,
                    creator varchar(64), updater varchar(64))
                """);
        flyway.migrate();
        jdbc.execute("CREATE TABLE pf_org_unit(dept_id bigint PRIMARY KEY,org_id bigint,kind varchar(8),status varchar(16))");
        jdbc.execute("CREATE TABLE system_dept(id bigint PRIMARY KEY,tenant_id bigint,status int,deleted int)");
        jdbc.update("INSERT INTO pf_org_unit VALUES(10,10,'ORG','ACTIVE')");
        jdbc.update("INSERT INTO system_dept VALUES(10,1,0,0)");
        jdbc.execute("CREATE TABLE test_audit(idempotency_key text PRIMARY KEY, aggregate_id bigint, payload text, payload_hash text)");
        CommandLog log = new CommandLog() {
            public CommandEventDO append(CommandEventDO event) {
                if (failAudit.getAndSet(false)) throw new IllegalStateException("injected audit outage");
                event.setPayloadHash(DigestUtil.sha256Hex(event.getPayload()));
                jdbc.update("INSERT INTO test_audit VALUES(?,?,?,?)", event.getIdempotencyKey(), event.getAggregateId(), event.getPayload(), event.getPayloadHash());
                return event;
            }
            public Optional<CommandEventDO> findByIdempotencyKey(String key) {
                var rows = jdbc.queryForList("SELECT * FROM test_audit WHERE idempotency_key=?", key);
                return rows.stream().findFirst().map(row -> new CommandEventDO().setAggregateType("fr-design-remote")
                        .setAggregateId(((Number) row.get("aggregate_id")).longValue()).setIdempotencyKey(key)
                        .setPayload(row.get("payload").toString()).setPayloadHash(row.get("payload_hash").toString()));
            }
            public List<CommandEventDO> listByAggregate(String domain, String type, long id) {
                return jdbc.queryForList("SELECT idempotency_key FROM test_audit WHERE aggregate_id=?", String.class, id)
                        .stream().map(this::findByIdempotencyKey).flatMap(Optional::stream).toList();
            }
        };
        charges = org.mockito.Mockito.mock(RemoteDesignChargePort.class);
        store = new RemoteDesignCommandStore(ds, new DataSourceTransactionManager(ds), consumerMapper(), new FranchiseCommands(log), charges);
    }

    private ObjectMapper consumerMapper() {
        var configuration = new cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration();
        var builder = new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder();
        configuration.ldtEpochMillisCustomizer().customize(builder);
        return builder.build().registerModule(configuration.timestampSupportModuleBean());
    }

    @Test
    void newBudgetsCheckEntitlementWithoutReservingPointsAndReplayTheOriginalReceipt() {
        var actor = new DesignServicePort.Actor(post.userId(), post.postId(), post.roleCode());
        for (String path : List.of("/budget-drafts/7/estimates", "/design-projects/9/budget-estimates/itemized")) {
            var body = path.contains("/budget-drafts") ? Map.<String,Object>of("expectedVersion", 2) :
                    Map.<String,Object>of("companyPrice", Map.of("subject", "budget_estimate", "quantity", 1, "credits", 7, "priceVersion", "old"));
            String key = path.contains("/budget-drafts") ? "free-independent" : "free-project-legacy-client";
            var call = new cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall("POST", path, Map.of(), body, key);
            var request = new Request<>("b-test", post.orgId(), actor, call.operation(), key, "nonce", call);
            var command = store.prepare(post, request);
            assertThat(command.price()).isNull();
            var expected = Map.of("budgetId", "701");
            var receipt = new Receipt("budget-receipt", call.operation(), key, command.fingerprint(), ReceiptStatus.SUCCEEDED, expected, null, null, null);
            assertThat(store.complete(store.claim(command), receipt)).isEqualTo(expected);
            var replay = store.prepare(post, request);
            assertThat(replay.commandId()).isEqualTo(command.commandId());
            assertThat(replay.result()).isEqualTo(expected);
            assertThat(replay.price()).isNull();
        }
        org.mockito.Mockito.verify(charges, org.mockito.Mockito.times(2)).assertBudgetAllowed(post);
        org.mockito.Mockito.verifyNoMoreInteractions(charges);
        assertThat(Operation.CREATE_BUDGET_ESTIMATE.freeBudget()).isTrue();
        assertThat(Operation.CREATE_BUDGET_ESTIMATE.paid()).isTrue(); // provider cost and receipt semantics
        assertThat(Operation.NOTE_POLISH.paid()).isTrue();
    }

    @Test
    void projectHistoryUsesActualCompanyNetAcrossJobsAndRefundsWithoutCrossingScope() throws Exception {
        jdbc.execute("CREATE TABLE org_point_ledger(org_id bigint,operation_id bigint,ref_type text,ref_id bigint,type text,available_delta bigint)");
        projectCharge("b-test",1,10,Operation.FLOW_FLAT_JOB,Map.of("path","/design-projects/100/flat-jobs"),4,0L);
        projectCharge("b-test",1,10,Operation.CREATE_ELEVATION_JOB,Map.of("projectId","100"),6,3L);
        projectCharge("b-test",1,10,Operation.FLOW_REVISION,Map.of("path","/design-projects/200/revision-requests"),6,null);
        projectCharge("b-test",1,10,Operation.FLOW_BUDGET,Map.of("path","/design-projects/100/budget-estimates/itemized"),1,0L);
        projectCharge("other-caller",1,10,Operation.FLOW_FLAT_JOB,Map.of("path","/design-projects/100/flat-jobs"),1000,0L);
        projectCharge("b-test",2,10,Operation.FLOW_FLAT_JOB,Map.of("path","/design-projects/100/flat-jobs"),2000,0L);
        projectCharge("b-test",1,20,Operation.FLOW_FLAT_JOB,Map.of("path","/design-projects/100/flat-jobs"),4000,0L);
        assertThat(store.projectPointsSpent("b-test",post,Set.of(100L,200L,999L)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(100L,7L,200L,6L,999L,0L));
        jdbc.update("UPDATE org_point_ledger SET available_delta=7 WHERE org_id=10 AND type='SETTLE' AND available_delta=3");
        assertThatThrownBy(()->store.projectPointsSpent("b-test",post,Set.of(100L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Invalid company project settlement");
    }

    private void projectCharge(String caller,long tenant,long org,Operation operation,Map<String,Object> payload,long reserved,Long refund) throws Exception {
        long command=jdbc.queryForObject("INSERT INTO fr_design_remote_command(caller_app_id,tenant_id,org_id,operation,request_key,fingerprint,actor_json,payload_json,state,price_snapshot) "
                +"VALUES(?,?,?,?,?,?,?::jsonb,?::jsonb,'SUCCEEDED','{\"maximumPoints\":99999}'::jsonb) RETURNING command_id",Long.class,
                caller,tenant,org,operation.name(),java.util.UUID.randomUUID().toString(),"a".repeat(64),consumerMapper().writeValueAsString(post),consumerMapper().writeValueAsString(payload));
        jdbc.update("INSERT INTO org_point_ledger VALUES(?,?,'design_command',?,'RESERVE',?)",org,command,command,-reserved);
        if(refund!=null)jdbc.update("INSERT INTO org_point_ledger VALUES(?,?,'design_command',?,'SETTLE',?)",org,command,command,refund);
    }

    @Test
    void highIdsSurviveRealConsumerMapperWirePersistenceAndProviderReceiptFingerprint() throws Exception {
        long id = 9_007_199_254_741_117L;
        var highPost = new PostSnapshot(id + 2, id + 1, id, "STORE", "DESIGNER", null, false, Set.of());
        jdbc.update("INSERT INTO pf_org_unit VALUES(?,?,'ORG','ACTIVE')", id, id);
        jdbc.update("INSERT INTO system_dept VALUES(?,1,0,0)", id);
        var consumer = consumerMapper();
        assertThat(consumer.readTree(consumer.writeValueAsString(highPost)).get("orgId").isTextual()).isTrue();
        var strict = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
        var providerJson = new cn.iocoder.yudao.module.franchise.api.capability.CapabilityJson(strict);
        var transport = org.mockito.Mockito.mock(cn.iocoder.yudao.module.franchise.design.remote.transport.DesignCapabilityTransport.class);
        var payload = new Selection(id + 3, id + 4, id + 5);
        org.mockito.Mockito.when(transport.exchange(org.mockito.ArgumentMatchers.eq(Operation.CONFIRM_FLAT_CANDIDATE), org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenAnswer(invocation -> {
                    byte[] body = invocation.getArgument(1);
                    Request<?> wire = strict.readValue(body, Request.class);
                    Selection typed = strict.convertValue(wire.payload(), Selection.class);
                    assertThat(wire.orgId()).isEqualTo(id);
                    assertThat(wire.actor()).isEqualTo(new DesignServicePort.Actor(id + 1, id + 2, "DESIGNER"));
                    assertThat(typed).isEqualTo(payload);
                    var parsed = new Request<>(wire.callerAppId(), wire.orgId(), wire.actor(), wire.operation(), wire.requestKey(), wire.nonce(), typed);
                    var result = new DesignServicePort.SelectionSaved(id + 6, 1L, true, false);
                    var receipt = new Receipt("high-id-receipt", wire.operation(), wire.requestKey(), providerJson.fingerprint(parsed),
                            ReceiptStatus.SUCCEEDED, result, null, null, null);
                    return new cn.iocoder.yudao.module.franchise.design.remote.transport.DesignCapabilityTransport.Response(
                            200, consumer.writeValueAsBytes(new Reply<>(0, "ok", result, receipt, false)));
                });
        var gateway = new RemoteDesignGateway("platform", transport, store, consumer);
        DesignServicePort.SelectionSaved result = gateway.call(highPost, Operation.CONFIRM_FLAT_CANDIDATE, "high-id-key", payload);
        assertThat(result.resultVersionId()).isEqualTo(id + 6);
        assertThat(jdbc.queryForObject("SELECT state FROM fr_design_remote_command", String.class)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(actor_json->'postId') FROM fr_design_remote_command", String.class)).isEqualTo("number");
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(payload_json->'expectedJobId') FROM fr_design_remote_command", String.class)).isEqualTo("number");
        DesignServicePort.SelectionSaved replay = gateway.call(highPost, Operation.CONFIRM_FLAT_CANDIDATE, "high-id-key", payload);
        assertThat(replay.resultVersionId()).isEqualTo(id + 6);
        assertThat(replay.replayed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_audit", Integer.class)).isEqualTo(1);
        org.mockito.Mockito.verify(transport).exchange(org.mockito.ArgumentMatchers.eq(Operation.CONFIRM_FLAT_CANDIDATE), org.mockito.ArgumentMatchers.any(byte[].class));
        assertThat(consumer.readTree(consumer.writeValueAsString(highPost)).get("orgId").isTextual()).isTrue();
    }

    private Request<?> request(String key, String source) {
        return new Request<>("platform", 10, new DesignServicePort.Actor(1, 2, "DESIGNER"), Operation.CREATE_PROJECT,
                key, "nonce", new DesignServicePort.DesignProjectCreate(source, null, null, Map.of(), "FLAT", key));
    }

    @Test
    void replayedRemoteSuccessRepairsLocalAuditAfterTransactionFailure() {
        var request = request("stable", "SELF_UPLOAD");
        var command = store.prepare(post, request);
        var lease = store.claim(command);
        var result = new DesignServicePort.ProjectCreated(99, true);
        var receipt = new Receipt("receipt", Operation.CREATE_PROJECT, command.request().requestKey(), command.fingerprint(), ReceiptStatus.SUCCEEDED, result, null, null, null);
        failAudit.set(true);
        assertThatThrownBy(() -> store.complete(lease, receipt)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_audit", Integer.class)).isZero();
        store.unknown(lease);
        var recovery = store.claim(command);
        store.complete(recovery, receipt);
        store.complete(recovery, receipt);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_audit", Integer.class)).isEqualTo(1);
        assertThat(store.get(command).state()).isEqualTo("SUCCEEDED");
    }

    @Test
    void fullBusinessKeysCannotCollideAtLegacyAuditTruncationBoundary() {
        String prefix = "x".repeat(100);
        var first = store.prepare(post, request(prefix + "a", "SELF_UPLOAD"));
        var second = store.prepare(post, request(prefix + "b", "SELF_UPLOAD"));
        assertThat(first.commandId()).isNotEqualTo(second.commandId());
        assertThat(store.prepare(post, request(prefix + "a", "SELF_UPLOAD")).commandId()).isEqualTo(first.commandId());
        assertThatThrownBy(() -> store.prepare(post, request(prefix + "a", "REFERENCE")))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }

    @Test
    void lostHttpResponseQueriesReceiptBeforeAnyResubmission() throws Exception {
        var transport = org.mockito.Mockito.mock(cn.iocoder.yudao.module.franchise.design.remote.transport.DesignCapabilityTransport.class);
        var mapper = new ObjectMapper();
        var gateway = new RemoteDesignGateway("platform", transport, store, mapper);
        var request = request("lost-response", "SELF_UPLOAD");
        org.mockito.Mockito.when(transport.exchange(org.mockito.ArgumentMatchers.eq(Operation.CREATE_PROJECT), org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenThrow(new IllegalStateException("test channel committed but its response was lost"));
        assertThatThrownBy(() -> gateway.call(post, Operation.CREATE_PROJECT, request.requestKey(), request.payload()))
                .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(RemoteDesignGateway.OUTCOME_UNKNOWN));
        var command = store.prepare(post, request);
        assertThat(command.state()).isEqualTo("UNKNOWN");
        var receipt = new Receipt("remote-receipt", Operation.CREATE_PROJECT, command.request().requestKey(), command.fingerprint(),
                ReceiptStatus.SUCCEEDED, new DesignServicePort.ProjectCreated(123, false), null, null, null);
        org.mockito.Mockito.when(transport.exchange(org.mockito.ArgumentMatchers.eq(Operation.QUERY_RECEIPT), org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenReturn(new cn.iocoder.yudao.module.franchise.design.remote.transport.DesignCapabilityTransport.Response(
                        200, mapper.writeValueAsBytes(new Reply<>(0, "", receipt, null, false))));
        DesignServicePort.ProjectCreated recovered = gateway.call(post, Operation.CREATE_PROJECT, request.requestKey(), request.payload());
        assertThat(recovered.projectId()).isEqualTo(123);
        assertThat(recovered.replayed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_audit", Integer.class)).isEqualTo(1);
        org.mockito.Mockito.verify(transport, org.mockito.Mockito.times(1)).exchange(
                org.mockito.ArgumentMatchers.eq(Operation.CREATE_PROJECT), org.mockito.ArgumentMatchers.any(byte[].class));
        org.mockito.Mockito.verify(transport, org.mockito.Mockito.times(1)).exchange(
                org.mockito.ArgumentMatchers.eq(Operation.QUERY_RECEIPT), org.mockito.ArgumentMatchers.any(byte[].class));
    }

    @Test
    void sameBusinessKeyCannotReplayAcrossTenantsAndRecoveryUsesPersistedTenant() {
        var original = store.prepare(post, request("same-key", "SELF_UPLOAD"));
        jdbc.update("UPDATE system_dept SET tenant_id=2 WHERE id=10");
        var moved = store.prepare(post, request("same-key", "SELF_UPLOAD"));
        assertThat(moved.commandId()).isNotEqualTo(original.commandId());
        assertThat(moved.request().requestKey()).isNotEqualTo(original.request().requestKey());
        assertThat(original.tenantId()).isEqualTo(1);
        assertThat(moved.tenantId()).isEqualTo(2);
        // Recovery does not ask CurrentPost or re-resolve the now changed organization directory.
        var lease = store.claim(original);
        var receipt = new Receipt("tenant-one-receipt", Operation.CREATE_PROJECT, original.request().requestKey(),
                original.fingerprint(), ReceiptStatus.SUCCEEDED, new DesignServicePort.ProjectCreated(100, false), null, null, null);
        store.complete(lease, receipt);
        assertThat(store.get(original).tenantId()).isEqualTo(1);
        assertThat(store.get(moved).state()).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT payload::jsonb->>'tenantId' FROM test_audit", String.class)).isEqualTo("1");
    }

    @Test
    void unchangedSettlementsDoNotStarveTheTwentySixthCommand() throws Exception {
        var mapper = new ObjectMapper();
        var transport = org.mockito.Mockito.mock(cn.iocoder.yudao.module.franchise.design.remote.transport.DesignCapabilityTransport.class);
        var gateway = new RemoteDesignGateway("platform", transport, store, mapper);
        var commands = new java.util.ArrayList<RemoteDesignCommandStore.Command>();
        for (int index = 1; index <= 26; index++) {
            var command = submittedJob(index);
            store.settle("platform", 1, 10, new Settlement(index, 1, "RUNNING", false, 1, 0, 100, 0, 0, 0, 0, 0));
            commands.add(store.get(command));
        }
        var visited = new java.util.ArrayList<Long>();
        org.mockito.Mockito.when(transport.exchange(org.mockito.ArgumentMatchers.eq(Operation.GET_SETTLEMENT), org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenAnswer(invocation -> {
                    long jobId = mapper.readTree((byte[]) invocation.getArgument(1)).path("payload").path("jobId").asLong();
                    visited.add(jobId);
                    boolean terminal = jobId == 26;
                    var facts = new Settlement(jobId, terminal ? 2 : 1, terminal ? "SUCCEEDED" : "RUNNING", terminal,
                            1, terminal ? 1 : 0, 100, terminal ? 100 : 0, 0, 0, terminal ? 1 : 0, 0);
                    return new cn.iocoder.yudao.module.franchise.design.remote.transport.DesignCapabilityTransport.Response(
                            200, mapper.writeValueAsBytes(new Reply<>(0, "", facts, null, false)));
                });
        var businessTimes = jdbc.queryForList("SELECT updated_at FROM fr_design_remote_command ORDER BY command_id");
        gateway.recover();
        assertThat(visited).hasSize(25).doesNotContain(26L);
        assertThat(jdbc.queryForList("SELECT updated_at FROM fr_design_remote_command ORDER BY command_id")).isEqualTo(businessTimes);
        gateway.recover();
        assertThat(visited).hasSize(26).contains(26L);
        assertThat(jdbc.queryForObject("SELECT settled_terminal FROM fr_design_remote_command WHERE command_id=?", Boolean.class,
                commands.get(25).commandId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_audit", Integer.class)).isEqualTo(26);
    }

    private RemoteDesignCommandStore.Command submittedJob(long jobId) {
        org.mockito.Mockito.when(charges.quote(org.mockito.ArgumentMatchers.eq(post), org.mockito.ArgumentMatchers.eq(Operation.CREATE_FLAT_JOB), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PlatformPriceSnapshot("test-price", 1, 1, 1, 1, 100, 1, 0, 0));
        String key = "job-" + jobId;
        var command = store.prepare(post, new Request<>("platform", 10, new DesignServicePort.Actor(1, 2, "DESIGNER"),
                Operation.CREATE_FLAT_JOB, key, "nonce", new FlatJob(1, new DesignServicePort.FlatJobCreate(1, "2K", "landscape", key))));
        store.complete(store.claim(command), new Receipt("receipt-" + jobId, Operation.CREATE_FLAT_JOB, command.request().requestKey(),
                command.fingerprint(), ReceiptStatus.SUCCEEDED, new DesignServicePort.JobSubmitted(jobId, false), jobId, null, null));
        return store.get(command);
    }

    @Test
    void expiredSettlementPollCannotCommitOrReleaseItsSuccessorsLease() {
        var command = submittedJob(1);
        var first = store.claimSettlement(command);
        assertThat(first.acquired()).isTrue();
        assertThat(store.claimSettlement(command).acquired()).isFalse();
        assertThat(store.recoverable(25)).isEmpty();
        jdbc.update("UPDATE fr_design_remote_command SET lease_until=clock_timestamp()-interval '1 second'");
        var second = store.claimSettlement(command);
        assertThat(second.acquired()).isTrue();
        var facts = new Settlement(1, 1, "SUCCEEDED", true, 1, 1, 100, 100, 0, 0, 1, 0);
        assertThatThrownBy(() -> store.settle(first, facts)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        store.finishSettlementPoll(first);
        assertThat(jdbc.queryForObject("SELECT lease_token FROM fr_design_remote_command", String.class)).isEqualTo(second.token());
        store.settle(second, facts);
        store.finishSettlementPoll(second);
        assertThat(jdbc.queryForObject("SELECT settled_terminal FROM fr_design_remote_command", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT lease_token FROM fr_design_remote_command", String.class)).isNull();
        org.mockito.Mockito.verify(charges, org.mockito.Mockito.times(1)).settle(
                org.mockito.ArgumentMatchers.eq(command.commandId()), org.mockito.ArgumentMatchers.eq(post), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(facts));
    }

    @Test
    void failedSettlementPollAdvancesOnlyItsScheduleAndRetainsFunds() {
        var command = submittedJob(1);
        var businessTime = jdbc.queryForObject("SELECT updated_at FROM fr_design_remote_command", java.sql.Timestamp.class);
        var transport = org.mockito.Mockito.mock(cn.iocoder.yudao.module.franchise.design.remote.transport.DesignCapabilityTransport.class);
        org.mockito.Mockito.when(transport.exchange(org.mockito.ArgumentMatchers.eq(Operation.GET_SETTLEMENT), org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenThrow(new IllegalStateException("simulated external timeout"));
        new RemoteDesignGateway("platform", transport, store, new ObjectMapper()).recover();
        assertThat(store.recoverable(25)).isEmpty();
        assertThat(store.get(command).state()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT lease_token FROM fr_design_remote_command", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT updated_at FROM fr_design_remote_command", java.sql.Timestamp.class)).isEqualTo(businessTime);
        assertThat(jdbc.queryForObject("SELECT settlement_version FROM fr_design_remote_command", Long.class)).isZero();
        org.mockito.Mockito.verify(charges, org.mockito.Mockito.never()).reject(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void budgetRuleRejectionIsExposedOnlyAfterLocalRejectionCommits() throws Exception {
        var mapper = new ObjectMapper();
        var transport = org.mockito.Mockito.mock(cn.iocoder.yudao.module.franchise.design.remote.transport.DesignCapabilityTransport.class);
        var gateway = new RemoteDesignGateway("platform", transport, store, mapper);
        org.mockito.Mockito.when(transport.exchange(org.mockito.ArgumentMatchers.eq(Operation.CREATE_BUDGET_ESTIMATE), org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenReturn(new cn.iocoder.yudao.module.franchise.design.remote.transport.DesignCapabilityTransport.Response(
                        422, mapper.writeValueAsBytes(new Reply<>(1_071_000_002, "该地区/结构/材料等级暂无生效预算规则", null, null, false))));
        var payload = new Budget(1, new DesignServicePort.BudgetEstimateCreate("missing", "FRAME", "STANDARD", 100, null, "budget-rejected"));
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> gateway.call(post, Operation.CREATE_BUDGET_ESTIMATE, "budget-rejected", payload))
                    .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                            failure -> assertThat(failure.getCode()).isEqualTo(1_071_000_002));
        }
        assertThat(jdbc.queryForObject("SELECT state FROM fr_design_remote_command WHERE request_key='budget-rejected'", String.class)).isEqualTo("REJECTED");
        org.mockito.Mockito.verify(transport, org.mockito.Mockito.times(1)).exchange(
                org.mockito.ArgumentMatchers.eq(Operation.CREATE_BUDGET_ESTIMATE), org.mockito.ArgumentMatchers.any(byte[].class));
        // Seed the same frozen state a pre-cutover request left behind; free rollout must not
        // discard the old reservation or allocate another one when retrying that command.
        var legacy = new Budget(1, new DesignServicePort.BudgetEstimateCreate("missing", "FRAME", "STANDARD", 100, null, "budget-unresolved"));
        var request = new Request<>("platform", post.orgId(),
                new DesignServicePort.Actor(post.userId(), post.postId(), post.roleCode()),
                Operation.CREATE_BUDGET_ESTIMATE, "budget-unresolved", "nonce", legacy);
        var prepared = store.prepare(post, request);
        jdbc.update("UPDATE fr_design_remote_command SET price_snapshot=CAST(? AS jsonb) WHERE command_id=?",
                new ObjectMapper().writeValueAsString(new PlatformPriceSnapshot("budget_estimate", 1, 0, 0, 0, 10, 0, 0, 1)),
                prepared.commandId());
        // The same upstream business code must remain UNKNOWN if local wallet release cannot commit.
        org.mockito.Mockito.doThrow(new IllegalStateException("simulated wallet release failure")).when(charges).reject(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
        assertThatThrownBy(() -> gateway.call(post, Operation.CREATE_BUDGET_ESTIMATE, "budget-unresolved", legacy))
                .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(RemoteDesignGateway.OUTCOME_UNKNOWN));
        assertThat(jdbc.queryForObject("SELECT state FROM fr_design_remote_command WHERE request_key='budget-unresolved'", String.class)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_audit", Integer.class)).isZero();
        org.mockito.Mockito.verify(charges, org.mockito.Mockito.never()).completeSynchronous(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pendingUploadRetainsItsKeyUntilTheReceiptBecomesTerminal() throws Exception {
        var mapper = new ObjectMapper();
        var transport = org.mockito.Mockito.mock(cn.iocoder.yudao.module.franchise.design.remote.transport.DesignCapabilityTransport.class);
        var gateway = new RemoteDesignGateway("platform", transport, store, mapper);
        var payload = new Asset(100);
        var command = store.prepare(post, new Request<>("platform", 10, new DesignServicePort.Actor(1, 2, "DESIGNER"),
                Operation.COMPLETE_UPLOAD, "upload-stable", "nonce", payload));
        var pending = new Receipt("upload-receipt", Operation.COMPLETE_UPLOAD, command.request().requestKey(), command.fingerprint(),
                ReceiptStatus.UNKNOWN, new DesignServicePort.AssetCompleted(100, "PENDING", null), null, null, null);
        org.mockito.Mockito.when(transport.exchange(org.mockito.ArgumentMatchers.eq(Operation.COMPLETE_UPLOAD), org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenReturn(new cn.iocoder.yudao.module.franchise.design.remote.transport.DesignCapabilityTransport.Response(
                        200, mapper.writeValueAsBytes(new Reply<>(0, "", pending.result(), pending, false))));
        assertThatThrownBy(() -> gateway.call(post, Operation.COMPLETE_UPLOAD, "upload-stable", payload))
                .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(RemoteDesignGateway.OUTCOME_UNKNOWN));
        assertThat(store.get(command).state()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_audit", Integer.class)).isZero();
        jdbc.update("UPDATE fr_design_remote_command SET lease_until=clock_timestamp()-interval '1 second'");
        var accepted = new Receipt(pending.receiptId(), pending.operation(), pending.requestKey(), pending.fingerprint(),
                ReceiptStatus.SUCCEEDED, new DesignServicePort.AssetCompleted(100, "ACCEPTED", null), null, null, null);
        org.mockito.Mockito.when(transport.exchange(org.mockito.ArgumentMatchers.eq(Operation.QUERY_RECEIPT), org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenReturn(new cn.iocoder.yudao.module.franchise.design.remote.transport.DesignCapabilityTransport.Response(
                        200, mapper.writeValueAsBytes(new Reply<>(0, "", accepted, null, false))));
        DesignServicePort.AssetCompleted result = gateway.call(post, Operation.COMPLETE_UPLOAD, "upload-stable", payload);
        assertThat(result.uploadStatus()).isEqualTo("ACCEPTED");
        assertThat(store.get(command).request().requestKey()).isEqualTo(command.request().requestKey());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_audit", Integer.class)).isEqualTo(1);
        org.mockito.Mockito.verify(transport, org.mockito.Mockito.times(1)).exchange(
                org.mockito.ArgumentMatchers.eq(Operation.COMPLETE_UPLOAD), org.mockito.ArgumentMatchers.any(byte[].class));
    }

    @Test
    void aSucceededUploadReceiptCannotFreezeAPendingAsset() {
        var command = store.prepare(post, new Request<>("platform", 10, new DesignServicePort.Actor(1, 2, "DESIGNER"),
                Operation.COMPLETE_UPLOAD, "invalid-terminal", "nonce", new Asset(100)));
        var lease = store.claim(command);
        var inconsistent = new Receipt("invalid", Operation.COMPLETE_UPLOAD, command.request().requestKey(), command.fingerprint(),
                ReceiptStatus.SUCCEEDED, new DesignServicePort.AssetCompleted(100, "PENDING", null), null, null, null);
        assertThatThrownBy(() -> store.complete(lease, inconsistent))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(store.get(command).state()).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_audit", Integer.class)).isZero();
    }

    @Test
    void anExpiredSendMustRecoverBeforeResubmission() {
        var command = store.prepare(post, request("stable", "SELF_UPLOAD"));
        var first = store.claim(command);
        assertThat(first.recovery()).isFalse();
        assertThat(store.claim(command).acquired()).isFalse();
        jdbc.update("UPDATE fr_design_remote_command SET lease_until=now()-interval '1 second'");
        var next = store.claim(command);
        assertThat(next.recovery()).isTrue();
        assertThat(next.token()).isNotEqualTo(first.token());
    }
}
