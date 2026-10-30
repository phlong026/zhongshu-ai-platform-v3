package cn.iocoder.yudao.framework.apilog.core.interceptor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.common.util.spring.SpringUtils;
import cn.iocoder.yudao.framework.web.core.util.SensitiveLogRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

class ApiAccessLogInterceptorTest {
    private final ApiAccessLogInterceptor interceptor = new ApiAccessLogInterceptor();
    private Logger logger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(ApiAccessLogInterceptor.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    @Test
    void nonProdTraceRequestOmitsBodyQueryAndSignatureWithoutConsumingBusinessPayload() {
        for (String path : java.util.List.of("/internal-api/design/v1/ai-jobs/123/call-traces",
                "/internal-api/design/v1/ai-jobs/123/call-traces/reconciliations")) {
            appender.list.clear();
            assertTraceRequestOmitted(path);
        }
    }

    private void assertTraceRequestOmitted(String path) {
        String body = "{\"request\":{\"promptText\":\"TRACE-PROMPT-MARKER\"},"
                + "\"unknownCopy\":\"SERIALIZED-COPY-MARKER\"}";
        MockHttpServletRequest request = request(path, body);
        request.addParameter("unknownCopy", "QUERY-COPY-MARKER");
        request.addHeader("X-ZS-Signature", "SIGNATURE-MARKER");

        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class)) {
            spring.when(SpringUtils::isProd).thenReturn(false);
            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
            interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
        }

        assertThat(ServletUtils.getBody(request)).isEqualTo(body);
        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.get(0).getFormattedMessage()).contains(SensitiveLogRedactor.RUNTIME_TRACE_OMITTED);
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage()).doesNotContain("TRACE-PROMPT-MARKER", "SERIALIZED-COPY-MARKER",
                    "QUERY-COPY-MARKER", "SIGNATURE-MARKER");
            assertThat(event.getThrowableProxy()).isNull();
        }
    }

    @Test
    void ordinaryNonProdRequestAlsoRedactsNestedPromptAndSystemPromptKeys() {
        MockHttpServletRequest request = request("/app-api/design/v1/prompt-preview",
                "{\"request\":{\"promptText\":\"USER-MARKER\",\"systemPrompt\":\"SYSTEM-MARKER\"},"
                        + "\"response\":[{\"promptText\":\"RESPONSE-MARKER\"}],\"model\":\"test-model\"}");

        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class)) {
            spring.when(SpringUtils::isProd).thenReturn(false);
            interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        }

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("test-model")
                .doesNotContain("USER-MARKER", "SYSTEM-MARKER", "RESPONSE-MARKER");
    }

    private static MockHttpServletRequest request(String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
