package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.module.design.capability.security.CapabilitySecurityProperties;
import cn.iocoder.yudao.module.franchise.api.capability.security.CapabilitySignature;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
/** No-state deployment probe for the API caller's real TLS and capability key contract. */
@RestController
@RequestMapping("/internal-api/deploy-smoke")
public class ProviderPlatformAuthSmokeController {
    private final CapabilitySecurityProperties properties;
    private final OneShotSmokeGate gate = new OneShotSmokeGate();

    public ProviderPlatformAuthSmokeController(CapabilitySecurityProperties properties) {
        this.properties = properties;
    }

    @PostMapping("/platform")
    public ResponseEntity<Void> platform(HttpServletRequest request) {
        try {
            var timestamp = request.getHeader(CapabilitySignature.TIMESTAMP_HEADER);
            var keyId = request.getHeader(CapabilitySignature.KEY_ID_HEADER);
            var caller = request.getHeader("X-ZS-Caller-App-Id");
            if (!validTimestamp(timestamp, Instant.now().getEpochSecond())) {
                return ResponseEntity.status(401).build();
            }
            var secret = properties.keyring().require(keyId, caller, "LIST_PROJECTS");
            if (!CapabilitySignature.verify(secret, timestamp, "POST", request.getRequestURI(), new byte[0],
                    request.getHeader(CapabilitySignature.SIGNATURE_HEADER))) {
                return ResponseEntity.status(401).build();
            }
            return gate.permit(request.getHeader(CapabilitySignature.SIGNATURE_HEADER))
                    ? ResponseEntity.noContent().build()
                    : ResponseEntity.notFound().build();
        } catch (RuntimeException rejected) {
            return ResponseEntity.status(401).build();
        }
    }

    static boolean validTimestamp(String value, long now) {
        try {
            long timestamp = Long.parseLong(value);
            return timestamp >= Math.subtractExact(now, 30) && timestamp <= Math.addExact(now, 30);
        } catch (NumberFormatException | ArithmeticException invalid) {
            return false;
        }
    }
}
