package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import cn.iocoder.yudao.module.design.capability.CaseReferenceImportPort;
import cn.iocoder.yudao.module.design.rights.RightsGrantService;
import cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Imports only an authoritative, versioned public entitlement. No B asset number is reused in the provider. */
@Component
public class PublishedCaseReferenceImporter implements CaseReferenceImportPort,AutoCloseable {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ObjectMapper json;
    private final AssetService assets;private final CaseCatalogService catalog;private final RightsGrantService rights;
    private final String sourceBase;private final Set<String> hosts;
    @Value("${zhongshu.design.reference.source-tenant-id:1}")
    private String sourceTenant="1";
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ThreadPoolExecutor reads=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"published-reference-read");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public PublishedCaseReferenceImporter(DataSource source,PlatformTransactionManager manager,ObjectMapper json,
            @Qualifier("capabilityAssetService") AssetService assets,CaseCatalogService catalog,RightsGrantService rights,
            @Value("${zhongshu.design.reference.source-base-url:}") String sourceBase,
            @Value("${zhongshu.design.reference.allowed-asset-hosts:}") String hostList) {
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(manager);this.json=json;this.assets=assets;this.catalog=catalog;this.rights=rights;
        this.sourceBase=sourceBase.replaceAll("/+$","");hosts=new HashSet<>();for(String host:hostList.split(","))if(!host.isBlank())hosts.add(host.trim().toLowerCase(Locale.ROOT));
    }
    private JsonNode manifest(long id,Long version,String line) {
        URI base=URI.create(sourceBase);
        if(!"https".equals(base.getScheme())||base.getHost()==null||base.getRawUserInfo()!=null||base.getRawQuery()!=null||base.getFragment()!=null||(!base.getPath().isEmpty()&&!base.getPath().equals("/")))throw new IllegalStateException("Published case source URL is not configured");
        byte[] bytes=read(URI.create(sourceBase+"/app-api/design/v1/cases/"+id+"/generation-reference?line="+line+(version==null?"":"&caseVersionId="+version)),1<<20);
        try {
            JsonNode reply=json.readTree(bytes);int code=reply.path("code").asInt(-1);
            if(code!=0)throw new ServiceException(code,reply.path("msg").asText("参考案例授权不可用"));
            JsonNode value=reply.path("data");
            if(!String.valueOf(id).equals(value.path("caseId").asText())||!line.equals(value.path("line").asText())||!value.path("images").isArray()
                    ||value.path("images").isEmpty()||value.path("images").size()>32||!Instant.parse(value.path("validUntil").asText()).isAfter(Instant.now()))throw new IllegalStateException("Invalid case entitlement response");
            if(version!=null&&!String.valueOf(version).equals(value.path("caseVersionId").asText()))throw new ServiceException(1099000001,"案例版本已更新，请重新选择");
            return value;
        } catch(java.io.IOException failure){throw new IllegalStateException("Invalid case entitlement JSON",failure);}
    }
    private byte[] read(URI uri,int limit) {
        HttpResponse<java.io.InputStream> response;
        boolean source=uri.getPath().startsWith("/app-api/design/v1/cases/")&&Objects.equals(uri.getHost(),URI.create(sourceBase).getHost());
        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET();
        if(source) {if(!sourceTenant.matches("[0-9]{1,18}"))throw new IllegalStateException("Invalid public catalog tenant");request.header("tenant-id",sourceTenant);}
        try { response=http.send(request.build(),HttpResponse.BodyHandlers.ofInputStream()); }
        catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw new IllegalStateException("Published reference is temporarily unavailable",failure);}
        try(var stream=response.body()) {
            if(response.statusCode()!=200) {
                if(response.statusCode()==403&&source)throw new ServiceException(403,"参考案例授权已失效");
                if(response.statusCode()==409)throw new ServiceException(1099000001,"案例版本已更新");
                throw new IllegalStateException("Reference source HTTP "+response.statusCode());
            }
            if(response.headers().firstValueAsLong("content-length").orElse(0)>limit)throw new IllegalStateException("Reference body exceeds limit");
            Future<byte[]> read=reads.submit(()->stream.readNBytes(limit+1));
            try {byte[] bytes=read.get(30,TimeUnit.SECONDS);if(bytes.length>limit)throw new IllegalStateException("Reference body exceeds limit");return bytes;}
            catch(Exception failure){read.cancel(true);throw new IllegalStateException("Reference body read failed",failure);}
        } catch(java.io.IOException failure){throw new IllegalStateException("Reference body close failed",failure);}
    }
    @Override public DesignFlowCall prepare(long owner,DesignFlowCall call) {
        if(call.body().get("refCaseId")==null)return call;
        long id=positive(call.body().get("refCaseId"));Long version=call.body().get("refCaseVersionId")==null?null:positive(call.body().get("refCaseVersionId"));
        String line=call.path().contains("elevation-jobs")?"ELEVATION":String.valueOf(call.body().getOrDefault("line","FLAT"));
        materialize(owner,manifest(id,version,line));return mapped(owner,call);
    }
    @Override public DesignFlowCall mapped(long owner,DesignFlowCall call) {
        if(call.body().get("refCaseId")==null)return call;
        long source=positive(call.body().get("refCaseId"));Object version=call.body().get("refCaseVersionId");
        var rows=jdbc.queryForList("SELECT case_id,version_id FROM design_capability_case_mirror WHERE owner_id=? AND source_case_id=? AND valid_until>now() "+(version==null?"":"AND source_version_id=? ")+"ORDER BY validated_at DESC LIMIT 1",version==null?new Object[]{owner,source}:new Object[]{owner,source,positive(version)});
        if(rows.isEmpty())throw new IllegalStateException("Prepared case entitlement expired");
        var body=new LinkedHashMap<>(call.body());body.put("refCaseId",rows.get(0).get("case_id").toString());
        if(call.path().equals("/design-projects"))body.put("refCaseVersionId",rows.get(0).get("version_id").toString());
        return new DesignFlowCall(call.method(),call.path(),call.query(),body,call.idempotencyKey());
    }
    @Override public void refreshProject(long owner,long project) {
        var rows=jdbc.queryForList("SELECT m.source_case_id,m.source_version_id,p.stage FROM design_project p JOIN design_capability_case_mirror m ON m.owner_id=p.user_id AND m.case_id=p.ref_case_id WHERE p.id=? AND p.user_id=? AND p.deleted=false",project,owner);
        if(rows.isEmpty())return;
        var row=rows.get(0);materialize(owner,manifest(((Number)row.get("source_case_id")).longValue(),((Number)row.get("source_version_id")).longValue(),row.get("stage").toString()));
    }
    private void materialize(long owner,JsonNode manifest) {
        materialize(owner,manifest,true);
    }
    private void materialize(long owner,JsonNode manifest,boolean renew) {
        long sourceCase=positive(manifest.path("caseId").asText()),sourceVersion=positive(manifest.path("caseVersionId").asText());
        Instant until=Instant.parse(manifest.path("validUntil").asText());if(until.isAfter(Instant.now().plusSeconds(180)))throw new IllegalStateException("Case entitlement lease is too long");
        var imported=new LinkedHashMap<String,Long>();
        for(JsonNode image:manifest.path("images")) {
            String sourceAsset=String.valueOf(positive(image.path("assetId").asText())),sha=image.path("sha256").asText(),mime=image.path("mimeType").asText();
            long size=image.path("sizeBytes").asLong();String key="case-"+sourceAsset+"-"+sha;
            boolean required="FLAT".equals(manifest.path("line").asText())?image.path("role").asText().equals("FLOOR_PLAN"):Set.of("COVER","ELEVATION").contains(image.path("role").asText());
            if(required&&!image.path("generationAllowed").asBoolean())throw new ServiceException(403,"参考案例图片授权不完整");
            if(!sha.matches("[a-fA-F0-9]{64}")||!cn.iocoder.yudao.module.design.asset.AssetTypePolicy.CASE_IMAGE.mimeAllowed(mime)||size<1||size>cn.iocoder.yudao.module.design.asset.AssetTypePolicy.CASE_IMAGE.maxBytes()||!Set.of("COVER","FLOOR_PLAN","ELEVATION").contains(image.path("role").asText()))throw new IllegalStateException("Invalid reference image metadata");
            var cached=jdbc.queryForList("SELECT a.id FROM design_capability_case_image r JOIN asset a ON a.id=r.asset_id WHERE r.owner_id=? AND r.request_key=? AND a.upload_status='ACCEPTED' AND a.deleted=false",Long.class,owner,key);
            long asset;
            if(!cached.isEmpty())asset=cached.get(0);
            else {
                URI url=URI.create(image.path("downloadUrl").asText());
                if(!"https".equals(url.getScheme())||url.getHost()==null||!hosts.contains(url.getHost().toLowerCase(Locale.ROOT))||url.getRawUserInfo()!=null||url.getFragment()!=null||(url.getPort()!=-1&&url.getPort()!=443))throw new IllegalStateException("Reference object host is not configured");
                byte[] bytes=read(url,(int)size);
                try {if(bytes.length!=size||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equalsIgnoreCase(sha))throw new ServiceException(1071000003,"参考案例图片校验未通过");}
                catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
                asset=assets.importPublishedCaseImage(owner,key,mime,bytes);
            }
            imported.put(sourceAsset,asset);
        }
        if(until.isBefore(Instant.now().plusSeconds(5))) {
            if(!renew)throw new ServiceException(403,"参考案例授权已过期，请重新选择");
            materialize(owner,manifest(sourceCase,sourceVersion,manifest.path("line").asText()),false);
            return;
        }
        tx.executeWithoutResult(status->{
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?,?)",Object.class,Long.hashCode(owner),Long.hashCode(sourceVersion));
            var old=jdbc.queryForList("SELECT * FROM design_capability_case_mirror WHERE owner_id=? AND source_case_id=? AND source_version_id=?",owner,sourceCase,sourceVersion);
            long localCase,localVersion;
            if(old.isEmpty()) {
                JsonNode c=manifest.path("case");
                @SuppressWarnings("unchecked") List<Map<String,Object>> rooms=json.convertValue(c.path("rooms"),List.class);
                @SuppressWarnings("unchecked") List<String> tags=json.convertValue(c.path("tags"),List.class);
                localCase=catalog.createCompanyCase(owner,c.path("title").asText(),c.path("description").asText(),c.path("styleCode").asText(),c.path("floorCount").asInt(),c.path("buildingArea").asInt(),number(c,"faceWidth"),number(c,"depth"),rooms,tags,c.path("dimensionBasis").asText("UNKNOWN"));
                localVersion=jdbc.queryForObject("SELECT current_version_id FROM design_case WHERE id=?",Long.class,localCase);
                for(JsonNode image:manifest.path("images"))jdbc.update("INSERT INTO design_case_asset(id,case_version_id,asset_id,asset_role,floor_no) VALUES(?,?,?,?,?)",IdWorker.getId(),localVersion,imported.get(image.path("assetId").asText()),image.path("role").asText(),image.path("floorNo").isIntegralNumber()?image.path("floorNo").asInt():null);
                jdbc.update("INSERT INTO design_capability_case_mirror(owner_id,source_case_id,source_version_id,case_id,version_id,line,valid_until) VALUES(?,?,?,?,?,?,?)",owner,sourceCase,sourceVersion,localCase,localVersion,manifest.path("line").asText(),Timestamp.from(until));
            } else {
                localCase=((Number)old.get(0).get("case_id")).longValue();localVersion=((Number)old.get(0).get("version_id")).longValue();
                var expected=new HashSet<String>();for(JsonNode image:manifest.path("images"))expected.add(imported.get(image.path("assetId").asText())+":"+image.path("role").asText()+":"+(image.path("floorNo").isInt()?image.path("floorNo").asInt():null));
                var actual=new HashSet<String>(jdbc.query("SELECT asset_id,asset_role,floor_no FROM design_case_asset WHERE case_version_id=? AND deleted=false",(rs,index)->rs.getLong(1)+":"+rs.getString(2)+":"+rs.getObject(3),localVersion));
                if(!actual.equals(expected))throw new ServiceException(1099000001,"案例图纸已变化，请重新选择案例版本");
            }
            Instant effective=jdbc.queryForObject("SELECT transaction_timestamp()",Timestamp.class).toInstant();
            for(JsonNode image:manifest.path("images")) {
                long asset=imported.get(image.path("assetId").asText());
                rights.createGrant(owner,asset,"PUBLIC_DISPLAY","Published case "+sourceCase+"@"+sourceVersion,"*","published case reference",effective,until);
                if(image.path("generationAllowed").asBoolean())rights.createGrant(owner,asset,"GENERATION_REFERENCE","Published case "+sourceCase+"@"+sourceVersion,"*","design generation",effective,until);
            }
            if(old.isEmpty()&&!catalog.publish(localCase,"reference-import"))throw new IllegalStateException("Imported case could not be published");
            jdbc.update("UPDATE design_capability_case_mirror SET valid_until=?,validated_at=now(),line=? WHERE owner_id=? AND source_case_id=? AND source_version_id=?",Timestamp.from(until),manifest.path("line").asText(),owner,sourceCase,sourceVersion);
        });
    }
    @Override public Object publicResult(long owner,Object result) {
        JsonNode tree=json.valueToTree(result);restoreIds(owner,tree);return tree;
    }
    private void restoreIds(long owner,JsonNode value) {
        if(value.isObject()&&value.hasNonNull("refCaseId")) {
            var rows=jdbc.queryForList("SELECT source_case_id,source_version_id FROM design_capability_case_mirror WHERE owner_id=? AND case_id=?",owner,positive(value.path("refCaseId").asText()));
            if(!rows.isEmpty()){((ObjectNode)value).put("refCaseId",rows.get(0).get("source_case_id").toString());if(value.has("refCaseVersionId"))((ObjectNode)value).put("refCaseVersionId",rows.get(0).get("source_version_id").toString());}
        }
        if(value.isContainerNode())value.forEach(child->restoreIds(owner,child));
    }
    private static java.math.BigDecimal number(JsonNode value,String name){return value.hasNonNull(name)?new java.math.BigDecimal(value.path(name).asText()):null;}
    private static long positive(Object value){try{long id=Long.parseLong(String.valueOf(value));if(id<=0)throw new NumberFormatException();return id;}catch(NumberFormatException e){throw new ServiceException(400,"案例编号无效");}}
    @Override public void close(){reads.shutdownNow();}
}
