package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.module.aiorchestration.controller.internal.InternalSignatureVerifier;
import cn.iocoder.yudao.module.design.asset.ObjectStoragePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runtime-only asset exchange. It accepts only a live fenced lease and private accepted inputs. */
@RestController
@PermitAll
@RequestMapping("/internal-api/design/v1/ai-jobs")
public class ProviderRuntimeAssetController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final ObjectStoragePort storage;
    private final InternalSignatureVerifier signatures;
    private final ProviderOutputTicketService tickets;

    public ProviderRuntimeAssetController(DataSource dataSource, ObjectStoragePort storage,
                                          InternalSignatureVerifier signatures,
                                          ProviderOutputTicketService tickets) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.storage = storage;
        this.signatures = signatures;
        this.tickets = tickets;
    }

    public record Lease(@Min(1) int attemptNo, @Min(1) long fencingToken) { }
    public record Upload(@Min(1) int attemptNo, @Min(1) long fencingToken,
                         @Min(1) @Max(4) int slot,
                         @NotNull @Pattern(regexp = "image/(png|jpeg)") String mimeType) { }

    @PostMapping("/{id}/inputs")
    public CommonResult<Map<String, Object>> inputs(@PathVariable String id, @Valid @RequestBody Lease lease,
                                                     HttpServletRequest request) throws Exception {
        Map<String, Object> job = requireLease(id, lease.attemptNo(), lease.fencingToken(), request);
        if (job.get("input_snapshot") == null) throw new IllegalStateException("RUNTIME_INPUT_SNAPSHOT_MISSING");
        Map<String, Object> snapshot = JSON.readValue((String) job.get("input_snapshot"), new TypeReference<>() { });
        Object rawIds = snapshot.get("assetIds");
        if (!(rawIds instanceof List<?> assetIds) || assetIds.size() > 8) {
            throw new IllegalStateException("RUNTIME_INPUT_INVALID");
        }
        List<?> references = null;
        if ("FLAT".equals(snapshot.get("phase")) && snapshot.containsKey("references")) {
            if (!(snapshot.get("references") instanceof List<?> values) || values.size() != assetIds.size()) {
                throw new IllegalStateException("RUNTIME_REFERENCE_INVALID");
            }
            references = values;
        }
        List<Map<String, Object>> images = new ArrayList<>();
        for (int index = 0; index < assetIds.size(); index++) {
            Object rawId = assetIds.get(index);
            long assetId = positiveId(rawId);
            var rows = jdbc.queryForList("SELECT id,object_key,stored_sha256,stored_size,declared_mime FROM asset "
                            + "WHERE id=? AND owner_user_id=? AND upload_status='ACCEPTED' "
                            + "AND security_scan_status='PASSED' AND moderation_status='PASSED' AND deleted=FALSE",
                    assetId, job.get("user_id"));
            if (rows.size() != 1) throw new AccessDeniedException("RUNTIME_INPUT_RIGHTS");
            Map<String, Object> asset = rows.get(0);
            var image = new LinkedHashMap<String, Object>(Map.of(
                    "assetId", String.valueOf(assetId),
                    "url", storage.presignDownloadUrl((String) asset.get("object_key"), 120),
                    "sha256", asset.get("stored_sha256"),
                    "sizeBytes", asset.get("stored_size"),
                    "mimeType", asset.get("declared_mime")));
            if (references != null) image.putAll(referenceMetadata(references.get(index), String.valueOf(assetId)));
            images.add(image);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schemaVersion", 1);
        response.put("phase", snapshot.get("phase"));
        response.put("requirements", snapshot.get("requirements"));
        if (snapshot.get("imageOptions") instanceof Map<?, ?> options) response.put("imageOptions", options);
        response.put("images", images);
        return CommonResult.success(response);
    }

    @PostMapping("/{id}/output-tickets")
    public CommonResult<Map<String, Object>> outputTicket(@PathVariable String id,
            @Valid @RequestBody Upload upload, HttpServletRequest request) {
        Map<String, Object> job = requireLease(id, upload.attemptNo(), upload.fencingToken(), request);
        if (upload.slot() > ((Number) job.get("requested_count")).intValue()) {
            throw new AccessDeniedException("RUNTIME_SLOT");
        }
        String key = tickets.issue(jobId(id), upload.attemptNo(), upload.fencingToken(), upload.slot(), upload.mimeType())
                .objectKey();
        return CommonResult.success(Map.of("objectKey", key, "uploadUrl", storage.presignUploadUrl(key, 120),
                "maxBytes", 20 * 1024 * 1024));
    }

    private Map<String, Object> requireLease(String id, int attempt, long fence, HttpServletRequest request) {
        byte[] body = ServletUtils.getBodyBytes(request);
        if (!signatures.verify(request.getHeader("X-ZS-Timestamp"), request.getHeader("X-ZS-Signature"),
                request.getMethod(), request.getRequestURI(), body)) {
            throw new AccessDeniedException("RUNTIME_SIGNATURE_OR_JOB");
        }
        long parsedJobId = jobId(id);
        var rows = jdbc.queryForList("SELECT j.id,j.user_id,j.requested_count,j.input_snapshot::text FROM ai_job j "
                        + "WHERE j.id=? AND j.fencing_token=? AND j.status='RUNNING' AND j.claim_expires_at>now() "
                        + "AND j.deleted=FALSE AND EXISTS (SELECT 1 FROM ai_job_attempt a WHERE a.job_id=j.id "
                        + "AND a.attempt_no=? AND a.fencing_token=? AND a.finished_at IS NULL AND a.deleted=FALSE)",
                parsedJobId, fence, attempt, fence);
        if (rows.size() != 1) throw new AccessDeniedException("RUNTIME_LEASE");
        return rows.get(0);
    }

    private static long jobId(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RUNTIME_JOB_ID_INVALID");
        }
    }

    private static long positiveId(Object value) {
        try {
            long id = Long.parseLong(String.valueOf(value));
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("RUNTIME_INPUT_INVALID");
        }
    }

    private static Map<String, Object> referenceMetadata(Object value, String assetId) {
        if (!(value instanceof Map<?, ?> reference) || !assetId.equals(reference.get("assetId"))) {
            throw new IllegalStateException("RUNTIME_REFERENCE_INVALID");
        }
        Object floor = reference.get("floorNo");
        Object version = reference.get("sourceCaseVersionId");
        Object source = reference.get("sourceType");
        if (floor != null && (!(floor instanceof Integer number) || number < 1)
                || version != null && (!(version instanceof String id) || !id.matches("[1-9][0-9]{0,18}"))
                || source != null && !java.util.Set.of("CASE_FLOOR_PLAN", "USER_SKETCH").contains(source)
                || "USER_SKETCH".equals(source) && (floor != null || version != null)) {
            throw new IllegalStateException("RUNTIME_REFERENCE_INVALID");
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("floorNo", floor);
        result.put("sourceCaseVersionId", version);
        result.put("sourceType", source);
        return result;
    }
}
