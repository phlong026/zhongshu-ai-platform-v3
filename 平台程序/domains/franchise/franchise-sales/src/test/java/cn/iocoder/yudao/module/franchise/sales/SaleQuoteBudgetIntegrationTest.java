package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.module.franchise.crm.CustomerSchemeAdapter;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
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
        CustomerSchemeAdapter.class, CustomerService.class, FranchiseCommands.class, CommandLogImpl.class})
class SaleQuoteBudgetIntegrationTest extends FranchisePgIntegrationTest {
    @Resource private SaleQuoteService quotes;
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
