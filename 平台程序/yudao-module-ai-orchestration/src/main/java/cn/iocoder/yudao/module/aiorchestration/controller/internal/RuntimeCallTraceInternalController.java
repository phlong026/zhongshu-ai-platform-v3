package cn.iocoder.yudao.module.aiorchestration.controller.internal;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.module.aiorchestration.job.RuntimeCallTraceService;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

/** Independent of prompt-call-events: this endpoint cannot reserve, confirm or refund points. */
@RestController
@PermitAll
@RequestMapping("/internal-api/design/v1/ai-jobs")
public class RuntimeCallTraceInternalController {
    private final RuntimeCallTraceService service;
    private final InternalSignatureVerifier signatures;
    public RuntimeCallTraceInternalController(RuntimeCallTraceService service, InternalSignatureVerifier signatures) {
        this.service = service;
        this.signatures = signatures;
    }
    @PostMapping("/{jobId}/call-traces")
    public CommonResult<Boolean> record(@PathVariable String jobId, @RequestBody RuntimeCallTraceService.Event event,
                                        HttpServletRequest request) {
        return CommonResult.success(service.record(verify(jobId, request), event));
    }

    @PostMapping("/{jobId}/call-traces/reconciliations")
    public CommonResult<String> reconcile(@PathVariable String jobId, @RequestBody RuntimeCallTraceService.Event event,
                                          HttpServletRequest request) {
        return CommonResult.success(service.reconcile(verify(jobId, request), event));
    }

    private long verify(String jobId, HttpServletRequest request) {
        byte[] body = ServletUtils.getBodyBytes(request);
        if (!signatures.verify(request.getHeader("X-ZS-Timestamp"), request.getHeader("X-ZS-Signature"),
                request.getMethod(), request.getRequestURI(), body)) throw new AccessDeniedException("RUNTIME_SIGNATURE");
        if (jobId == null || !jobId.matches("[1-9][0-9]{0,18}")) throw new AccessDeniedException("RUNTIME_JOB");
        try { return Long.parseLong(jobId); }
        catch (NumberFormatException ignored) { throw new AccessDeniedException("RUNTIME_JOB"); }
    }
}
