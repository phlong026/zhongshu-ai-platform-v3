package cn.iocoder.yudao.module.platform.wallet.export;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
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
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0 批3：CSV 导出 POST /franchise/finance/exports 的 HTTP 契约 + B-only 装配负例。
 *
 * <p>装配方式与批2 FinancePageHttpContractTest 一致：ApplicationContextRunner +
 * @EnableMethodSecurity 让 @post.has 真源代理生效；MockMvc standaloneSetup 挂真实
 * GlobalExceptionHandler（L1 生产异常链），所有业务错误按生产形态落 HTTP 200 +
 * CommonResult.code（400 参数/405 方法/403 越权）。
 *
 * <p>导出 Service 在 HTTP 契约层为 Mockito mock（真实 SQL/PG 由 FinanceExportPgIntegrationTest
 * 覆盖，与批2「HTTP 契约 mock Mapper + 真实 PG 集成」分层一致）。Controller 的显式
 * validateFilters 为静态真实方法，故参数校验/5 万上限用例不受 Service mock 影响。
 *
 * <p>红期（已落盘 red-tests.log）测试用反射按类名探测尚不存在的生产类，产生干净断言红
 * （端点 404 / 类不存在）；绿期改为编译期引用，形态演进如实登记于冻结 manifest。
 */
class FinanceExportHttpContractTest {

    private static final PostSnapshot FINANCE = new PostSnapshot(21L, 7L, 100L, "store", "finance", null, false, Set.of());

    private final CurrentPost currentPost = mock(CurrentPost.class);
    private final SystemDirectory directory = mock(SystemDirectory.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final OperateLogCommonApi operateLogApi = mock(OperateLogCommonApi.class);
    private final FinanceExportService exportService = mock(FinanceExportService.class);

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withPropertyValues("platform.legacy-design-bridge.enabled=false")
                .withUserConfiguration(Security.class)
                .withBean(CurrentPost.class, () -> currentPost)
                .withBean(SystemDirectory.class, () -> directory)
                .withBean("post", PostPermissionEvaluator.class, () -> new PostPermissionEvaluator(currentPost, directory))
                .withBean(DataSource.class, () -> dataSource)
                .withBean(OperateLogCommonApi.class, () -> operateLogApi)
                .withBean(FinanceExportService.class, () -> exportService)
                .withBean(FinanceExportController.class);
    }

    /** 挂生产 GlobalExceptionHandler：错误统一 HTTP 200 + CommonResult.code。 */
    private MockMvc mvc(org.springframework.context.ApplicationContext ctx) {
        return MockMvcBuilders.standaloneSetup(ctx.getBean(FinanceExportController.class))
                .setControllerAdvice(new GlobalExceptionHandler("test", mock(ApiErrorLogCommonApi.class)))
                .build();
    }

