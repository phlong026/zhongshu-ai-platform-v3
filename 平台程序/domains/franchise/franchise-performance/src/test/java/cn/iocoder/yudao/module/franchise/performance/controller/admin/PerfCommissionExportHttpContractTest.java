package cn.iocoder.yudao.module.franchise.performance.controller.admin;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.logger.dto.OperateLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.franchise.performance.content.PerfCommissionExportService;
import cn.iocoder.yudao.module.franchise.performance.content.PerfCommissionService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.authz.PostPermissionEvaluator;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1 尾批③：绩效报表导出 GET /franchise/performance/commission/export 的 HTTP 契约 + B-only 装配。
 *
 * <p>装配方式同 P0 批3 导出与批1 看板：ApplicationContextRunner + @EnableMethodSecurity 让
 * {@code @post.has} 真源代理生效；MockMvc standaloneSetup 挂真实 GlobalExceptionHandler，
 * 业务错误按生产形态落 HTTP 200 + CommonResult.code（400 参数/405 方法/403 越权/500 规则缺失）。
 *
 * <p>流式返回（Codex r1 P2-2）：成功分支为 {@code StreamingResponseBody}，MockMvc 按
 * asyncStarted → asyncDispatch 取响应体；prepare 阶段异常仍同步走异常链。
 * Service 在契约层为 mock（真实聚合/流式为 PG 集成测试覆盖）。
 */
class PerfCommissionExportHttpContractTest {

    private static final PostSnapshot FINANCE =
            new PostSnapshot(21L, 7L, 100L, "store", "finance", null, false, Set.of());

