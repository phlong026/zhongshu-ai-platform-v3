package cn.iocoder.yudao.module.franchise.performance.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * P1 批1：经营看板跨域聚合的真实 PG 回归（Testcontainers PG17）。
 *
 * <p>覆盖：当月口径、月边界、组织隔离、空月份、状态分组零填、设计委托 operation 过滤、period 校验。
 *
 * <p><b>夹具说明</b>：{@code franchise-performance} 的测试 classpath 只含本域/组织域迁移，
 * 不含 crm/engineering/design 模块的迁移（红线禁止为测试新增这些模块依赖）。因此本类按任务书
 * “各域最小夹具表”约定，用测试夹具 DDL 建四张<b>最小读/写集</b>表，列名与生产 DDL
 * （802/814/925）一致并保留关键 CHECK/UNIQUE 约束；这不是生产 schema，也不冒充生产验收。
 */
@Import(PerfDashboardService.class)
class PerfDashboardPgIntegrationTest extends FranchisePgIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(9_500_000_000L + System.nanoTime() % 400_000_000L);
    private static final String PERIOD = "2026-11";
    private static final String MONTH_START = "2026-11-01 00:00:00";
    private static final String NEXT_MONTH_START = "2026-12-01 00:00:00";

    @Resource
    private PerfDashboardService service;
    @Resource
    private JdbcTemplate jdbc;

    private long orgId;
    private PostSnapshot post;

    @BeforeEach
    void setUp() {
        var store = fixture.store();
        orgId = store.orgId();
        post = fixture.post(orgId, "finance", null);
        createFixtureTables();
    }

    /** 四域最小夹具表（非生产 schema；生产 DDL 见 802 fr_crm / 814 fr_eng_core / 925 远程命令）。 */
    private void createFixtureTables() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_customer ("
                + "id bigint PRIMARY KEY, company_id bigint NOT NULL, owner_user_id bigint NOT NULL,"
                + "name varchar(64) NOT NULL, phone varchar(16) NOT NULL, source varchar(64) NOT NULL,"
                + "status varchar(16) NOT NULL CHECK (status IN ('REGISTERED','FOLLOWING')),"
                + "create_time timestamptz NOT NULL DEFAULT now(), update_time timestamptz NOT NULL DEFAULT now(),"
                + "UNIQUE (company_id, phone))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_lead ("
                + "id bigint PRIMARY KEY, company_id bigint NOT NULL, name varchar(64) NOT NULL,"
                + "phone varchar(16) NOT NULL, need varchar(500) NOT NULL, source varchar(64) NOT NULL,"
                + "status varchar(16) NOT NULL CHECK (status IN ('LINK_REQUIRED','CONVERTED','INVALID')),"
                + "conversion varchar(16) NULL, customer_id bigint NULL,"
                + "registrar_user_id bigint NOT NULL, registrar_post_id bigint NOT NULL,"
                + "create_time timestamptz NOT NULL DEFAULT now(), update_time timestamptz NOT NULL DEFAULT now(),"
                + "CHECK ((status = 'CONVERTED') = (conversion IS NOT NULL AND customer_id IS NOT NULL)))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_project ("
                + "id bigint PRIMARY KEY, company_id bigint NOT NULL,"
                + "source_type varchar(16) NOT NULL CHECK (source_type IN ('HANDOVER','MANUAL','INCREMENT')),"
                + "manager_user_id bigint NOT NULL, name varchar(128) NOT NULL,"
                + "status varchar(16) NOT NULL CHECK (status IN "
                + "('PENDING_START','IN_PROGRESS','READY_DELIVER','DELIVERED','ARCHIVED')),"
                + "author_user_id bigint NOT NULL,"
                + "create_time timestamptz NOT NULL DEFAULT now(), update_time timestamptz NOT NULL DEFAULT now())");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_design_remote_command ("
                + "command_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
                + "caller_app_id varchar(80) NOT NULL DEFAULT 'platform',"
                + "org_id bigint NOT NULL, tenant_id bigint NOT NULL DEFAULT 1,"
                + "operation varchar(64) NOT NULL, request_key varchar(200) NOT NULL DEFAULT '',"
                + "created_at timestamptz NOT NULL DEFAULT now())");
    }

    @Test
    void dashboardCountsCurrentMonthAndZeroFillsStatusBuckets() {
        insertCustomer(orgId, "REGISTERED", "2026-11-02 10:00:00");
        insertCustomer(orgId, "REGISTERED", "2026-11-20 10:00:00");
        insertCustomer(orgId, "FOLLOWING", "2026-11-05 10:00:00");
        insertLead(orgId, "2026-11-06 10:00:00");
        insertLead(orgId, "2026-11-07 10:00:00");
        insertProject(orgId, "PENDING_START", "2026-11-08 10:00:00");
        insertProject(orgId, "IN_PROGRESS", "2026-11-09 10:00:00");
        insertProject(orgId, "ARCHIVED", "2026-11-10 10:00:00");
        insertDesignCommand(orgId, "CREATE_PROJECT", "2026-11-11 10:00:00");

        Map<String, Object> view = service.dashboard(post, PERIOD);

        assertThat(view.get("period")).isEqualTo(PERIOD);
        Map<String, Object> crm = map(view.get("crm"));
        assertThat(number(crm.get("newCustomers"))).isEqualTo(3L);
        assertThat(number(crm.get("newLeads"))).isEqualTo(2L);
        Map<String, Object> customerStatus = map(crm.get("newCustomersByStatus"));
        assertThat(number(customerStatus.get("REGISTERED"))).isEqualTo(2L);
        assertThat(number(customerStatus.get("FOLLOWING"))).isEqualTo(1L);

        Map<String, Object> engineering = map(view.get("engineering"));
        assertThat(number(engineering.get("created"))).isEqualTo(3L);
        Map<String, Object> engineeringStatus = map(engineering.get("byStatus"));
        assertThat(number(engineeringStatus.get("PENDING_START"))).isEqualTo(1L);
        assertThat(number(engineeringStatus.get("IN_PROGRESS"))).isEqualTo(1L);
        assertThat(number(engineeringStatus.get("READY_DELIVER"))).isZero();
        assertThat(number(engineeringStatus.get("DELIVERED"))).isZero();
        assertThat(number(engineeringStatus.get("ARCHIVED"))).isEqualTo(1L);

        assertThat(number(map(view.get("design")).get("serviceCommissions"))).isEqualTo(1L);
    }

    @Test
    void dashboardUsesHalfOpenMonthBoundary() {
        insertCustomer(orgId, "REGISTERED", MONTH_START);                 // 月初零点：计入
        insertCustomer(orgId, "REGISTERED", "2026-10-31 23:59:59");       // 上月最后一秒：排除
        insertCustomer(orgId, "REGISTERED", NEXT_MONTH_START);            // 次月零点：排除
        insertDesignCommand(orgId, "CREATE_PROJECT", MONTH_START);
        insertDesignCommand(orgId, "CREATE_PROJECT", NEXT_MONTH_START);

        Map<String, Object> crm = map(service.dashboard(post, PERIOD).get("crm"));
        assertThat(number(crm.get("newCustomers"))).isEqualTo(1L);
        assertThat(number(map(service.dashboard(post, PERIOD).get("design")).get("serviceCommissions")))
                .isEqualTo(1L);
    }

    @Test
    void dashboardIsolatesOtherOrganizations() {
        insertCustomer(orgId, "REGISTERED", "2026-11-02 10:00:00");
        insertLead(orgId, "2026-11-02 10:00:00");
        insertProject(orgId, "PENDING_START", "2026-11-02 10:00:00");
        insertDesignCommand(orgId, "CREATE_PROJECT", "2026-11-02 10:00:00");

        long otherOrg = fixture.store().orgId();
        insertCustomer(otherOrg, "REGISTERED", "2026-11-03 10:00:00");
        insertLead(otherOrg, "2026-11-03 10:00:00");
        insertProject(otherOrg, "IN_PROGRESS", "2026-11-03 10:00:00");
        insertDesignCommand(otherOrg, "CREATE_PROJECT", "2026-11-03 10:00:00");

        Map<String, Object> view = service.dashboard(post, PERIOD);
        assertThat(number(map(view.get("crm")).get("newCustomers"))).isEqualTo(1L);
        assertThat(number(map(view.get("crm")).get("newLeads"))).isEqualTo(1L);
        assertThat(number(map(view.get("engineering")).get("created"))).isEqualTo(1L);
        assertThat(number(map(view.get("design")).get("serviceCommissions"))).isEqualTo(1L);
    }

    @Test
    void emptyPeriodReturnsZeroCountsWithAllBucketsPresent() {
        Map<String, Object> view = service.dashboard(post, "2031-01");

        Map<String, Object> crm = map(view.get("crm"));
        assertThat(number(crm.get("newCustomers"))).isZero();
        assertThat(number(crm.get("newLeads"))).isZero();
        assertThat(map(crm.get("newCustomersByStatus"))).containsOnlyKeys("REGISTERED", "FOLLOWING");

        Map<String, Object> engineering = map(view.get("engineering"));
        assertThat(number(engineering.get("created"))).isZero();
        assertThat(map(engineering.get("byStatus")))
                .containsOnlyKeys("PENDING_START", "IN_PROGRESS", "READY_DELIVER", "DELIVERED", "ARCHIVED");

        assertThat(number(map(view.get("design")).get("serviceCommissions"))).isZero();
    }

    @Test
    void engineeringGroupsByStatusAndCreatedEqualsBucketSum() {
        insertProject(orgId, "PENDING_START", "2026-11-01 08:00:00");
        insertProject(orgId, "PENDING_START", "2026-11-02 08:00:00");
        insertProject(orgId, "READY_DELIVER", "2026-11-03 08:00:00");
        insertProject(orgId, "DELIVERED", "2026-11-04 08:00:00");
        insertProject(orgId, "DELIVERED", "2026-11-05 08:00:00");
        insertProject(orgId, "DELIVERED", "2026-11-06 08:00:00");

        Map<String, Object> engineering = map(service.dashboard(post, PERIOD).get("engineering"));
        Map<String, Object> byStatus = map(engineering.get("byStatus"));
        assertThat(number(engineering.get("created"))).isEqualTo(6L);
        assertThat(number(byStatus.get("PENDING_START"))).isEqualTo(2L);
        assertThat(number(byStatus.get("READY_DELIVER"))).isEqualTo(1L);
        assertThat(number(byStatus.get("DELIVERED"))).isEqualTo(3L);
        long sum = byStatus.values().stream().mapToLong(PerfDashboardPgIntegrationTest::number).sum();
        assertThat(sum).isEqualTo(6L);
    }

    @Test
    void designCountsOnlyCreateProjectCommandsInPeriod() {
        insertDesignCommand(orgId, "CREATE_PROJECT", "2026-11-05 10:00:00");
        insertDesignCommand(orgId, "CREATE_FLAT_JOB", "2026-11-05 10:00:00");
        insertDesignCommand(orgId, "NOTE_POLISH", "2026-11-05 10:00:00");
        insertDesignCommand(orgId, "CREATE_PROJECT", "2026-10-31 23:59:59");
        long otherOrg = fixture.store().orgId();
        insertDesignCommand(otherOrg, "CREATE_PROJECT", "2026-11-05 10:00:00");

        Map<String, Object> design = map(service.dashboard(post, PERIOD).get("design"));
        assertThat(number(design.get("serviceCommissions"))).isEqualTo(1L);
    }

    @Test
    void malformedPeriodIsRejectedWithPerformancePeriodError() {
        Logger logger = (Logger) LoggerFactory.getLogger(ServiceExceptionUtil.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ServiceException error;
        try {
            error = catchThrowableOfType(() -> service.dashboard(post, "2026-13"), ServiceException.class);
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(1_116_000_008);
        // 不传多余参数：消息必须是错误码模板原文，且 doFormat 不得记录 ERROR。
        assertThat(error.getMessage()).isEqualTo("周期或日期不合法（period 格式/due 越月/快照须为过去月）");
        assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    // ===================== 夹具 =====================

    private long nextId() {
        return SEQ.incrementAndGet();
    }

    private void insertCustomer(long org, String status, String createTime) {
        long id = nextId();
        jdbc.update("INSERT INTO fr_customer (id, company_id, owner_user_id, name, phone, source, status, "
                        + "create_time, update_time) VALUES (?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
                id, org, post.userId(), "客户-" + id, "13" + id, "test", status, createTime, createTime);
    }

    private void insertLead(long org, String createTime) {
        long id = nextId();
        jdbc.update("INSERT INTO fr_lead (id, company_id, name, phone, need, source, status, registrar_user_id, "
                        + "registrar_post_id, create_time, update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
                id, org, "线索-" + id, "15" + id, "看房", "test", "LINK_REQUIRED", post.userId(),
                post.postId(), createTime, createTime);
    }

    private void insertProject(long org, String status, String createTime) {
        long id = nextId();
        jdbc.update("INSERT INTO fr_eng_project (id, company_id, source_type, manager_user_id, name, status, "
                        + "author_user_id, create_time, update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
                id, org, "MANUAL", post.userId(), "工程-" + id, status, post.userId(), createTime, createTime);
    }

    private void insertDesignCommand(long org, String operation, String createTime) {
        jdbc.update("INSERT INTO fr_design_remote_command (org_id, operation, created_at) "
                + "VALUES (?,?,?::timestamptz)", org, operation, createTime);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }
}