    /** 绿 stub：Service 返回 UTF-8 BOM + §8.2 实收表头的 CSV。 */
    private static byte[] receiptsCsv() {
        String header = "\"收款单号\",\"项目\",\"客户\",\"来源类型\",\"来源单据\",\"名目\",\"金额（元）\","
                + "\"状态\",\"业务日期\",\"登记时间\",\"登记人\",\"收款方式\",\"交易号\",\"凭证\","
                + "\"修订版本\",\"作废原因\",\"作废人\"\r\n";
        byte[] head = header.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[3 + head.length];
        out[0] = (byte) 0xEF; out[1] = (byte) 0xBB; out[2] = (byte) 0xBF;
        System.arraycopy(head, 0, out, 3, head.length);
        return out;
    }

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, List.of()));
        when(currentPost.find()).thenReturn(Optional.of(FINANCE));
        when(currentPost.require()).thenReturn(FINANCE);
        when(directory.roleHasPermission("finance", "franchise:fin:query")).thenReturn(true);
        when(exportService.export(any(), any(), eq(100L), eq(7L))).thenReturn(receiptsCsv());
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
    @Import(FinanceExportController.class)
    static class ControllerWiring {
    }

    /** 经 @Import 交给 Spring 条件求值器，使 Service 上的 @ConditionalOnProperty 真实生效。 */
    @Configuration(proxyBeanMethods = false)
    @Import(FinanceExportService.class)
    static class ServiceWiring {
    }

    // ===== 端点 / CSV BOM / §8.2 表头 =====

    @Test
    void exportEndpointReturnsCsvWithUtf8BomAndReceiptsHeader() {
        context().run(ctx -> {
            byte[] body = mvc(ctx).perform(post("/franchise/finance/exports").param("table", "receipts"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(body[0]).isEqualTo((byte) 0xEF);
            assertThat(body[1]).isEqualTo((byte) 0xBB);
            assertThat(body[2]).isEqualTo((byte) 0xBF);
            String head = new String(body, 3, Math.min(body.length - 3, 600), StandardCharsets.UTF_8);
            assertThat(head).contains("收款单号", "项目", "客户", "来源类型", "来源单据", "名目",
                    "金额（元）", "状态", "业务日期", "登记时间", "登记人", "收款方式", "交易号",
                    "凭证", "修订版本", "作废原因", "作废人");
        });
    }

    @Test
    void exportResponseCarriesCsvHeadersAndNoStore() {
        context().run(ctx -> mvc(ctx).perform(post("/franchise/finance/exports").param("table", "receipts"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store"))));
    }

    // ===== 权限 / 显式参数校验 / 5 万上限 =====

    @Test
    void exportIsPostOnlyReturnsBusinessCode405() {
        context().run(ctx -> mvc(ctx).perform(get("/franchise/finance/exports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(405)));
    }

    @Test
    void readPermissionIsRequiredReturnsBusinessCode403() {
        when(directory.roleHasPermission("finance", "franchise:fin:query")).thenReturn(false);
        context().run(ctx -> mvc(ctx).perform(post("/franchise/finance/exports").param("table", "receipts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403)));
    }

    @Test
    void illegalTableIsRejectedWithBusinessCode400() {
        context().run(ctx -> mvc(ctx).perform(post("/franchise/finance/exports").param("table", "unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400)));
    }

    @Test
    void reversedDateRangeIsRejectedWithBusinessCode400() {
        context().run(ctx -> mvc(ctx).perform(post("/franchise/finance/exports")
                        .param("table", "receipts")
                        .param("receivedDateFrom", "2026-11-10").param("receivedDateTo", "2026-11-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400)));
    }

    @Test
    void illegalRefundDirectionIsRejectedWithBusinessCode400() {
        context().run(ctx -> mvc(ctx).perform(post("/franchise/finance/exports")
                        .param("table", "refunds").param("refundDirection", "unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400)));
    }

    // 5 万行上限由 Service 的 count(*) 前置短路在真实 PG 测试中覆盖
    // （FinanceExportPgIntegrationTest.fiveMillionRowLimitUsesDedicatedGuard）；
    // HTTP 契约层 Service 为 mock，不做 count 查询，故不在此重复断言。

    // ===== 审计：Controller 以正确 actor/orgId/筛选调用 Service（审计写入在 Service 内，另有单测） =====

    @Test
    void exportActionPassesActorOrgAndFiltersToService() {
        context().run(ctx -> {
            mvc(ctx).perform(post("/franchise/finance/exports").param("table", "receipts")
                            .param("status", "已登记"))
                    .andExpect(status().isOk());
            verify(exportService).export(eq("receipts"),
                    org.mockito.ArgumentMatchers.argThat(f -> "已登记".equals(f.get("status"))),
                    eq(100L), eq(7L));
        });
    }

    // ===== 装配负例：@ConditionalOnProperty(havingValue="false") 真实求值 =====

    @Test
    void controllerCarriesBOnlyConditionalAnnotation() {
        ConditionalOnProperty cond = AnnotatedElementUtils.findMergedAnnotation(
                FinanceExportController.class, ConditionalOnProperty.class);
        assertThat(cond).isNotNull();
        assertThat(cond.name()).containsExactly("platform.legacy-design-bridge.enabled");
        assertThat(cond.havingValue()).isEqualTo("false");
        assertThat(cond.matchIfMissing()).isFalse();
    }

    @Test
    void serviceCarriesBOnlyConditionalAnnotation() {
        ConditionalOnProperty cond = AnnotatedElementUtils.findMergedAnnotation(
                FinanceExportService.class, ConditionalOnProperty.class);
        assertThat(cond).isNotNull();
        assertThat(cond.havingValue()).isEqualTo("false");
        assertThat(cond.matchIfMissing()).isFalse();
    }

    // ===== Codex P2①：Controller + Service 均验证 absent / true / false 三档 =====
    // B 独立进程 = false（装配）；旧融合 yudao-server = absent（资源目录零命中）或显式 true（零 bean）。

    /**
     * 构造装配 runner；property 为 null 表示属性缺省（旧融合缺省形态）。
     * preRegisterService=true 仅用于 Controller wiring（补其 @Resource 依赖）；
     * Service wiring 必须 false，否则 withBean 绕过条件注解会让「零 bean」断言假失败。
     */
    private ApplicationContextRunner assemblyRunner(String property, Class<?> wiring, boolean preRegisterService) {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(wiring);
        if (property != null) {
            runner = runner.withPropertyValues("platform.legacy-design-bridge.enabled=" + property);
        }
        if (preRegisterService) {
            runner = runner.withBean(FinanceExportService.class, () -> mock(FinanceExportService.class));
        }
        return runner
                .withBean(CurrentPost.class, () -> mock(CurrentPost.class))
                // 补齐 Service 的 @Resource 依赖，避免 CommonAnnotationBeanPostProcessor 注入失败
                .withBean(OperateLogCommonApi.class, () -> mock(OperateLogCommonApi.class))
                .withBean(DataSource.class, () -> mock(DataSource.class));
    }

    @Test
    void controllerAbsentPropertyHasNoBean() {
        assemblyRunner(null, ControllerWiring.class, true)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(FinanceExportController.class));
    }

    @Test
    void controllerTruePropertyHasNoBean() {
        assemblyRunner("true", ControllerWiring.class, true)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(FinanceExportController.class));
    }

    @Test
    void controllerFalsePropertyRegistersBean() {
        assemblyRunner("false", ControllerWiring.class, true)
                .run(ctx -> assertThat(ctx).hasSingleBean(FinanceExportController.class));
    }

    @Test
    void serviceAbsentPropertyHasNoBean() {
        assemblyRunner(null, ServiceWiring.class, false)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(FinanceExportService.class));
    }

    @Test
    void serviceTruePropertyHasNoBean() {
        assemblyRunner("true", ServiceWiring.class, false)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(FinanceExportService.class));
    }

    @Test
    void serviceFalsePropertyRegistersBean() {
        assemblyRunner("false", ServiceWiring.class, false)
                .run(ctx -> assertThat(ctx).hasSingleBean(FinanceExportService.class));
    }

    // ===== L1：生产异常链断言（HTTP 200 + code，非异常逃逸） =====

    @Test
    void productionExceptionAdviceReturnsHttp200WithBusinessCode400() {
        context().run(ctx -> mvc(ctx).perform(post("/franchise/finance/exports").param("table", "illegal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400)));
    }
}
