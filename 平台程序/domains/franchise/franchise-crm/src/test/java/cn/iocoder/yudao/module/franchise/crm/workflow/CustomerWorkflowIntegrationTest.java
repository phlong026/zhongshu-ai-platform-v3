package cn.iocoder.yudao.module.franchise.crm.workflow;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.CustomerWorkflowFactsPort;
import cn.iocoder.yudao.module.franchise.api.CustomerWorkflowReferencePort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.crm.CrmOrgScope;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.LeadService;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchiseFixture.Store;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel.*;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** PostgreSQL/Flyway and real row/advisory locks; only external catalog/asset/source ports are controlled fixtures. */
@Import({LeadService.class, CustomerService.class, CrmOrgScope.class, FranchiseCommands.class, CommandLogImpl.class,
        CustomerWorkflowReadService.class, CustomerWorkflowService.class, CustomerWorkflowAssetPolicy.class, CustomerWorkflowIntegrationTest.Ports.class})
class CustomerWorkflowIntegrationTest extends FranchisePgIntegrationTest {
    private static final AtomicLong PHONES = new AtomicLong(15_000_000_000L + System.nanoTime() % 900_000_000L);
    @Resource CustomerWorkflowService workflow;
    @Resource CustomerWorkflowReadService reads;
    @Resource CustomerService customers;
    @Resource LeadService leads;
    @Resource JdbcTemplate jdbc;
    @Resource AssetAccessPort assets;
    @Resource StoreServiceAccountPort accounts;
    @Resource SourceFacts facts;
    private Store store;
    private PostSnapshot sales, colleague, teamLead, boss, otherSales;
    private long tenant;

    @TestConfiguration
    static class Ports {
        @Bean AssetAccessPort assets() { return mock(AssetAccessPort.class); }
        @Bean StoreServiceAccountPort accounts() { return mock(StoreServiceAccountPort.class); }
        @Bean SourceFacts facts() { return new SourceFacts(); }
        @Bean CustomerWorkflowReferencePort references() {
            return (post, id) -> {
                if (id != 7001) throw new ServiceException(404, "户型未公开");
                return new CustomerWorkflowReferencePort.Reference("7001", "公开山居户型");
            };
        }
    }
    static class SourceFacts implements CustomerWorkflowFactsPort {
        volatile List<Fact> rows = List.of();
        @Override public List<Fact> findFacts(long tenant, Map<Long, Long> customers) {
            return rows.stream().filter(row -> customers.containsKey(row.customerId())).toList();
        }
    }

    @BeforeEach
    void setup() {
        store = fixture.store();
        sales = fixture.post(store.orgId(), SALES, store.salesTeamA());
        colleague = fixture.post(store.orgId(), SALES, store.salesTeamA());
        teamLead = fixture.post(store.orgId(), SALES_LEAD, store.salesTeamA());
        boss = fixture.post(store.orgId(), BOSS, null);
        var other = fixture.store();
        otherSales = fixture.post(other.orgId(), SALES, other.salesTeamA());
        tenant = store.orgId();
        bindTenant(store.orgId(), tenant); bindTenant(other.orgId(), tenant);
        TenantContextHolder.setTenantId(tenant);
        reset(assets, accounts); facts.rows = List.of();
        when(accounts.requireServiceAccount(anyLong())).thenReturn(5001L);
        when(assets.validateReference(any(), anyLong(), anySet())).thenAnswer(invocation -> {
            AssetAccessPort.Actor actor = invocation.getArgument(0);
            return actor.orgId() == store.orgId() && (long) invocation.getArgument(1) == 9001L;
        });
    }
    @AfterEach void clearTenant() { TenantContextHolder.clear(); }

