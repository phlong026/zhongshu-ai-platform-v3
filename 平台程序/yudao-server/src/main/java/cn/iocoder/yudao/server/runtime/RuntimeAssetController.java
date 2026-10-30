package cn.iocoder.yudao.server.runtime;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.aiorchestration.controller.internal.InternalSignatureVerifier;
import cn.iocoder.yudao.module.design.asset.ObjectStoragePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.util.*;

/** Cross-domain Runtime assembly belongs to server; provider never receives a database credential. */
@RestController @PermitAll
@RequestMapping("/internal-api/design/v1/ai-jobs")
public class RuntimeAssetController {
    private final JdbcTemplate jdbc;
    private final ObjectStoragePort storage;
    private final InternalSignatureVerifier signatures;
    public RuntimeAssetController(DataSource ds,ObjectStoragePort storage,InternalSignatureVerifier signatures) {
        jdbc=new JdbcTemplate(ds);this.storage=storage;this.signatures=signatures;
    }
    public record Lease(@Min(1) int attemptNo,@Min(1) long fencingToken) { }
    public record Upload(@Min(1) int attemptNo,@Min(1) long fencingToken,@Min(1) @Max(8) int slot,
                         @NotNull @Pattern(regexp="image/(png|jpeg)") String mimeType) { }

    private Map<String,Object> requireLease(String id, int attempt, long fence, HttpServletRequest request) {
        byte[] body=cn.iocoder.yudao.framework.common.util.servlet.ServletUtils.getBodyBytes(request);
        if (!signatures.verify(request.getHeader("X-ZS-Timestamp"),request.getHeader("X-ZS-Signature"),request.getMethod(),request.getRequestURI(),body))
            throw new AccessDeniedException("RUNTIME_SIGNATURE");
        if (!id.matches("[1-9][0-9]{0,18}")) throw new AccessDeniedException("RUNTIME_JOB");
        var rows=jdbc.queryForList("SELECT j.id,j.user_id,j.requested_count,j.input_snapshot::text FROM ai_job j WHERE j.id=? AND j.fencing_token=? "
                + "AND j.status='RUNNING' AND j.claim_expires_at>now() AND j.deleted=FALSE AND EXISTS (SELECT 1 FROM ai_job_attempt a WHERE a.job_id=j.id AND a.attempt_no=? AND a.fencing_token=? AND a.finished_at IS NULL)",Long.parseLong(id),fence,attempt,fence);
        if (rows.isEmpty()) throw new AccessDeniedException("RUNTIME_LEASE");
        return rows.get(0);
    }

