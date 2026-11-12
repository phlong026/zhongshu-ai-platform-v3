package cn.iocoder.yudao.module.franchise.design.remote.transport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityTransportConfigurationTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(CapabilityTransportConfiguration.class);

    @Test
    void defaultDisabledAndMissingDedicatedKeysFailClosed() {
        contexts.run(context -> assertEquals(0, context.getBeansOfType(DesignCapabilityTransport.class).size()));
        contexts.withPropertyValues("zhongshu.design.remote.enabled=true", "zhongshu.design.remote.origin=https://example.com",
                "zhongshu.design.remote.active-key-id=runtime", "zhongshu.ai.internal-secret=runtime-secret-must-not-be-used")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void activeRotationKeyMustExistAndProductionCannotEnableHttp() {
        String testKey = Base64.getEncoder().encodeToString(new byte[32]);
        contexts.withPropertyValues("zhongshu.design.remote.enabled=true", "zhongshu.design.remote.origin=https://example.com",
                "zhongshu.design.remote.active-key-id=new", "zhongshu.design.remote.keys.old=" + testKey)
                .run(context -> assertNotNull(context.getStartupFailure()));
        contexts.withPropertyValues("zhongshu.design.remote.enabled=true", "zhongshu.design.remote.origin=http://127.0.0.1:1234",
                "zhongshu.design.remote.active-key-id=new", "zhongshu.design.remote.keys.new=" + testKey)
                .run(context -> assertNotNull(context.getStartupFailure()));
        contexts.withPropertyValues("zhongshu.design.remote.enabled=true", "zhongshu.design.remote.origin=https://example.com",
                "zhongshu.design.remote.active-key-id=new", "zhongshu.design.remote.keys.old=" + testKey,
                "zhongshu.design.remote.keys.new=" + testKey)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(DesignCapabilityTransport.class).size());
                });
    }
}
