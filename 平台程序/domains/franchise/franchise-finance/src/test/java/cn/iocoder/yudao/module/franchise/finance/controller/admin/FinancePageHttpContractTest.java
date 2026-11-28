package cn.iocoder.yudao.module.franchise.finance.controller.admin;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.CommissionQueryPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.finance.FinAbnormalFundService;
import cn.iocoder.yudao.module.franchise.finance.FinAttemptRecorder;
import cn.iocoder.yudao.module.franchise.finance.FinBankService;
import cn.iocoder.yudao.module.franchise.finance.FinFileService;
import cn.iocoder.yudao.module.franchise.finance.FinPaymentService;
import cn.iocoder.yudao.module.franchise.finance.FinProjectAccountService;
import cn.iocoder.yudao.module.franchise.finance.FinReceiptService;
import cn.iocoder.yudao.module.franchise.finance.FinReconciliationService;
import cn.iocoder.yudao.module.franchise.finance.FinRefundService;
import cn.iocoder.yudao.module.franchise.finance.dal.FinAbnormalFundMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinBankConfirmMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinBankFlowMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinMatchAttemptMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPayableMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptRevisionMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinRefundMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinTxnNoMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinVoucherMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FixedCurrentPost;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.authz.PostPermissionEvaluator;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0 批2：财务账表分页 + 组合筛选「只读 GET」HTTP 契约（红期=端点未注册 404，绿期=200）。
 *
 * <p>装配方式与 WalletControllerSecurityTest / OperationsConfigurationHttpTest 一致：
 * ApplicationContextRunner + @EnableMethodSecurity 让 @post.has 真源代理生效，
 * MockMvc standaloneSetup 使用被代理的 Controller bean。FinanceController 为 @Resource 字段注入，
 * 因此把其 16 个依赖及 Service 级联依赖全部注册为 mock bean（均为接口/Mapper，无更深级联），
 * 仅验证「端点形态 / 权限 / 参数校验 / 旧端点兼容」，真实 SQL 由 FinancePageMapperIntegrationTest 覆盖。
 */
class FinancePageHttpContractTest {

    @Test
    void legacyRefundFilterMatchesLargeLongByValue() {
        Long stored = Long.valueOf("9223372036854775000");
        Long requested = Long.valueOf("9223372036854775000");

        assertThat(FinanceController.sameReceiptId(stored, requested)).isTrue();
    }

    @Test
    void financialCreationRequiresIdempotencyHeaderAtHttpBoundary() {
        when(directory.roleHasPermission("finance", "franchise:fin:register")).thenReturn(true);
        context.run(ctx -> mvc(ctx).perform(post("/franchise/finance/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"manual\",\"sourceRef\":\"R-1\",\"name\":\"实收\",\"amountCents\":100}"))
                .andExpect(status().isBadRequest()));
    }

    private static final PostSnapshot FINANCE = new PostSnapshot(21L, 7L, 100L, "store", "finance", null, false, Set.of());

