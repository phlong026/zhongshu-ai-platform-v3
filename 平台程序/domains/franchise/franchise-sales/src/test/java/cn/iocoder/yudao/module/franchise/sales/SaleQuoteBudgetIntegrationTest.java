package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.module.franchise.crm.CustomerSchemeAdapter;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowAssetPolicy;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel.ReopenCommand;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel.SaveCommand;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowReadService;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowService;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleQuoteDO;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.SALES;
import static org.assertj.core.api.Assertions.*;

/** 真实 PG + CRM 回写；验证报价与委托预算是同一事务事实，而非 UI 伪造完成。 */
@Import({SaleQuoteService.class, SaleRequirementService.class, SalesAccess.class,
        CustomerSchemeAdapter.class, CustomerService.class, FranchiseCommands.class, CommandLogImpl.class,
        CustomerWorkflowReadService.class, CustomerWorkflowService.class, CustomerWorkflowAssetPolicy.class})
class SaleQuoteBudgetIntegrationTest extends FranchisePgIntegrationTest {
    @Resource private SaleQuoteService quotes;
    @Resource private CustomerWorkflowService workflow;
    @Resource private JdbcTemplate jdbc;
    @Resource private PlatformTransactionManager transactions;
    @Resource private javax.sql.DataSource dataSource;
    private PostSnapshot sales;
    private long customer;

    @BeforeEach void customer() {
        var store = fixture.store();
        sales = fixture.post(store.orgId(), SALES, store.salesTeamA());
        customer = cn.hutool.core.util.IdUtil.getSnowflakeNextId();
        jdbc.update("INSERT INTO fr_customer (id,company_id,owner_user_id,name,phone,source,status) VALUES (?,?,?,'联调预算客户',?,'业务登记','REGISTERED')",
                customer, sales.orgId(), sales.userId(), "138" + String.format("%08d", Math.floorMod(customer,100_000_000L)));
    }

    @Test void quoteCreatesFrozenBudgetAndReplayDoesNotCreateAnotherVersion() {
        String key = key();
        SaleQuoteDO first = as(() -> quotes.create(sales, command("12", "sales-v1"), key));
        SaleQuoteDO replay = as(() -> quotes.create(sales, command("12.00", "sales-v1"), key));
        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(budgetCount()).isEqualTo(1);
        var budget = jdbc.queryForMap("SELECT * FROM fr_customer_scheme WHERE customer_id=? AND kind='BUDGET'", customer);
        assertThat(((Number)budget.get("total_cents")).longValue()).isEqualTo(first.getTotalCents());
        assertThat((BigDecimal)budget.get("area")).isEqualByComparingTo("360");
        assertThat((String)budget.get("payload")).contains("\"quoteId\"").contains("sales-v1");
        assertThat(jdbc.queryForObject("SELECT budget_scheme_id FROM fr_sale_quote WHERE id=?", Long.class, first.getId()))
                .isEqualTo(((Number)budget.get("id")).longValue());
        as(() -> quotes.listByCustomer(sales, customer));
        assertThat(budgetCount()).isEqualTo(1);
    }

    @Test void reusedKeyCannotChangeDimensionsOrPriceVersion() {
        String key = key();
        as(() -> quotes.create(sales, command("12", "sales-v1"), key));
        assertThatThrownBy(() -> as(() -> quotes.create(sales, command("13", "sales-v1"), key))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> as(() -> quotes.create(sales, command("12", "sales-v2"), key))).isInstanceOf(RuntimeException.class);
        assertThat(budgetCount()).isEqualTo(1);
    }

