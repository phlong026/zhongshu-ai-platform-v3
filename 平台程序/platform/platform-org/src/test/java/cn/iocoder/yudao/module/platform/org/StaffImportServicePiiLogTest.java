package cn.iocoder.yudao.module.platform.org;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.iocoder.yudao.module.platform.org.dal.OrgUnitDO;
import cn.iocoder.yudao.module.platform.org.dal.PostTemplateDO;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** P1-2：行级未知异常日志不得输出异常 message/Throwable（可能含手机号）；行结果仍为 500 通用消息。 */
class StaffImportServicePiiLogTest {

    private static final long ORG = 1L;
    private static final long TEAM = 10L;
    private static final String MOBILE = "13800138000";

    @Test
    void rowFailureLogOmitsMobileAndReturnsGeneric500() {
        StaffPostService staffPostService = mock(StaffPostService.class);
        OrgService orgService = mock(OrgService.class);
        SystemDirectory directory = mock(SystemDirectory.class);
        when(orgService.listTeams(ORG)).thenReturn(List.of(new OrgUnitDO().setDeptId(TEAM)));
        when(orgService.listPostTemplates(ORG)).thenReturn(List.of(new PostTemplateDO().setRoleCode("sales")));
        when(directory.deptName(TEAM)).thenReturn("业务一组");
        when(directory.userIdByMobile(MOBILE)).thenReturn(null);
        when(staffPostService.createStaffInOrg(eq(ORG), anyString(), eq(MOBILE), eq("sales"), eq(TEAM),
                anyLong())).thenThrow(new RuntimeException("导入失败：手机号 " + MOBILE + " 已存在"));

        Logger logger = (Logger) LoggerFactory.getLogger(StaffImportService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        StaffImportService.ImportResult result;
        try {
            result = new StaffImportService(staffPostService, orgService, directory).submit(ORG,
                    List.of(new StaffImportService.Row(1, "张三", MOBILE, "sales", "业务一组")), 99L);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("FAILED");
            assertThat(row.code()).isEqualTo(500);
            assertThat(row.message()).isEqualTo("该行处理失败，请稍后重试");
        });
        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(MOBILE);
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertThat(appender.list).anySatisfy(event ->
                assertThat(event.getFormattedMessage()).contains("java.lang.RuntimeException").contains("500"));
    }

    @Test
    void contextFailureReturnsSafeFailedRowsWithoutLoggingMobile() {
        StaffPostService staffPostService = mock(StaffPostService.class);
        OrgService orgService = mock(OrgService.class);
        SystemDirectory directory = mock(SystemDirectory.class);
        when(orgService.listTeams(ORG)).thenThrow(new RuntimeException("teams failed for " + MOBILE));

        Logger logger = (Logger) LoggerFactory.getLogger(StaffImportService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        StaffImportService.ImportResult result;
        try {
            result = new StaffImportService(staffPostService, orgService, directory)
                    .precheck(ORG, List.of(new StaffImportService.Row(1, "张三", MOBILE, "sales", "业务一组")));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("FAILED");
            assertThat(row.code()).isEqualTo(500);
            assertThat(row.message()).isEqualTo("批次加载失败（服务暂不可用），请整批稍后重试");
        });
        assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(MOBILE);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void submitSuccessLogsCountsWithoutPii() {
        StaffPostService staffPostService = mock(StaffPostService.class);
        OrgService orgService = mock(OrgService.class);
        SystemDirectory directory = mock(SystemDirectory.class);
        when(orgService.listTeams(ORG)).thenReturn(List.of(new OrgUnitDO().setDeptId(TEAM)));
        when(orgService.listPostTemplates(ORG)).thenReturn(List.of(new PostTemplateDO().setRoleCode("sales")));
        when(directory.deptName(TEAM)).thenReturn("业务一组");
        when(directory.userIdByMobile(MOBILE)).thenReturn(null);

        Logger logger = (Logger) LoggerFactory.getLogger(StaffImportService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        StaffImportService.ImportResult result;
        try {
            result = new StaffImportService(staffPostService, orgService, directory).submit(ORG,
                    List.of(new StaffImportService.Row(1, "张三", MOBILE, "sales", "业务一组")), 99L);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(result.created()).isEqualTo(1);
        assertThat(appender.list).anySatisfy(event -> {
            String msg = event.getFormattedMessage();
            assertThat(msg).contains("staff-import][submitted]").contains("orgId=1")
                    .contains("operatorUserId=99").contains("total=1").contains("created=1")
                    .contains("skipped=0").contains("failed=0");
            assertThat(msg).doesNotContain(MOBILE).doesNotContain("张三");
        });
    }

    @Test
    void precheckDoesNotLogSubmittedSummary() {
        StaffPostService staffPostService = mock(StaffPostService.class);
        OrgService orgService = mock(OrgService.class);
        SystemDirectory directory = mock(SystemDirectory.class);
        when(orgService.listTeams(ORG)).thenReturn(List.of(new OrgUnitDO().setDeptId(TEAM)));
        when(orgService.listPostTemplates(ORG)).thenReturn(List.of(new PostTemplateDO().setRoleCode("sales")));
        when(directory.deptName(TEAM)).thenReturn("业务一组");
        when(directory.userIdByMobile(MOBILE)).thenReturn(null);

        Logger logger = (Logger) LoggerFactory.getLogger(StaffImportService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new StaffImportService(staffPostService, orgService, directory).precheck(ORG,
                    List.of(new StaffImportService.Row(1, "张三", MOBILE, "sales", "业务一组")));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).noneSatisfy(event ->
                assertThat(event.getFormattedMessage()).contains("staff-import][submitted]"));
    }

    @Test
    void checkRowFailureReturnsSafeFailedRowWithoutLoggingMobile() {
        StaffPostService staffPostService = mock(StaffPostService.class);
        OrgService orgService = mock(OrgService.class);
        SystemDirectory directory = mock(SystemDirectory.class);
        when(orgService.listTeams(ORG)).thenReturn(List.of(new OrgUnitDO().setDeptId(TEAM)));
        when(orgService.listPostTemplates(ORG)).thenReturn(List.of(new PostTemplateDO().setRoleCode("sales")));
        when(directory.deptName(TEAM)).thenReturn("业务一组");
        when(directory.userIdByMobile(MOBILE)).thenReturn(42L);
        when(staffPostService.listActivePosts(42L))
                .thenThrow(new RuntimeException("post lookup failed for " + MOBILE));

        Logger logger = (Logger) LoggerFactory.getLogger(StaffImportService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        StaffImportService.ImportResult result;
        try {
            result = new StaffImportService(staffPostService, orgService, directory)
                    .precheck(ORG, List.of(new StaffImportService.Row(1, "张三", MOBILE, "sales", "业务一组")));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("FAILED");
            assertThat(row.code()).isEqualTo(500);
            assertThat(row.message()).isEqualTo("该行处理失败，请稍后重试");
        });
        assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(MOBILE);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }
}