    private final CurrentPost currentPost = mock(CurrentPost.class);
    private final SystemDirectory directory = mock(SystemDirectory.class);
    private final PerfCommissionExportService exportService = mock(PerfCommissionExportService.class);
    private final PerfCommissionService commissions = mock(PerfCommissionService.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final OperateLogCommonApi audit = mock(OperateLogCommonApi.class);

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, List.of()));
        when(currentPost.find()).thenReturn(java.util.Optional.of(FINANCE));
        when(currentPost.require()).thenReturn(FINANCE);
        when(directory.roleHasPermission("finance", "franchise:performance:commission")).thenReturn(true);
        when(exportService.prepare(any(), eq("2026-11"))).thenReturn(plan());
        doAnswer(invocation -> {
            java.io.OutputStream out = invocation.getArgument(2);
            out.write(csv());
            return null;
        }).when(exportService).writeCsv(any(), any(), any());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Security {
    }

    /** 经 @Import 交给 Spring 条件求值器，使 Controller 上的 @ConditionalOnProperty 真实生效。 */
    @Configuration(proxyBeanMethods = false)
    @Import(PerfCommissionExportController.class)
    static class ControllerWiring {
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PerfCommissionExportService.class)
    static class ServiceWiring {
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withPropertyValues("platform.legacy-design-bridge.enabled=false")
                .withUserConfiguration(Security.class, ControllerWiring.class)
                .withBean(CurrentPost.class, () -> currentPost)
                .withBean(SystemDirectory.class, () -> directory)
                .withBean("post", PostPermissionEvaluator.class, () -> new PostPermissionEvaluator(currentPost, directory))
                .withBean(PerfCommissionExportService.class, () -> exportService);
    }

    private MockMvc mvc(org.springframework.context.ApplicationContext ctx) {
        return MockMvcBuilders.standaloneSetup(ctx.getBean(PerfCommissionExportController.class))
                .setControllerAdvice(new GlobalExceptionHandler("test", mock(ApiErrorLogCommonApi.class)))
                .build();
    }

    @Test
    void exportStreamsUtf8BomCsvWithAttachmentAndNoStore() throws Exception {
        context().run(ctx -> {
            MockMvc mvc = mvc(ctx);
            MvcResult pending = mvc.perform(get("/franchise/performance/commission/export")
                            .param("month", "2026-11"))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            mvc.perform(asyncDispatch(pending))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=performance-commission-2026-11.csv"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andDo(result -> {
                        byte[] body = result.getResponse().getContentAsByteArray();
                        assertThat(body).hasSizeGreaterThan(3);
                        assertThat(body[0]).isEqualTo((byte) 0xEF);
                        assertThat(body[1]).isEqualTo((byte) 0xBB);
                        assertThat(body[2]).isEqualTo((byte) 0xBF);
                    });
        });
        verify(exportService).writeCsv(any(), any(), any());
    }

    @Test
    void missingMonthFallsIntoTenantErrorCode400() {
        context().run(ctx -> mvc(ctx).perform(get("/franchise/performance/commission/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400)));
    }

    @Test
    void malformedMonthIsBadRequestWithReadableMessage() {
        context().run(ctx -> mvc(ctx).perform(get("/franchise/performance/commission/export")
                        .param("month", "2026-13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("月份格式须为 YYYY-MM")));
        verify(exportService, never()).prepare(any(), any());
    }

    @Test
    void missingCommissionPermissionIsDeniedWith403() {
        when(directory.roleHasPermission("finance", "franchise:performance:commission")).thenReturn(false);
        when(directory.roleHasPermission("finance", "franchise:performance:query")).thenReturn(true);
        context().run(ctx -> mvc(ctx).perform(get("/franchise/performance/commission/export")
                        .param("month", "2026-11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403)));
    }

    @Test
    void incompleteRuleDataIsServerErrorAndNeverStreamsCsv() throws Exception {
        when(exportService.prepare(any(), eq("2026-11")))
                .thenThrow(ServiceExceptionUtil.exception0(500, "提成规则数据不完整，导出已中止，请联系管理员核对"));
        context().run(ctx -> mvc(ctx).perform(get("/franchise/performance/commission/export")
                        .param("month", "2026-11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("提成规则数据不完整，导出已中止，请联系管理员核对")));
        verify(exportService, never()).writeCsv(any(), any(), any());
    }

    /**
     * Codex r3 P2-3：HTTP 层中途失败。真实 Service + mock JdbcTemplate：第一名员工行写出后、
     * 第二名员工处抛错（响应已提交）。断言：无 Content-Length（chunked，截断客户端可感知）、
     * 审计仅有首字节前 attempt、无 success；已写字节只是前缀
     * （含表头+第一名员工，缺第二名员工），不以完整 CSV 成功结束。
     */
    @Test
    void partialStreamFailureIsNotSuccessfulAndNeverWritesAudit() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        SingleConnectionDataSource single = new SingleConnectionDataSource(connection, true);
        when(jdbc.getDataSource()).thenReturn(single);
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(1L, 2L));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        String firstEmployeeName = "前缀员工" + "X".repeat(20_000);
        ResultSet first = releaseRow(1L, 1L, firstEmployeeName);
        ResultSet second = releaseRow(2L, 2L, "第二批员工");
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            handler.processRow(first);
            handler.processRow(second); // 第一名员工行写出后、第二名员工处理中异常
            throw new IllegalStateException("stream down");
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        Constructor<PerfCommissionExportService> ctor = PerfCommissionExportService.class
                .getDeclaredConstructor(JdbcTemplate.class, OperateLogCommonApi.class);
        ctor.setAccessible(true);
        PerfCommissionExportService realService = ctor.newInstance(jdbc, audit);

        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withPropertyValues("platform.legacy-design-bridge.enabled=false")
                .withUserConfiguration(Security.class, ControllerWiring.class)
                .withBean(CurrentPost.class, () -> currentPost)
                .withBean(SystemDirectory.class, () -> directory)
                .withBean("post", PostPermissionEvaluator.class,
                        () -> new PostPermissionEvaluator(currentPost, directory))
                .withBean(PerfCommissionExportService.class, () -> realService);

        runner.run(ctx -> {
            MockMvc mvc = mvc(ctx);
            MvcResult pending = mvc.perform(get("/franchise/performance/commission/export")
                            .param("month", "2026-11"))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            MvcResult dispatched = null;
            Throwable dispatchError = null;
            try {
                dispatched = mvc.perform(asyncDispatch(pending)).andReturn();
            } catch (Throwable error) {
                dispatchError = error;
            }
            MvcResult observed = dispatched != null ? dispatched : pending;

            // 没有 Content-Length → 容器以 chunked 下发；异常导致末尾 chunk 缺失，客户端判定下载失败
            assertThat(observed.getResponse().getHeader("Content-Length")).isNull();
            byte[] partial = observed.getResponse().getContentAsByteArray();
            String text = new String(partial, StandardCharsets.UTF_8);
            assertThat(text).startsWith("\uFEFF");
            // 表头 + 第一名员工行已开始写出（长姓名被 buffer 截断），第二名员工未写出
            assertThat(text).contains("\"1\",\"前缀员工");
            assertThat(text).doesNotContain("第二批员工");
            // 末行不完整（非 CRLF 结尾）→ 前缀不构成完整 CSV
            assertThat(text.endsWith("\r\n")).isFalse();
            assertThat(text.substring(1).split("\r\n", -1)).hasSizeLessThan(4);
            // 若容器未把异常重新抛出，至少不能再以 2xx 正常完成
            if (dispatchError == null) {
                int status = observed.getResponse().getStatus();
                assertThat(status >= 200 && status < 300).isFalse();
            }
        });
        ArgumentCaptor<OperateLogCreateReqDTO> auditCaptor = ArgumentCaptor.forClass(OperateLogCreateReqDTO.class);
        verify(audit).createOperateLog(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAction())
                .contains("状态=attempt").doesNotContain("状态=success");
    }

    private static ResultSet releaseRow(long projectId, long employeeId, String employeeName) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("policy_id", Long.class)).thenReturn(1L);
        when(rs.getLong("assignee_user_id")).thenReturn(employeeId);
        when(rs.getString("assignee_name")).thenReturn(employeeName);
        when(rs.getObject("policy_update_time", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.parse("2026-11-01T00:00:00Z"));
        when(rs.getLong("total_cents")).thenReturn(100L);
        when(rs.getLong("start_cents")).thenReturn(100L);
        when(rs.getLong("project_id")).thenReturn(projectId);
        when(rs.getString("stage")).thenReturn("SIGN_AFTER");
        when(rs.getObject("current_version", Integer.class)).thenReturn(1);
        when(rs.getObject("policy_version", Integer.class)).thenReturn(1);
        when(rs.getString("receipt_signature")).thenReturn("sig");
        return rs;
    }

    @Test
    void exportIsGetOnly() {
        context().run(ctx -> mvc(ctx).perform(post("/franchise/performance/commission/export")
                        .param("month", "2026-11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(405)));
    }

    @Test
    void preAuthorizeUsesCommissionPermission() throws Exception {
        Method method = PerfCommissionExportController.class.getMethod("export", String.class);
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("@post.has('franchise:performance:commission')");
    }

    @Test
    void controllerAndServiceAreBOnly() {
        for (Class<?> type : List.of(PerfCommissionExportController.class, PerfCommissionExportService.class)) {
            ConditionalOnProperty cond = type.getAnnotation(ConditionalOnProperty.class);
            assertThat(cond).as(type.getSimpleName()).isNotNull();
            assertThat(cond.name()).containsExactly("platform.legacy-design-bridge.enabled");
            assertThat(cond.havingValue()).isEqualTo("false");
            assertThat(cond.matchIfMissing()).isFalse();
        }
    }

    @Test
    void bOnlyAssemblyThreeStatesForControllerAndService() {
        ApplicationContextRunner controllerRunner = new ApplicationContextRunner()
                .withUserConfiguration(ControllerWiring.class)
                .withBean(CurrentPost.class, () -> currentPost)
                .withBean(PerfCommissionExportService.class, () -> exportService);
        controllerRunner.run(ctx -> assertThat(ctx).doesNotHaveBean(PerfCommissionExportController.class));
        controllerRunner.withPropertyValues("platform.legacy-design-bridge.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(PerfCommissionExportController.class));
        controllerRunner.withPropertyValues("platform.legacy-design-bridge.enabled=false")
                .run(ctx -> assertThat(ctx).hasSingleBean(PerfCommissionExportController.class));

        ApplicationContextRunner serviceRunner = new ApplicationContextRunner()
                .withUserConfiguration(ServiceWiring.class)
                .withBean(DataSource.class, () -> dataSource)
                .withBean(PerfCommissionService.class, () -> commissions)
                .withBean(OperateLogCommonApi.class, () -> audit);
        serviceRunner.run(ctx -> assertThat(ctx).doesNotHaveBean(PerfCommissionExportService.class));
        serviceRunner.withPropertyValues("platform.legacy-design-bridge.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(PerfCommissionExportService.class));
        serviceRunner.withPropertyValues("platform.legacy-design-bridge.enabled=false")
                .run(ctx -> assertThat(ctx).hasSingleBean(PerfCommissionExportService.class));
    }

    private static PerfCommissionExportService.ExportPlan plan() {
        return new PerfCommissionExportService.ExportPlan(
                "2026-11", "2026-11-01 00:00:00", "2026-12-01 00:00:00", List.of(1L));
    }

    /** 绿档 stub：UTF-8 BOM + 10 列表头（内容细节由纯函数/PG 测试覆盖）。 */
    private static byte[] csv() {
        String text = String.join(",", PerfCommissionExportService.header().stream()
                .map(h -> "\"" + h + "\"").toList()) + "\r\n";
        byte[] head = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[3 + head.length];
        out[0] = (byte) 0xEF;
        out[1] = (byte) 0xBB;
        out[2] = (byte) 0xBF;
        System.arraycopy(head, 0, out, 3, head.length);
        return out;
    }
}