    @Test void workflowLinksOneFormalQuoteAndKeepsTheCommunicatedVersionWithoutAnotherUpload() {
        bindWorkflowTenant();
        try {
            var original = as(() -> quotes.create(sales, command("12", "门店价目"), key()));
            as(() -> quotes.create(sales, command("13", "新价目"), key()));
            String originalId = original.getId().toString();
            var date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
            var input = new SaveCommand(0, date, "", Map.of("quoteId", originalId, "amountCents", "1",
                    "versionLabel", "应忽略", "result", "已发给客户", "communicated", true), List.of());
            String saveKey = key();
            var saved = as(() -> workflow.save(sales, customer, "QUOTATION", input, saveKey));
            assertThat(saved.completedCount()).isZero();
            assertThat(saved.steps().get(5).fields()).containsEntry("quoteId", originalId)
                    .containsEntry("amountCents", original.getTotalCents().toString())
                    .containsEntry("versionLabel", "V" + original.getBudgetVersion() + " · 门店价目");
            assertThat(saved.steps().get(5).attachments()).isEmpty();
            assertThat(as(() -> workflow.save(sales, customer, "QUOTATION", input, saveKey)).revision()).isEqualTo(saved.revision());
            String completeKey = key();
            var done = as(() -> workflow.complete(sales, customer, "QUOTATION", saved.revision(), completeKey));
            assertThat(done.completedCount()).isEqualTo(1);
            assertThat(done.steps().get(5).status()).isEqualTo("COMPLETED");
            assertThat(as(() -> workflow.complete(sales, customer, "QUOTATION", saved.revision(), completeKey)).revision()).isEqualTo(done.revision());
            assertThat(as(() -> workflow.records(sales, customer, 1, 20)).getList()).hasSize(2)
                    .allSatisfy(event -> assertThat(event.fields()).containsEntry("quoteId", originalId));
            assertThat(budgetCount()).isEqualTo(2);
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test void reopenedQuotationKeepsTheOldCommunicationAndRequiresAResultForTheReplacement() {
        bindWorkflowTenant();
        try {
            var date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
            var original = as(() -> quotes.create(sales, command("12", "原价目"), key()));
            var replacement = as(() -> quotes.create(sales, command("13", "调整价目"), key()));
            String first = original.getId().toString(), second = replacement.getId().toString();
            assertThat(as(() -> quotes.exportSheet(sales, original.getId())))
                    .containsEntry("quoteVersionLabel", "V" + original.getBudgetVersion() + " · 原价目");
            var firstFields = Map.<String,Object>of("quoteId", first, "communicated", true, "result", "客户要求调整");
            var saved = as(() -> workflow.save(sales, customer, "QUOTATION",
                    new SaveCommand(0, date.minusDays(1), "", firstFields, List.of()), key()));
            var done = as(() -> workflow.complete(sales, customer, "QUOTATION", saved.revision(), key()));
            var reopened = as(() -> workflow.reopen(sales, customer, "QUOTATION", new ReopenCommand(done.revision(), "客户要求调整"), key()));
            var unconfirmed = as(() -> workflow.save(sales, customer, "QUOTATION",
                    new SaveCommand(reopened.revision(), date, "", Map.of("quoteId", second), List.of()), key()));
            assertThat(unconfirmed.steps().get(5).canComplete()).isFalse();
            assertThatThrownBy(() -> as(() -> workflow.complete(sales, customer, "QUOTATION", unconfirmed.revision(), key())))
                    .isInstanceOf(ServiceException.class).hasMessageContaining("向客户发送或说明");
            var secondFields = Map.<String,Object>of("quoteId", second, "communicated", true, "result", "已向客户讲解");
            var confirmed = as(() -> workflow.save(sales, customer, "QUOTATION",
                    new SaveCommand(unconfirmed.revision(), date, "", secondFields, List.of()), key()));
            var result = as(() -> workflow.complete(sales, customer, "QUOTATION", confirmed.revision(), key()));
            assertThat(result.steps().get(5).fields()).containsEntry("quoteId", second)
                    .containsEntry("versionLabel", "V" + replacement.getBudgetVersion() + " · 调整价目");
            var completed = as(() -> workflow.records(sales, customer, 1, 20)).getList().stream()
                    .filter(row -> row.type().equals("COMPLETE")).toList();
            assertThat(completed).hasSize(2);
            assertThat(completed.get(0).fields()).containsAllEntriesOf(secondFields);
            assertThat(completed.get(0).occurredOn()).isEqualTo(date);
            assertThat(completed.get(1).fields()).containsAllEntriesOf(firstFields)
                    .containsEntry("amountCents", original.getTotalCents().toString());
            assertThat(completed.get(1).occurredOn()).isEqualTo(date.minusDays(1));
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test void workflowRejectsAnotherCustomersQuoteAndCompletionWithoutCommunication() {
        bindWorkflowTenant();
        try {
            var original = as(() -> quotes.create(sales, command("12", "原价目"), key()));
            long otherCustomer = cn.hutool.core.util.IdUtil.getSnowflakeNextId();
            jdbc.update("INSERT INTO fr_customer (id,company_id,owner_user_id,name,phone,source,status) VALUES (?,?,?,'另一客户',?,'业务登记','REGISTERED')",
                    otherCustomer, sales.orgId(), sales.userId(), "139" + String.format("%08d", Math.floorMod(otherCustomer,100_000_000L)));
            var date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
            var input = new SaveCommand(0, date, "", Map.of("quoteId", original.getId().toString()), List.of());
            assertThatThrownBy(() -> as(() -> workflow.save(sales, otherCustomer, "QUOTATION", input, key())))
                    .isInstanceOf(ServiceException.class);
            assertThat(as(() -> workflow.get(sales, otherCustomer)).workflowId()).isNull();
            var saved = as(() -> workflow.save(sales, customer, "QUOTATION", input, key()));
            assertThatThrownBy(() -> as(() -> workflow.complete(sales, customer, "QUOTATION", saved.revision(), key())))
                    .isInstanceOf(ServiceException.class).hasMessageContaining("向客户发送或说明");
        } finally {
            TenantContextHolder.clear();
        }
    }

    private void bindWorkflowTenant() {
        jdbc.update("""
                INSERT INTO system_dept(id,name,status,deleted,tenant_id) VALUES(?,'报价测试门店',0,0,?)
                ON CONFLICT(id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id,status=0,deleted=0
                """, sales.orgId(), sales.orgId());
        TenantContextHolder.setTenantId(sales.orgId());
    }

    @Test void actualBudgetAreaIsFrozenInsteadOfBeingOverwrittenByTheOuterDimensions() {
        var base = command("12", "sales-v1");
        var actual = new SaleQuoteService.QuoteCommand(base.customerId(), base.schemeId(), base.items(), base.priceVersion(),
                base.address(), base.width(), base.depth(), base.floors(), new BigDecimal("330.50"));
        String key = key();
        as(() -> quotes.create(sales, actual, key));
        assertThat(jdbc.queryForObject("SELECT area FROM fr_customer_scheme WHERE customer_id=? AND kind='BUDGET'", BigDecimal.class, customer))
                .isEqualByComparingTo("330.50");
        assertThatThrownBy(() -> as(() -> quotes.create(sales, base, key))).isInstanceOf(RuntimeException.class);
        assertThat(budgetCount()).isEqualTo(1);
    }

    @Test void concurrentSameCommandReturnsOneQuoteAndOneBudget() throws Exception {
        String key = key();
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Long> write = () -> as(() -> quotes.create(sales, command("12", "sales-v1"), key)).getId();
            var results = executor.invokeAll(List.of(write, write));
            assertThat(results.get(0).get()).isEqualTo(results.get(1).get());
            assertThat(budgetCount()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_quote WHERE customer_id=?", Integer.class, customer)).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test void outerRollbackRemovesQuoteBudgetAndCommandTogether() {
        as(() -> new TransactionTemplate(transactions).execute(status -> {
            quotes.create(sales, command("12", "sales-v1"), key());
            assertThat(budgetCount()).isEqualTo(1);
            status.setRollbackOnly();
            return null;
        }));
        assertThat(budgetCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_quote WHERE customer_id=?", Integer.class, customer)).isZero();
    }

    @Test void undoRequiresNoFrozenQuoteLinksAndNeverDropsExistingProvenance() throws Exception {
        String sql;
        try (var input = new org.springframework.core.io.ClassPathResource(
                "db/migration-undo/franchise/V20261115.985__undo_quote_budget_basis.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        // 同一真实PG连接内以临时表遮蔽业务表，演练undo而不破坏其它用例的事实。
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (boolean hasLink : List.of(false, true)) {
                statement.execute("CREATE TEMP TABLE fr_sale_quote(id bigint,budget_scheme_id bigint,budget_version integer,CONSTRAINT fr_sale_quote_budget_basis_check CHECK (budget_version IS NULL OR budget_version>0))");
                statement.execute("CREATE UNIQUE INDEX uk_fr_sale_quote_budget_basis ON fr_sale_quote(budget_scheme_id) WHERE budget_scheme_id IS NOT NULL");
                if (hasLink) {
                    statement.execute("INSERT INTO fr_sale_quote VALUES(1,2,3)");
                    assertThatThrownBy(() -> statement.execute(sql)).isInstanceOf(java.sql.SQLException.class).hasMessageContaining("restore isolated B backup");
                    try (var row = statement.executeQuery("SELECT budget_scheme_id,budget_version FROM fr_sale_quote")) {
                        assertThat(row.next()).isTrue(); assertThat(row.getLong(1)).isEqualTo(2); assertThat(row.getInt(2)).isEqualTo(3);
                    }
                } else {
                    statement.execute(sql);
                    assertThatThrownBy(() -> statement.executeQuery("SELECT budget_scheme_id FROM fr_sale_quote")).isInstanceOf(java.sql.SQLException.class);
                }
                statement.execute("DROP TABLE fr_sale_quote");
            }
        }
    }

    private int budgetCount() { return jdbc.queryForObject("SELECT count(*) FROM fr_customer_scheme WHERE customer_id=? AND kind='BUDGET'", Integer.class, customer); }
    @Test void quoteExportUsesShanghaiCalendarDateAtTheUtcBoundary() {
        var quote = as(() -> quotes.create(sales, command("12", "audit-date"), key()));
        jdbc.update("UPDATE fr_sale_quote SET create_time=? WHERE id=?",
                java.time.OffsetDateTime.parse("2026-11-15T15:59:59Z"), quote.getId());
        assertThat(as(() -> quotes.exportSheet(sales, quote.getId())).get("date")).isEqualTo("2026-11-15");
        jdbc.update("UPDATE fr_sale_quote SET create_time=? WHERE id=?",
                java.time.OffsetDateTime.parse("2026-11-15T16:00:00Z"), quote.getId());
        assertThat(as(() -> quotes.exportSheet(sales, quote.getId())).get("date")).isEqualTo("2026-11-16");
    }

    @Test void exportRecoversRecordedConfigurationFromFrozenRows() {
        var quote = quoteWithLabels("基础 · 独立基础", "结构 · 框架结构");
        assertThat(as(() -> quotes.exportSheet(sales, quote.getId())).get("form"))
                .isEqualTo(Map.of("foundation", "独立基础", "structure", "框架结构"));
    }

    @Test void exportDoesNotGuessConfigurationFromMissingOrConflictingRows() {
        var quote = quoteWithLabels("基础 · 独立基础", "基础 · 筏板基础", "未知结构");
        assertThat(as(() -> quotes.exportSheet(sales, quote.getId())).get("form")).isEqualTo(Map.of());
    }

    private SaleQuoteDO quoteWithLabels(String... labels) {
        var base = command("12", "audit-configuration");
        List<Map<String,Object>> rows = java.util.Arrays.stream(labels).map(label ->
                (Map<String,Object>) new java.util.LinkedHashMap<String,Object>(Map.of(
                        "label", label, "quantity", 1, "unitPriceCents", 100))).toList();
        return as(() -> quotes.create(sales, new SaleQuoteService.QuoteCommand(customer, null,
                Map.of("main", rows, "decor", List.of(), "insuranceCents", 0, "managementCents", 0),
                base.priceVersion(), base.address(), base.width(), base.depth(), base.floors(), base.area()), key()));
    }

    private SaleQuoteService.QuoteCommand command(String width, String version) {
        var row = new java.util.LinkedHashMap<String,Object>(Map.of("label","主体","quantity",1,"unitPriceCents",100000));
        return new SaleQuoteService.QuoteCommand(customer, null,
                Map.of("main",List.of(row),"decor",List.of(),"insuranceCents",0,"managementCents",0), version, "武汉联调地址",
                new BigDecimal(width), new BigDecimal("15"), 2, null);
    }
    private <T> T as(java.util.function.Supplier<T> action) {
        currentPost.use(sales);
        try { return action.get(); } finally { currentPost.clear(); }
    }
    private static String key() { return "quote-"+UUID.randomUUID(); }
}
