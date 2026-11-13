package cn.iocoder.yudao.module.franchise.crm.controller.admin;

import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.authz.PostPermissionEvaluator;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1 批4：客资表格筛选与接手人候选 HTTP 契约（生产异常链）。
 * 可见范围由 CustomerService/CustomerAccess 承担；这里锁权限、参数校验与响应形态。
 */
class CustomerPageTransferHttpContractTest {

    private static final long ORG = 151L;
    private static final PostSnapshot BOSS =
            new PostSnapshot(21L, 9L, ORG, "store", "boss", null, false, Set.of());

    private final CurrentPost currentPost = mock(CurrentPost.class);
    private final SystemDirectory directory = mock(SystemDirectory.class);
    private final CustomerService service = mock(CustomerService.class);

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Security.class)
            .withBean(CurrentPost.class, () -> currentPost)
            .withBean(SystemDirectory.class, () -> directory)
            .withBean("post", PostPermissionEvaluator.class,
                    () -> new PostPermissionEvaluator(currentPost, directory))
            .withBean(CustomerService.class, () -> service)
            .withBean(CustomerController.class);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, List.of()));
        when(currentPost.find()).thenReturn(Optional.of(BOSS));
        when(currentPost.require()).thenReturn(BOSS);
        when(directory.roleHasPermission("boss", "franchise:customer:query")).thenReturn(true);
        when(directory.roleHasPermission("boss", "franchise:customer:transfer")).thenReturn(true);
        when(service.page(any(), any(), any(), any(), any()))
                .thenReturn(new cn.iocoder.yudao.framework.common.pojo.PageResult<>(List.of(), 0L));
        when(service.ownerOptions(any())).thenReturn(List.of(
                new CustomerService.OwnerOption(7L, "李四")));
        when(service.transferCandidates(any(), anyLong())).thenReturn(List.of(
                new CustomerService.TransferCandidate(7L, "李四", "sales", 11L, "业务一组")));
        when(directory.usersByIds(any())).thenReturn(List.of(
                new SystemDirectory.UserBrief(7L, "李四", "13800000000", true)));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Security {
    }

    private MockMvc mvc(org.springframework.context.ApplicationContext ctx) {
        return MockMvcBuilders.standaloneSetup(ctx.getBean(CustomerController.class))
                .setControllerAdvice(new GlobalExceptionHandler("test", null))
                .build();
    }

    @Test
    void pagePassesFiltersAndReturnsRows() {
        context.run(ctx -> mvc(ctx).perform(get("/franchise/customers")
                        .param("pageNo", "1").param("pageSize", "20")
                        .param("status", "REGISTERED").param("ownerUserId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0)));
    }

    @Test
    void invalidStatusIsRejectedWithProductionEnvelope() {
        context.run(ctx -> mvc(ctx).perform(get("/franchise/customers").param("status", "ARCHIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400)));
    }

    @Test
    void ownerOptionsRequireQueryPermission() {
        when(directory.roleHasPermission("boss", "franchise:customer:query")).thenReturn(false);
        when(directory.roleHasPermission("boss", "franchise:customer:transfer")).thenReturn(true);
        context.run(ctx -> mvc(ctx).perform(get("/franchise/customers/owner-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403)));
    }

    @Test
    void ownerOptionsReturnVisibleOwners() {
        context.run(ctx -> mvc(ctx).perform(get("/franchise/customers/owner-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].userId").value(7))
                .andExpect(jsonPath("$.data[0].name").value("李四")));
    }

    @Test
    void transferCandidatesRequireTransferPermission() {
        when(directory.roleHasPermission("boss", "franchise:customer:transfer")).thenReturn(false);
        when(directory.roleHasPermission("boss", "franchise:customer:query")).thenReturn(true);
        context.run(ctx -> mvc(ctx).perform(get("/franchise/customers/transfer-candidates")
                        .param("customerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403)));
    }

    @Test
    void transferCandidatesReturnActiveSalesWithTeamName() {
        context.run(ctx -> mvc(ctx).perform(get("/franchise/customers/transfer-candidates")
                        .param("customerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].userId").value(7))
                .andExpect(jsonPath("$.data[0].name").value("李四"))
                .andExpect(jsonPath("$.data[0].roleCode").value("sales"))
                .andExpect(jsonPath("$.data[0].teamName").value("业务一组")));
    }

    @Test
    void transferCandidatesRequireCustomerId() {
        context.run(ctx -> mvc(ctx).perform(get("/franchise/customers/transfer-candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400)));
    }

    @Test
    void pageMapsOwnerNameAndFallsBackToEmpty() {
        var customer = new cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO();
        customer.setId(5L);
        customer.setCompanyId(ORG);
        customer.setName("张三");
        customer.setPhone("13800000001");
        customer.setSource("门店");
        customer.setStatus("REGISTERED");
        customer.setOwnerUserId(7L);
        customer.setRevision(1L);
        when(service.page(any(), any(), any(), any(), any()))
                .thenReturn(new cn.iocoder.yudao.framework.common.pojo.PageResult<>(List.of(customer), 1L));
        context.run(ctx -> mvc(ctx).perform(get("/franchise/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].ownerName").value("李四")));
    }

    @Test
    void unreadableCustomerIdIsRejectedByServiceEnvelope() {
        when(service.transferCandidates(any(), anyLong())).thenThrow(
                new cn.iocoder.yudao.framework.common.exception.ServiceException(1_110_000_000, "客户不存在或无权访问"));
        context.run(ctx -> mvc(ctx).perform(get("/franchise/customers/transfer-candidates")
                        .param("customerId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1110000000)));
    }

    @Test
    void pageStatusFilterIsPassedToService() {
        context.run(ctx -> {
            mvc(ctx).perform(get("/franchise/customers").param("status", "FOLLOWING").param("ownerUserId", "7"));
            org.mockito.Mockito.verify(service).page(any(), any(), any(), eq("FOLLOWING"), eq(7L));
            assertThat(true).isTrue();
        });
    }
}
