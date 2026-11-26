package cn.iocoder.yudao.module.franchise.crm.workflow;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.CustomerWorkflowFactsPort;
import cn.iocoder.yudao.module.franchise.api.CustomerWorkflowQuotePort;
import cn.iocoder.yudao.module.franchise.api.CustomerWorkflowReferencePort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.crm.CrmOrgScope;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.LeadService;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerMapper;
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
        CustomerWorkflowReadService.class, CustomerWorkflowService.class, CustomerWorkflowSupplementService.class,
        CustomerWorkflowAssetPolicy.class, CustomerWorkflowIntegrationTest.Ports.class})
class CustomerWorkflowIntegrationTest extends FranchisePgIntegrationTest {
    private static final AtomicLong PHONES = new AtomicLong(15_000_000_000L + System.nanoTime() % 900_000_000L);
    @Resource CustomerWorkflowService workflow;
    @Resource CustomerWorkflowReadService reads;
    @Resource CustomerWorkflowSupplementService supplements;
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
        @Bean CustomerWorkflowQuotePort quotes() {
            return (post, customerId, quoteId) -> new CustomerWorkflowQuotePort.Quote(1_234_500L, "V1 · 原价目");
        }
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
    void measurementObservationsSurviveLegacySavesAndKeepEachArchivedVersion() {
        long id = customer();
        var legacy = Map.<String,Object>of("address","宅基地","measurer","陈工","width","12","depth","15","area","180","checked",true);
        var proof = List.of(new Attachment("9001","实测原件.pdf"));
        var first = new SaveCommand(0, today(), "", legacy, proof);
        var original = as(sales, () -> workflow.save(sales,id,"MEASUREMENT",first,"legacy-0"));
        assertThat(CustomerWorkflowRules.missing(Step.MEASUREMENT,first.occurredOn(),original.steps().get(4).fields(),proof)).isEmpty();
        assertThat(original.steps().get(4).fields()).doesNotContainKeys("orientation","siteElevationNote");

        var surveyed = new java.util.LinkedHashMap<>(legacy);
        surveyed.put("orientation"," 南偏东 15° "); surveyed.put("siteElevationNote","北界比南界高 0.45 米，车库入口需放坡");
        var capture = new SaveCommand(1, today(), "", surveyed, proof);
        as(sales, () -> workflow.save(sales,id,"MEASUREMENT",capture,"site-1"));
        var olderApp = new SaveCommand(2, today(), "复查尺寸", legacy, proof);
        var kept = as(sales, () -> workflow.save(sales,id,"MEASUREMENT",olderApp,"legacy-2"));
        assertThat(kept.steps().get(4).fields()).containsEntry("orientation","南偏东 15°")
                .containsEntry("siteElevationNote",surveyed.get("siteElevationNote"));
        var clear = new java.util.LinkedHashMap<>(legacy);
        clear.put("orientation"," ");
        failure(1099000000, () -> as(sales, () -> workflow.save(sales,id,"MEASUREMENT",
                new SaveCommand(2,today(),"复查尺寸",clear,proof),"legacy-2")));
        var updated = as(sales, () -> workflow.save(sales,id,"MEASUREMENT",
                new SaveCommand(3,today(),"更正朝向",clear,proof),"site-3"));
        assertThat(updated.steps().get(4).fields()).doesNotContainKey("orientation")
                .containsEntry("siteElevationNote",surveyed.get("siteElevationNote"));
        assertThat(as(sales, () -> workflow.save(sales,id,"MEASUREMENT",first,"legacy-0")).revision()).isEqualTo(4);
        var history = as(sales, () -> workflow.records(sales,id,1,20)).getList();
        assertThat(history).extracting(record -> record.fields().get("orientation"))
                .containsExactly(null,"南偏东 15°","南偏东 15°",null);
        assertThat(history.get(3).fields()).doesNotContainKey("siteElevationNote");
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
    void legacyQuotationSaveCannotSilentlyRemoveALinkedFormalQuote() {
        long id = customer();
        String quoteId = "1234567890123456789";
        View saved = as(sales, () -> workflow.save(sales, id, "QUOTATION",
                new SaveCommand(0, today(), "原备注",
                        Map.of("quoteId", quoteId, "communicated", true, "result", "已发给客户"), List.of()), "linked-quote"));
        var before = saved.steps().get(5);
        var old = new SaveCommand(saved.revision(), today(), "只改备注",
                Map.of("amountCents", "1234500", "versionLabel", "V1 · 原价目", "scope", "", "communicated", true), List.of());
        assertThatThrownBy(() -> as(sales, () -> workflow.save(sales, id, "QUOTATION", old, "old-save")))
                .isInstanceOf(ServiceException.class).hasMessageContaining("请更新小程序");
        View unchanged = as(sales, () -> workflow.get(sales, id));
        assertThat(unchanged.revision()).isEqualTo(saved.revision());
        assertThat(unchanged.steps().get(5)).isEqualTo(before);
        assertThat(as(sales, () -> workflow.records(sales, id, 1, 20)).getTotal()).isEqualTo(1);

        var external = new SaveCommand(saved.revision(), null, "", Map.of("quoteId", "", "communicated", false), List.of());
        View switched = as(sales, () -> workflow.save(sales, id, "QUOTATION", external, "external-quote"));
        assertThat(switched.steps().get(5).fields()).doesNotContainKey("quoteId").containsEntry("communicated", false);
        assertThat(switched.steps().get(5).canComplete()).isFalse();
        assertThat(as(sales, () -> workflow.save(sales, id, "QUOTATION", external, "external-quote")).revision())
                .isEqualTo(switched.revision());
        var history = as(sales, () -> workflow.records(sales, id, 1, 20)).getList();
        assertThat(history).hasSize(2);
        assertThat(history.get(1).fields()).containsEntry("quoteId", quoteId);
    }

    @Test
    void designContractSlotLivesInFieldsAndBlocksLegacySilentRemoval() {
        long id = customer();
        var measurement = new SaveCommand(0, today(), "实测完成",
                Map.of("address", "村东头", "measurer", "老周", "width", "12", "depth", "15", "area", "180",
                        "floors", "2", "checked", true, "designContractAssetId", "9001", "designContractName", "设计服务合同.pdf"),
                List.of());
        View saved = as(sales, () -> workflow.save(sales, id, "MEASUREMENT", measurement, "slot-save"));
        assertThat(saved.steps().get(4).fields()).containsEntry("designContractAssetId", "9001")
                .containsEntry("designContractName", "设计服务合同.pdf");
        // 未验收或他店资产拒绝引用
        failure(400, () -> as(sales, () -> workflow.save(sales, id, "MEASUREMENT", new SaveCommand(saved.revision(), today(), "换合同",
                Map.of("designContractAssetId", "9002", "designContractName", "别店合同.pdf"), List.of()), "slot-bad")));
        // 旧版小程序漏发槽位键会随整字段替换清掉引用，守卫拒绝
        assertThatThrownBy(() -> as(sales, () -> workflow.save(sales, id, "MEASUREMENT", new SaveCommand(saved.revision(), today(), "旧版只改地址",
                Map.of("address", "村西头"), List.of()), "slot-legacy")))
                .isInstanceOf(ServiceException.class).hasMessageContaining("请更新小程序");
        // 只有显式空值才能清除
        var cleared = new SaveCommand(saved.revision(), today(), "删除设计合同",
                Map.of("address", "村东头", "measurer", "老周", "width", "12", "depth", "15", "area", "180",
                        "floors", "2", "checked", true, "designContractAssetId", "", "designContractName", ""), List.of());
        assertThat(as(sales, () -> workflow.save(sales, id, "MEASUREMENT", cleared, "slot-clear")).steps().get(4).fields())
                .doesNotContainKeys("designContractAssetId", "designContractName");
        // 已归属本客户的资产（事件历史里仍有引用）不能被另一客户借用
        long other = customer();
        failure(400, () -> as(sales, () -> workflow.save(sales, other, "MEASUREMENT", new SaveCommand(0, today(), "借用合同",
                Map.of("address", "别家", "measurer", "x", "width", "1", "depth", "1", "area", "1", "floors", "1", "checked", true,
                        "designContractAssetId", "9001", "designContractName", "设计服务合同.pdf"), List.of()), "slot-steal")));
        assertThat(as(sales, () -> workflow.records(sales, id, 1, 20)).getTotal()).isEqualTo(2);
    }

    @Test
    void quotationDesignFileSurvivesQuoteSwitchesAndTicketDownloadAcceptsFieldReferences() {
        long id = customer();
        var withFile = new SaveCommand(0, today(), "报价与设计文件",
                Map.of("amountCents", "12000000", "scope", "主体土建", "versionLabel", "V1", "communicated", true, "result", "客户接受",
                        "designFileAssetId", "9001", "designFileName", "别墅设计方案V1.pdf"), List.of());
        View saved = as(sales, () -> workflow.save(sales, id, "QUOTATION", withFile, "design-file-save"));
        assertThat(saved.steps().get(5).fields()).containsEntry("designFileAssetId", "9001");
        // 关联正式报价（快照替换）保留设计文件：客户端每次都带全部定义字段
        var linked = new SaveCommand(saved.revision(), today(), "改用原单",
                Map.of("quoteId", "1234567890123456789", "communicated", true, "result", "已发给客户",
                        "designFileAssetId", "9001", "designFileName", "别墅设计方案V1.pdf"), List.of());
        View switched = as(sales, () -> workflow.save(sales, id, "QUOTATION", linked, "design-file-linked"));
        assertThat(switched.steps().get(5).fields()).containsEntry("quoteId", "1234567890123456789")
                .containsEntry("designFileAssetId", "9001").containsEntry("designFileName", "别墅设计方案V1.pdf");
        // 下载票据：fields 引用可出票；未被任何记录引用的资产拒绝
        when(assets.createWorkflowDownloadUrl(any(), eq(id), eq("QUOTATION"), eq(9001L)))
                .thenReturn(new AssetAccessPort.DownloadUrlView("https://ticket/design", null));
        assertThat(as(sales, () -> workflow.download(sales, id, "QUOTATION", 9001L)).downloadUrl()).isEqualTo("https://ticket/design");
        failure((int) RESOURCE_FORBIDDEN.getCode(), () -> as(sales, () -> workflow.download(sales, id, "QUOTATION", 9002L)));
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
    void legacyHouseSelectionCannotEraseTheRecordedDirectionButExplicitSwitchMayProceed() {
        long id = customer();
        String step = "HOUSE_SELECTION";
        var custom = Map.<String,Object>of("selectionMode", "EXTERNAL_CUSTOM", "customDirection", "一层无台阶，老人房朝南", "confirmed", true);
        View before = as(sales, () -> workflow.save(sales, id, step, new SaveCommand(0, today(), "原备注", custom, List.of()), "custom-kept"));
        var legacy = new SaveCommand(before.revision(), today(), "旧版只改备注", Map.of("confirmed", true,
                "referenceCaseId", "", "referenceCaseName", "", "selectionResult", "", "versionLabel", ""), List.of());
        assertThatThrownBy(() -> as(sales, () -> workflow.save(sales, id, step, legacy, "legacy-mode-omitted")))
                .isInstanceOf(ServiceException.class).hasMessageContaining("请更新小程序");
        View unchanged = as(sales, () -> workflow.get(sales, id));
        assertThat(unchanged.revision()).isEqualTo(before.revision());
        assertThat(unchanged.steps().get(2)).isEqualTo(before.steps().get(2));
        assertThat(as(sales, () -> workflow.records(sales, id, 1, 20)).getTotal()).isEqualTo(1);

        var standard = new SaveCommand(before.revision(), null, "主动改用参考户型",
                Map.of("selectionMode", "", "referenceCaseId", "7001", "selectionResult", "改用标准户型", "confirmed", false), List.of());
        View switched = as(sales, () -> workflow.save(sales, id, step, standard, "explicit-standard"));
        assertThat(switched.steps().get(2).fields()).doesNotContainKeys("selectionMode", "customDirection")
                .containsEntry("referenceCaseId", "7001").containsEntry("confirmed", false);
        assertThat(switched.steps().get(2).canComplete()).isFalse();
        assertThat(as(sales, () -> workflow.save(sales, id, step, standard, "explicit-standard")).revision())
                .isEqualTo(switched.revision());
        var history = as(sales, () -> workflow.records(sales, id, 1, 20)).getList();
        assertThat(history).hasSize(2);
        assertThat(history.get(1).fields()).isEqualTo(custom);
    }

    @Test
    void externalCustomDirectionRequiresAnExplicitChoiceAndCustomerConfirmationWithoutInventingAnAttachment() {
        long id = customer();
        String step = "HOUSE_SELECTION", direction = "一层无台阶，老人房朝南，厨房临院";
        var incomplete = Map.<String, Object>of("selectionMode", "EXTERNAL_CUSTOM", "customDirection", direction, "confirmed", false);
        var draft = as(sales, () -> workflow.save(sales, id, step, new SaveCommand(0, today(), "", incomplete, List.of()), "direction-draft"));
        assertThat(draft.steps().get(2).canComplete()).isFalse();
        failure(400, () -> as(sales, () -> workflow.complete(sales, id, step, draft.revision(), "unconfirmed")));
        var confirmed = Map.<String, Object>of("selectionMode", "EXTERNAL_CUSTOM", "customDirection", direction, "confirmed", true);
        var doneDraft = as(sales, () -> workflow.save(sales, id, step, new SaveCommand(draft.revision(), today(), "", confirmed, List.of()), "direction-confirmed"));
        var done = as(sales, () -> workflow.complete(sales, id, step, doneDraft.revision(), "direction-done"));
        assertThat(done.steps().get(2).attachments()).isEmpty();
        assertThat(done.steps().get(2).fields()).containsAllEntriesOf(confirmed).doesNotContainKeys("referenceCaseId", "versionLabel");
        assertThat(done.completedCount()).isEqualTo(1);
        assertThat(as(sales, () -> workflow.complete(sales, id, step, doneDraft.revision(), "direction-done")).revision()).isEqualTo(done.revision());
        assertThat(as(boss, () -> workflow.records(boss, id, 1, 20)).getList()).filteredOn(row -> row.type().equals("COMPLETE"))
                .singleElement().satisfies(row -> assertThat(row.fields()).containsAllEntriesOf(confirmed));
        failure(1_110_000_000, () -> as(colleague, () -> workflow.records(colleague, id, 1, 20)));
    }

    @Test
    void anUnselectedDirectionOrBareConfirmationCannotReplaceAnExistingHousePlan() {
        long id = customer();
        String step = "HOUSE_SELECTION";
        for (var bad : List.of(Map.<String, Object>of("selectionMode", "OTHER", "confirmed", true),
                Map.<String, Object>of("customDirection", "庭院", "confirmed", true),
                Map.<String, Object>of("selectionMode", "EXTERNAL_CUSTOM", "referenceCaseId", "7001", "customDirection", "庭院"),
                Map.<String, Object>of("selectionMode", "EXTERNAL_CUSTOM", "referenceCaseName", "假原单", "confirmed", true))) {
            failure(400, () -> as(sales, () -> workflow.save(sales, id, step, new SaveCommand(0, today(), "", bad, List.of()), "invalid-direction")));
        }
        var blank = Map.<String, Object>of("selectionMode", "EXTERNAL_CUSTOM", "customDirection", "  ", "confirmed", true);
        var draft = as(sales, () -> workflow.save(sales, id, step, new SaveCommand(0, today(), "", blank, List.of()), "empty-direction"));
        assertThat(draft.steps().get(2).completionErrors()).anyMatch(error -> error.contains("定制方向"));
        failure(400, () -> as(sales, () -> workflow.complete(sales, id, step, draft.revision(), "empty-done")));
        var onlyMode = Map.<String, Object>of("selectionMode", "EXTERNAL_CUSTOM", "customDirection", "外部定制", "confirmed", true);
        var placeholder = as(sales, () -> workflow.save(sales, id, step, new SaveCommand(draft.revision(), today(), "", onlyMode, List.of()), "mode-as-direction"));
        assertThat(placeholder.steps().get(2).canComplete()).isFalse();
        var legacy = Map.<String, Object>of("selectionResult", "已确定外部方案", "confirmed", true);
        failure(400, () -> as(sales, () -> workflow.save(sales, id, step,
                new SaveCommand(placeholder.revision(), today(), "", legacy, List.of()), "legacy-cannot-replace-direction")));
        assertThat(as(sales, () -> workflow.get(sales, id)).steps().get(2)).isEqualTo(placeholder.steps().get(2));

        // Legacy external plans remain supported when there is no recorded custom direction to replace.
        long legacyId = customer();
        var missing = as(sales, () -> workflow.save(sales, legacyId, step, new SaveCommand(0, today(), "", legacy, List.of()), "legacy-no-proof"));
        assertThat(missing.steps().get(2).canComplete()).isFalse();
        var file = as(sales, () -> workflow.save(sales, legacyId, step, new SaveCommand(missing.revision(), today(), "", legacy,
                List.of(new Attachment("9001", "客户原户型.pdf"))), "old-external-plan"));
        assertThat(file.steps().get(2).canComplete()).isTrue();
        assertThat(file.steps().get(2).fields()).doesNotContainKeys("selectionMode", "customDirection");
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
        View paused = as(sales, () -> workflow.status(sales, id, new StatusCommand(1, "PAUSED", null, "等待客户返乡", today().plusDays(3)), "pause"));
        assertThat(paused.status()).isEqualTo("PAUSED"); assertThat(paused.canWrite()).isFalse();
        failure(409, () -> as(sales, () -> workflow.complete(sales, id, "INVITATION", 2, "paused-complete")));
        View resumed = as(sales, () -> workflow.status(sales, id, new StatusCommand(2, "ACTIVE", "客户已返乡"), "resume"));
        assertThat(resumed.steps().get(0).canComplete()).isTrue();
        assertThat(resumed.events()).extracting(RecordView::type).containsExactly("STATUS", "STATUS", "SAVE");
    }

    @Test
    void skippingVisitCountsProgressAndPreservesBothDraftAndSeparateAudit() {
        long id = customer();
        String code = Step.SITE_VISIT.name();
        var draft = new SaveCommand(0, today(), "之前记下的草稿", Map.of("siteName", "备选现场", "feedback", "先核对地址"),
                List.of(new Attachment("9001", "带看草稿.pdf")));
        as(sales, () -> workflow.save(sales, id, code, draft, "draft"));
        var command = new SkipVisitCommand(1, "客户决定此次免带看");
        var skipped = as(sales, () -> workflow.skipVisit(sales, id, command, "skip"));
        var step = skipped.steps().get(3);
        assertThat(step.status()).isEqualTo("SKIPPED");
        assertThat(step.note()).isEqualTo(command.reason());
        assertThat(step.completedAt()).isNotNull();
        assertThat(step.occurredOn()).isNull();
        assertThat(step.attachments()).isEmpty();
        assertThat(step.fields()).containsOnlyKeys("visitSkip");
        assertThat(step.canWrite()).isFalse();
        assertThat(step.canReopen()).isTrue();
        assertThat(skipped.completedCount()).isOne();
        assertThat(skipped.progressPercent()).isEqualByComparingTo("12.5");
        assertThat(as(sales, () -> workflow.summaries(sales, List.of(id))).get(id).completedCount()).isOne();
        var headquarters = reads.readAuthorized(tenant, store.orgId(), id).steps().get(3);
        assertThat(headquarters.status()).isEqualTo("SKIPPED");
        assertThat(headquarters.canReopen()).isFalse();
        var history = reads.recordsAuthorized(tenant, store.orgId(), id, 1, 20).getList();
        assertThat(history).extracting(RecordView::type).containsExactly("SKIP", "SAVE");
        assertThat(history.get(0).actorUserId()).isEqualTo(Long.toString(sales.userId()));
        assertThat(history.get(0).createdAt()).isNotNull();
        assertThat(history.get(0).fields()).isEqualTo(step.fields());
        assertThat(as(sales, () -> workflow.skipVisit(sales, id, command, "skip"))).isEqualTo(skipped);
        assertThatThrownBy(() -> as(sales, () -> workflow.skipVisit(sales, id, new SkipVisitCommand(1, "另一个原因"), "skip"))).isInstanceOf(ServiceException.class);
        failure(409, () -> as(sales, () -> workflow.complete(sales, id, code, 2, "legacy-complete")));
        var reopened = as(sales, () -> workflow.reopen(sales, id, code, new ReopenCommand(2, "客户重新预约"), "reopen"));
        assertThat(reopened.completedCount()).isZero();
        assertThat(reopened.steps().get(3).fields()).isEqualTo(draft.fields());
        assertThat(reopened.steps().get(3).note()).isEqualTo(draft.note());
        assertThat(reopened.steps().get(3).attachments()).isEqualTo(draft.attachments());
        var visited = as(sales, () -> workflow.complete(sales, id, code, 3, "actual"));
        assertThat(visited.steps().get(3).status()).isEqualTo("COMPLETED");
        assertThat(visited.events()).extracting(RecordView::type).containsExactly("COMPLETE", "REOPEN", "SKIP", "SAVE");
        failure(409, () -> as(sales, () -> workflow.skipVisit(sales, id, new SkipVisitCommand(4, "已实际带看"), "cannot-replace")));
    }

    @Test
    void onlyCurrentWriterCanSkipAnActiveVisitWithExplicitReason() {
        long id = customer();
        var requested = new SkipVisitCommand(0, "客户已确认免带看");
        failure(400, () -> as(sales, () -> workflow.skipVisit(sales, id, new SkipVisitCommand(0, "  "), "empty-reason")));
        failure(400, () -> as(sales, () -> workflow.skipVisit(sales, id, new SkipVisitCommand(0, "长".repeat(1001)), "length")));
        for (var reader : List.of(boss, otherSales, colleague)) {
            assertThatThrownBy(() -> as(reader, () -> workflow.skipVisit(reader, id, requested, "reader"))).isInstanceOf(ServiceException.class);
        }
        failure(400, () -> as(sales, () -> workflow.save(sales, id, "SITE_VISIT",
                new SaveCommand(0, null, "", Map.of("visitSkip", Map.of("reason","伪造")), List.of()), "normal-save")));
        assertThat(count("fr_customer_workflow", id)).isZero();
        as(sales, () -> workflow.save(sales, id, "INVITATION", invitation(0), "first"));
        as(sales, () -> workflow.status(sales, id, new StatusCommand(1, "PAUSED", null, "暂缓", today().plusDays(3)), "pause"));
        failure(409, () -> as(sales, () -> workflow.skipVisit(sales, id, new SkipVisitCommand(2, "免带看"), "paused")));
        assertThat(as(sales, () -> workflow.get(sales, id)).completedCount()).isZero();
    }

    @Test
    void concurrentFirstSkipWritesOneProgressCreditAndOneOriginalEvent() throws Exception {
        long id = customer();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<View> request = () -> {
                TenantContextHolder.setTenantId(tenant);
                try {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return workflow.skipVisit(sales, id, new SkipVisitCommand(0, "客户已看本村同类工程"), "same-skip");
                } finally { TenantContextHolder.clear(); }
            };
            var first = executor.submit(request);
            var second = executor.submit(request);
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).completedCount()).isOne();
            assertThat(second.get(10, TimeUnit.SECONDS).revision()).isOne();
        } finally { executor.shutdownNow(); }
        assertThat(as(sales, () -> workflow.records(sales, id, 1, 20)).getList()).singleElement()
                .satisfies(e -> assertThat(e.type()).isEqualTo("SKIP"));
    }

    @Test
    void nextFollowOnIsTheCustomersAppointmentAndDrivesTheDueAndAppointmentLists() {
        long id = customer();
        var page = new PageParam(); page.setPageNo(1); page.setPageSize(50);
        LocalDate day = today().plusDays(2);
        View saved = as(sales, () -> workflow.save(sales, id, "INVITATION",
                new SaveCommand(0, today(), "已约到店", Map.of("contactMethod", "微信"), List.of(), day), "plan"));
        assertThat(saved.nextFollowOn()).isEqualTo(day);
        assertThat(saved.steps().get(0).nextFollowOn()).isEqualTo(day);
        assertThat(jdbc.queryForObject("SELECT next_visit_date::text FROM fr_customer WHERE id=?", String.class, id)).isEqualTo(day.toString());
        assertThat(jdbc.queryForObject("SELECT status FROM fr_customer WHERE id=?", String.class, id)).isEqualTo(CustomerDO.STATUS_FOLLOWING);
        assertThat(jdbc.queryForObject("SELECT last_follow_at IS NOT NULL FROM fr_customer WHERE id=?", Boolean.class, id)).isTrue();
        assertThat(as(sales, () -> customers.page(sales, page, null, null, null, CustomerMapper.FollowFilter.APPOINTMENTS).getList()))
                .extracting(CustomerDO::getId).containsExactly(id);
        assertThat(as(sales, () -> customers.page(sales, page, null, null, null, CustomerMapper.FollowFilter.DUE).getList())).isEmpty();
        assertThat(as(sales, () -> customers.listAwaitingFirstFollow(sales))).isEmpty();

        // 不带日期的保存不改约定；带了就按新的算
        as(sales, () -> workflow.save(sales, id, "INVITATION", new SaveCommand(1, today(), "再沟通", Map.of("contactMethod", "电话"), List.of()), "keep"));
        assertThat(as(sales, () -> customers.get(sales, id).customer().getNextVisitDate())).isEqualTo(day);
        as(sales, () -> workflow.save(sales, id, "INVITATION", new SaveCommand(2, today(), "改到今天", Map.of("contactMethod", "电话"), List.of(), today()), "today"));
        assertThat(as(sales, () -> customers.page(sales, page, null, null, null, CustomerMapper.FollowFilter.DUE).getList()))
                .extracting(CustomerDO::getId).containsExactly(id);
        assertThat(as(sales, () -> customers.listDueVisits(sales, today()))).extracting(CustomerDO::getId).containsExactly(id);
        assertThat(as(sales, () -> customers.page(sales, page, null, null, null, CustomerMapper.FollowFilter.APPOINTMENTS).getList())).isEmpty();
        failure(400, () -> as(sales, () -> workflow.save(sales, id, "INVITATION",
                new SaveCommand(3, today(), "过去", Map.of(), List.of(), today().minusDays(1)), "past")));
        assertThat(as(sales, () -> workflow.get(sales, id).revision())).isEqualTo(3);
    }

    @Test
    void legacyInvitationNextContactStaysAWorkflowDateAndNeverMovesTheCustomersNextFollowDate() {
        var page = new PageParam(); page.setPageNo(1); page.setPageSize(50);
        long id = customer();
        LocalDate day = today().plusDays(3);
        // 升级前的小程序只带第 1 步的“下次联系”：留在步骤里，不改客户的下次跟进日期
        View saved = as(sales, () -> workflow.save(sales, id, "INVITATION",
                new SaveCommand(0, today(), "", Map.of("contactMethod", "微信", "nextContactOn", day.toString()), List.of()), "legacy-next"));
        assertThat(saved.steps().get(0).fields()).containsEntry("nextContactOn", day.toString());
        assertThat(saved.steps().get(0).nextFollowOn()).isNull();
        assertThat(saved.nextFollowOn()).isNull();
        assertThat(as(sales, () -> customers.get(sales, id).customer().getNextVisitDate())).isNull();
        // 客户已经约定的下次跟进日期，不会被旧版本的一次保存改掉
        LocalDate agreed = today().plusDays(5);
        as(sales, () -> workflow.save(sales, id, "INVITATION",
                new SaveCommand(1, today(), "已约到店", Map.of("contactMethod", "微信"), List.of(), agreed), "agree"));
        View legacy = as(sales, () -> workflow.save(sales, id, "INVITATION",
                new SaveCommand(2, today(), "", Map.of("contactMethod", "微信", "nextContactOn", day.toString()), List.of()), "legacy-again"));
        assertThat(legacy.nextFollowOn()).isEqualTo(agreed);
        assertThat(as(sales, () -> customers.page(sales, page, null, null, null, CustomerMapper.FollowFilter.APPOINTMENTS).getList()))
                .extracting(CustomerDO::getId).containsExactly(id);
    }

    @Test
    void lostNeedsAReasonAndLeavesTheFollowupListsWhileResumeBringsTheCustomerBackToday() {
        long id = customer();
        as(sales, () -> workflow.save(sales, id, "INVITATION", new SaveCommand(0, today(), "", Map.of(), List.of(), today().plusDays(1)), "plan"));
        failure(400, () -> as(sales, () -> workflow.status(sales, id, new StatusCommand(1, "LOST", "客户不建了"), "lost-without-code")));
        failure(400, () -> as(sales, () -> workflow.status(sales, id, new StatusCommand(1, "LOST", "UNKNOWN", "客户不建了", null), "lost-unknown-code")));
        failure(400, () -> as(sales, () -> workflow.status(sales, id, new StatusCommand(1, "PAUSED", "等宅基地批复"), "pause-without-date")));
        failure(400, () -> as(sales, () -> workflow.status(sales, id, new StatusCommand(1, "PAUSED", null, "等宅基地批复", today()), "pause-today")));
        View lost = as(sales, () -> workflow.status(sales, id, new StatusCommand(1, "LOST", "COMPETITOR", null, null), "lost"));
        assertThat(lost.status()).isEqualTo("LOST");
        assertThat(lost.statusReasonCode()).isEqualTo("COMPETITOR");
        assertThat(lost.statusReason()).isEqualTo("选了同行");
        assertThat(lost.nextFollowOn()).isNull();
        assertThat(lost.canWrite()).isFalse();
        assertThat(lost.canChangeStatus()).isTrue();
        assertThat(as(boss, () -> workflow.get(boss, id)).canChangeStatus()).isFalse();
        assertThat(as(sales, () -> customers.get(sales, id).customer().getNextVisitDate())).isNull();
        assertThat(as(sales, () -> customers.listDueVisits(sales, today()))).isEmpty();
        assertThat(as(sales, () -> customers.listAwaitingFirstFollow(sales))).isEmpty();
        failure(409, () -> as(sales, () -> workflow.status(sales, id, new StatusCommand(lost.revision(), "LOST", "PLAN", null, null), "lost-again")));
        assertThat(as(sales, () -> workflow.records(sales, id, 1, 20)).getList().get(0).fields()).containsEntry("reasonCode", "COMPETITOR");

        // “其他”必须自己填写原因
        long other = customer();
        failure(400, () -> as(sales, () -> workflow.status(sales, other, new StatusCommand(0, "LOST", "OTHER", " ", null), "lost-other-blank")));
        View custom = as(sales, () -> workflow.status(sales, other, new StatusCommand(0, "LOST", "OTHER", "选了同村施工队", null), "lost-other"));
        assertThat(custom.statusReasonCode()).isEqualTo("OTHER");
        assertThat(custom.statusReason()).isEqualTo("选了同村施工队");
        // 即使客户表的状态列仍是“已登记”，已流失的客户也不提醒首次跟进
        jdbc.update("UPDATE fr_customer SET status='REGISTERED' WHERE id=?", other);
        assertThat(as(sales, () -> customers.listAwaitingFirstFollow(sales))).isEmpty();

        View resumed = as(sales, () -> workflow.status(sales, id, new StatusCommand(lost.revision(), "ACTIVE", null, null, null), "resume"));
        assertThat(resumed.status()).isEqualTo("ACTIVE");
        assertThat(resumed.statusReason()).isNull();
        assertThat(resumed.steps().get(0).canWrite()).isTrue();
        assertThat(as(sales, () -> customers.listDueVisits(sales, today()))).extracting(CustomerDO::getId).containsExactly(id);
        LocalDate later = today().plusMonths(3);
        View deferred = as(sales, () -> workflow.status(sales, id, new StatusCommand(resumed.revision(), "PAUSED", null, "等宅基地批复", later), "pause"));
        assertThat(deferred.nextFollowOn()).isEqualTo(later);
        assertThat(as(sales, () -> customers.listDueVisits(sales, today()))).isEmpty();
        assertThat(as(sales, () -> customers.listDueVisits(sales, later))).extracting(CustomerDO::getId).containsExactly(id);
        failure(409, () -> as(sales, () -> workflow.complete(sales, id, "INVITATION", deferred.revision(), "paused-complete")));
    }

    @Test
    void timelineMergesStepsSupplementsConsultsAndLegacyFollows() {
        long id = customer();
        as(sales, () -> customers.follow(sales, id, new CustomerService.FollowCommand("升级前的电话沟通", today()), null));
        as(sales, () -> workflow.save(sales, id, "INVITATION", invitation(0), "save"));
        as(sales, () -> workflow.complete(sales, id, "INVITATION", 1, "done"));
        as(sales, () -> supplements.append(sales, id, "INVITATION",
                new CustomerWorkflowSupplementService.Input("补充：客户周六到店", List.of()), "supplement"));
        String phone = jdbc.queryForObject("SELECT phone FROM fr_customer WHERE id=?", String.class, id);
        var linked = as(colleague, () -> leads.capture(colleague, new LeadService.CaptureCommand("陈先生", phone, "想了解两层的报价", "朋友介绍"), null));
        assertThat(linked.outcome()).isEqualTo(LeadService.CaptureOutcome.LINKED);

        var timeline = as(sales, () -> workflow.timeline(sales, id, 1, 20));
        assertThat(timeline.getTotal()).isEqualTo(5L);
        assertThat(timeline.getList()).extracting(RecordView::type).containsExactly("CONSULT", "SUPPLEMENT", "COMPLETE", "SAVE", "LEGACY");
        assertThat(timeline.getList().get(0).note()).isEqualTo("想了解两层的报价");
        assertThat(timeline.getList().get(0).fields()).containsEntry("source", "朋友介绍");
        assertThat(timeline.getList().get(4).note()).isEqualTo("升级前的电话沟通");
        assertThat(timeline.getList().get(4).actorUserId()).isEqualTo(Long.toString(sales.userId()));
        assertThat(as(sales, () -> workflow.timeline(sales, id, 2, 4).getList())).extracting(RecordView::type).containsExactly("LEGACY");
        assertThat(as(sales, () -> workflow.timeline(sales, id, 3, 4).getList())).isEmpty();
        assertThat(as(sales, () -> workflow.timeline(sales, id, 3, 4).getTotal())).isEqualTo(5L);
        assertThat(as(sales, () -> workflow.records(sales, id, 1, 20)).getList()).extracting(RecordView::type)
                .containsExactly("CONSULT", "COMPLETE", "SAVE");
        // 再次咨询不是负责人的跟进：最近跟进时间不变，没约定的下次跟进日期提到今天
        assertThat(as(sales, () -> workflow.get(sales, id).nextFollowOn())).isEqualTo(today());
        failure(1_110_000_000, () -> as(colleague, () -> workflow.timeline(colleague, id, 1, 20)));
    }

    @Test
    void followSummaryCountsCustomersWithTheSameRulesAsTheListFilters() {
        long planned = customer(), overdue = customer(), lost = customer(), fresh = customer();
        as(sales, () -> workflow.save(sales, planned, "INVITATION", new SaveCommand(0, today(), "", Map.of(), List.of(), today().plusDays(2)), "planned"));
        as(sales, () -> workflow.save(sales, overdue, "INVITATION", new SaveCommand(0, today(), "", Map.of(), List.of(), today()), "overdue"));
        as(sales, () -> workflow.save(sales, lost, "INVITATION", new SaveCommand(0, today(), "", Map.of(), List.of(), today()), "lost-plan"));
        as(sales, () -> workflow.status(sales, lost, new StatusCommand(1, "LOST", "PLAN", null, null), "lost"));

        var summary = as(sales, () -> customers.followSummary(sales));
        assertThat(summary.total()).isEqualTo(4);
        assertThat(summary.due()).isEqualTo(2); // 新客户，加上约定今天跟进的客户；已流失不计
        assertThat(summary.appointments()).isEqualTo(1);
        assertThat(summary.overdue()).isZero();
        assertThat(summary.following()).isEqualTo(2);
        assertThat(summary.lost()).isEqualTo(1);
        assertThat(summary.paused()).isZero();
        assertThat(as(colleague, () -> customers.followSummary(colleague)).total()).isZero();
        assertThat(as(boss, () -> customers.followSummary(boss)).total()).isEqualTo(4);
    }

    @Test
    void visitedListKeepsTheVisibilityScopeAndLeavesOutLostCustomers() {
        var page = new PageParam(); page.setPageNo(1); page.setPageSize(50);
        long visited = customer(), lost = customer(), abroad = as(otherSales, () -> capture(otherSales));
        for (long id : List.of(visited, lost)) {
            as(sales, () -> workflow.save(sales, id, "INTRODUCTION", new SaveCommand(0, today(), "已到店", Map.of(), List.of()), "intro-" + id));
        }
        as(otherSales, () -> workflow.save(otherSales, abroad, "INTRODUCTION", new SaveCommand(0, today(), "已到店", Map.of(), List.of()), "intro-abroad"));
        as(sales, () -> workflow.status(sales, lost, new StatusCommand(1, "LOST", "PLAN", null, null), "lost"));
        // 到过店的客户在列；已流失的不在；同店其他业务员、别的门店看不到不是自己的客户
        assertThat(as(sales, () -> customers.page(sales, page, null, null, null, CustomerMapper.FollowFilter.VISITED).getList()))
                .extracting(CustomerDO::getId).containsExactly(visited);
        assertThat(as(colleague, () -> customers.page(colleague, page, null, null, null, CustomerMapper.FollowFilter.VISITED).getList())).isEmpty();
        assertThat(as(otherSales, () -> customers.page(otherSales, page, null, null, null, CustomerMapper.FollowFilter.VISITED).getList()))
                .extracting(CustomerDO::getId).containsExactly(abroad);
        assertThat(as(boss, () -> customers.page(boss, page, null, null, null, CustomerMapper.FollowFilter.VISITED).getList()))
                .extracting(CustomerDO::getId).containsExactly(visited);
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
