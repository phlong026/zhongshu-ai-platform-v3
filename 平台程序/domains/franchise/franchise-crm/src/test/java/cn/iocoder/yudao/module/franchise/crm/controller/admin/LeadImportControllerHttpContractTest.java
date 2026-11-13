package cn.iocoder.yudao.module.franchise.crm.controller.admin;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.franchise.crm.LeadImportService;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerMapper;
import cn.iocoder.yudao.module.franchise.crm.dal.LeadMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.crm.LeadService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.authz.PostPermissionEvaluator;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1 批3：客资导入 HTTP 契约（B-only false 装配 + 生产异常链 + 注解 PII 开关）。
 * 权限=franchise:lead:handle；组织范围=当前岗位组织；boss/owner 角色校验在 Service（PG 测试覆盖）。
 */
class LeadImportControllerHttpContractTest {

    private static final long ORG = 151L;
    private static final PostSnapshot BOSS =
            new PostSnapshot(21L, 9L, ORG, "store", "boss", null, false, Set.of());
    private static final String ONE_ROW = "{\"orgId\":" + ORG + ",\"batchId\":\"b-test-1\",\"rows\":["
            + "{\"line\":1,\"name\":\"张三\",\"phone\":\"13800000000\","
            + "\"need\":\"三层小楼\",\"source\":\"门店自然流量\"}]}";

