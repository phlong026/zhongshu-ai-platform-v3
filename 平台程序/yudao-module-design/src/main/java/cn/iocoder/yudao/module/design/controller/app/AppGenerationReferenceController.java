package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import cn.iocoder.yudao.module.design.catalog.CaseGenerationCandidateService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.security.PermitAll;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/** Anonymous, read-only catalog entitlement export. Only globally granted, published images are eligible. */
@RestController
@RequestMapping("/design/v1/cases")
@PermitAll
public class AppGenerationReferenceController {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    private final CaseCatalogService catalog;private final CaseGenerationCandidateService candidates;private final AssetService assets;
    public AppGenerationReferenceController(DataSource source,PlatformTransactionManager manager,CaseCatalogService catalog,
            CaseGenerationCandidateService candidates,@org.springframework.beans.factory.annotation.Qualifier("assetService") AssetService assets) {
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(manager);tx.setIsolationLevel(4);this.catalog=catalog;this.candidates=candidates;this.assets=assets;
    }
    @GetMapping("/{caseId}/generation-reference")
    public CommonResult<Map<String,Object>> manifest(@PathVariable long caseId,@RequestParam(required=false) Long caseVersionId,@RequestParam String line) {
        if(!Set.of("FLAT","ELEVATION").contains(line))throw new IllegalArgumentException("Invalid design line");
        return success(tx.execute(status->{
            var detail=catalog.getPublishedCase(caseId).orElseThrow(()->denied("案例已下架或不存在"));
            if(caseVersionId!=null&&caseVersionId!=detail.caseVersionId())throw new cn.iocoder.yudao.framework.common.exception.ServiceException(1099000001,"案例版本已更新，请重新选择");
            if(line.equals("FLAT"))candidates.requireCandidate(caseId,detail.caseVersionId());
            var rows=jdbc.queryForList("SELECT ca.asset_id,ca.asset_role,ca.floor_no,COALESCE(a.stored_sha256,a.sha256) AS sha256,CASE WHEN a.declared_mime IN ('image/gif','image/bmp','image/tiff') THEN 'image/png' ELSE a.declared_mime END AS stored_mime,COALESCE(a.stored_size,a.size_bytes) AS size_bytes,"+
                    "EXISTS(SELECT 1 FROM asset_rights_grant g WHERE g.asset_id=a.id AND g.tenant_id=0 AND g.scope='PUBLIC_DISPLAY' AND g.status='ACTIVE' AND g.deleted=false AND g.effective_at<=now() AND (g.expires_at IS NULL OR g.expires_at>now())) AS display_allowed,"+
                    "EXISTS(SELECT 1 FROM asset_rights_grant g WHERE g.asset_id=a.id AND g.tenant_id=0 AND g.scope='GENERATION_REFERENCE' AND g.status='ACTIVE' AND g.deleted=false AND g.effective_at<=now() AND (g.expires_at IS NULL OR g.expires_at>now())) AS generation_allowed "+
                    "FROM design_case_asset ca JOIN asset a ON a.id=ca.asset_id AND a.tenant_id=0 AND a.deleted=false "+
                    "AND a.upload_status='ACCEPTED' AND a.security_scan_status='PASSED' AND a.moderation_status='PASSED' "+
                    "WHERE ca.case_version_id=? AND ca.tenant_id=0 AND ca.deleted=false AND ca.asset_role IN ('COVER','FLOOR_PLAN','ELEVATION') ORDER BY ca.asset_role,ca.floor_no NULLS LAST,ca.id",detail.caseVersionId());
            if(rows.isEmpty())throw denied("案例图纸不可用");
            long expected=jdbc.queryForObject("SELECT count(*) FROM design_case_asset WHERE case_version_id=? AND tenant_id=0 AND deleted=false AND asset_role IN ('COVER','FLOOR_PLAN','ELEVATION')",Long.class,detail.caseVersionId());
            if(expected!=rows.size())throw denied("案例图纸校验状态已改变");
            List<Map<String,Object>> images=new ArrayList<>();
            Instant validUntil=Instant.now().plusSeconds(120);
            for(var row:rows) {
                String role=String.valueOf(row.get("asset_role"));boolean generation=Boolean.TRUE.equals(row.get("generation_allowed"));
                boolean required=line.equals("FLAT")?role.equals("FLOOR_PLAN"):Set.of("COVER","ELEVATION").contains(role);
                if(!Boolean.TRUE.equals(row.get("display_allowed"))||(required&&!generation))throw denied("案例图片授权已失效");
                long asset=((Number)row.get("asset_id")).longValue();var token=assets.requestDownloadTicket(0,asset);
                for(String scope:generation?List.of("PUBLIC_DISPLAY","GENERATION_REFERENCE"):List.of("PUBLIC_DISPLAY")) {
                    var expiry=jdbc.queryForObject("SELECT max(LEAST(COALESCE(expires_at,now()+interval '120 seconds'),now()+interval '120 seconds')) FROM asset_rights_grant WHERE asset_id=? AND tenant_id=0 AND scope=? AND status='ACTIVE' AND deleted=false AND effective_at<=now() AND (expires_at IS NULL OR expires_at>now())",java.sql.Timestamp.class,asset,scope);
                    if(expiry==null)throw denied("案例图片授权已失效");
                    if(expiry.toInstant().isBefore(validUntil))validUntil=expiry.toInstant();
                }
                var image=new LinkedHashMap<String,Object>();image.put("assetId",String.valueOf(asset));image.put("role",role);image.put("floorNo",row.get("floor_no"));
                image.put("sha256",row.get("sha256"));image.put("mimeType",row.get("stored_mime"));image.put("sizeBytes",row.get("size_bytes"));
                image.put("generationAllowed",generation);image.put("downloadUrl",assets.resolveDownloadTicket(0,asset,token.getToken()));images.add(image);
            }
            var result=new LinkedHashMap<String,Object>();result.put("caseId",String.valueOf(caseId));result.put("caseVersionId",String.valueOf(detail.caseVersionId()));
            if(validUntil.isBefore(Instant.now().plusSeconds(1)))throw denied("案例图片授权即将到期");
            result.put("line",line);result.put("validUntil",validUntil.toString());result.put("case",detail);result.put("images",images);
            return result;
        }));
    }
    @GetMapping("/public-generation-candidates")
    public CommonResult<CaseGenerationCandidateService.Candidates> candidates(@RequestParam int floorCount,
            @RequestParam(required=false) java.math.BigDecimal faceWidth,@RequestParam(required=false) java.math.BigDecimal depth,
            @RequestParam(defaultValue="UNKNOWN") String dimensionBasis) {
        return success(candidates.find(floorCount,faceWidth,depth,dimensionBasis));
    }
    @GetMapping("/{caseId}/public-generation-candidate")
    public CommonResult<CaseGenerationCandidateService.Candidate> candidate(@PathVariable long caseId,@RequestParam long caseVersionId) {
        return success(candidates.requireCandidate(caseId,caseVersionId));
    }
    private static RuntimeException denied(String message){return new cn.iocoder.yudao.framework.common.exception.ServiceException(403,message);}
}
