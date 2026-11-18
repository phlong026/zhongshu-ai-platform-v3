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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
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


        @Bean
        OperateLogCommonApi operateLogCommonApi() {
            return mock(OperateLogCommonApi.class);
        }

        @Bean
        PerformancePayablePort performancePayablePort() {
            return mock(PerformancePayablePort.class);
        }

        @Bean cn.iocoder.yudao.module.franchise.api.AssetAccessPort assets() {
            return mock(cn.iocoder.yudao.module.franchise.api.AssetAccessPort.class);
        }
        @Bean cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort accounts() {
            return mock(cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort.class);
        }

        @Bean
        FranchiseCommands franchiseCommands() {
            return mock(FranchiseCommands.class);
        }

        @Bean
        UnifiedStageAccountPort unifiedStageAccountPort(JdbcTemplate jdbc) {
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
                public List<StageBalance> stageBalances(PostSnapshot post, long contractId) {
                    return jdbc.query("""
                            SELECT s.stage,s.amount_cents,coalesce(sum(sr.amount_cents),0),
                                   coalesce(sum((SELECT sum(g.amount_cents) FROM fr_fin_refund g
                                       WHERE g.receipt_id=sr.receipt_id AND g.company_id=s.company_id
                                         AND g.status='已核实' AND g.replaced_by_id IS NULL)),0)
                            FROM fr_sale_payment_stage s LEFT JOIN fr_sale_stage_receipt sr ON sr.stage_id=s.id
                            WHERE s.company_id=? AND s.contract_id=? GROUP BY s.stage,s.amount_cents
                            """, (rs, i) -> new StageBalance(rs.getString(1), rs.getLong(2),
                            rs.getLong(3)-rs.getLong(4), rs.getLong(4)), post.orgId(), contractId);
                }

                @Override
                public StageCollectResult collectStage(PostSnapshot post, long contractId, String stage,
                                                       CollectInput input) {
                    throw new UnsupportedOperationException("本测试不写统一实收账");
                }
            };
        }
    }

    @Resource private OperateLogCommonApi auditApi;
    @Resource private cn.iocoder.yudao.module.franchise.api.AssetAccessPort assets;
    @Resource private cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort accounts;
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
        reset(auditApi);
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
        jdbc.execute("ALTER TABLE fr_eng_project ADD COLUMN IF NOT EXISTS sale_contract_id bigint NULL");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_fin_receipt(id bigint PRIMARY KEY,company_id bigint NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_collection_term ("
                + "company_id bigint NOT NULL, project_id bigint NOT NULL, stage varchar(16) NOT NULL,"
                + "amount_cents bigint NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_collection ("
                + "id bigint PRIMARY KEY, company_id bigint NOT NULL, project_id bigint NOT NULL,"
                + "stage varchar(16) NOT NULL, amount_cents bigint NOT NULL, receipt_id bigint NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_fin_refund ("
                + "company_id bigint NOT NULL, receipt_id bigint NULL, amount_cents bigint NOT NULL, status varchar(16) NOT NULL,"
                + "replaced_by_id bigint NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_fin_payment ("
                + "id bigint PRIMARY KEY, payable_id bigint NULL, company_id bigint NOT NULL,"
                + "amount_cents bigint NOT NULL, status varchar(16) NOT NULL, pay_date date NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_fin_payable ("
                + "id bigint PRIMARY KEY, company_id bigint NOT NULL, project_id bigint NULL,"
                + "stage varchar(64) NULL, total_cents bigint NOT NULL, recipient varchar(128) NULL,"
                + "status varchar(16) NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_contract ("
                + "id bigint PRIMARY KEY, company_id bigint NOT NULL, customer_id bigint NOT NULL,"
                + "status varchar(16) NOT NULL)");
        jdbc.execute("ALTER TABLE fr_sale_contract ADD COLUMN IF NOT EXISTS deleted boolean NOT NULL DEFAULT false");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_payment_stage ("
                + "id bigint PRIMARY KEY, company_id bigint NOT NULL, contract_id bigint NOT NULL,"
                + "stage varchar(16) NOT NULL)");
        jdbc.execute("ALTER TABLE fr_sale_payment_stage ADD COLUMN IF NOT EXISTS amount_cents bigint");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_stage_receipt ("
                + "id bigint PRIMARY KEY, company_id bigint NOT NULL, stage_id bigint NOT NULL,"
                + "receipt_id bigint NOT NULL, amount_cents bigint NOT NULL)");
        jdbc.execute("ALTER TABLE fr_sale_stage_receipt ADD COLUMN IF NOT EXISTS create_time timestamptz DEFAULT now()");
        jdbc.execute("ALTER TABLE fr_fin_refund ADD COLUMN IF NOT EXISTS create_time timestamptz DEFAULT now(), "
                + "ADD COLUMN IF NOT EXISTS update_time timestamptz DEFAULT now(), ADD COLUMN IF NOT EXISTS voided_at timestamptz");
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
        verify(auditApi, times(2)).createOperateLog(captor.capture());
        OperateLogCreateReqDTO audit = captor.getAllValues().get(1);
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
    void auditAttemptFailureWritesZeroBytes() {
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditApi).createOperateLog(any());
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        IllegalStateException error = catchThrowableOfType(
                () -> exportService.writeCsv(post,
                        new PerfCommissionExportService.ExportPlan(MONTH,
                                "2026-11-01 00:00:00", "2026-12-01 00:00:00", List.of()), output),
                IllegalStateException.class);

        assertThat(error).hasMessageContaining("audit unavailable");
        assertThat(output.size()).isZero();
    }

    @Test
    void streamFailureKeepsAttemptAndNeverWritesSuccessAudit() {
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("client disconnected");
            }
        };

        IOException error = catchThrowableOfType(
                () -> exportService.writeCsv(post,
                        new PerfCommissionExportService.ExportPlan(MONTH,
                                "2026-11-01 00:00:00", "2026-12-01 00:00:00", List.of()), broken),
                IOException.class);

        assertThat(error).hasMessageContaining("client disconnected");
        ArgumentCaptor<OperateLogCreateReqDTO> captor = ArgumentCaptor.forClass(OperateLogCreateReqDTO.class);
        verify(auditApi).createOperateLog(captor.capture());
        assertThat(captor.getValue().getAction()).contains("状态=attempt").doesNotContain("状态=success");
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
                    new JdbcTemplate(single), auditApi);

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
        verify(auditApi, times(2)).createOperateLog(captor.capture());
        assertThat(captor.getAllValues().get(1).getAction()).contains("状态=success", "行数=0");
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
        assertThat(stageReviewDue(project, FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER)).isFalse();

        // 释放后新增一笔收款 → 收款签名变化 → 行视图「待人工核对」，导出口径一致
        jdbc.update("INSERT INTO fr_eng_collection (id, company_id, project_id, stage, amount_cents, receipt_id) "
                + "VALUES (?,?,?,?,?,NULL)", nextId(), orgId, project, "SIGN_AFTER", 1);
        assertThat(bodyRows(export(post, MONTH)).get(0).get(8)).isEqualTo("1");
    }

    @Test
    void engineeringSignatureOrderDoesNotCreateFalseReviewDue() {
        long employee = 925L;
        long project = nextId();
        long policy = insertPolicy(orgId, project, 10_000, 10_000, 0, 0, employee, "顺序兼容员工");
        long payable = payableId();
        long lowCollectionId = nextId();
        long highCollectionId = nextId();
        insertProject(orgId, project);
        jdbc.update("INSERT INTO fr_eng_collection_term (company_id,project_id,stage,amount_cents) "
                + "VALUES (?,?,'SIGN_AFTER',10000)", orgId, project);
        // 先插入高 ID，复现旧 stageFacts 按物理顺序生成签名的场景。
        jdbc.update("INSERT INTO fr_eng_collection (id,company_id,project_id,stage,amount_cents,receipt_id) "
                + "VALUES (?,?,?,'SIGN_AFTER',6000,NULL)", highCollectionId, orgId, project);
        jdbc.update("INSERT INTO fr_eng_collection (id,company_id,project_id,stage,amount_cents,receipt_id) "
                + "VALUES (?,?,?,'SIGN_AFTER',4000,NULL)", lowCollectionId, orgId, project);
        String generatedSignature = commissions.stageFacts(
                postFor(orgId), project, FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER).signature();
        insertPayable(orgId, project, payable, "SIGN_AFTER", 10_000);
        jdbc.update("INSERT INTO fr_perf_commission_release (id,company_id,policy_id,project_id,stage,payable_id,"
                        + "policy_version,receipt_signature,receipt_ids,release_user_id,release_at) "
                        + "VALUES (?,?,?,?, 'SIGN_AFTER',?,1,?,'[]'::jsonb,?,?::timestamptz)",
                nextId(), orgId, policy, project, payable, generatedSignature,
                post.userId(), "2026-11-15 11:00:00");

        assertThat(bodyRows(export(post, MONTH)).get(0).get(8)).isEqualTo("0");

        String legacyReverseSignature = "required:10000|c:" + highCollectionId + ":6000:0|c:"
                + lowCollectionId + ":4000:0";
        jdbc.update("UPDATE fr_perf_commission_release SET receipt_signature = ? "
                + "WHERE company_id = ? AND project_id = ?", legacyReverseSignature, orgId, project);
        assertThat(bodyRows(export(post, MONTH)).get(0).get(8)).isEqualTo("0");
        assertThat(stageReviewDue(project, FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER)).isFalse();
    }

    @Test
    void unifiedContractFactsKeepNetReceiptSignatureAndReviewDueSemantics() {
        long employee = 950L;
        long project = nextId();
        long customer = nextId();
        long contract = nextId();
        long saleStage = nextId();
        long receipt = nextId();
        long payable = payableId();
        long policy = insertPolicy(orgId, project, 10_000, 10_000, 0, 0, employee, "统一账员工");
        insertProject(orgId, project);
        jdbc.update("UPDATE fr_eng_project SET customer_id = ? WHERE id = ?", customer, project);
        jdbc.update("INSERT INTO fr_eng_collection_term (company_id,project_id,stage,amount_cents) "
                + "VALUES (?,?,'SIGN_AFTER',10000)", orgId, project);
        jdbc.update("INSERT INTO fr_sale_contract (id,company_id,customer_id,status) VALUES (?,?,?,'SIGNED')",
                contract, orgId, customer);
        jdbc.update("INSERT INTO fr_sale_payment_stage (id,company_id,contract_id,stage,amount_cents) "
                + "VALUES (?,?,?,'SIGN_AFTER',10000)", saleStage, orgId, contract);
        jdbc.update("INSERT INTO fr_sale_stage_receipt (id,company_id,stage_id,receipt_id,amount_cents) "
                + "VALUES (?,?,?,?,12000)", nextId(), orgId, saleStage, receipt);
        jdbc.update("INSERT INTO fr_fin_refund (company_id,receipt_id,amount_cents,status,replaced_by_id) "
                + "VALUES (?,?,2000,'已核实',NULL)", orgId, receipt);
        insertPayable(orgId, project, payable, "SIGN_AFTER", 10_000);
        String signature = "required:10000|unified:" + contract + ":SIGN_AFTER:10000";
        jdbc.update("INSERT INTO fr_perf_commission_release (id,company_id,policy_id,project_id,stage,payable_id,"
                        + "policy_version,receipt_signature,receipt_ids,release_user_id,release_at) "
                        + "VALUES (?,?,?,?, 'SIGN_AFTER',?,1,?,'[]'::jsonb,?,?::timestamptz)",
                nextId(), orgId, policy, project, payable, signature, post.userId(), "2026-11-15 10:00:00");

        // 净额已补回，原签名仍需核对。老板保存新签名后恢复。
        assertThat(bodyRows(export(post, MONTH)).get(0).get(8)).isEqualTo("1");
        jdbc.update("UPDATE fr_sale_stage_receipt SET create_time='2026-11-14T00:00:00Z' WHERE stage_id=?",saleStage);
        jdbc.update("UPDATE fr_fin_refund SET create_time='2026-11-14T01:00:00Z',update_time='2026-11-14T01:00:00Z' WHERE receipt_id=?",receipt);
        var review = Map.of("receiptSignature",signature,"actorUserId",post.userId(),"stageNetCollectedCents",10000,
                "evidenceRef","123","note","原退款及补收已核实","at","2026-11-16T12:00:00Z");
        jdbc.update("UPDATE fr_perf_commission_release SET reconciliations=?::jsonb,update_time='2026-11-16T11:59:59Z' "
                + "WHERE project_id=? AND company_id=?",JsonUtils.toJsonString(List.of(review)), project,orgId);
        // A JSON note on its own cannot authorize payment.
        assertThat(bodyRows(export(post, MONTH)).get(0).get(8)).isEqualTo("1");
        assertThatThrownBy(() -> commissions.requirePayable(post,payable)).isInstanceOf(ServiceException.class);
        jdbc.update("""
                INSERT INTO pf_command_event(id,domain,aggregate_type,aggregate_id,revision,command,org_id,actor_user_id,
                    actor_role_code,payload,payload_hash,occurred_at)
                SELECT ?,'franchise','fr-perf-commission',id,5,'perf-commission-reconcile',company_id,?,
                    'boss',?,repeat('0',64),'2026-11-16T12:00:01Z'
                FROM fr_perf_commission_release WHERE project_id=? AND company_id=?
                """,nextId(),post.userId(),JsonUtils.toJsonString(Map.of("projectId", project, "stage","SIGN_AFTER",
                "note","原退款及补收已核实","evidenceRef","123")),project,orgId);
        assertThat(bodyRows(export(post, MONTH)).get(0).get(8)).isEqualTo("0");
        commissions.requirePayable(post,payable);
        // A later equal refund and compensating receipt must block the same old review.
        jdbc.update("INSERT INTO fr_fin_refund(company_id,receipt_id,amount_cents,status,create_time,update_time) "
                + "VALUES(?,?,100,'已核实','2026-11-17T10:00:00Z','2026-11-17T10:00:00Z')",orgId,receipt);
        jdbc.update("INSERT INTO fr_sale_stage_receipt(id,company_id,stage_id,receipt_id,amount_cents,create_time) "
                + "VALUES(?,?,?,?,100,'2026-11-17T11:00:00Z')",nextId(),orgId,saleStage,nextId());
        assertThat(bodyRows(export(post, MONTH)).get(0).get(8)).isEqualTo("1");
        assertThatThrownBy(() -> commissions.requirePayable(post,payable)).isInstanceOf(ServiceException.class);
        jdbc.update("UPDATE fr_perf_commission_release SET receipt_signature=? WHERE project_id=? AND company_id=?",
                signature + "|refunded:2100", project, orgId);
        assertThat(bodyRows(export(post, MONTH)).get(0).get(8)).isEqualTo("0");
        jdbc.update("INSERT INTO fr_fin_refund (company_id,receipt_id,amount_cents,status,replaced_by_id) "
                + "VALUES (?,?,1,'已核实',NULL)", orgId, receipt);
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
    void archived0105AndCandidateSaveRulesAndReconcileUsingVerifiedEvidence() {
        var boss=fixture.post(orgId,"boss",null);
        var employee=cn.iocoder.yudao.module.franchise.test.FakeSystemDirectory.INSTANCE.createUser("员工","",null);
        org.mockito.Mockito.when(accounts.requireServiceAccount(orgId)).thenReturn(777L);
        for(boolean legacy : new boolean[]{true,false}) {
            long project=nextId(), voucher=nextId();
            insertProject(orgId,project);
            var actor=new cn.iocoder.yudao.module.franchise.api.AssetAccessPort.Actor(orgId,777L,boss.userId(),boss.postId());
            org.mockito.Mockito.when(assets.validateReference(actor,voucher,java.util.Set.of("FIN_VOUCHER"))).thenReturn(true);
            String reference=legacy?"{\"name\":\"proof.pdf\",\"path\":\"wxfile://saved/proof.pdf\"}":Long.toString(voucher);
            String note=legacy?"规则依据 [凭据#"+voucher+" 分期 400/300/300]":"规则依据";
            if(legacy) {
                var error=catchThrowableOfType(()->commissions.policy(boss,project,1000,0,0,0,employee,"员工",reference,"规则依据","missing-"+project),ServiceException.class);
                assertThat(error.getMessage()).contains("经营看板","旧版提成凭据");
            }
            commissions.policy(boss,project,1000,legacy?0:400,legacy?0:300,legacy?0:300,employee,"员工",reference,note,"policy-"+project);
            var row=jdbc.queryForMap("SELECT id,evidence_ref,start_cents,base_done_cents,topping_cents,note FROM fr_perf_commission_policy WHERE project_id=?",project);
            assertThat(row).containsEntry("evidence_ref",Long.toString(voucher)).containsEntry("note","规则依据")
                    .containsEntry("start_cents",400L).containsEntry("base_done_cents",300L).containsEntry("topping_cents",300L);
            release(orgId,project,((Number)row.get("id")).longValue(),"SIGN_AFTER",400,null,"2026-11-06 10:00:00");
            commissions.reconcile(boss,project,"SIGN_AFTER",reference,legacy?"复核依据 [凭据#"+voucher+"]":"复核依据","review-"+project);
            var review=jdbc.queryForMap("SELECT reconciliations->0->>'evidenceRef' proof,reconciliations->0->>'note' note FROM fr_perf_commission_release WHERE project_id=?",project);
            assertThat(review).containsEntry("proof",Long.toString(voucher)).containsEntry("note","复核依据");
        }
    }

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
        verify(auditApi, never()).createOperateLog(any());
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

            org.mockito.Mockito.when(accounts.requireServiceAccount(orgId)).thenReturn(777L);
            var actor=new cn.iocoder.yudao.module.franchise.api.AssetAccessPort.Actor(orgId,777L,boss.userId(),boss.postId());
            org.mockito.Mockito.when(assets.validateReference(actor,project,java.util.Set.of("FIN_VOUCHER"))).thenReturn(true);
            commissions.reconcile(boss, project, stage, Long.toString(project), "note-" + stage, "idem-" + stage);

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
        verify(auditApi, times(2)).createOperateLog(captor.capture());
        assertThat(captor.getAllValues().get(1).getAction()).contains("状态=success", "行数=50000");

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
                spyJdbc, auditApi);

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
                spyJdbc, auditApi);

        PostSnapshot owner = postFor(bulkOrg);
        PerfCommissionExportService.ExportPlan plan = service.prepare(owner, MONTH);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RuntimeException error = catchThrowableOfType(
                () -> service.writeCsv(owner, plan, output), RuntimeException.class);

        assertThat(error).isNotNull().hasMessageContaining("second batch down");
        ArgumentCaptor<OperateLogCreateReqDTO> audit = ArgumentCaptor.forClass(OperateLogCreateReqDTO.class);
        verify(auditApi).createOperateLog(audit.capture());
        assertThat(audit.getValue().getAction()).contains("状态=attempt").doesNotContain("状态=success");
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
        verify(auditApi, never()).createOperateLog(any());
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
                recording, auditApi);
        PostSnapshot owner = postFor(bulkOrg);
        PerfCommissionExportService.ExportPlan plan = service.prepare(owner, MONTH);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeCsv(owner, plan, out);

        assertThat(recording.batchQueries()).isGreaterThanOrEqualTo(2);
        assertThat(recording.autoCommitAtPrepare()).isNotEmpty().contains(false);
        assertThat(recording.fetchSizes()).contains(500);
        assertThat(bodyRows(out.toByteArray())).hasSize(501);
    }

    @Test
    void payableFactsForFiveHundredOneEmployeesUseOnlyTwoBatchQueries() throws Exception {
        long bulkOrg = fixture.store().orgId();
        long userBase = 96_000_000L;
        insertBulkWithPayables(bulkOrg, 96_100_000_000L, 96_200_000_000L, userBase, 501);

        RecordingDataSource recording = new RecordingDataSource(dataSource);
        PerfCommissionExportService service = new PerfCommissionExportService(recording, auditApi);

        PostSnapshot owner = postFor(bulkOrg);
        PerfCommissionExportService.ExportPlan plan = service.prepare(owner, MONTH);
        recording.resetQueryCount();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeCsv(owner, plan, out);

        assertThat(recording.totalQueries()).isEqualTo(2);
        assertThat(recording.batchQueries()).isEqualTo(2);
        assertThat(bodyRows(out.toByteArray())).hasSize(501)
                .allSatisfy(row -> assertThat(row.get(4)).isEqualTo("1.00"));
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

    /** 501 员工真实 payable + 工程收款事实，覆盖正常 reviewDue 路径。 */
    private void insertBulkWithPayables(long org, long policyBase, long projectBase, long userBase, int count) {
        long payableBase = policyBase + 2_000_000L;
        long collectionBase = policyBase + 4_000_000L;
        insertBulk(org, policyBase, projectBase, userBase, count);
        jdbc.update("INSERT INTO fr_eng_project (id, company_id, source_type, manager_user_id, name, status, "
                        + "author_user_id, customer_id) SELECT ? + g, ?, 'MANUAL', 1, '批量工程', "
                        + "'IN_PROGRESS', 1, NULL FROM generate_series(1, ?) g",
                projectBase, org, count);
        jdbc.update("INSERT INTO fr_eng_collection_term (company_id, project_id, stage, amount_cents) "
                        + "SELECT ?, ? + g, 'SIGN_AFTER', 100 FROM generate_series(1, ?) g",
                org, projectBase, count);
        jdbc.update("INSERT INTO fr_eng_collection (id, company_id, project_id, stage, amount_cents, receipt_id) "
                        + "SELECT ? + g, ?, ? + g, 'SIGN_AFTER', 100, NULL FROM generate_series(1, ?) g",
                collectionBase, org, projectBase, count);
        jdbc.update("INSERT INTO fr_fin_payable (id, company_id, project_id, stage, total_cents, recipient, status) "
                        + "SELECT ? + g, ?, ? + g, 'SIGN_AFTER', 100, '员工', '进行中' "
                        + "FROM generate_series(1, ?) g",
                payableBase, org, projectBase, count);
        jdbc.update("UPDATE fr_perf_commission_release r SET payable_id = ? + (p.id - ?), "
                        + "receipt_signature = 'required:100|c:' || (? + (p.id - ?)) || ':100:0' "
                        + "FROM fr_perf_commission_policy p WHERE r.policy_id = p.id "
                        + "AND p.company_id = ? AND p.id > ? AND p.id <= ?",
                payableBase, policyBase, collectionBase, policyBase,
                org, policyBase, policyBase + count);
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

    @SuppressWarnings("unchecked")
    private boolean stageReviewDue(long projectId, String stage) {
        Map<String, Object> row = commissions.row(postFor(orgId), projectId);
        List<Map<String, Object>> stages = (List<Map<String, Object>>) row.get("stages");
        return (Boolean) stages.stream()
                .filter(item -> stage.equals(item.get("stage")))
                .findFirst()
                .orElseThrow()
                .get("reviewDue");
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
        private final AtomicInteger totalQueries = new AtomicInteger();

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
                                && args[0] instanceof String) {
                            totalQueries.incrementAndGet();
                            PreparedStatement statement = (PreparedStatement) result;
                            if (String.valueOf(args[0]).contains("fp.total_cents AS payable_cents")) {
                                batchQueries.incrementAndGet();
                                autoCommitAtPrepare.add(real.getAutoCommit());
                            }
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

        private int totalQueries() {
            return totalQueries.get();
        }

        private void resetQueryCount() {
            totalQueries.set(0);
            batchQueries.set(0);
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
