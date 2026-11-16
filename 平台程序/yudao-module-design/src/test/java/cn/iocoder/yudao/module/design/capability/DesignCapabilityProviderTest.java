package cn.iocoder.yudao.module.design.capability;

import cn.iocoder.yudao.module.design.FranchiseDesignAdapter;
import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.budget.BudgetService;
import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.design.capability.security.ServicePrincipal;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import cn.iocoder.yudao.module.infra.zhongshu.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real project/receipt/ownership persistence; external AI is a test double, not a live channel test. */
@Testcontainers
class DesignCapabilityProviderTest {
    @Container static final PostgreSQLContainer<?> PG = cn.iocoder.yudao.module.platform.test.TestPostgres.database("postgres:17-alpine");
    private DesignCapabilityProvider provider;
    private CapabilityOrganizationMapping mapping;
    private CapabilityReceiptStore receipts;
    private ServiceDesignBillingPort billing;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ServicePrincipal first;
    private ServicePrincipal second;
    private AiJobPort jobPort;
    private FranchiseDesignAdapter adapter;

    @BeforeEach void setup() {
        var source = new SimpleDriverDataSource(new org.postgresql.Driver(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        var flyway = Flyway.configure().dataSource(source).cleanDisabled(false).baselineOnMigrate(true).baselineVersion("0")
                .locations("classpath:db/migration/platform", "classpath:db/migration/design", "classpath:db/migration/design-capability").load();
        flyway.clean();
        jdbc = new JdbcTemplate(source);
        // The design module does not depend on identity. This fixture exercises the real account ID constraint.
        jdbc.execute("CREATE TABLE account(id bigint PRIMARY KEY,status varchar(20) NOT NULL)");
        jdbc.update("INSERT INTO account VALUES(7,'ACTIVE')");
        flyway.migrate();
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource(
                "db/migration/ai-orchestration/V20260905.401__p4b_ai_job.sql")).execute(source);
        var transactions = new DataSourceTransactionManager(source);
        tx = new TransactionTemplate(transactions);
        jdbc.update("INSERT INTO design_capability_organization(caller_app_id,org_id,tenant_id,active) VALUES('platform',10,1,true),('platform',11,2,true)");
        mapping = new CapabilityOrganizationMapping(source);
        first = mapping.resolve("platform", 10);
        second = mapping.resolve("platform", 11);
        var projects = new DesignProjectService(source, transactions, mock(AiJobPort.class), mock(AssetService.class),
                null, null, new CapabilityServiceAccountGuard(source));
        jobPort=mock(AiJobPort.class);
        adapter = new FranchiseDesignAdapter(projects, new CaseCatalogService(source, transactions), mock(AssetService.class),
                mock(BudgetService.class), jobPort, null, null,
                new StaticListableBeanFactory().getBeanProvider(UsagePricingPort.class), transactions, source);
        receipts = new CapabilityReceiptStore(source, transactions, new ObjectMapper());
        billing = mock(ServiceDesignBillingPort.class);
        provider = new DesignCapabilityProvider(adapter, mapping, receipts, billing, source, mock(AssetService.class));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private Reply<Object> invoke(ServicePrincipal principal, Operation action, String key, Object payload) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal, null, "SERVICE"));
        return provider.invoke(principal, action, new Request<>(principal.callerAppId(), principal.orgId(),
                new DesignServicePort.Actor(5, 6, "DESIGNER"), action, key, UUID.randomUUID().toString(), payload));
    }

    @Test void historicalJobSpecsUseTheOriginalPhaseDefaultsAndExplicitSpecsRemainUnchanged() {
        long owner=first.serviceAccountId();
        jdbc.execute("ALTER TABLE ai_job ADD COLUMN IF NOT EXISTS input_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb");
        jdbc.update("INSERT INTO ai_job(id,user_id,project_ref,phase,requested_count,output_prefix,tenant_id,input_snapshot) VALUES(101,?,'61','FLAT',2,'test/legacy',?,'{}')",owner,first.tenantId());
        var job=mock(AiJobPort.JobView.class);when(job.jobId()).thenReturn(101L);when(job.userId()).thenReturn(owner);when(job.phase()).thenReturn("FLAT");when(job.status()).thenReturn("SUCCEEDED");when(job.projectRef()).thenReturn("61");when(job.requestedCount()).thenReturn(2);
        when(jobPort.getJob(101)).thenReturn(java.util.Optional.of(job));
        assertThat(adapter.getAiJob(owner,101).imageOptions()).containsEntry("resolution","2K").containsEntry("orientation","PORTRAIT");
        assertThat(adapter.getAiJob(owner,101).createdAt()).endsWith("Z");
        when(job.status()).thenReturn("CREATED");assertThat(adapter.getAiJob(owner,101).allowedActions()).contains("CANCEL");
        jdbc.update("UPDATE ai_job SET phase='ELEVATION' WHERE id=101");
        when(job.phase()).thenReturn("ELEVATION");
        assertThat(adapter.getAiJob(owner,101).imageOptions()).containsEntry("orientation","LANDSCAPE");
        jdbc.update("UPDATE ai_job SET input_snapshot='{\"imageOptions\":{\"resolution\":\"4K\",\"orientation\":\"PORTRAIT\"}}' WHERE id=101");
        assertThat(adapter.getAiJob(owner,101).imageOptions()).containsEntry("resolution","4K").containsEntry("orientation","PORTRAIT");
        assertThatThrownBy(()->adapter.getAiJob(second.serviceAccountId(),101)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }

    @Test void originalBudgetDraftRoundTripUsesAValidStableSourceKeyAndReplaysItsReceipt() {
        var source=jdbc.getDataSource();var manager=new DataSourceTransactionManager(source);
        var drafts=new cn.iocoder.yudao.module.design.budget.BudgetDraftService(source,manager,mock(DesignProjectService.class),new CapabilityServiceAccountGuard(source));
        var port=mock(DesignServicePort.class);
        when(port.designFlow(anyLong(),any(),any())).thenAnswer(invocation->{
            cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall call=invocation.getArgument(1);
            return drafts.create(invocation.getArgument(0),call.idempotencyKey());
        });
        org.springframework.test.util.ReflectionTestUtils.setField(provider,"design",port);
        var payload=new cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall("POST","/budget-drafts",Map.of(),Map.of(),"source-request");
        var firstReply=invoke(first,Operation.FLOW_WRITE,"source-budget",payload);
        var secondReply=invoke(first,Operation.FLOW_WRITE,"source-budget",payload);
        com.fasterxml.jackson.databind.JsonNode firstResult=new ObjectMapper().valueToTree(firstReply.result());
        assertThat(firstResult).isEqualTo(new ObjectMapper().valueToTree(secondReply.result()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_draft",Long.class)).isEqualTo(1);
        verify(port,times(1)).designFlow(anyLong(),any(),any());verifyNoInteractions(billing);
    }

    @Test void realProjectWriteReceiptAndReadAreScopedAndDoNotProvisionConsumerIdentity() {
        var payload = new DesignServicePort.DesignProjectCreate("SELF_UPLOAD", null, null, Map.of(), "FLAT", "stable");
        var firstReply = invoke(first, Operation.CREATE_PROJECT, "stable", payload);
        var created = (DesignServicePort.ProjectCreated) firstReply.result();
        assertThat(invoke(first, Operation.CREATE_PROJECT, "stable", payload).replayed()).isTrue();
        var own = (DesignServicePort.ProjectPageView) invoke(first, Operation.LIST_PROJECTS, "read", new Page(null, 20)).result();
        var other = (DesignServicePort.ProjectPageView) invoke(second, Operation.LIST_PROJECTS, "read", new Page(null, 20)).result();
        assertThat(own.list()).extracting(DesignServicePort.ProjectListItem::projectId).containsExactly(created.projectId());
        assertThat(other.list()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> invoke(second, Operation.GET_PROJECT, "read", new Project(created.projectId(), null)))
                .isInstanceOf(CapabilityException.class);
        assertThatThrownBy(() -> invoke(second, Operation.CONFIRM_FLAT_CANDIDATE, "select", new Selection(created.projectId(), null, 1)))
                .isInstanceOf(CapabilityException.class);
        assertThat(((Receipt) invoke(second, Operation.QUERY_RECEIPT, "query", new ReceiptQuery(Operation.CREATE_PROJECT, "stable")).result()).status())
                .isEqualTo(ReceiptStatus.NOT_FOUND);
    }

    @Test void anotherOrganizationCannotReadCancelPromoteOrSettleAnExistingJob() {
        var created = (DesignServicePort.ProjectCreated) invoke(first, Operation.CREATE_PROJECT, "job-project",
                new DesignServicePort.DesignProjectCreate("SELF_UPLOAD", null, null, Map.of(), "FLAT", "job-project")).result();
        jdbc.update("INSERT INTO ai_job(id,user_id,project_ref,phase,requested_count,output_prefix,tenant_id) VALUES(100,?,?,'FLAT',1,'test/job',?)",
                first.serviceAccountId(), String.valueOf(created.projectId()), first.tenantId());
        var jobRequest = new Request<>("platform", 10, new DesignServicePort.Actor(5, 6, "DESIGNER"), Operation.CREATE_FLAT_JOB,
                "job-history", "nonce", new FlatJob(created.projectId(), new DesignServicePort.FlatJobCreate(1, "2K", "landscape", "job-history")));
        receipts.executeAtomic(jobRequest, () -> new DesignServicePort.JobSubmitted(100, false));
        for (Operation action : new Operation[]{Operation.GET_AI_JOB, Operation.CANCEL_AI_JOB, Operation.GET_SETTLEMENT}) {
            assertThatThrownBy(() -> invoke(second, action, "foreign-job", new Job(100))).isInstanceOf(CapabilityException.class);
        }
        assertThatThrownBy(() -> invoke(second, Operation.GET_PROJECT, "foreign-promotion", new Project(created.projectId(), 100L)))
                .isInstanceOf(CapabilityException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_job WHERE id=100", String.class)).isEqualTo("QUEUED");
        verify(billing, never()).settlement(any(), anyLong());
    }

    @Test void consumerOwnerCannotBeUsedAsAServiceMappingOrDownloadedByTheService() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO account VALUES(?,'ACTIVE')", first.serviceAccountId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO design_capability_organization(caller_app_id,org_id,tenant_id,service_account_id,active) VALUES('platform',12,1,7,true)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO asset(id,object_key,owner_user_id,asset_type,source_type,sha256,declared_mime,size_bytes) VALUES(100,'test/consumer',7,'USER_SKETCH','USER_UPLOAD',?,'image/png',10)", "a".repeat(64));
        for (Operation action : new Operation[]{Operation.CREATE_DOWNLOAD_URL, Operation.COMPLETE_UPLOAD}) {
            assertThatThrownBy(() -> invoke(first, action, "asset", new Asset(100))).isInstanceOf(CapabilityException.class);
        }
        assertThatThrownBy(() -> invoke(first, Operation.CREATE_PROJECT, "foreign-sketch",
                new DesignServicePort.DesignProjectCreate("SELF_UPLOAD", null, 100L, Map.of(), "ELEVATION", "foreign-sketch")))
                .isInstanceOf(CapabilityException.class);
    }

    @Test void disabledMappingAllowsOnlyHistoricalReceiptScopeAndCannotMutateFrozenIdentity() {
        var payload = new DesignServicePort.DesignProjectCreate("SELF_UPLOAD", null, null, Map.of(), "FLAT", "history");
        invoke(first, Operation.CREATE_PROJECT, "history", payload);
        jdbc.update("UPDATE design_capability_organization SET active=false WHERE org_id=10");
        var query = new Request<>("platform", 10, new DesignServicePort.Actor(5, 6, "DESIGNER"), Operation.QUERY_RECEIPT,
                "lookup", "nonce", new ReceiptQuery(Operation.CREATE_PROJECT, "history"));
        ServicePrincipal historical = mapping.resolveRequest(query);
        assertThat(historical).isEqualTo(first);
        Receipt found = (Receipt) provider.invoke(historical, Operation.QUERY_RECEIPT, query).result();
        assertThat(found.status()).isEqualTo(ReceiptStatus.SUCCEEDED);
        assertThatThrownBy(() -> invoke(first, Operation.CREATE_PROJECT, "new-project", payload)).isInstanceOf(CapabilityException.class);
        assertThatThrownBy(() -> invoke(first, Operation.LIST_PROJECTS, "new-read", new Page(null, 20))).isInstanceOf(CapabilityException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE design_capability_receipt SET tenant_id=2 WHERE request_key='history'"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> mapping.resolveRequest(new Request<>("platform", 10, query.actor(), Operation.QUERY_RECEIPT,
                "lookup-missing", "nonce", new ReceiptQuery(Operation.CREATE_PROJECT, "not-found"))))
                .isInstanceOf(CapabilityException.class);
    }

    @Test void disabledPolishRecoveryCanOnlyQueryAnAlreadyDispatchedFact() {
        var note = new Note("已有需求", "FLAT");
        var request = new Request<>("platform", 10, new DesignServicePort.Actor(5, 6, "DESIGNER"),
                Operation.NOTE_POLISH, "polish-history", "nonce", note);
        receipts.claim(request);
        jdbc.update("UPDATE design_capability_receipt SET lease_until=now()-interval '1 second'");
        jdbc.update("UPDATE design_capability_organization SET active=false WHERE org_id=10");
        var prepared = new ServiceDesignBillingPort.Prepared("durable-preparation");
        when(billing.recoverPreparation(any(), eq(Operation.NOTE_POLISH), eq(note), eq(false)))
                .thenReturn(java.util.Optional.of(prepared));
        when(billing.completeRecoveredPolish(any(), eq(note), eq(prepared))).thenReturn("已完成的润色事实");
        var reply = invoke(first, Operation.QUERY_RECEIPT, "lookup", new ReceiptQuery(Operation.NOTE_POLISH, "polish-history"));
        var receipt = (Receipt) reply.result();
        assertThat(receipt.status()).isEqualTo(ReceiptStatus.SUCCEEDED);
        assertThat(receipt.result()).isEqualTo("已完成的润色事实");
        verify(billing, never()).prepare(any(), any(), any());
        verify(billing, never()).execute(any(), any(), any(), any());
        verify(billing).recoverPreparation(any(), eq(Operation.NOTE_POLISH), eq(note), eq(false));
    }

    @Test void disabledPolishWithoutACommittedExternalResultStaysUnresolved() {
        var note = new Note("已有需求", "FLAT");
        receipts.claim(new Request<>("platform", 10, new DesignServicePort.Actor(5, 6, "DESIGNER"),
                Operation.NOTE_POLISH, "polish-unknown", "nonce", note));
        jdbc.update("UPDATE design_capability_receipt SET lease_until=now()-interval '1 second'");
        jdbc.update("UPDATE design_capability_organization SET active=false WHERE org_id=10");
        when(billing.recoverPreparation(any(), eq(Operation.NOTE_POLISH), eq(note), eq(false))).thenReturn(java.util.Optional.empty());
        Receipt receipt = (Receipt) invoke(first, Operation.QUERY_RECEIPT, "lookup", new ReceiptQuery(Operation.NOTE_POLISH, "polish-unknown")).result();
        assertThat(receipt.status()).isEqualTo(ReceiptStatus.UNKNOWN);
        verify(billing, never()).prepare(any(), any(), any());
        verify(billing, never()).execute(any(), any(), any(), any());
        verify(billing, never()).resolveFailure(any(), any(), any(), any());
    }

    @Test void billingPreparationCanValidateAnAlreadyCommittedReceiptWithoutAFrontendSession() {
        var request = new Request<>(first.callerAppId(), first.orgId(), new DesignServicePort.Actor(5, 6, "DESIGNER"),
                Operation.NOTE_POLISH, "paid", "nonce", new Note("需求", "FLAT"));
        var lease = receipts.claim(request);
        var subject = new PlatformCostPort.Subject(first.callerAppId(), first.orgId(), first.tenantId(), first.serviceAccountId(),
                lease.receipt().receiptId(), request.requestKey());
        SecurityContextHolder.clearContext();
        tx.executeWithoutResult(status -> mapping.requireActive(subject));
        var foreign = new PlatformCostPort.Subject(second.callerAppId(), second.orgId(), second.tenantId(), second.serviceAccountId(),
                lease.receipt().receiptId(), request.requestKey());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> mapping.requireActive(foreign))).isInstanceOf(CapabilityException.class);
    }
}