    @PostMapping("/{id}/inputs")
    public CommonResult<Map<String,Object>> inputs(@PathVariable String id,@Valid @RequestBody Lease lease,HttpServletRequest request) throws Exception {
        var job=requireLease(id,lease.attemptNo(),lease.fencingToken(),request);
        if (job.get("input_snapshot")==null) throw new IllegalStateException("RUNTIME_INPUT_SNAPSHOT_MISSING");
        Map<String,Object> snapshot=new ObjectMapper().readValue((String)job.get("input_snapshot"),Map.class);
        var images=new ArrayList<Map<String,Object>>();
        Object raw=snapshot.get("assetIds");
        if (!(raw instanceof List<?> ids) || ids.size()>8) throw new IllegalStateException("RUNTIME_INPUT_INVALID");
        List<?> references=null;
        if ("FLAT".equals(snapshot.get("phase")) && snapshot.containsKey("references")) {
            if (!(snapshot.get("references") instanceof List<?> refs) || refs.size()!=ids.size())
                throw new IllegalStateException("RUNTIME_REFERENCE_INVALID");
            references=refs;
        }
        int referenceIndex=0;
        for (Object assetId : ids) {
            if (references!=null && references.get(referenceIndex)==null) throw new IllegalStateException("RUNTIME_REFERENCE_INVALID");
            Map<String,Object> reference="FLAT".equals(snapshot.get("phase"))
                    ? referenceMetadata(references==null ? null : references.get(referenceIndex),String.valueOf(assetId)) : null;
            referenceIndex++;
            // New FLAT evidence is bound to business domain 0. A grant from another domain
            // cannot replace a withdrawn/expired domain-0 grant. Preserve older/elevation policy.
            String assetDomain=references==null ? "" : "AND a.tenant_id=0 ";
            String grantDomain=references==null ? "" : "AND g.tenant_id=0 ";
            var rows=jdbc.queryForList("SELECT a.id,a.object_key,a.stored_sha256,a.stored_size,a.declared_mime FROM asset a WHERE a.id=? AND a.deleted=FALSE "
                    + assetDomain
                    + "AND a.upload_status='ACCEPTED' AND a.security_scan_status='PASSED' AND a.moderation_status='PASSED' "
                    + "AND (a.owner_user_id=? OR EXISTS (SELECT 1 FROM asset_rights_grant g WHERE g.asset_id=a.id " + grantDomain
                    + "AND g.scope='GENERATION_REFERENCE' AND g.status='ACTIVE' AND g.effective_at<=now() AND (g.expires_at IS NULL OR g.expires_at>now()) AND g.deleted=FALSE))",Long.parseLong(String.valueOf(assetId)),job.get("user_id"));
            if (rows.isEmpty()) throw new AccessDeniedException("RUNTIME_INPUT_RIGHTS");
            var asset=rows.get(0);
            var image=new LinkedHashMap<String,Object>(Map.of("assetId",String.valueOf(assetId),"url",storage.presignDownloadUrl((String)asset.get("object_key"),120),
                    "sha256",asset.get("stored_sha256"),"sizeBytes",asset.get("stored_size"),"mimeType",asset.get("declared_mime")));
            if (reference!=null) image.putAll(reference);
            images.add(image);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schemaVersion", 1);
        response.put("phase", snapshot.get("phase"));
        response.put("requirements", snapshot.get("requirements"));
        if (snapshot.get("imageOptions") instanceof Map<?, ?> imageOptions) {
            response.put("imageOptions", imageOptions);
        }
        response.put("images", images);
        return CommonResult.success(response);
    }

    private static Map<String,Object> referenceMetadata(Object raw,String assetId) {
        var result=new LinkedHashMap<String,Object>();
        // Old snapshots are intentionally not enriched from today's case version.
        Object floor=null,version=null,source=null;
        if (raw!=null) {
            if (!(raw instanceof Map<?,?> ref) || !assetId.equals(ref.get("assetId")))
                throw new IllegalStateException("RUNTIME_REFERENCE_INVALID");
            floor=ref.get("floorNo");version=ref.get("sourceCaseVersionId");source=ref.get("sourceType");
            if (floor!=null && (!(floor instanceof Integer n) || n<1)
                    || version!=null && (!(version instanceof String id) || !id.matches("[1-9][0-9]{0,18}"))
                    || source!=null && !Set.of("CASE_FLOOR_PLAN","USER_SKETCH").contains(source)
                    || "USER_SKETCH".equals(source) && (floor!=null || version!=null))
                throw new IllegalStateException("RUNTIME_REFERENCE_INVALID");
        }
        result.put("floorNo",floor);result.put("sourceCaseVersionId",version);result.put("sourceType",source);
        return result;
    }

    @PostMapping("/{id}/output-tickets")
    public CommonResult<Map<String,Object>> upload(@PathVariable String id,@Valid @RequestBody Upload upload,HttpServletRequest request) {
        var job=requireLease(id,upload.attemptNo(),upload.fencingToken(),request);
        if(upload.slot()>((Number)job.get("requested_count")).intValue()) throw new AccessDeniedException("RUNTIME_SLOT");
        String key="ai-quarantine/"+id+"/"+upload.fencingToken()+"/"+upload.slot()+"/"+UUID.randomUUID()
                +("image/png".equals(upload.mimeType())?".png":".jpg");
        return CommonResult.success(Map.of("objectKey",key,"uploadUrl",storage.presignUploadUrl(key,120),
                "maxBytes",20*1024*1024));
    }
}
