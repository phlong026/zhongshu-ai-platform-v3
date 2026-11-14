package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.module.aiorchestration.job.AiJobSettlementService;
import cn.iocoder.yudao.module.aiorchestration.billing.PlatformCostService;
import cn.iocoder.yudao.module.infra.zhongshu.api.PlatformCostPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderPromptEventServiceTest {
    @Test
    void dispatchedThenFirstUnknownUsesRealCostStateAndAcksExactReceipt() throws Exception {
        try (var postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.15-alpine"))
                .withDatabaseName("provider_prompt_state").withUsername("owner").withPassword("test-password")) {
            postgres.start();
            var dataSource = new org.postgresql.ds.PGSimpleDataSource();
            dataSource.setURL(postgres.getJdbcUrl());
            dataSource.setUser(postgres.getUsername());
            dataSource.setPassword(postgres.getPassword());
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/platform",
                    "classpath:db/migration/commerce", "classpath:db/migration/ai-orchestration",
                    "classpath:db/migration/design-provider").load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.update("INSERT INTO platform_cost_business(id,caller_app_id,org_id,tenant_id,service_account_id,"
                    + "request_key,task_key,fingerprint,product,price_version,unit,prompt_micros,image_micros,"
                    + "usage_micros,requested_count,job_id,reserved_micros,prompt_state,prompt_token) VALUES "
                    + "('receipt-5101','platform-b',1,1,901,'request-5101','svc:receipt-5101','fingerprint',"
                    + "'FLAT:2K',1,'MICRO_CNY',3,0,0,1,5101,3,'RESERVED',77)");
            jdbc.update("INSERT INTO ai_job(id,user_id,phase,status,requested_count,output_prefix,unit_point_cost,"
                    + "total_point_cost,input_snapshot,billing_mode,cost_business_id,fencing_token,claim_expires_at) VALUES "
                    + "(5101,901,'FLAT','RUNNING',1,'ai-quarantine/5101',0,0,'{}'::jsonb,'SERVICE_COST',"
                    + "'receipt-5101',77,now()+interval '1 minute')");
            jdbc.update("INSERT INTO ai_job_attempt(id,job_id,attempt_no,worker_id,fencing_token) "
                    + "VALUES (15101,5101,2,'fixture',77)");
            var tx = new DataSourceTransactionManager(dataSource);
            var costs = new PlatformCostService(dataSource, tx);
            var settlement = mock(AiJobSettlementService.class);
            when(settlement.finishPromptCall(org.mockito.ArgumentMatchers.eq(5101L),
                    org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.eq(77L),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("plan-5101"),
                    org.mockito.ArgumentMatchers.isNull())).thenAnswer(invocation -> costs.finishPrompt(5101L, 77L,
                            invocation.getArgument(3), "plan-5101", null, true));
            var service = new ProviderPromptEventService(dataSource, tx, settlement, costs);
            var dispatched = new ProviderPromptEventService.Event(5101L, 2, 77L, "DISPATCHED",
                    "plan-5101", null, null, null);
            var unknown = new ProviderPromptEventService.Event(5101L, 2, 77L, "UNKNOWN",
                    "plan-5101", null, null, null);

            assertThat(service.confirm(dispatched)).isTrue();
            assertThat(jdbc.queryForObject("SELECT prompt_state FROM platform_cost_business WHERE job_id=5101", String.class))
                    .isEqualTo("UNKNOWN");
            jdbc.update("UPDATE ai_job SET fencing_token=78,claim_expires_at=now()+interval '1 minute' WHERE id=5101");
            jdbc.update("UPDATE ai_job_attempt SET finished_at=now() WHERE job_id=5101 AND attempt_no=2");
            jdbc.update("INSERT INTO ai_job_attempt(id,job_id,attempt_no,worker_id,fencing_token) "
                    + "VALUES (15102,5101,3,'recovery-fixture',78)");
            assertThat(service.confirm(unknown)).isTrue();
            assertThat(service.confirm(unknown)).isTrue();
            assertConflict(service, new ProviderPromptEventService.Event(5101L, 2, 77L, "NOT_SENT",
                    "plan-5101", null, null, null));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM design_provider_prompt_event_receipt "
                    + "WHERE job_id=5101 AND state='APPLIED'", Integer.class)).isEqualTo(2);
            verify(settlement, times(1)).finishPromptCall(org.mockito.ArgumentMatchers.eq(5101L),
                    org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.eq(77L),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("plan-5101"),
                    org.mockito.ArgumentMatchers.isNull());

            jdbc.update("INSERT INTO platform_cost_business(id,caller_app_id,org_id,tenant_id,service_account_id,"
                    + "request_key,task_key,fingerprint,product,price_version,unit,prompt_micros,image_micros,"
                    + "usage_micros,requested_count,job_id,reserved_micros,prompt_state,prompt_token) VALUES "
                    + "('receipt-5102','platform-b',1,1,901,'request-5102','svc:receipt-5102','fingerprint',"
                    + "'FLAT:2K',1,'MICRO_CNY',3,0,0,1,5102,3,'RESERVED',88)");
            jdbc.update("INSERT INTO ai_job(id,user_id,phase,status,requested_count,output_prefix,unit_point_cost,"
                    + "total_point_cost,input_snapshot,billing_mode,cost_business_id,fencing_token,claim_expires_at) VALUES "
                    + "(5102,901,'FLAT','RUNNING',1,'ai-quarantine/5102',0,0,'{}'::jsonb,'SERVICE_COST',"
                    + "'receipt-5102',89,now()+interval '1 minute')");
            jdbc.update("INSERT INTO ai_job_attempt(id,job_id,attempt_no,worker_id,fencing_token,finished_at) "
                    + "VALUES (25101,5102,1,'expired-fixture',88,now())");
            jdbc.update("INSERT INTO ai_job_attempt(id,job_id,attempt_no,worker_id,fencing_token) "
                    + "VALUES (25102,5102,2,'recovery-fixture',89)");
            var notSent = new ProviderPromptEventService.Event(5102L, 1, 88L, "NOT_SENT",
                    "plan-5102", null, null, null);
            assertThat(service.confirm(notSent)).isTrue();
            assertThat(service.confirm(notSent)).isTrue();
            assertThat(jdbc.queryForObject("SELECT prompt_state FROM platform_cost_business WHERE job_id=5102", String.class))
                    .isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT state FROM design_provider_prompt_event_receipt "
                    + "WHERE job_id=5102 AND outcome='NOT_SENT'", String.class)).isEqualTo("APPLIED");
            assertConflict(service, new ProviderPromptEventService.Event(5102L, 1, 88L, "DISPATCHED",
                    "plan-5102", null, null, null));

            jdbc.update("INSERT INTO platform_cost_business(id,caller_app_id,org_id,tenant_id,service_account_id,"
                    + "request_key,task_key,fingerprint,product,price_version,unit,prompt_micros,image_micros,"
                    + "usage_micros,requested_count,job_id,reserved_micros,prompt_state,prompt_token) VALUES "
                    + "('receipt-5103','platform-b',1,1,901,'request-5103','svc:receipt-5103','fingerprint',"
                    + "'FLAT:2K',1,'MICRO_CNY',3,0,0,1,5103,3,'RESERVED',90)");
            jdbc.update("INSERT INTO ai_job(id,user_id,phase,status,requested_count,output_prefix,unit_point_cost,"
                    + "total_point_cost,input_snapshot,billing_mode,cost_business_id,fencing_token,claim_expires_at) VALUES "
                    + "(5103,901,'FLAT','RUNNING',1,'ai-quarantine/5103',0,0,'{}'::jsonb,'SERVICE_COST',"
                    + "'receipt-5103',90,now()+interval '1 minute')");
            jdbc.update("INSERT INTO ai_job_attempt(id,job_id,attempt_no,worker_id,fencing_token,finished_at) "
                    + "VALUES (35101,5103,1,'expired-fixture',89,now())");
            jdbc.update("INSERT INTO ai_job_attempt(id,job_id,attempt_no,worker_id,fencing_token) "
                    + "VALUES (35102,5103,2,'current-fixture',90)");
            assertConflict(service, new ProviderPromptEventService.Event(5103L, 1, 89L, "NOT_SENT",
                    "plan-5103", null, null, null));
            assertThat(jdbc.queryForObject("SELECT prompt_state FROM platform_cost_business WHERE job_id=5103", String.class))
                    .isEqualTo("RESERVED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM design_provider_prompt_event_receipt WHERE job_id=5103",
                    Integer.class)).isZero();

            jdbc.update("INSERT INTO platform_cost_business(id,caller_app_id,org_id,tenant_id,service_account_id,"
                    + "request_key,task_key,fingerprint,product,price_version,unit,prompt_micros,image_micros,"
                    + "usage_micros,requested_count,job_id,reserved_micros,prompt_state,prompt_token) VALUES "
                    + "('receipt-5104','platform-b',1,1,901,'request-5104','svc:receipt-5104','fingerprint',"
                    + "'FLAT:2K',1,'MICRO_CNY',3,0,0,1,5104,3,'RESERVED',99)");
            jdbc.update("INSERT INTO ai_job(id,user_id,phase,status,requested_count,output_prefix,unit_point_cost,"
                    + "total_point_cost,input_snapshot,billing_mode,cost_business_id,fencing_token,claim_expires_at) VALUES "
                    + "(5104,901,'FLAT','RUNNING',1,'ai-quarantine/5104',0,0,'{}'::jsonb,'SERVICE_COST',"
                    + "'receipt-5104',99,now()+interval '1 minute')");
            jdbc.update("INSERT INTO ai_job_attempt(id,job_id,attempt_no,worker_id,fencing_token) "
                    + "VALUES (45101,5104,1,'concurrent-fixture',99)");
            when(settlement.finishPromptCall(org.mockito.ArgumentMatchers.eq(5104L),
                    org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(99L),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("plan-5104"),
                    org.mockito.ArgumentMatchers.isNull())).thenAnswer(invocation -> costs.finishPrompt(5104L, 99L,
                            invocation.getArgument(3), "plan-5104", null, true));
            var concurrentDispatch = new ProviderPromptEventService.Event(5104L, 1, 99L, "DISPATCHED",
                    "plan-5104", null, null, null);
            var concurrentNotSent = new ProviderPromptEventService.Event(5104L, 1, 99L, "NOT_SENT",
                    "plan-5104", null, null, null);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var dispatchResult = pool.submit(() -> confirmOrRejected(service, concurrentDispatch));
                var notSentResult = pool.submit(() -> confirmOrRejected(service, concurrentNotSent));
                assertThat(java.util.List.of(dispatchResult.get(), notSentResult.get()))
                        .containsExactlyInAnyOrder(true, false);
            } finally {
                pool.shutdownNow();
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM design_provider_prompt_event_receipt "
                    + "WHERE job_id=5104 AND state='APPLIED'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT prompt_state FROM platform_cost_business WHERE job_id=5104", String.class))
                    .isIn("UNKNOWN", "PENDING");
        }
    }

    @Test
    void exactAckReplayIsDurableConcurrentAndConflictsFailClosed() throws Exception {
        try (var postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.15-alpine"))
                .withDatabaseName("provider_prompt_receipt").withUsername("owner").withPassword("test-password")) {
            postgres.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration/platform", "classpath:db/migration/commerce",
                            "classpath:db/migration/ai-orchestration", "classpath:db/migration/design-provider")
                    .load().migrate();
            var pgDataSource = new org.postgresql.ds.PGSimpleDataSource();
            pgDataSource.setURL(postgres.getJdbcUrl());
            pgDataSource.setUser(postgres.getUsername());
            pgDataSource.setPassword(postgres.getPassword());
            var jdbc = new JdbcTemplate(pgDataSource);
            insertRunningJob(jdbc, 41, 2, 77);
            insertRunningJob(jdbc, 42, 1, 9);
            insertRunningJob(jdbc, 43, 1, 9);
            insertRunningJob(jdbc, 44, 1, 9);
            insertRunningJob(jdbc, 45, 1, 10);
            jdbc.update("UPDATE ai_job SET status='SUCCEEDED',claim_expires_at=NULL WHERE id=44");
            jdbc.update("UPDATE ai_job_attempt SET finished_at=now() WHERE job_id=44");
            var settlement = mock(AiJobSettlementService.class);
            var platformCosts = mock(PlatformCostPort.class);
            var service = new ProviderPromptEventService(pgDataSource,
                    new DataSourceTransactionManager(pgDataSource), settlement, platformCosts);
            var event = new ProviderPromptEventService.Event(41L, 2, 77L, "DISPATCHED",
                    "plan-41", null, null, null);
            assertBadRequest(service, new ProviderPromptEventService.Event(41L, 2, 77L, "DISPATCHED",
                    "plan-41", "a".repeat(64), null, null));
            assertBadRequest(service, new ProviderPromptEventService.Event(41L, 2, 77L, "UNKNOWN",
                    "plan-41", null, "polluted", null));
            assertBadRequest(service, new ProviderPromptEventService.Event(41L, 2, 77L, "SUCCEEDED",
                    "plan-41", null, "prompt", null));
            assertBadRequest(service, new ProviderPromptEventService.Event(41L, 2, 77L, "SUCCEEDED",
                    "plan-41", "a".repeat(64), "prompt", null));
            assertBadRequest(service, new ProviderPromptEventService.Event(41L, 2, 77L, "SUCCEEDED",
                    "plan-41", sha256("   "), "   ", null));
            assertBadRequest(service, new ProviderPromptEventService.Event(41L, 2, 77L, "SUCCEEDED",
                    "plan-41", sha256("prompt"), "prompt", " "));
            when(settlement.finishPromptCall(41L, 2, 77L, "DISPATCHED", "plan-41", null)).thenReturn(true);

            var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> service.confirm(event));
                var replay = pool.submit(() -> service.confirm(event));
                assertThat(first.get()).isTrue();
                assertThat(replay.get()).isTrue();
            } finally {
                pool.shutdownNow();
            }
            assertThat(service.confirm(event)).isTrue();
            verify(settlement, times(1)).finishPromptCall(41L, 2, 77L, "DISPATCHED", "plan-41", null);

            var unknown = new ProviderPromptEventService.Event(41L, 2, 77L, "UNKNOWN",
                    "plan-41", null, null, null);
            when(settlement.finishPromptCall(41L, 2, 77L, "UNKNOWN", "plan-41", null)).thenReturn(false);
            assertThat(service.confirm(unknown)).isTrue();
            assertThat(service.confirm(unknown)).isTrue();
            verify(settlement, times(1)).finishPromptCall(41L, 2, 77L, "UNKNOWN", "plan-41", null);

            assertConflict(service, new ProviderPromptEventService.Event(41L, 3, 77L, "DISPATCHED",
                    "plan-41", null, null, null));
            assertConflict(service, new ProviderPromptEventService.Event(41L, 2, 78L, "DISPATCHED",
                    "plan-41", null, null, null));
            assertConflict(service, new ProviderPromptEventService.Event(41L, 2, 77L, "DISPATCHED",
                    "plan-other", null, null, null));
            assertBadRequest(service, new ProviderPromptEventService.Event(41L, 2, 77L, "DISPATCHED",
                    "plan-41", "a".repeat(64), null, null));

            var notSent = new ProviderPromptEventService.Event(42L, 1, 9L, "NOT_SENT",
                    "plan-42", null, null, null);
            when(settlement.finishPromptCall(42L, 1, 9L, "NOT_SENT", "plan-42", null)).thenReturn(false);
            assertThatThrownBy(() -> service.confirm(notSent)).isInstanceOf(ResponseStatusException.class);
            assertThat(new JdbcTemplate(pgDataSource).queryForObject(
                    "SELECT count(*) FROM design_provider_prompt_event_receipt WHERE job_id=42", Integer.class)).isZero();
            var orphanUnknown = new ProviderPromptEventService.Event(43L, 1, 9L, "UNKNOWN",
                    "plan-43", null, null, null);
            when(settlement.finishPromptCall(43L, 1, 9L, "UNKNOWN", "plan-43", null)).thenReturn(false);
            assertThatThrownBy(() -> service.confirm(orphanUnknown)).isInstanceOf(ResponseStatusException.class);
            String promptHash = sha256("prompt");
            var lateSucceeded = new ProviderPromptEventService.Event(44L, 1, 9L, "SUCCEEDED",
                    "plan-44", promptHash, "prompt", "model");
            when(settlement.finishPromptCall(44L, 1, 9L, "SUCCEEDED", "plan-44", promptHash))
                    .thenReturn(true);
            assertThat(service.confirm(lateSucceeded)).isTrue();
            assertThat(jdbc.queryForObject("SELECT state FROM design_provider_prompt_event_receipt "
                    + "WHERE job_id=44 AND outcome='SUCCEEDED'", String.class)).isEqualTo("APPLIED");
            String unicodePrompt = "  中式庭院\n二层  ";
            String unicodeHash = sha256(unicodePrompt);
            assertThat(unicodeHash).isEqualTo("b3745e7f13cd12f01fceff8fbb0d57a1a303843b246293acbfc89ff81a3ee56d");
            when(settlement.finishPromptCall(45L, 1, 10L, "SUCCEEDED", "plan-45", unicodeHash))
                    .thenReturn(true);
            assertThat(service.confirm(new ProviderPromptEventService.Event(45L, 1, 10L, "SUCCEEDED",
                    "plan-45", unicodeHash, unicodePrompt, "gpt-4.1/model:v2"))).isTrue();
            jdbc.update("UPDATE ai_job SET claim_expires_at=now()-interval '1 second' WHERE id=41");
            jdbc.update("UPDATE ai_job_attempt SET finished_at=now() WHERE job_id=41 AND attempt_no=2");
            assertThat(service.confirm(event)).isTrue();
            verify(settlement, times(1)).finishPromptCall(41L, 2, 77L, "DISPATCHED", "plan-41", null);
            jdbc.update("UPDATE ai_job SET fencing_token=78 WHERE id=41");
            assertThat(service.confirm(event)).isTrue();
            verify(settlement, times(1)).finishPromptCall(41L, 2, 77L, "DISPATCHED", "plan-41", null);
            assertConflict(service, new ProviderPromptEventService.Event(41L, 2, 77L, "SUCCEEDED",
                    "plan-41", promptHash, "prompt", "other-model"));
        }
    }

    private static void assertConflict(ProviderPromptEventService service, ProviderPromptEventService.Event event) {
        assertThatThrownBy(() -> service.confirm(event)).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(409));
    }

    private static void assertBadRequest(ProviderPromptEventService service, ProviderPromptEventService.Event event) {
        assertThatThrownBy(() -> service.confirm(event)).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(400));
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static boolean confirmOrRejected(ProviderPromptEventService service,
            ProviderPromptEventService.Event event) {
        try {
            return service.confirm(event);
        } catch (ResponseStatusException rejected) {
            assertThat(rejected.getStatusCode().value()).isEqualTo(409);
            return false;
        }
    }

    private static void insertRunningJob(JdbcTemplate jdbc, long jobId, int attempt, long fence) {
        jdbc.update("INSERT INTO ai_job(id,user_id,phase,status,requested_count,output_prefix,unit_point_cost,"
                        + "total_point_cost,input_snapshot,fencing_token,claim_expires_at) VALUES "
                        + "(?,901,'FLAT','RUNNING',1,?,0,0,'{}'::jsonb,?,now()+interval '1 minute')",
                jobId, "ai-quarantine/" + jobId, fence);
        jdbc.update("INSERT INTO ai_job_attempt(id,job_id,attempt_no,worker_id,fencing_token) VALUES (?,?,?,?,?)",
                jobId * 10 + attempt, jobId, attempt, "fixture", fence);
    }
}
