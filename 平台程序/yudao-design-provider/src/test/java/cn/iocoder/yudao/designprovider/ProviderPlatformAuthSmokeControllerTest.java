package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.module.design.capability.security.CapabilitySecurityProperties;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract;
import cn.iocoder.yudao.module.franchise.api.capability.security.CapabilitySignature;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderPlatformAuthSmokeControllerTest {
    @Test
    void timestampWindowRejectsOverflowAndMalformedValues() {
        long now = 1_800_000_000L;
        assertThat(ProviderPlatformAuthSmokeController.validTimestamp(Long.toString(now), now)).isTrue();
        assertThat(ProviderPlatformAuthSmokeController.validTimestamp(Long.toString(now - 30), now)).isTrue();
        assertThat(ProviderPlatformAuthSmokeController.validTimestamp(Long.toString(now + 30), now)).isTrue();
        assertThat(ProviderPlatformAuthSmokeController.validTimestamp(Long.toString(Long.MIN_VALUE), now)).isFalse();
        assertThat(ProviderPlatformAuthSmokeController.validTimestamp(Long.toString(Long.MAX_VALUE), now)).isFalse();
        assertThat(ProviderPlatformAuthSmokeController.validTimestamp("not-a-number", now)).isFalse();
    }

    @Test
    void validSmokeAllowsExactRetryButRejectsAnotherSignedRequest() {
        var secret = new byte[32];
        var key = new CapabilitySecurityProperties.KeyProperties();
        key.setCallerAppId("platform-b");
        key.setSecretBase64(Base64.getEncoder().encodeToString(secret));
        key.setOperations(Set.of(DesignCapabilityContract.Operation.LIST_PROJECTS));
        var properties = new CapabilitySecurityProperties();
        properties.setKeys(Map.of("b-platform", key));
        var request = mock(jakarta.servlet.http.HttpServletRequest.class);
        var timestamp = Long.toString(Instant.now().getEpochSecond());
        var path = "/internal-api/deploy-smoke/platform";
        when(request.getHeader(CapabilitySignature.TIMESTAMP_HEADER)).thenReturn(timestamp);
        when(request.getHeader(CapabilitySignature.KEY_ID_HEADER)).thenReturn("b-platform");
        when(request.getHeader("X-ZS-Caller-App-Id")).thenReturn("platform-b");
        when(request.getHeader(CapabilitySignature.SIGNATURE_HEADER))
                .thenReturn(CapabilitySignature.sign(secret, timestamp, "POST", path, new byte[0]));
        when(request.getRequestURI()).thenReturn(path);
        var controller = new ProviderPlatformAuthSmokeController(properties);
        assertThat(controller.platform(request).getStatusCode().value()).isEqualTo(204);
        assertThat(controller.platform(request).getStatusCode().value()).isEqualTo(204);
        var secondTimestamp = Long.toString(Instant.now().plusSeconds(1).getEpochSecond());
        when(request.getHeader(CapabilitySignature.TIMESTAMP_HEADER)).thenReturn(secondTimestamp);
        when(request.getHeader(CapabilitySignature.SIGNATURE_HEADER)).thenReturn(
                CapabilitySignature.sign(secret, secondTimestamp, "POST", path, new byte[0]));
        assertThat(controller.platform(request).getStatusCode().value()).isEqualTo(404);

        var runtime = new ProviderRuntimeAuthSmokeController();
        var runtimeRequest = mock(jakarta.servlet.http.HttpServletRequest.class);
        assertThat(runtime.smoke(runtimeRequest).getStatusCode().value()).isEqualTo(404);
        when(runtimeRequest.getHeader("X-ZS-Signature")).thenReturn("first-valid-signature");
        assertThat(runtime.smoke(runtimeRequest).getStatusCode().value()).isEqualTo(204);
        assertThat(runtime.smoke(runtimeRequest).getStatusCode().value()).isEqualTo(204);
        when(runtimeRequest.getHeader("X-ZS-Signature")).thenReturn("another-valid-signature");
        assertThat(runtime.smoke(runtimeRequest).getStatusCode().value()).isEqualTo(404);
    }
}
