package cn.iocoder.yudao.module.franchise.performance.content;

import cn.iocoder.yudao.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.logger.dto.OperateLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.api.PerformancePayablePort;
import cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfCommissionReleaseDO;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * P1 尾批③：绩效报表导出真实 PG17 回归（Testcontainers）。
 *
 * <p><b>月份口径（权威）</b>：{@code fr_perf_commission_release.release_at} 所在自然月，
 * 半开区间 {@code [YYYY-MM-01 00:00:00, 次月-01 00:00:00)}；边界字面量解释时区 =
 * 数据库会话时区（与 PerfDashboardService 同型）。<b>部署前置</b>：上线前 `SHOW TimeZone` 核验，
 * 中国区工资核算月应为 Asia/Shanghai；本类用
 * {@link #sessionTimezoneSwitchChangesMonthAttribution()} 在同一连接上切换 UTC / Asia/Shanghai，
 * 断言同一 release_at 的月份归属随之变化，证明「边界=会话时区」而非硬编码；上线前仍须
 * `SHOW TimeZone` 部署核验。
 *
 * <p><b>已生成应付口径</b>：优先 {@code fr_fin_payable.total_cents}（应付生成时冻结）；
 * 应付行缺失时回退当前规则阶段额并计入待核对，不静默少算。
 *
 * <p><b>夹具说明</b>：{@code franchise-performance} 测试 classpath 不含工程/财务域迁移
 * （红线禁止为测试新增这些模块依赖），本类按既有 PerfDashboardPgIntegrationTest 先例，
 * 用测试夹具 DDL 建最小读写集表，列名与生产 DDL（804/814）一致；不是生产 schema。
 */
@Import({PerfCommissionService.class, PerfCommissionExportService.class,
        PerfCommissionExportPgIntegrationTest.Config.class})
@TestPropertySource(properties = "platform.legacy-design-bridge.enabled=false")
class PerfCommissionExportPgIntegrationTest extends FranchisePgIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(9_700_000_000L + System.nanoTime() % 300_000_000L);
    private static final String MONTH = "2026-11";

    @TestConfiguration
    static class Config {
        static final OperateLogCommonApi AUDIT = mock(OperateLogCommonApi.class);

        @Bean
        OperateLogCommonApi operateLogCommonApi() {
            return AUDIT;
        }

        @Bean
        PerformancePayablePort performancePayablePort() {
            return mock(PerformancePayablePort.class);
        }

        @Bean
        FranchiseCommands franchiseCommands() {
            return mock(FranchiseCommands.class);
        }

        @Bean
        UnifiedStageAccountPort unifiedStageAccountPort() {
            return new UnifiedStageAccountPort() {
                @Override
                public Long activeContractId(PostSnapshot post, long customerId) {
                    return null; // 夹具走工程留痕行，不触 sales 统一合同表
                }

                @Override
                public long stageCollectedCents(PostSnapshot post, long contractId, String stage) {
                    return 0;
                }

                @Override
                public StageCollectResult collectStage(PostSnapshot post, long contractId, String stage,
                                                       CollectInput input) {
                    throw new UnsupportedOperationException("本测试不写统一实收账");
                }
            };
        }
    }

    @Resource
    private PerfCommissionExportService exportService;
    @Resource
    private PerfCommissionService commissions;
    @Resource
    private cn.iocoder.yudao.module.franchise.test.FixedCurrentPost currentPost;
    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private DataSource dataSource;

    private long orgId;
    private long otherOrgId;
    private PostSnapshot post;

    @BeforeEach
    void setUp() {
        reset(Config.AUDIT);
        orgId = fixture.store().orgId();
        otherOrgId = fixture.store().orgId();
        post = fixture.post(orgId, "finance", null);
        createFixtureTables();
    }

    /** 工程/财务域最小夹具表（非生产 schema；生产 DDL 见 814 fr_eng_core / 804 fr_finance）。 */
    private void createFixtureTables() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_project ("
                + "id bigint PRIMARY KEY, company_id bigint NOT NULL,"
                + "source_type varchar(16) NOT NULL DEFAULT 'MANUAL', manager_user_id bigint NOT NULL DEFAULT 0,"
                + "name varchar(128) NOT NULL DEFAULT '', status varchar(16) NOT NULL DEFAULT 'IN_PROGRESS',"
                + "author_user_id bigint NOT NULL DEFAULT 0,"
                + "create_time timestamptz NOT NULL DEFAULT now(), update_time timestamptz NOT NULL DEFAULT now())");
        // 其它测试类可能先建了 compat 表（无 customer_id），补列保证 stageFacts 可读
        jdbc.execute("ALTER TABLE fr_eng_project ADD COLUMN IF NOT EXISTS customer_id bigint NULL");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_collection_term ("
                + "company_id bigint NOT NULL, project_id bigint NOT NULL, stage varchar(16) NOT NULL,"
                + "amount_cents bigint NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_collection ("
                + "id bigint PRIMARY KEY, company_id bigint NOT NULL, project_id bigint NOT NULL,"
                + "stage varchar(16) NOT NULL, amount_cents bigint NOT NULL, receipt_id bigint NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_fin_refund ("
                + "receipt_id bigint NULL, amount_cents bigint NOT NULL, status varchar(16) NOT NULL,"
                + "replaced_by_id bigint NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_fin_payment ("
                + "id bigint PRIMARY KEY, payable_id bigint NULL, company_id bigint NOT NULL,"
                + "amount_cents bigint NOT NULL, status varchar(16) NOT NULL, pay_date date NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_fin_payable ("
                + "id bigint PRIMARY KEY, company_id bigint NOT NULL, project_id bigint NULL,"
                + "stage varchar(64) NULL, total_cents bigint NOT NULL, recipient varchar(128) NULL,"
                + "status varchar(16) NULL)");
    }

    // ===================== 主聚合口径 =====================

    @Test
    void aggregatesEmployeeMonthAcrossProjectsAndPayments() {
        long empA = 501L;
        long empB = 502L;

        long p1 = nextId();
        long policy1 = insertPolicy(orgId, p1, 100_000, 40_000, 30_000, 30_000, empA, "张三");
        release(orgId, p1, policy1, FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER, 40_000, payableId(),
                "2026-11-05 10:00:00");
        long payable1b = payableId();
        release(orgId, p1, policy1, FrPerfCommissionReleaseDO.STAGE_BASE_DONE, 30_000, payable1b,
                "2026-11-18 10:00:00");

        long p2 = nextId();
        long policy2 = insertPolicy(orgId, p2, 50_000, 50_000, 0, 0, empA, "张三");
        long payable2 = payableId();
        release(orgId, p2, policy2, FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER, 50_000, payable2,
                "2026-11-20 10:00:00");

        long p3 = nextId();
        long policy3 = insertPolicy(orgId, p3, 20_000, 20_000, 0, 0, empB, "李四");
        long payable3 = payableId();
        release(orgId, p3, policy3, FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER, 20_000, payable3,
                "2026-11-21 10:00:00");

        // A：P1 第一期足额、第二期部分；P2 足额
        payment(payableFor(p1, FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER), 40_000, "已核实");
        payment(payable1b, 10_000, "已核实");
        payment(payable2, 50_000, "已核实");

        List<List<String>> rows = bodyRows(export(post, MONTH));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly(
                String.valueOf(empA), "张三", "2", "1500.00", "1200.00", "1000.00", "200.00", "1", "0", "1");
        assertThat(rows.get(1)).containsExactly(
                String.valueOf(empB), "李四", "1", "200.00", "200.00", "0.00", "200.00", "0", "0", "1");

        ArgumentCaptor<OperateLogCreateReqDTO> captor = ArgumentCaptor.forClass(OperateLogCreateReqDTO.class);
        verify(Config.AUDIT).createOperateLog(captor.capture());
        OperateLogCreateReqDTO audit = captor.getValue();
        assertThat(audit.getUserId()).isEqualTo(post.userId());
        assertThat(audit.getBizId()).isEqualTo(orgId);
        assertThat(audit.getAction()).contains("month=" + MONTH, "行数=2");
        assertThat(audit.getExtra()).contains("\"orgId\":" + orgId, "\"month\":\"2026-11\"", "\"rows\":2");
        assertThat(audit.getRequestMethod()).isEqualTo("GET");
        assertThat(audit.getRequestUrl()).isEqualTo("/franchise/performance/commission/export");
        assertThat(audit.getUserIp()).isNotBlank();
        assertThat(audit.getUserAgent()).isNotBlank();
    }

    @Test
    void monthBoundaryIsHalfOpenOnReleaseAt() {
        long startOfMonth = 601L;
        long lastSecondOfPrevMonth = 602L;
        long startOfNextMonth = 603L;
        long lastSecondOfMonth = 604L;

        releaseAt(startOfMonth, "2026-11-01 00:00:00");
        releaseAt(lastSecondOfPrevMonth, "2026-10-31 23:59:59");
        releaseAt(startOfNextMonth, "2026-12-01 00:00:00");
        releaseAt(lastSecondOfMonth, "2026-11-30 23:59:59");

        List<List<String>> rows = bodyRows(export(post, MONTH));
        assertThat(rows).extracting(row -> row.get(0))
                .containsExactly(String.valueOf(startOfMonth), String.valueOf(lastSecondOfMonth));
    }

    /**
     * Codex r2 P2-3：在同一连接上切换会话时区各跑一次，断言同一 release_at 的月份归属随
     * 会话时区变化（UTC：月初边界落 10 月、月末边界落 11 月；Asia/Shanghai：反之），
     * 证明「边界=数据库会话时区」而非硬编码。
     */
    @Test
    void sessionTimezoneSwitchChangesMonthAttribution() throws Exception {
        long earlyEdge = 1_201L;
        long lateEdge = 1_202L;
        long earlyProject = nextId();
        long earlyPolicy = insertPolicy(orgId, earlyProject, 10_000, 10_000, 0, 0, earlyEdge, "月初边界");
        // 2026-10-31T23:59:59Z：UTC 属 10 月；Asia/Shanghai 为 11-01 07:59:59，属 11 月
        release(orgId, earlyProject, earlyPolicy, FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER, 10_000, null,
                "2026-10-31 23:59:59+00");
        long lateProject = nextId();
        long latePolicy = insertPolicy(orgId, lateProject, 10_000, 10_000, 0, 0, lateEdge, "月末边界");
        // 2026-11-30T16:00:00Z：UTC 属 11 月；Asia/Shanghai 为 12-01 00:00:00，属 12 月
        release(orgId, lateProject, latePolicy, FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER, 10_000, null,
                "2026-11-30 16:00:00+00");

        try (Connection connection = dataSource.getConnection()) {
            SingleConnectionDataSource single = new SingleConnectionDataSource(connection, true);
            PerfCommissionExportService service = new PerfCommissionExportService(
                    new JdbcTemplate(single), commissions, Config.AUDIT);

            setSessionTimeZone(connection, "UTC");
            assertThat(showSessionTimeZone(connection)).contains("UTC");
            List<String> utcEmployees = exportedEmployeeIds(service, post, MONTH);
            assertThat(utcEmployees).doesNotContain(String.valueOf(earlyEdge));
            assertThat(utcEmployees).contains(String.valueOf(lateEdge));

            setSessionTimeZone(connection, "Asia/Shanghai");
            assertThat(showSessionTimeZone(connection)).contains("Asia/Shanghai");
            List<String> shanghaiEmployees = exportedEmployeeIds(service, post, MONTH);
            assertThat(shanghaiEmployees).contains(String.valueOf(earlyEdge));
            assertThat(shanghaiEmployees).doesNotContain(String.valueOf(lateEdge));
        }
    }

    @Test
    void emptyMonthStillWritesHeaderOnly() {
        byte[] csv = export(post, "2031-01");
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).startsWith("\uFEFF");
        List<String> lines = new ArrayList<>(Arrays.asList(text.substring(1).split("\r\n")));
        assertThat(lines).hasSize(1);
        assertThat(parse(lines.get(0))).containsExactlyElementsOf(PerfCommissionExportService.header());

        ArgumentCaptor<OperateLogCreateReqDTO> captor = ArgumentCaptor.forClass(OperateLogCreateReqDTO.class);
        verify(Config.AUDIT).createOperateLog(captor.capture());
        assertThat(captor.getValue().getAction()).contains("行数=0");
    }

    @Test
    void isolatesOtherOrganizationsWithSameAssignee() {
        long employee = 701L;
        long mine = nextId();
        long minePolicy = insertPolicy(orgId, mine, 10_000, 10_000, 0, 0, employee, "同号员工");
        release(orgId, mine, minePolicy, FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER, 10_000, null,
                "2026-11-10 10:00:00");

        long theirs = nextId();
        long theirPolicy = insertPolicy(otherOrgId, theirs, 99_900, 99_900, 0, 0, employee, "同号员工");
        release(otherOrgId, theirs, theirPolicy, FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER, 99_900, null,
                "2026-11-10 10:00:00");

        List<List<String>> rows = bodyRows(export(post, MONTH));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(4)).isEqualTo("100.00"); // 只含本组织 10000 分
    }

    @Test
    void ignoresVoidedPaymentsAndNeverReportsNegativeUnpaid() {
        long employee = 801L;
        long project = nextId();
        long policy = insertPolicy(orgId, project, 10_000, 10_000, 0, 0, employee, "王五");
        long payable = payableId();
        release(orgId, project, policy, FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER, 10_000, payable,
                "2026-11-12 10:00:00");
        payment(payable, 6_000, "已作废");
        payment(payable, 12_000, "已核实"); // 超付：已付>应付，未付必须收敛为 0

        List<String> row = bodyRows(export(post, MONTH)).get(0);
        assertThat(row.get(4)).isEqualTo("100.00");
        assertThat(row.get(5)).isEqualTo("120.00");
        assertThat(row.get(6)).isEqualTo("0.00");
    }

    @Test
    void countsReviewDueWhenReceiptFactsChangedAfterRelease() {
        long employee = 901L;
        long project = nextId();
        long policy = insertPolicy(orgId, project, 10_000, 10_000, 0, 0, employee, "赵六");
        release(orgId, project, policy, FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER, 10_000, null,
                "2026-11-15 10:00:00");

        assertThat(bodyRows(export(post, MONTH)).get(0).get(8)).isEqualTo("0");

        // 释放后新增一笔收款 → 收款签名变化 → 行视图「待人工核对」，导出口径一致
        jdbc.update("INSERT INTO fr_eng_collection (id, company_id, project_id, stage, amount_cents, receipt_id) "
                + "VALUES (?,?,?,?,?,NULL)", nextId(), orgId, project, "SIGN_AFTER", 1);
        assertThat(bodyRows(export(post, MONTH)).get(0).get(8)).isEqualTo("1");
    }

    @Test
    void countsUnagreedProjectsWhenAnyStageIsZero() {
        long employee = 1_001L;
        long project = nextId();
        // 只约定基础完成后 30000，签约后/封顶后为 0（未约定）
        long policy = insertPolicy(orgId, project, 30_000, 0, 30_000, 0, employee, "孙七");
        release(orgId, project, policy, FrPerfCommissionReleaseDO.STAGE_BASE_DONE, 30_000, null,
                "2026-11-16 10:00:00");

        List<String> row = bodyRows(export(post, MONTH)).get(0);
        assertThat(row.get(3)).isEqualTo("300.00");
        assertThat(row.get(4)).isEqualTo("300.00");
        assertThat(row.get(9)).isEqualTo("1");
    }

    @Test
    void protectsEmployeeNameAgainstFormulaInjection() {
        long employee = 1_101L;
        long project = nextId();
        long policy = insertPolicy(orgId, project, 10_000, 10_000, 0, 0, employee, "=HYPERLINK(\"http://evil\")");
        release(orgId, project, policy, FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER, 10_000, null,
                "2026-11-17 10:00:00");

        List<String> row = bodyRows(export(post, MONTH)).get(0);
        assertThat(row.get(1)).isEqualTo("'=HYPERLINK(\"http://evil\")");
    }

    // ===================== Codex r1 补测 =====================

    @Test
    void missingPolicyFailsExplicitlyWithoutCsvOrAudit() {
        long orphanProject = nextId();
        long missingPolicyId = 8_888_888_888L;
        jdbc.update("INSERT INTO fr_perf_commission_release (id, company_id, policy_id, project_id, stage, "
                        + "payable_id, policy_version, receipt_signature, receipt_ids, release_user_id, release_at) "
                        + "VALUES (?,?,?,?, 'SIGN_AFTER', NULL, 1, 'sig', '[]'::jsonb, ?, ?::timestamptz)",
                nextId(), orgId, missingPolicyId, orphanProject, post.userId(), "2026-11-19 10:00:00");

        ServiceException error = catchThrowableOfType(
                () -> export(post, MONTH), ServiceException.class);
        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(500);
        assertThat(error.getMessage()).isEqualTo("提成规则数据不完整，导出已中止，请联系管理员核对");
        assertThat(error.getMessage()).doesNotContain(String.valueOf(missingPolicyId));
        verify(Config.AUDIT, never()).createOperateLog(any());
    }

    @Test
    void versionChangeUsesFrozenPayableAmountAndFlagsReviewDue() {
        long employee = 1_301L;
        long project = nextId();
        long policy = insertPolicy(orgId, project, 10_000, 10_000, 0, 0, employee, "周八");
        release(orgId, project, policy, FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER, 10_000, null,
                "2026-11-22 10:00:00");

        // 服务层对已释放规则整行锁定；此处模拟越权直改（异常数据），验证映射不静默
        jdbc.update("UPDATE fr_perf_commission_policy SET version = 2, total_cents = 20_000, "
                + "start_cents = 20_000, base_done_cents = 0, topping_cents = 0 WHERE id = ?", policy);

        List<String> row = bodyRows(export(post, MONTH)).get(0);
        assertThat(row.get(3)).isEqualTo("200.00"); // 提成总额=当前规则口径
        assertThat(row.get(4)).isEqualTo("100.00"); // 已生成应付=应付冻结额（不随规则变动）
        assertThat(row.get(8)).isEqualTo("1");      // 版本变化 → 待核对
    }

    @Test
    void reconcileUsesSameStageAmountMappingAsExport() {
        PostSnapshot boss = fixture.post(orgId, "boss", null);
        currentPost.use(boss);
        long employee = 1_401L;
        String[][] cases = {
                {FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER, "40000"},
                {FrPerfCommissionReleaseDO.STAGE_BASE_DONE, "30000"},
                {FrPerfCommissionReleaseDO.STAGE_CAP_DONE, "30000"}
        };
        for (String[] item : cases) {
            String stage = item[0];
            long expectedCents = Long.parseLong(item[1]);
            long project = nextId();
            long policy = insertPolicy(orgId, project, 100_000, 40_000, 30_000, 30_000, employee, "核对员工");
            release(orgId, project, policy, stage, expectedCents, null, "2026-11-06 10:00:00");

            commissions.reconcile(boss, project, stage, "evidence-" + stage, "note-" + stage, "idem-" + stage);

            String json = jdbc.queryForObject(
                    "SELECT reconciliations FROM fr_perf_commission_release "
                            + "WHERE company_id = ? AND project_id = ? AND stage = ?",
                    String.class, orgId, project, stage);
            List<Map<String, Object>> records = JsonUtils.parseObject(json,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            assertThat(records).isNotEmpty();
            Number commissionAmount = (Number) records.get(records.size() - 1).get("commissionAmountCents");
            assertThat(commissionAmount.longValue()).as(stage).isEqualTo(expectedCents);
        }
    }

    @Test
    void streamsExactlyFiftyThousandEmployeesAndRejectsOneMore() {
        long bulkOrg = fixture.store().orgId();
        long policyBase = 50_000_000_000L;
        long projectBase = 60_000_000_000L;
        long userBase = 70_000_000L;
        insertBulk(bulkOrg, policyBase, projectBase, userBase, 50_000);

        byte[] csv = export(postFor(bulkOrg), MONTH);
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).startsWith("\uFEFF");
        assertThat(text.substring(1).split("\r\n")).hasSize(50_001); // 表头 + 5 万员工行

        ArgumentCaptor<OperateLogCreateReqDTO> captor = ArgumentCaptor.forClass(OperateLogCreateReqDTO.class);
        verify(Config.AUDIT).createOperateLog(captor.capture());
        assertThat(captor.getValue().getAction()).contains("行数=50000");

        // 第 50001 名员工 → 前置上限失败
        insertBulk(bulkOrg, policyBase + 50_000, projectBase + 50_000, userBase + 50_000, 1);
        ServiceException error = catchThrowableOfType(
                () -> exportService.prepare(postFor(bulkOrg), MONTH), ServiceException.class);
        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(400);
        assertThat(error.getMessage()).contains("5 万行上限");
    }

    // ===================== Codex r2 补测 =====================

    /**
     * r2 P2-2a：增量写出锁。501 名员工 → 两批查询；记录每次字节写出时的批查询计数，
     * 断言第一批查询期间已收到字节（若恢复为「先缓冲整份 CSV、最后一次性写出」，
     * 所有写出都会发生在第 2 批查询之后，本用例必红）。
     */
    @Test
    void writesFirstBatchBeforeSecondBatchQuery() throws Exception {
        long bulkOrg = fixture.store().orgId();
        long userBase = 82_000_000L;
        insertBulk(bulkOrg, 80_000_000_000L, 81_000_000_000L, userBase, 501);

        AtomicInteger batchQueries = new AtomicInteger();
        JdbcTemplate spyJdbc = spy(new JdbcTemplate(dataSource));
        doAnswer(invocation -> {
            batchQueries.incrementAndGet();
            return invocation.callRealMethod();
        }).when(spyJdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        PerfCommissionExportService service = new PerfCommissionExportService(
                spyJdbc, commissions, Config.AUDIT);

        PostSnapshot owner = postFor(bulkOrg);
        PerfCommissionExportService.ExportPlan plan = service.prepare(owner, MONTH);
        CountingOutputStream output = new CountingOutputStream(batchQueries);
        service.writeCsv(owner, plan, output);

        assertThat(batchQueries.get()).isGreaterThanOrEqualTo(2);
        assertThat(output.queryCounts()).as("第一批查询期间必须已有字节写出")
                .anyMatch(count -> count == 1);
        assertThat(bodyRows(output.toByteArray())).hasSize(501);
    }

    /**
     * r2 P2-2b：第二批查询失败 → 请求以异常结束、不写成功审计、已写字节缺最后员工
     * （不构成完整 CSV；生产下连接中止，前端按失败处理）。
     */
    @Test
    void secondBatchFailureEndsWithIncompleteCsvAndNoAudit() {
        long bulkOrg = fixture.store().orgId();
        long userBase = 84_000_000L;
        insertBulk(bulkOrg, 83_000_000_000L, 85_000_000_000L, userBase, 501);

        AtomicInteger batchQueries = new AtomicInteger();
        JdbcTemplate spyJdbc = spy(new JdbcTemplate(dataSource));
        doAnswer(invocation -> {
            if (batchQueries.incrementAndGet() == 2) {
                throw new IllegalStateException("second batch down");
            }
            return invocation.callRealMethod();
        }).when(spyJdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        PerfCommissionExportService service = new PerfCommissionExportService(
                spyJdbc, commissions, Config.AUDIT);

        PostSnapshot owner = postFor(bulkOrg);
        PerfCommissionExportService.ExportPlan plan = service.prepare(owner, MONTH);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RuntimeException error = catchThrowableOfType(
                () -> service.writeCsv(owner, plan, output), RuntimeException.class);

        assertThat(error).isNotNull().hasMessageContaining("second batch down");
        verify(Config.AUDIT, never()).createOperateLog(any());
        List<List<String>> written = bodyRows(output.toByteArray());
        assertThat(written).hasSize(500); // 第一批 500 行；第二批（第 501 行）未写出
        assertThat(written).noneMatch(row -> row.get(0).equals(String.valueOf(userBase + 501)));
    }

    /** r2 P2-2c：单员工 release 超 5000 → prepare 前置 500，不产出 CSV、不写成功审计。 */
    @Test
    void singleEmployeeReleaseLimitIsEnforcedBeforeCsv() {
        long bulkOrg = fixture.store().orgId();
        long employee = 9_900L;
        insertSingleEmployeeBulk(bulkOrg, 90_000_000_000L, 91_000_000_000L, employee, 5_001);

        ServiceException error = catchThrowableOfType(
                () -> exportService.prepare(postFor(bulkOrg), MONTH), ServiceException.class);
        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(500);
        assertThat(error.getMessage()).contains("5000");
        verify(Config.AUDIT, never()).createOperateLog(any());
    }

    /**
     * r3 P1：PgJDBC 游标条件。用 RecordingDataSource 记录批次 SQL 执行时的 autoCommit 与
     * PreparedStatement.fetchSize；断言每批在 autoCommit=false 的事务内、fetchSize=500。
     * 501 员工 → 两批查询，证明不是整批一次取回后应用层 List 收集。
     */
    @Test
    void batchQueryUsesCursorModeWithFetchSize() throws Exception {
        long bulkOrg = fixture.store().orgId();
        insertBulk(bulkOrg, 92_000_000_000L, 93_000_000_000L, 94_000_000L, 501);

        RecordingDataSource recording = new RecordingDataSource(dataSource);
        PerfCommissionExportService service = new PerfCommissionExportService(
                recording, commissions, Config.AUDIT);
        PostSnapshot owner = postFor(bulkOrg);
        PerfCommissionExportService.ExportPlan plan = service.prepare(owner, MONTH);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeCsv(owner, plan, out);

        assertThat(recording.batchQueries()).isGreaterThanOrEqualTo(2);
        assertThat(recording.autoCommitAtPrepare()).isNotEmpty().contains(false);
        assertThat(recording.fetchSizes()).contains(500);
        assertThat(bodyRows(out.toByteArray())).hasSize(501);
    }

    // ===================== 夹具 =====================

    /** 释放一条提成应付；收款事实先行，签名由真实 stageFacts 计算，默认不触发待核对。 */
    private void release(long org, long projectId, long policyId, String stage, long amountCents,
                         Long payableId, String releaseAt) {
        insertProject(org, projectId);
        jdbc.update("DELETE FROM fr_eng_collection_term WHERE company_id = ? AND project_id = ? AND stage = ?",
                org, projectId, stage);
        jdbc.update("INSERT INTO fr_eng_collection_term (company_id, project_id, stage, amount_cents) "
                + "VALUES (?,?,?,?)", org, projectId, stage, amountCents);
        jdbc.update("INSERT INTO fr_eng_collection (id, company_id, project_id, stage, amount_cents, receipt_id) "
                + "VALUES (?,?,?,?,?,NULL)", nextId(), org, projectId, stage, amountCents);
        String signature = commissions.stageFacts(postFor(org), projectId, stage).signature();
        long actualPayableId = payableId == null ? payableId() : payableId;
        insertPayable(org, projectId, actualPayableId, stage, amountCents);
        jdbc.update("INSERT INTO fr_perf_commission_release (id, company_id, policy_id, project_id, stage, "
                        + "payable_id, policy_version, receipt_signature, receipt_ids, release_user_id, release_at) "
                        + "VALUES (?,?,?,?,?,?,1,?, '[]'::jsonb, ?, ?::timestamptz)",
                nextId(), org, policyId, projectId, stage, actualPayableId, signature, post.userId(), releaseAt);
    }

    private void releaseAt(long employee, String releaseAt) {
        long project = nextId();
        long policy = insertPolicy(orgId, project, 10_000, 10_000, 0, 0, employee, "员工" + employee);
        release(orgId, project, policy, FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER, 10_000, null, releaseAt);
    }

    private long insertPolicy(long org, long projectId, long total, long signAfter, long baseDone,
                              long capDone, long assigneeUserId, String assigneeName) {
        long id = nextId();
        jdbc.update("INSERT INTO fr_perf_commission_policy (id, company_id, project_id, total_cents, "
                        + "assignee_user_id, assignee_name, start_cents, topping_cents, base_done_cents, version, "
                        + "evidence_ref, note, author_user_id) VALUES (?,?,?,?,?,?,?,?,?,1,'ev','note',?)",
                id, org, projectId, total, assigneeUserId, assigneeName, signAfter, capDone, baseDone,
                post.userId());
        return id;
    }

    private void insertProject(long org, long projectId) {
        jdbc.update("INSERT INTO fr_eng_project (id, company_id, source_type, manager_user_id, name, status, "
                        + "author_user_id, customer_id) VALUES (?,?,'MANUAL',?,'夹具工程','IN_PROGRESS',?,NULL) "
                        + "ON CONFLICT (id) DO NOTHING",
                projectId, org, post.userId(), post.userId());
    }

    private void insertPayable(long org, long projectId, long payableId, String stage, long totalCents) {
        jdbc.update("INSERT INTO fr_fin_payable (id, company_id, project_id, stage, total_cents, recipient, status) "
                        + "VALUES (?,?,?,?,?,'测试收款方','进行中') ON CONFLICT (id) DO NOTHING",
                payableId, org, projectId, stage, totalCents);
    }

    private void payment(long payableId, long cents, String status) {
        jdbc.update("INSERT INTO fr_fin_payment (id, payable_id, company_id, amount_cents, status) "
                + "VALUES (?,?,?,?,?)", nextId(), payableId, orgId, cents, status);
    }

    private long payableFor(long projectId, String stage) {
        return jdbc.queryForObject("SELECT payable_id FROM fr_perf_commission_release "
                + "WHERE company_id = ? AND project_id = ? AND stage = ?", Long.class, orgId, projectId, stage);
    }

    private long payableId() {
        return nextId();
    }

    /** 批量造员工（1 policy + 1 release/人；payable_id=NULL 触发「应付缺失回退+待核对」，不触 facts）。 */
    private void insertBulk(long org, long policyBase, long projectBase, long userBase, int count) {
        jdbc.update("INSERT INTO fr_perf_commission_policy (id, company_id, project_id, total_cents, "
                        + "assignee_user_id, assignee_name, start_cents, topping_cents, base_done_cents, version, "
                        + "evidence_ref, note, author_user_id) "
                        + "SELECT ? + g, ?, ? + g, 100, ? + g, '员工', 100, 0, 0, 1, 'ev', 'note', 1 "
                        + "FROM generate_series(1, ?) g",
                policyBase, org, projectBase, userBase, count);
        jdbc.update("INSERT INTO fr_perf_commission_release (id, company_id, policy_id, project_id, stage, "
                        + "payable_id, policy_version, receipt_signature, receipt_ids, release_user_id, release_at) "
                        + "SELECT id, ?, id, project_id, 'SIGN_AFTER', NULL, 1, 'sig', '[]'::jsonb, 1, ?::timestamptz "
                        + "FROM fr_perf_commission_policy WHERE company_id = ? AND id > ? AND id <= ?",
                org, "2026-11-10 00:00:00", org, policyBase, policyBase + count);
    }

    /** 单员工 5001 条 release（5001 个项目 × SIGN_AFTER），用于单员工上限用例。 */
    private void insertSingleEmployeeBulk(long org, long policyBase, long projectBase, long employee, int count) {
        jdbc.update("INSERT INTO fr_perf_commission_policy (id, company_id, project_id, total_cents, "
                        + "assignee_user_id, assignee_name, start_cents, topping_cents, base_done_cents, version, "
                        + "evidence_ref, note, author_user_id) "
                        + "SELECT ? + g, ?, ? + g, 100, ?, '超限员工', 100, 0, 0, 1, 'ev', 'note', 1 "
                        + "FROM generate_series(1, ?) g",
                policyBase, org, projectBase, employee, count);
        jdbc.update("INSERT INTO fr_perf_commission_release (id, company_id, policy_id, project_id, stage, "
                        + "payable_id, policy_version, receipt_signature, receipt_ids, release_user_id, release_at) "
                        + "SELECT id, ?, id, project_id, 'SIGN_AFTER', NULL, 1, 'sig', '[]'::jsonb, 1, ?::timestamptz "
                        + "FROM fr_perf_commission_policy WHERE company_id = ? AND id > ? AND id <= ?",
                org, "2026-11-10 00:00:00", org, policyBase, policyBase + count);
    }

    private List<String> exportedEmployeeIds(PerfCommissionExportService service, PostSnapshot owner, String month)
            throws IOException {
        PerfCommissionExportService.ExportPlan plan = service.prepare(owner, month);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeCsv(owner, plan, out);
        return bodyRows(out.toByteArray()).stream().map(row -> row.get(0)).toList();
    }

    private static void setSessionTimeZone(Connection connection, String zone) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET TIME ZONE '" + zone + "'");
        }
    }

    private static String showSessionTimeZone(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery("SHOW TimeZone")) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private long nextId() {
        return SEQ.incrementAndGet();
    }

    /** 同岗位快照换组织（stageFacts 只读 orgId；不额外造任职）。 */
    private PostSnapshot postFor(long org) {
        return new PostSnapshot(post.postId(), post.userId(), org, post.orgType(), post.roleCode(),
                post.teamId(), post.lead(), post.managedTeamIds());
    }

    /** prepare + 流式写出（测试侧缓冲到内存，生产走 StreamingResponseBody）。 */
    private byte[] export(PostSnapshot owner, String month) {
        try {
            PerfCommissionExportService.ExportPlan plan = exportService.prepare(owner, month);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            exportService.writeCsv(owner, plan, out);
            return out.toByteArray();
        } catch (IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    // ===================== CSV 解析 =====================

    private static List<List<String>> bodyRows(byte[] csv) {
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).startsWith("\uFEFF");
        String[] lines = text.substring(1).split("\r\n");
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) { // 跳过表头
            rows.add(parse(lines[i]));
        }
        return rows;
    }

    /**
     * 包装真实 DataSource：拦截批次 SQL 的 prepareStatement，记录连接 autoCommit 与
     * PreparedStatement.setFetchSize，验证 PgJDBC 走游标（autoCommit=false + fetchSize>0）。
     */
    private static final class RecordingDataSource extends DelegatingDataSource {
        private final List<Boolean> autoCommitAtPrepare = Collections.synchronizedList(new ArrayList<>());
        private final List<Integer> fetchSizes = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger batchQueries = new AtomicInteger();

        private RecordingDataSource(DataSource delegate) {
            super(delegate);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection real = super.getConnection();
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        Object result = method.invoke(real, args);
                        if ("prepareStatement".equals(method.getName()) && args != null && args.length > 0
                                && String.valueOf(args[0]).contains("fp.total_cents AS payable_cents")) {
                            batchQueries.incrementAndGet();
                            autoCommitAtPrepare.add(real.getAutoCommit());
                            PreparedStatement statement = (PreparedStatement) result;
                            return Proxy.newProxyInstance(
                                    PreparedStatement.class.getClassLoader(),
                                    new Class<?>[]{PreparedStatement.class},
                                    (statementProxy, statementMethod, statementArgs) -> {
                                        if ("setFetchSize".equals(statementMethod.getName())
                                                && statementArgs != null && statementArgs.length == 1) {
                                            fetchSizes.add((Integer) statementArgs[0]);
                                        }
                                        return statementMethod.invoke(statement, statementArgs);
                                    });
                        }
                        return result;
                    });
        }

        private int batchQueries() {
            return batchQueries.get();
        }

        private List<Boolean> autoCommitAtPrepare() {
            return autoCommitAtPrepare;
        }

        private List<Integer> fetchSizes() {
            return fetchSizes;
        }
    }

    /** 记录每次写出时的批查询计数（用于证明增量写出，而非整份缓冲后一次写出）。 */
    private static final class CountingOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private final AtomicInteger batchQueries;
        private final List<Integer> queryCounts = new ArrayList<>();

        private CountingOutputStream(AtomicInteger batchQueries) {
            this.batchQueries = batchQueries;
        }

        @Override
        public void write(int b) {
            queryCounts.add(batchQueries.get());
            delegate.write(b);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            queryCounts.add(batchQueries.get());
            delegate.write(bytes, offset, length);
        }

        private List<Integer> queryCounts() {
            return queryCounts;
        }

        private byte[] toByteArray() {
            return delegate.toByteArray();
        }
    }

    private static List<String> parse(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out;
    }
}