    @Test
    void readDoesNotCreateOrBackfillFromLegacyFollowingAndListUsesTheSameProjection() {
        long id = customer();
        jdbc.update("UPDATE fr_customer SET status='FOLLOWING' WHERE id=?", id);
        View view = as(sales, () -> workflow.get(sales, id));
        assertThat(view.workflowId()).isNull(); assertThat(view.revision()).isZero();
        assertThat(view.steps()).hasSize(8).allSatisfy(step -> assertThat(step.status()).isEqualTo("TODO"));
        assertThat(view.completedCount()).isZero(); assertThat(count("fr_customer_workflow", id)).isZero();
        var page = new PageParam(); page.setPageNo(1); page.setPageSize(20);
        List<CustomerDO> rows = as(sales, () -> customers.page(sales, page, null).getList());
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getWorkflow().progressPercent()).isEqualByComparingTo("0");
            assertThat(row.getWorkflow().currentStepCode()).isEqualTo("INVITATION");
        });
        assertThat(count("fr_customer_workflow", id)).isZero();
    }

    @Test
    void savingCompletingAndReopeningAreSeparateVersionedFactsWithImmutableHistory() {
        long id = customer();
        SaveCommand input = invitation(0);
        View draft = as(sales, () -> workflow.save(sales, id, "INVITATION", input, "save-1"));
        assertThat(draft.revision()).isEqualTo(1); assertThat(draft.completedCount()).isZero();
        assertThat(draft.steps().get(0).canComplete()).isTrue();
        View done = as(sales, () -> workflow.complete(sales, id, "INVITATION", 1, "done-1"));
        assertThat(done.revision()).isEqualTo(2); assertThat(done.progressPercent()).isEqualByComparingTo("12.5");
        assertThat(done.currentStepCode()).isEqualTo("INTRODUCTION");
        assertThat(done.steps().get(0).canWrite()).isFalse();
        assertThat(as(sales, () -> workflow.save(sales, id, "INVITATION", input, "save-1")).revision()).isEqualTo(2);
        failure(409, () -> as(sales, () -> workflow.save(sales, id, "INVITATION", invitation(2), "overwrite")));
        failure(400, () -> as(sales, () -> workflow.reopen(sales, id, "INVITATION", new ReopenCommand(2, " "), "empty-reason")));
        View reopened = as(sales, () -> workflow.reopen(sales, id, "INVITATION", new ReopenCommand(2, "到店时间需要更正"), "reopen-1"));
        assertThat(reopened.revision()).isEqualTo(3); assertThat(reopened.completedCount()).isZero();
        var history = as(sales, () -> workflow.records(sales, id, 1, 20));
        assertThat(history.getTotal()).isEqualTo(3);
        assertThat(history.getList()).extracting(RecordView::type).containsExactly("REOPEN", "COMPLETE", "SAVE");
        assertThat(history.getList().get(0).note()).isEqualTo("到店时间需要更正");
        assertThat(history.getList().get(1).fields()).containsEntry("invitationResult", "CONFIRMED");
        assertThat(count("fr_customer_workflow", id)).isEqualTo(1);
    }

    @Test
    void revisionConflictsAndReusedKeysRollbackWithoutExtraFacts() {
        long id = customer(), other = customer();
        as(sales, () -> workflow.save(sales, id, "INVITATION", invitation(0), "one-key"));
        failure(409, () -> as(sales, () -> workflow.save(sales, id, "INVITATION", invitation(0), "stale")));
        failure(1_099_000_000, () -> as(sales, () -> workflow.save(sales, other, "INVITATION", invitation(0), "one-key")));
        failure(1_099_000_000, () -> as(sales, () -> workflow.complete(sales, id, "INVITATION", 1, "one-key")));
        assertThat(as(sales, () -> workflow.records(sales, id, 1, 20)).getTotal()).isEqualTo(1);
        assertThat(count("fr_customer_workflow", other)).isZero();
    }

    @Test
    void actualCompletionRequiresStructuredEvidenceAndAcceptedAssetReferences() {
        long id = customer();
        var pending = new SaveCommand(0, today(), "仍待确认", Map.of("contactMethod", "微信", "invitationResult", "PENDING"), List.of());
        View draft = as(sales, () -> workflow.save(sales, id, "INVITATION", pending, "pending"));
        assertThat(draft.steps().get(0).canComplete()).isFalse();
        failure(400, () -> as(sales, () -> workflow.complete(sales, id, "INVITATION", 1, "bad-complete")));
        var quote = Map.<String, Object>of("amountCents", "12000000", "scope", "主体土建", "versionLabel", "V1", "communicated", true, "result", "客户接受");
        failure(400, () -> as(sales, () -> workflow.save(sales, id, "QUOTATION",
                new SaveCommand(1, today(), "报价", quote, List.of(new Attachment("9002", "别店报价.pdf"))), "bad-file")));
        assertThat(as(sales, () -> workflow.get(sales, id)).revision()).isEqualTo(1);
        View saved = as(sales, () -> workflow.save(sales, id, "QUOTATION",
                new SaveCommand(1, today(), "报价", quote, List.of(new Attachment("9001", "报价.pdf"))), "good-file"));
        assertThat(saved.steps().get(5).canComplete()).isTrue();
        View completed = as(sales, () -> workflow.complete(sales, id, "QUOTATION", 2, "quote-done"));
        assertThat(completed.progressPercent()).isEqualByComparingTo("12.5");
        assertThat(completed.currentStepCode()).isEqualTo("INVITATION"); // historical records may be supplemented out of order
        verify(assets, atLeastOnce()).validateReference(argThat(actor -> actor.userId() == sales.userId()), eq(9001L), anySet());
    }

    @Test
    void onlyPublishedCatalogReferencesAreAllowedAndTheirNameIsServerOwned() {
        long id = customer();
        var fields = Map.<String, Object>of("referenceCaseId", "7001", "referenceCaseName", "伪造名称", "selectionResult", "两层方案", "confirmed", true);
        View view = as(sales, () -> workflow.save(sales, id, "HOUSE_SELECTION", new SaveCommand(0, today(), "", fields, List.of()), "public-case"));
        assertThat(view.steps().get(2).fields()).containsEntry("referenceCaseName", "公开山居户型");
        assertThat(view.steps().get(2).canComplete()).isTrue();
        failure(404, () -> as(sales, () -> workflow.save(sales, id, "HOUSE_SELECTION",
                new SaveCommand(1, today(), "", Map.of("referenceCaseId", "7002"), List.of()), "private-case")));
        assertThat(as(sales, () -> workflow.get(sales, id)).revision()).isEqualTo(1);
    }

    @Test
    void ownerTeamCompanyTenantAndReadonlyBossAreEnforcedBeforeAnyMutation() {
        long id = customer();
        assertThatThrownBy(() -> as(colleague, () -> workflow.get(colleague, id))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> as(otherSales, () -> workflow.get(otherSales, id))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> as(boss, () -> workflow.save(boss, id, "INVITATION", invitation(0), "boss-write"))).isInstanceOf(ServiceException.class);
        assertThat(as(boss, () -> workflow.get(boss, id)).canWrite()).isFalse();
        assertThat(as(teamLead, () -> workflow.get(teamLead, id)).canWrite()).isTrue();
        long other = as(otherSales, () -> capture(otherSales));
        failure(404, () -> as(sales, () -> workflow.summaries(sales, List.of(id, other))));
        TenantContextHolder.setTenantId(tenant + 999);
        failure(404, () -> as(sales, () -> workflow.get(sales, id)));
        failure(404, () -> reads.readAuthorized(tenant + 999, store.orgId(), id));
        TenantContextHolder.setTenantId(tenant);
        failure(404, () -> reads.readAuthorized(tenant, otherSales.orgId(), id));
        assertThat(reads.readAuthorized(tenant, store.orgId(), id).canWrite()).isFalse();
        assertThat(count("fr_customer_workflow", id)).isZero();
    }

    @Test
    void contractAndConstructionAreReadonlySourceFactsAndMissingEvidenceNeverCompletes() {
        long id = customer();
        facts.rows = List.of(new CustomerWorkflowFactsPort.Fact(id, store.orgId(), "CONTRACT", null,
                Map.of("contractId", "21", "status", "SIGNED"), false, List.of("待补录签约日期及附件")));
        View incomplete = as(sales, () -> workflow.get(sales, id));
        assertThat(incomplete.steps().get(6).status()).isEqualTo("DRAFT"); assertThat(incomplete.completedCount()).isZero();
        failure(400, () -> as(sales, () -> workflow.complete(sales, id, "CONTRACT", 0, "fabricate-sign")));
        failure(400, () -> as(sales, () -> workflow.save(sales, id, "START", invitation(0), "fabricate-start")));
        facts.rows = List.of(new CustomerWorkflowFactsPort.Fact(id, store.orgId(), "CONTRACT", today(),
                        Map.of("contractId", "21", "status", "SIGNED", "assetId", "9001"), true, List.of()),
                new CustomerWorkflowFactsPort.Fact(id, store.orgId(), "START", today(), Map.of("projectId", "32"), true, List.of()));
        View complete = as(sales, () -> workflow.get(sales, id));
        assertThat(complete.completedCount()).isEqualTo(2); assertThat(complete.progressPercent()).isEqualByComparingTo("25");
        assertThat(complete.steps().subList(6, 8)).allSatisfy(step -> {
            assertThat(step.canWrite()).isFalse(); assertThat(step.canComplete()).isFalse(); assertThat(step.canReopen()).isFalse();
        });
        assertThat(count("fr_customer_workflow", id)).isZero();
    }

    @Test
    void concurrentSameKeyFirstSaveCreatesOnlyOneWorkflowAndOneAuditRecord() throws Exception {
        long id = customer(); currentPost.use(sales);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(4);
        try {
            Callable<View> request = () -> {
                TenantContextHolder.setTenantId(tenant);
                try { assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return workflow.save(sales, id, "INVITATION", invitation(0), "concurrent"); }
                finally { TenantContextHolder.clear(); }
            };
            var futures = java.util.stream.IntStream.range(0, 4).mapToObj(i -> executor.submit(request)).toList();
            start.countDown();
            for (var future : futures) assertThat(future.get(20, TimeUnit.SECONDS).revision()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
        assertThat(count("fr_customer_workflow", id)).isEqualTo(1);
        assertThat(as(sales, () -> workflow.records(sales, id, 1, 20)).getTotal()).isEqualTo(1);
    }

    @Test
    void pauseAndLostRequireReasonAndResumePreservesEarlierFacts() {
        long id = customer();
        as(sales, () -> workflow.save(sales, id, "INVITATION", invitation(0), "save"));
        View paused = as(sales, () -> workflow.status(sales, id, new StatusCommand(1, "PAUSED", "等待客户返乡"), "pause"));
        assertThat(paused.status()).isEqualTo("PAUSED"); assertThat(paused.canWrite()).isFalse();
        failure(409, () -> as(sales, () -> workflow.complete(sales, id, "INVITATION", 2, "paused-complete")));
        View resumed = as(sales, () -> workflow.status(sales, id, new StatusCommand(2, "ACTIVE", "客户已返乡"), "resume"));
        assertThat(resumed.steps().get(0).canComplete()).isTrue();
        assertThat(resumed.events()).extracting(RecordView::type).containsExactly("STATUS", "STATUS", "SAVE");
    }

    private long customer() { return as(sales, () -> capture(sales)); }
    private long capture(PostSnapshot post) {
        return leads.capture(post, new LeadService.CaptureCommand("陈先生", Long.toString(PHONES.incrementAndGet()), "建房咨询", "到店"), null).customerId();
    }
    private <T> T as(PostSnapshot post, Supplier<T> operation) { currentPost.use(post); return operation.get(); }
    private void bindTenant(long company, long tenant) {
        jdbc.update("INSERT INTO system_dept(id,name,status,deleted,tenant_id) VALUES(?,'流程测试门店',0,0,?) ON CONFLICT(id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id,status=0,deleted=0", company, tenant);
    }
    private long count(String table, long customer) { return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE customer_id=?", Long.class, customer); }
    private static SaveCommand invitation(long revision) {
        return new SaveCommand(revision, today(), "已确认周末到店", Map.of("contactMethod", "微信", "invitationResult", "CONFIRMED",
                "appointmentAt", today().plusDays(1) + "T10:00"), List.of());
    }
    private static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }
    private static void failure(int code, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(code));
    }
}
