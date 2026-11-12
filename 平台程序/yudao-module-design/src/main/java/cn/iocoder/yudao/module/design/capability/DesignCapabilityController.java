package cn.iocoder.yudao.module.design.capability;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.capability.security.CapabilityAuthenticationFilter;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@ConditionalOnProperty(name = "zhongshu.design.capability.enabled", havingValue = "true")
@RequestMapping("/internal-api/design/v1/platform")
public class DesignCapabilityController {
    private final DesignCapabilityProvider provider;
    public DesignCapabilityController(DesignCapabilityProvider provider) { this.provider = provider; }

    @PostMapping("/{action}")
    public ResponseEntity<Reply<Object>> execute(@PathVariable String action, HttpServletRequest servletRequest) {
        // The filter decoded the exact signed bytes. Re-reading an XSS wrapper would change their meaning.
        var principal = CapabilityAuthenticationFilter.requirePrincipal(servletRequest);
        var request = CapabilityAuthenticationFilter.requireRequest(servletRequest);
        try {
            var reply = provider.invoke(principal, Operation.fromAction(action), request);
            int status = reply.code() == 0 ? 200 : reply.code() == 1_112_100_002 ? 202 : reply.code() == 409 ? 409 : 422;
            return ResponseEntity.status(status).body(reply);
        } catch (CapabilityException error) {
            log.warn("[design-capability][caller={} org={} action={} code={}]", principal.callerAppId(), principal.orgId(), action, error.getCode());
            return ResponseEntity.status(error.getHttpStatus()).body(new Reply<>(error.getCode(), error.getMessage(), null, null, false));
        } catch (ServiceException error) {
            log.warn("[design-capability][business caller={} org={} action={} code={}]", principal.callerAppId(), principal.orgId(), action, error.getCode());
            int status = error.getCode() == 409 ? 409 : 422;
            return ResponseEntity.status(status).body(new Reply<>(error.getCode(), error.getMessage(), null, null, false));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(new Reply<>(400, error.getMessage(), null, null, false));
        } catch (RuntimeException error) {
            log.error("[design-capability][failed caller={} org={} action={}]", principal.callerAppId(), principal.orgId(), action, error);
            return ResponseEntity.internalServerError().body(new Reply<>(500, "设计能力暂不可用，请查询原业务键收据", null, null, false));
        }
    }
}