    @Test
    void refundConfirmationEndpointsRequireRegisterPermissionAndPreserveStringIds() {
        context.run(ctx -> {
            var service = ctx.getBean(FinRefundService.class);
            when(service.registrationResult(FINANCE, "original-key", false)).thenReturn(java.util.Map.of("refundId", "9223372036854775000"));
            when(service.registrationResult(FINANCE, "original-key", true)).thenReturn(java.util.Map.of("refundId", "9223372036854775001"));
            for (var path : List.of("refunds", "expense-refunds")) {
                assertThatThrownBy(() -> mvc(ctx).perform(get("/franchise/finance/" + path + "/registration-result")
                        .param("key", "original-key"))).hasRootCauseInstanceOf(AccessDeniedException.class);
            }
            when(directory.roleHasPermission("finance", "franchise:fin:register")).thenReturn(true);
            mvc(ctx).perform(get("/franchise/finance/refunds/registration-result").param("key", "original-key"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.refundId").value("9223372036854775000"));
            mvc(ctx).perform(get("/franchise/finance/expense-refunds/registration-result").param("key", "original-key"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.refundId").value("9223372036854775001"));
            mvc(ctx).perform(get("/franchise/finance/refunds/registration-result")).andExpect(status().isBadRequest());
        });
    }

    private final FixedCurrentPost currentPost = new FixedCurrentPost();
    private final SystemDirectory directory = mock(SystemDirectory.class);
    private final FinReceiptMapper receiptMapper = mock(FinReceiptMapper.class);
    private final FinPayableMapper payableMapper = mock(FinPayableMapper.class);
    private final FinPaymentMapper paymentMapper = mock(FinPaymentMapper.class);
    private final FinRefundMapper refundMapper = mock(FinRefundMapper.class);
    private final FinAbnormalFundMapper abnormalFundMapper = mock(FinAbnormalFundMapper.class);

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Security.class)
            // 授权真源
            .withBean(CurrentPost.class, () -> currentPost)
            .withBean(SystemDirectory.class, () -> directory)
            .withBean("post", PostPermissionEvaluator.class, () -> new PostPermissionEvaluator(currentPost, directory))
            // FinanceController 的 5 个列表 Mapper（断言 verify）
            .withBean(FinReceiptMapper.class, () -> receiptMapper)
            .withBean(FinPayableMapper.class, () -> payableMapper)
            .withBean(FinPaymentMapper.class, () -> paymentMapper)
            .withBean(FinRefundMapper.class, () -> refundMapper)
            .withBean(FinAbnormalFundMapper.class, () -> abnormalFundMapper)
            // FinanceController 其余 @Resource
            .withBean(FinReceiptRevisionMapper.class, () -> mock(FinReceiptRevisionMapper.class))
            .withBean(FinBankFlowMapper.class, () -> mock(FinBankFlowMapper.class))
            .withBean(FinReceiptService.class, () -> mock(FinReceiptService.class))
            .withBean(FinRefundService.class, () -> mock(FinRefundService.class))
            .withBean(FinPaymentService.class, () -> mock(FinPaymentService.class))
            .withBean(FinBankService.class, () -> mock(FinBankService.class))
            .withBean(FinProjectAccountService.class, () -> mock(FinProjectAccountService.class))
            .withBean(cn.iocoder.yudao.module.franchise.finance.DesignFeeActionService.class, () -> mock(cn.iocoder.yudao.module.franchise.finance.DesignFeeActionService.class))
            .withBean(cn.iocoder.yudao.module.franchise.finance.FinProjectLedgerService.class, () -> mock(cn.iocoder.yudao.module.franchise.finance.FinProjectLedgerService.class))
            .withBean(cn.iocoder.yudao.module.franchise.finance.DesignFinanceLocks.class, () -> mock(cn.iocoder.yudao.module.franchise.finance.DesignFinanceLocks.class))
            .withBean(cn.iocoder.yudao.module.franchise.finance.FinTransactionIdentityGuard.class, () -> mock(cn.iocoder.yudao.module.franchise.finance.FinTransactionIdentityGuard.class))
            .withBean(org.springframework.jdbc.core.JdbcTemplate.class, () -> mock(org.springframework.jdbc.core.JdbcTemplate.class))
            .withBean(FinFileService.class, () -> mock(FinFileService.class))
            .withBean(FinAbnormalFundService.class, () -> mock(FinAbnormalFundService.class))
            .withBean(FinReconciliationService.class, () -> mock(FinReconciliationService.class))
            // Service 级联 @Resource 所需（接口/Mapper mock，无更深级联）
            .withBean(FinTxnNoMapper.class, () -> mock(FinTxnNoMapper.class))
            .withBean(FinVoucherMapper.class, () -> mock(FinVoucherMapper.class))
            .withBean(FinMatchAttemptMapper.class, () -> mock(FinMatchAttemptMapper.class))
            .withBean(FinBankConfirmMapper.class, () -> mock(FinBankConfirmMapper.class))
            .withBean(CommissionQueryPort.class, () -> mock(CommissionQueryPort.class))
            .withBean(AssetAccessPort.class, () -> mock(AssetAccessPort.class))
            .withBean(StoreServiceAccountPort.class, () -> mock(StoreServiceAccountPort.class))
            .withBean(FranchiseCommands.class, () -> mock(FranchiseCommands.class))
            .withBean(FinAttemptRecorder.class, () -> mock(FinAttemptRecorder.class))
            .withBean(FinanceController.class);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, List.of()));
        currentPost.use(FINANCE);
        // 财务岗默认持有只读查询权限
        when(directory.roleHasPermission("finance", "franchise:fin:query")).thenReturn(true);
        when(receiptMapper.selectByCompany(any(), any())).thenReturn(List.of());
        when(payableMapper.selectByCompany(any(), any())).thenReturn(List.of());
        when(paymentMapper.selectByCompany(any(), any())).thenReturn(List.of());
        when(refundMapper.selectByCompany(any())).thenReturn(List.of());
        when(abnormalFundMapper.selectByCompany(any(), any(), anyBoolean())).thenReturn(List.of());
        // 新分页端点：mock 返回空页，真实 SQL 由 FinancePageMapperIntegrationTest 覆盖
        when(receiptMapper.selectPage(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new cn.iocoder.yudao.framework.common.pojo.PageResult<>(List.of(), 0L));
        when(paymentMapper.selectPage(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new cn.iocoder.yudao.framework.common.pojo.PageResult<>(List.of(), 0L));
        when(refundMapper.selectPage(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new cn.iocoder.yudao.framework.common.pojo.PageResult<>(List.of(), 0L));
        when(payableMapper.selectPage(any(), any(), any(), any(), any()))
                .thenReturn(new cn.iocoder.yudao.framework.common.pojo.PageResult<>(List.of(), 0L));
        when(abnormalFundMapper.selectPage(any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(new cn.iocoder.yudao.framework.common.pojo.PageResult<>(List.of(), 0L));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        currentPost.clear();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Security {
    }

    private MockMvc mvc(org.springframework.context.ApplicationContext ctx) {
        return MockMvcBuilders.standaloneSetup(ctx.getBean(FinanceController.class)).build();
    }

    /** L1（批2 Codex P2①）：挂生产 GlobalExceptionHandler 的生产异常链，错误落 HTTP 200 + CommonResult.code。 */
    private MockMvc mvcWithAdvice(org.springframework.context.ApplicationContext ctx) {
        return MockMvcBuilders.standaloneSetup(ctx.getBean(FinanceController.class))
                .setControllerAdvice(new cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler(
                        "test", mock(cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi.class)))
                .build();
    }

    // ===== 红：5 个新 page 端点尚未注册（404）；绿：200 + CommonResult<PageResult> 形态 =====

    @Test
    void receiptsPageReturnsPagedResult() {
        context.run(ctx -> mvc(ctx).perform(get("/franchise/finance/receipts/page")
                        .param("pageNo", "1").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").exists())
                .andExpect(jsonPath("$.data.list").isArray()));
    }

    @Test
    void projectLedgerEvidencePreservesExactStringsUnderExistingReadPermission() {
        context.run(ctx -> {
            var ledger=ctx.getBean(cn.iocoder.yudao.module.franchise.finance.FinProjectLedgerService.class);
            var details=new cn.iocoder.yudao.module.franchise.finance.FinProjectLedgerService.Details("原费用","运输","运输商","公司账户","原流水","9007199254740995","原说明");
            var row=new cn.iocoder.yudao.module.franchise.finance.FinProjectLedgerService.Row(9007199254740993L,"PAYMENT",java.time.LocalDate.of(2026,11,28),1000,null,"已核实",null,"9007199254740993",details);
            when(ledger.page(FINANCE.orgId(),9L,1,10)).thenReturn(new cn.iocoder.yudao.module.franchise.finance.FinProjectLedgerService.Ledger(
                    new cn.iocoder.yudao.module.franchise.finance.FinProjectLedgerService.Summary(0,0,0,1000,0,1000),
                    new cn.iocoder.yudao.framework.common.pojo.PageResult<>(List.of(row),1L),"项目"));
            for(String role:List.of("boss","owner")) {
                currentPost.use(new PostSnapshot(21L,7L,FINANCE.orgId(),"store",role,null,false,Set.of()));
                assertThatThrownBy(()->mvc(ctx).perform(get("/franchise/finance/projects/9/ledger"))).hasRootCauseInstanceOf(AccessDeniedException.class);
                when(directory.roleHasPermission(role,"franchise:fin:query")).thenReturn(true);
                mvc(ctx).perform(get("/franchise/finance/projects/9/ledger")).andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.entries.list[0].recordId").value("9007199254740993"))
                        .andExpect(jsonPath("$.data.entries.list[0].type").value("PAYMENT"))
                        .andExpect(jsonPath("$.data.entries.list[0].details.voucherFileId").value("9007199254740995"))
                        .andExpect(jsonPath("$.data.entries.list[0].details.counterparty").value("运输商"));
            }
        });
    }

    @Test
    void legacyProjectAccountIncludesContractReceiptsFromUnifiedLedger() {
        context.run(ctx -> {
            var old=ctx.getBean(FinProjectAccountService.class);
            var ledger=ctx.getBean(cn.iocoder.yudao.module.franchise.finance.FinProjectLedgerService.class);
            when(old.projectAccount(FINANCE.orgId(),9L)).thenReturn(new FinProjectAccountService.ProjectAccount(9L,100L,200L,0L,300L));
            when(ledger.page(FINANCE.orgId(),9L,1,1)).thenReturn(new cn.iocoder.yudao.module.franchise.finance.FinProjectLedgerService.Ledger(
                    new cn.iocoder.yudao.module.franchise.finance.FinProjectLedgerService.Summary(30000L,26000L,210L,5150L,100L,4450L),
                    new cn.iocoder.yudao.framework.common.pojo.PageResult<>(List.of(),0L),"项目"));
            mvc(ctx).perform(get("/franchise/finance/project-accounts/9")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.receivedConfirmed").value(26000))
                    .andExpect(jsonPath("$.data.refundedNet").value(210))
                    .andExpect(jsonPath("$.data.paidConfirmed").value(5050));
        });
    }

    @Test
    void paymentsPageReturnsPagedResult() {
        context.run(ctx -> mvc(ctx).perform(get("/franchise/finance/payments/page")
                        .param("pageNo", "1").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").exists())
                .andExpect(jsonPath("$.data.list").isArray()));
    }

    @Test
    void refundsPageReturnsPagedResult() {
        context.run(ctx -> mvc(ctx).perform(get("/franchise/finance/refunds/page")
                        .param("pageNo", "1").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").exists())
                .andExpect(jsonPath("$.data.list").isArray()));
    }

    @Test
    void payablesPageReturnsPagedResult() {
        context.run(ctx -> mvc(ctx).perform(get("/franchise/finance/payables/page")
                        .param("pageNo", "1").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").exists())
                .andExpect(jsonPath("$.data.list").isArray()));
    }

    @Test
    void abnormalFundsPageReturnsPagedResult() {
        context.run(ctx -> mvc(ctx).perform(get("/franchise/finance/abnormal-funds/page")
                        .param("pageNo", "1").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").exists())
                .andExpect(jsonPath("$.data.list").isArray()));
    }

    // ===== 红：参数无效/越权/写方法断言（红期因端点未注册而失败，绿期逐一转绿） =====

    @Test
    void pageSizeAboveHundredIsRejected() {
        context.run(ctx -> {
            Throwable thrown = catchThrowable(() -> mvc(ctx).perform(get("/franchise/finance/receipts/page")
                    .param("pageNo", "1").param("pageSize", "101")));
            assertThat(thrown).hasRootCauseInstanceOf(ServiceException.class);
            Throwable root = thrown;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(((ServiceException) root).getCode()).isEqualTo(400);
        });
    }

    @Test
    void pageNoBelowOneIsRejected() {
        context.run(ctx -> assertThatThrownBy(() -> mvc(ctx).perform(get("/franchise/finance/receipts/page")
                        .param("pageNo", "0").param("pageSize", "10")))
                .hasRootCauseInstanceOf(ServiceException.class));
    }

    @Test
    void reversedDateRangeIsRejected() {
        context.run(ctx -> assertThatThrownBy(() -> mvc(ctx).perform(get("/franchise/finance/receipts/page")
                        .param("receivedDateFrom", "2026-11-10").param("receivedDateTo", "2026-11-01")))
                .hasRootCauseInstanceOf(ServiceException.class));
    }

    @Test
    void illegalRefundDirectionIsRejected() {
        context.run(ctx -> assertThatThrownBy(() -> mvc(ctx).perform(get("/franchise/finance/refunds/page")
                        .param("refundDirection", "unknown")))
                .hasRootCauseInstanceOf(ServiceException.class));
    }

    @Test
    void writePermissionStubCannotAuthorizePageEndpoints() {
        // 同岗位 stub 写权限码=true，但 franchise:fin:query=false → 只读页端点必须拒
        when(directory.roleHasPermission("finance", "franchise:fin:query")).thenReturn(false);
        when(directory.roleHasPermission("finance", "franchise:fin:register")).thenReturn(true);
        context.run(ctx -> assertThatThrownBy(() -> mvc(ctx).perform(get("/franchise/finance/receipts/page")))
                .hasRootCauseInstanceOf(AccessDeniedException.class));
    }

    @Test
    void writePermissionStubCannotAuthorizeLegacyEndpointsEither() {
        // 绿基线：旧端点同样受 @post.has 真源保护，写权限码不能替代 query
        when(directory.roleHasPermission("finance", "franchise:fin:query")).thenReturn(false);
        when(directory.roleHasPermission("finance", "franchise:fin:register")).thenReturn(true);
        context.run(ctx -> assertThatThrownBy(() -> mvc(ctx).perform(get("/franchise/finance/receipts")))
                .hasRootCauseInstanceOf(AccessDeniedException.class));
    }

    @Test
    void pageEndpointsAreReadOnlyGetOnly() {
        context.run(ctx -> mvc(ctx).perform(post("/franchise/finance/receipts/page"))
                .andExpect(status().isMethodNotAllowed()));
    }

    // ===== 绿基线：旧 List 端点兼容（小程序 finance-desk 零回归） =====

    @Test
    void legacyReceiptsStillReturnsUnpagedList() throws Exception {
        context.run(ctx -> {
            mvc(ctx).perform(get("/franchise/finance/receipts").param("status", "已登记"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
            verify(receiptMapper).selectByCompany(100L, "已登记");
        });
    }

    @Test
    void legacyPaymentsStillReturnsUnpagedList() throws Exception {
        context.run(ctx -> {
            mvc(ctx).perform(get("/franchise/finance/payments").param("status", "已核实"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
            verify(paymentMapper).selectByCompany(100L, "已核实");
        });
    }

    @Test
    void legacyPayablesStillReturnsUnpagedList() throws Exception {
        context.run(ctx -> {
            mvc(ctx).perform(get("/franchise/finance/payables").param("status", "待付款"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
            verify(payableMapper).selectByCompany(100L, "待付款");
        });
    }

    @Test
    void legacyRefundsKeepsReceiptIdFilterSemantics() throws Exception {
        context.run(ctx -> {
            mvc(ctx).perform(get("/franchise/finance/refunds").param("receiptId", "555"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
            // 旧语义：先 selectByCompany(orgId) 全量，再按 receiptId 过滤，而非 status
            verify(refundMapper).selectByCompany(100L);
        });
    }

    @Test
    void legacyAbnormalFundsKeepsStatusAndDatePendingOnly() throws Exception {
        context.run(ctx -> {
            mvc(ctx).perform(get("/franchise/finance/abnormal-funds")
                            .param("status", "已核待关联").param("datePendingOnly", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
            verify(abnormalFundMapper).selectByCompany(100L, "已核待关联", true);
        });
    }

    // ===== L1（批2 Codex P2①）：生产异常链断言 + 5 表参数化覆盖 =====

    @Test
    void productionExceptionAdviceReturnsHttp200WithBusinessCode400() {
        // pageSize=101 经真实 GlobalExceptionHandler.serviceExceptionHandler → HTTP 200 + code=400
        context.run(ctx -> mvcWithAdvice(ctx).perform(get("/franchise/finance/receipts/page")
                        .param("pageNo", "1").param("pageSize", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400)));
    }

    @Test
    void productionExceptionAdviceReturnsHttp200WithBusinessCode405ForPost() {
        // POST 到只读 /page 端点 → HttpRequestMethodNotSupportedException → HTTP 200 + code=405
        context.run(ctx -> mvcWithAdvice(ctx).perform(post("/franchise/finance/receipts/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(405)));
    }

    /** 5 表参数化：pageSize=101 显式校验逐表生效（O3 批2 显式 pageParam() 路径，非 PageParam @Max(200)）。 */
    @ParameterizedTest
    @ValueSource(strings = {"receipts", "payments", "refunds", "payables", "abnormal-funds"})
    void pageSizeAboveHundredIsRejectedForEveryTable(String table) {
        context.run(ctx -> {
            Throwable thrown = catchThrowable(() -> mvc(ctx).perform(get("/franchise/finance/" + table + "/page")
                    .param("pageNo", "1").param("pageSize", "101")));
            assertThat(thrown).hasRootCauseInstanceOf(ServiceException.class);
            Throwable root = thrown;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(((ServiceException) root).getCode()).isEqualTo(400);
        });
    }

    /** 5 表参数化：pageNo<1 显式校验逐表生效。 */
    @ParameterizedTest
    @ValueSource(strings = {"receipts", "payments", "refunds", "payables", "abnormal-funds"})
    void pageNoBelowOneIsRejectedForEveryTable(String table) {
        context.run(ctx -> assertThatThrownBy(() -> mvc(ctx).perform(get("/franchise/finance/" + table + "/page")
                        .param("pageNo", "0").param("pageSize", "10")))
                .hasRootCauseInstanceOf(ServiceException.class));
    }

    /** 5 表参数化：POST 一律 405（只读 GET 契约）。 */
    @ParameterizedTest
    @ValueSource(strings = {"receipts", "payments", "refunds", "payables", "abnormal-funds"})
    void postIsMethodNotAllowedForEveryTable(String table) {
        context.run(ctx -> mvc(ctx).perform(post("/franchise/finance/" + table + "/page"))
                .andExpect(status().isMethodNotAllowed()));
    }

    /** 5 表参数化：写权限码 stub 不能替代 franchise:fin:query（越权拒逐表生效）。 */
    @ParameterizedTest
    @ValueSource(strings = {"receipts", "payments", "refunds", "payables", "abnormal-funds"})
    void writePermissionStubCannotAuthorizeAnyTable(String table) {
        when(directory.roleHasPermission("finance", "franchise:fin:query")).thenReturn(false);
        when(directory.roleHasPermission("finance", "franchise:fin:register")).thenReturn(true);
        context.run(ctx -> assertThatThrownBy(() -> mvc(ctx).perform(get("/franchise/finance/" + table + "/page")))
                .hasRootCauseInstanceOf(AccessDeniedException.class));
    }
}
