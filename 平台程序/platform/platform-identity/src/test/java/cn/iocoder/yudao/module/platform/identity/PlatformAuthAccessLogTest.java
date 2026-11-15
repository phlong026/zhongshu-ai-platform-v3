package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.module.platform.identity.controller.admin.PlatformAuthController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformAuthAccessLogTest {

    @Test
    void sensitiveAuthEndpointsDoNotRecordRequestOrResponseBodies() throws Exception {
        assertBodyLoggingDisabled(method("accountMatches"));
        assertBodyLoggingDisabled(method("accountLogin"));
        assertBodyLoggingDisabled(method("bindWechat"));
    }

    private static Method method(String name) {
        return java.util.Arrays.stream(PlatformAuthController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst().orElseThrow();
    }

    private static void assertBodyLoggingDisabled(Method method) {
        ApiAccessLog annotation = method.getAnnotation(ApiAccessLog.class);
        assertThat(annotation).as(method.getName()).isNotNull();
        assertThat(annotation.requestEnable()).isFalse();
        assertThat(annotation.responseEnable()).isFalse();
    }
}
