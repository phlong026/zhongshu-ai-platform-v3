package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.apilog.core.filter.ApiAccessLogFilter;
import cn.iocoder.yudao.framework.apilog.core.interceptor.ApiAccessLogInterceptor;
import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiAccessLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.infra.logger.dto.ApiAccessLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.platform.org.StaffImportService;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * P1-1：员工导入访问日志 PII 回归（真实 ApiAccessLogFilter）。
 *
 * <p>precheck 为只读 dry-run：@ApiAccessLog(enable=false) → 过滤链零写入；
 * submit：@ApiAccessLog(requestEnable=false) → 保留访问记录，但 requestParams 为空，不落手机号。
 */
class StaffImportAccessLogFilterTest {

    private static final String MOBILE = "13800138000";
    private static final String BODY = "{\"orgId\":151,\"rows\":[{\"line\":1,\"name\":\"张三\","
            + "\"mobile\":\"" + MOBILE + "\",\"roleCode\":\"sales\",\"teamName\":\"业务一组\"}]}";

    private ApiAccessLogCommonApi apiAccessLogApi;
    private ApiAccessLogFilter filter;

    @BeforeEach
    void setUp() {
        apiAccessLogApi = mock(ApiAccessLogCommonApi.class);
        filter = new ApiAccessLogFilter(new WebProperties(), "test-app", apiAccessLogApi);
    }

    @Test
    void precheckIsExcludedFromAccessLog() throws Exception {
        try (MockedStatic<WebFrameworkUtils> ignored = mockStatic(WebFrameworkUtils.class)) {
            filter.doFilter(request("/admin-api/platform/org/staff-import/precheck", "precheck"),
                    new MockHttpServletResponse(), (req, res) -> { });
        }
        verify(apiAccessLogApi, never()).createApiAccessLogAsync(any());
    }

    @Test
    void submitKeepsAccessRecordButOmitsRequestBody() throws Exception {
        String password = "FixtureOnlyInitial2026";
        try (MockedStatic<WebFrameworkUtils> ignored = mockStatic(WebFrameworkUtils.class)) {
            MockHttpServletRequest req = request("/admin-api/platform/org/staff-import", "submit");
            var account = new StaffImportService.AccountDelivery("account-1", "张三", "门店", "zhangsan",
                    java.util.List.of("zhangsan", "zs"), password, false);
            ignored.when(() -> WebFrameworkUtils.getCommonResult(req)).thenReturn(CommonResult.success(account));
            filter.doFilter(req,
                    new MockHttpServletResponse(), (chainRequest, chainResponse) -> { });
        }
        ArgumentCaptor<ApiAccessLogCreateReqDTO> captor =
                ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class);
        verify(apiAccessLogApi, times(1)).createApiAccessLogAsync(captor.capture());
        ApiAccessLogCreateReqDTO dto = captor.getValue();
        assertThat(dto.getRequestUrl()).endsWith("/platform/org/staff-import");
        assertThat(dto.getRequestParams()).isNull();
        assertThat(dto.getResponseBody()).isNull();
        assertThat(JsonUtils.toJsonString(dto)).doesNotContain(MOBILE, password);
    }

    @Test
    void annotationsCarryExpectedSwitches() throws Exception {
        Method precheck = StaffImportController.class.getMethod("precheck",
                StaffImportController.ImportReq.class);
        Method submit = StaffImportController.class.getMethod("submit",
                StaffImportController.ImportReq.class);
        ApiAccessLog precheckLog = precheck.getAnnotation(ApiAccessLog.class);
        ApiAccessLog submitLog = submit.getAnnotation(ApiAccessLog.class);
        assertThat(precheckLog).isNotNull();
        assertThat(precheckLog.enable()).isFalse();
        assertThat(submitLog).isNotNull();
        assertThat(submitLog.requestEnable()).isFalse();
        assertThat(submitLog.enable()).isTrue();
        assertThat(submitLog.responseEnable()).isFalse();
    }

    private MockHttpServletRequest request(String uri, String methodName) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContentType("application/json");
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        StaffImportController controller = mock(StaffImportController.class);
        HandlerMethod handlerMethod = new HandlerMethod(controller,
                StaffImportController.class.getMethod(methodName, StaffImportController.ImportReq.class));
        request.setAttribute(ApiAccessLogInterceptor.ATTRIBUTE_HANDLER_METHOD, handlerMethod);
        return request;
    }
}