    private final CurrentPost currentPost = mock(CurrentPost.class);
    private final SystemDirectory directory = mock(SystemDirectory.class);
    private final LeadImportService service = mock(LeadImportService.class);

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withPropertyValues("platform.legacy-design-bridge.enabled=false")
            .withUserConfiguration(Security.class)
            .withBean(CurrentPost.class, () -> currentPost)
            .withBean(SystemDirectory.class, () -> directory)
            .withBean("post", PostPermissionEvaluator.class,
                    () -> new PostPermissionEvaluator(currentPost, directory))
            .withBean(LeadImportService.class, () -> service)
            .withBean(LeadImportController.class);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, List.of()));
        when(currentPost.find()).thenReturn(Optional.of(BOSS));
        when(currentPost.require()).thenReturn(BOSS);
        when(directory.roleHasPermission("boss", "franchise:lead:handle")).thenReturn(true);
        when(service.precheck(any(), eq("b-test-1"), any())).thenReturn(result("OK"));
        when(service.submit(any(), eq("b-test-1"), any())).thenReturn(result("CREATED"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Security {
    }

    private static LeadImportService.ImportResult result(String status) {
        return new LeadImportService.ImportResult(ORG, 1, 1, 0, 0,
                List.of(new LeadImportService.RowResult(1, "张三", "13800000000", "三层小楼",
                        "门店自然流量", status, null, "", null, null, null,
                        LeadImportService.ACTION_CREATE)));
    }

    private MockMvc mvc(org.springframework.context.ApplicationContext ctx) {
        return MockMvcBuilders.standaloneSetup(ctx.getBean(LeadImportController.class))
                .setControllerAdvice(new GlobalExceptionHandler("test", null))
                .build();
    }

    @Test
    void precheckUsesProductionEnvelope() {
        context.run(ctx -> mvc(ctx).perform(post("/franchise/lead-import/precheck")
                        .contentType("application/json").content(ONE_ROW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.created").value(1)));
    }

    @Test
    void missingHandlePermissionIsDeniedWithProductionEnvelope() {
        when(directory.roleHasPermission("boss", "franchise:lead:handle")).thenReturn(false);
        when(directory.roleHasPermission("boss", "franchise:performance:operate")).thenReturn(true);
        context.run(ctx -> mvc(ctx).perform(post("/franchise/lead-import/precheck")
                        .contentType("application/json").content(ONE_ROW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403)));
    }

    @Test
    void crossOrganizationScopeIsForbiddenWithProductionEnvelope() {
        String otherOrg = ONE_ROW.replace("\"orgId\":" + ORG, "\"orgId\":" + (ORG + 1));
        context.run(ctx -> mvc(ctx).perform(post("/franchise/lead-import/precheck")
                        .contentType("application/json").content(otherOrg))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1103000004)));
    }

    @Test
    void overLimitIsServiceAuthoritativeAndLogsNoPhone() throws Exception {
        // 501 行不再走 @Size（Bean Validation 会把整批值写进异常 message）；由 Service 抛业务 400。
        when(service.submit(any(), eq("b-test-1"), any()))
                .thenThrow(new cn.iocoder.yudao.framework.common.exception.ServiceException(400, "单批须为 1-500 行"));
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 501; i++) {
            if (i > 1) rows.append(',');
            rows.append("{\"line\":").append(i)
                    .append(",\"name\":\"员工\",\"phone\":\"1380000").append(String.format("%04d", i))
                    .append("\",\"need\":\"需求\",\"source\":\"来源\"}");
        }
        String body = "{\"orgId\":" + ORG + ",\"batchId\":\"b-test-1\",\"rows\":[" + rows + "]}";
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            context.run(ctx -> mvc(ctx).perform(post("/franchise/lead-import")
                            .contentType("application/json").content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400)));
        } finally {
            logger.detachAppender(appender);
        }
        String logs = appender.list.stream()
                .map(e -> String.valueOf(e.getFormattedMessage()) + " "
                        + (e.getThrowableProxy() == null ? "" : e.getThrowableProxy().getMessage()))
                .reduce("", String::concat);
        assertThat(logs).doesNotContain("1380000").doesNotContain("1380000000");
    }

    @Test
    void rowsCarriesNoBeanValidationThatEchoesValues() throws Exception {
        // P1 回归锁：行列表不得挂 @Size 等会把整批值写入异常 message 的约束。
        // 记录组件、字段、访问器三处都要查（注解可能只传播到 field/accessor）。
        var component = java.util.Arrays.stream(LeadImportController.ImportReq.class.getRecordComponents())
                .filter(c -> c.getName().equals("rows")).findFirst().orElseThrow();
        assertThat(component.getAnnotations()).isEmpty();
        assertThat(LeadImportController.ImportReq.class.getDeclaredField("rows").getAnnotations()).isEmpty();
        assertThat(LeadImportController.ImportReq.class.getMethod("rows").getAnnotations()).isEmpty();
    }

    @Test
    void endpointsDisableAccessLogPayloadsForPii() throws Exception {
        Method precheck = LeadImportController.class.getMethod("precheck",
                LeadImportController.ImportReq.class);
        Method submit = LeadImportController.class.getMethod("submit",
                LeadImportController.ImportReq.class);
        ApiAccessLog pre = precheck.getAnnotation(ApiAccessLog.class);
        ApiAccessLog sub = submit.getAnnotation(ApiAccessLog.class);
        assertThat(pre).isNotNull();
        assertThat(pre.enable()).isFalse();
        assertThat(sub).isNotNull();
        assertThat(sub.requestEnable()).isFalse();
        assertThat(precheck.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("@post.has('franchise:lead:handle')");
    }

    @Test
    void controllerAndServiceAreBOnly() {
        for (Class<?> type : List.of(LeadImportController.class, LeadImportService.class)) {
            ConditionalOnProperty cond = type.getAnnotation(ConditionalOnProperty.class);
            assertThat(cond).as(type.getSimpleName()).isNotNull();
            assertThat(cond.name()).containsExactly("platform.legacy-design-bridge.enabled");
            assertThat(cond.havingValue()).isEqualTo("false");
            assertThat(cond.matchIfMissing()).isFalse();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(LeadImportController.class)
    static class ControllerWiring {
    }

    @Configuration(proxyBeanMethods = false)
    @Import(LeadImportService.class)
    static class ServiceWiring {
    }

    @Test
    void bOnlyAssemblyThreeStatesForControllerAndService() {
        ApplicationContextRunner controllerRunner = new ApplicationContextRunner()
                .withUserConfiguration(ControllerWiring.class)
                .withBean(CurrentPost.class, () -> mock(CurrentPost.class))
                .withBean(LeadImportService.class, () -> mock(LeadImportService.class));
        controllerRunner.run(ctx -> assertThat(ctx).doesNotHaveBean(LeadImportController.class));
        controllerRunner.withPropertyValues("platform.legacy-design-bridge.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(LeadImportController.class));
        controllerRunner.withPropertyValues("platform.legacy-design-bridge.enabled=false")
                .run(ctx -> assertThat(ctx).hasSingleBean(LeadImportController.class));

        ApplicationContextRunner serviceRunner = new ApplicationContextRunner()
                .withUserConfiguration(ServiceWiring.class)
                .withBean(LeadService.class, () -> mock(LeadService.class))
                .withBean(LeadMapper.class, () -> mock(LeadMapper.class))
                .withBean(CustomerMapper.class, () -> mock(CustomerMapper.class))
                .withBean(FranchiseCommands.class, () -> mock(FranchiseCommands.class));
        serviceRunner.run(ctx -> assertThat(ctx).doesNotHaveBean(LeadImportService.class));
        serviceRunner.withPropertyValues("platform.legacy-design-bridge.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(LeadImportService.class));
        serviceRunner.withPropertyValues("platform.legacy-design-bridge.enabled=false")
                .run(ctx -> assertThat(ctx).hasSingleBean(LeadImportService.class));
    }
}
