package cn.iocoder.yudao.designprovider;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HMAC/TLS release probe. Authentication happens before this no-business-state response. */
@RestController
@RequestMapping("/internal-api/design/v1/ai-jobs")
public class ProviderRuntimeAuthSmokeController {
    private final OneShotSmokeGate gate = new OneShotSmokeGate();

    @PostMapping("/auth-smoke")
    public ResponseEntity<Void> smoke(HttpServletRequest request) {
        return gate.permit(request.getHeader("X-ZS-Signature")) ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
