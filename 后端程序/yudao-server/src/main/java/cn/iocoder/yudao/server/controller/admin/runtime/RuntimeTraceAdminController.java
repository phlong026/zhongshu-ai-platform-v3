package cn.iocoder.yudao.server.controller.admin.runtime;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.aiorchestration.enums.PermissionConstants;
import cn.iocoder.yudao.module.aiorchestration.job.RuntimeCallTraceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.util.List;

/** Cross-domain, permission-gated detail assembly. Pixels come from the existing accepted-asset scan. */
@RestController
@RequestMapping("/design/v1/ai-jobs")
public class RuntimeTraceAdminController {
    private final RuntimeCallTraceService traces;
    private final JdbcTemplate jdbc;
    public RuntimeTraceAdminController(RuntimeCallTraceService traces, DataSource ds) {
        this.traces = traces;
        jdbc = new JdbcTemplate(ds);
    }
    public record AcceptedOutput(String assetId, int slot, String sourceSha256, Integer width, Integer height) { }
    public record Detail(List<RuntimeCallTraceService.Trace> calls, List<AcceptedOutput> acceptedOutputs) { }

    @GetMapping("/{jobId}/call-traces")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.AI_JOB_QUERY + "')")
    public CommonResult<Detail> get(@PathVariable String jobId) {
        long id = Long.parseLong(jobId);
        List<AcceptedOutput> outputs = jdbc.query("SELECT a.id,c.slot_no,r.source_content_sha256,a.width,a.height "
                        + "FROM design_candidate c JOIN ai_job j ON j.id=c.job_id AND j.deleted=FALSE "
                        + "JOIN ai_job_result r ON r.id=c.ai_result_id AND r.job_id=c.job_id AND r.candidate_slot_no=c.slot_no "
                        + "AND r.validation_state='ACCEPTED' AND r.deleted=FALSE "
                        + "JOIN asset a ON a.id=c.asset_id AND a.deleted=FALSE AND a.upload_status='ACCEPTED' "
                        + "AND a.security_scan_status='PASSED' AND a.moderation_status='PASSED' AND a.stored_sha256=r.content_sha256 "
                        + "WHERE c.job_id=? AND c.deleted=FALSE ORDER BY c.slot_no",
                (rs, i) -> new AcceptedOutput(rs.getString("id"), rs.getInt("slot_no"), rs.getString("source_content_sha256"),
                        rs.getObject("width", Integer.class), rs.getObject("height", Integer.class)), id);
        return CommonResult.success(new Detail(traces.list(id), outputs));
    }
}
