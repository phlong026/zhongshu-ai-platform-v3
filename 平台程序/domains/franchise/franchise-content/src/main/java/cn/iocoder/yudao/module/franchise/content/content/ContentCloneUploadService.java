package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.content.provider.*;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Legacy direct uploads and idempotent imports of canonical, verified B content assets. */
@Service
public class ContentCloneUploadService {
    private static final long DIRECT_UPLOAD_LIMIT = 20L * 1024 * 1024;
    private static final java.util.concurrent.Semaphore IMPORT_SLOTS = new java.util.concurrent.Semaphore(2);
    private final JdbcTemplate jdbc;
    private final ContentPersonaService personas;
    private final ContentProviderProperties properties;
    private final HiflyCloneClient provider;
    private AssetAccessPort assets;
    private StoreServiceAccountPort accounts;
    @org.springframework.beans.factory.annotation.Autowired
    public void setAssets(AssetAccessPort assets, StoreServiceAccountPort accounts) {
        this.assets = assets; this.accounts = accounts;
    }
    public record Uploaded(long id,String fileId,String sha256) { }
    public ContentCloneUploadService(JdbcTemplate jdbc,ContentPersonaService personas,ContentProviderProperties properties,
                                    ContentProviderTransport transport,ContentProviderCallRecorder recorder) {
        this.jdbc=jdbc;this.personas=personas;this.properties=properties;
        this.provider=new HiflyCloneClient(properties.getHifly(),transport,recorder);
    }
    private static ServiceException invalid(String message){return new ServiceException(1_114_000_001,message);}
    private static ServiceException retryable(String message){return new ServiceException(cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.CLONE_UPLOAD_RETRYABLE.getCode(),message);}
    private void persona(PostSnapshot post,long id) {
        if(!Boolean.TRUE.equals(personas.requireWritable(post,id).getEnabled()))throw invalid("人物不存在、已停用或无权使用");
    }
    private static void media(String kind,String mime,long size,String sha) {
        validateFormat(kind,mime);
        boolean video="AVATAR".equals(kind);
        if(size<=0||size>(video?500L:20L)*1024*1024)throw invalid(video?"人物视频须在500MB以内":"声音样本须在20MB以内");
        if(sha==null||!sha.matches("[a-fA-F0-9]{64}"))throw invalid("素材校验信息无效，请重新选择");
    }
    private static void validateFormat(String kind, String mime) {
        if(!"AVATAR".equals(kind)&&!"VOICE".equals(kind))throw invalid("请选择分身或声音");
        boolean video="AVATAR".equals(kind);
        String extension=extension(mime);
        if(extension==null || (video?!Set.of("mp4","mov").contains(extension):!Set.of("mp3","m4a","wav").contains(extension)))throw invalid(video?"请上传MP4或MOV人物视频（H.264编码）":"请上传MP3、M4A或WAV声音样本");
    }

    private static String extension(String mime) {
        if(mime==null)return null;
        return switch(mime) {case "video/mp4"->"mp4";case "video/mov","video/quicktime"->"mov";case "audio/mpeg","audio/mp3"->"mp3";case "audio/m4a","audio/mp4","audio/x-m4a"->"m4a";case "audio/wav","audio/x-wav","audio/wave"->"wav";default->null;};
    }
    public Map<String,Object> issue(PostSnapshot post,long persona,String kind,String mime,long size,String sha) {
        persona(post,persona);media(kind,mime,size,sha);
        if(size>DIRECT_UPLOAD_LIMIT)throw invalid("素材须在20MB以内，请压缩后重试");
        return grant(post,persona,kind,mime,size,sha.toLowerCase(Locale.ROOT),"ISSUED");
    }

    public Map<String,Object> prepareFileUpload(PostSnapshot post,long persona,String kind,String mime) {
        persona(post,persona);
        validateFormat(kind,mime);
        return grant(post,persona,kind,mime,null,null,"AWAITING_FILE");
    }

    private Map<String,Object> grant(PostSnapshot post,long persona,String kind,String mime,Long size,String sha,String status) {
        if(properties.getProvider()!=ContentProviderProperties.ProviderMode.REAL||!provider.configured())throw invalid("数字人制作服务尚未配置");
        long id=IdWorker.getId();
        HiflyCloneClient.UploadGrant grant;
        try{grant=provider.uploadGrant(extension(mime),new ProviderCallAudit(post.orgId(),id));}
        catch(ContentProviderException error){throw invalid(error.getMessage());}
        if(!extension(mime).equals(extension(grant.contentType())))throw invalid("素材上传类型不匹配，请重试");
        Instant expires=Instant.now().plusSeconds(900);
        jdbc.update("INSERT INTO fr_content_hifly_upload(id,company_id,persona_id,author_user_id,author_post_id,kind,provider_file_id,mime_type,size_bytes,declared_sha256,status,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id,post.orgId(),persona,post.userId(),post.postId(),kind,grant.fileId(),mime,size,sha,status,Timestamp.from(expires));
        return Map.of("uploadId",String.valueOf(id),"uploadUrl",grant.url(),"contentType",grant.contentType(),"expiresAt",expires.toEpochMilli());
    }
    private Map<String,Object> owned(PostSnapshot post,long id) {
        return jdbc.queryForList("SELECT * FROM fr_content_hifly_upload WHERE id=? AND company_id=? AND author_user_id=?",id,post.orgId(),post.userId())
                .stream().findFirst().orElseThrow(()->invalid("上传记录不存在或无权使用"));
    }
    public Map<String,Object> complete(PostSnapshot post,long id) {
        var row=owned(post,id);persona(post,((Number)row.get("persona_id")).longValue());
        if (row.get("source_asset_id") != null) throw invalid("请通过原素材确认上传结果");
        if(!"UPLOADED".equals(row.get("status"))) {
            int changed=jdbc.update("UPDATE fr_content_hifly_upload SET status='UPLOADED',completed_at=now() WHERE id=? AND company_id=? AND author_user_id=? AND status='ISSUED' AND expires_at>now()",id,post.orgId(),post.userId());
            if(changed==0 && !"UPLOADED".equals(owned(post,id).get("status"))) {
                throw invalid("上传凭证已过期，请重新上传");
            }
        }
        // UPLOADED is the client's transfer receipt. Hifly confirms processing via the clone task.
        return receipt(id);
    }
    public Map<String,Object> completeFileUpload(PostSnapshot post,long id,long size,String sha) {
        var row=owned(post,id);
        if (row.get("source_asset_id") != null) throw invalid("请通过原素材确认上传结果");
        if (((Number)row.get("author_post_id")).longValue()!=post.postId()) throw invalid("请使用发起上传的岗位");
        persona(post,((Number)row.get("persona_id")).longValue());
        media((String)row.get("kind"),(String)row.get("mime_type"),size,sha);
        if(size>DIRECT_UPLOAD_LIMIT) throw invalid("素材须在20MB以内，请压缩后重试");
        String digest=sha.toLowerCase(Locale.ROOT);
        int changed=jdbc.update("""
                UPDATE fr_content_hifly_upload SET size_bytes=?,declared_sha256=?,status='UPLOADED',completed_at=now()
                 WHERE id=? AND company_id=? AND author_user_id=? AND author_post_id=?
                   AND status='AWAITING_FILE' AND expires_at>now()
                """,size,digest,id,post.orgId(),post.userId(),post.postId());
        if(changed==0) {
            var existing=owned(post,id);
            if(!"UPLOADED".equals(existing.get("status")) || !digest.equals(existing.get("declared_sha256"))
                    || ((Number)existing.get("size_bytes")).longValue()!=size) throw invalid("上传凭证过期或文件已变化");
        }
        return receipt(id);
    }

    private static Map<String,Object> receipt(long id) {
        return Map.of("uploadId",String.valueOf(id),"status","UPLOADED","providerStatus","UNVERIFIED");
    }

    public Map<String,Object> importAsset(PostSnapshot post, long persona, String kind, long assetId) {
        if (!IMPORT_SLOTS.tryAcquire()) throw retryable("素材处理繁忙，请稍后重试同一素材");
        try { return importOwnedAsset(post, persona, kind, assetId); }
        finally { IMPORT_SLOTS.release(); }
    }

    private record VerifiedSource(String mime, AssetAccessPort.ContentMetadata meta, AssetAccessPort.ContentMedia media,
                                  AssetAccessPort.Actor actor) { }

    private VerifiedSource verifySource(PostSnapshot post, long persona, String kind, long assetId) {
        persona(post, persona);
        if (kind == null || !Set.of("AVATAR", "VOICE").contains(kind) || assetId <= 0) throw invalid("请选择人物视频或声音素材");
        if (assets == null || accounts == null) throw invalid("素材处理通道尚未配置");
        if (properties.getProvider() != ContentProviderProperties.ProviderMode.REAL || !provider.configured())
            throw invalid("数字人制作服务尚未配置");
        var actor = new AssetAccessPort.Actor(post.orgId(), accounts.requireServiceAccount(post.orgId()), post.userId(), post.postId());
        var meta = assets.contentMetadata(actor, assetId);
        String mime = "AVATAR".equals(kind) ? "video/mp4" : "audio/mpeg";
        if (meta == null || !mime.equals(meta.mimeType())) throw invalid("AVATAR".equals(kind) ? "请选择已处理的人物视频" : "请选择已处理的声音样本");
        media(kind, mime, meta.sizeBytes(), meta.sha256());
        // Also enforce the uploader's ownership on receipt replay; organization-wide material read is insufficient.
        var source = assets.readContentMedia(actor, assetId);
        if (source == null || !mime.equals(source.mimeType()) || !meta.sha256().equals(source.sha256())
                || source.bytes().length != meta.sizeBytes()) throw invalid("素材内容与已校验摘要不一致");
        double seconds = source.durationSeconds();
        if (!Double.isFinite(seconds) || seconds < 5 || seconds > ("AVATAR".equals(kind) ? 1800 : 180))
            throw invalid("AVATAR".equals(kind) ? "人物视频需为5秒至30分钟" : "声音样本需为5秒至3分钟");
        if ("AVATAR".equals(kind) && (Math.min(source.width(), source.height()) < 360
                || Math.max(source.width(), source.height()) > 4096)) throw invalid("人物视频分辨率需为360p至4K");
        return new VerifiedSource(mime, meta, source, actor);
    }

    private Map<String,Object> importOwnedAsset(PostSnapshot post, long persona, String kind, long assetId) {
        var verified = verifySource(post, persona, kind, assetId);
        var meta = verified.meta();
        var existing = imports(post, persona, kind, assetId);
        if (existing.isEmpty()) {
            long candidate = IdWorker.getId();
            jdbc.update("""
                    INSERT INTO fr_content_hifly_upload(id,company_id,persona_id,author_user_id,author_post_id,kind,
                      provider_file_id,mime_type,size_bytes,declared_sha256,status,expires_at,source_asset_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?,'ISSUED',?,?) ON CONFLICT DO NOTHING
                    """, candidate, post.orgId(), persona, post.userId(), post.postId(), kind, "asset-import:" + candidate,
                    verified.mime(), meta.sizeBytes(), meta.sha256(), Timestamp.from(Instant.now().plusSeconds(900)), assetId);
        }
        var row = imports(post, persona, kind, assetId).get(0);
        if (!meta.sha256().equals(row.get("declared_sha256"))) throw invalid("素材内容已变化，请重新选择");
        // Keep the current mini-program receipt shape: it has uploaded to our
        // platform, not to the provider. ISSUED remains an untransferred intent.
        return importedReceipt(row);
    }

    public Uploaded requireAuthorizedUpload(PostSnapshot post, long persona, long id, String kind, String consentVersion) {
        ContentCloneService.requireConsent(consentVersion);
        var row = checkedUpload(post, persona, id, kind);
        if (row.get("source_asset_id") == null) return requireUploaded(post, persona, id, kind);
        if (!IMPORT_SLOTS.tryAcquire()) throw retryable("素材处理繁忙，请稍后重试同一素材");
        try {
            long assetId = ((Number) row.get("source_asset_id")).longValue();
            var source = verifySource(post, persona, kind, assetId);
            if (!source.meta().sha256().equals(row.get("declared_sha256"))) throw invalid("素材内容已变化，请重新选择");
            if (!"UPLOADED".equals(row.get("status"))) transferAuthorized(post, persona, id, kind, assetId, source);
            return requireUploaded(post, persona, id, kind);
        } finally { IMPORT_SLOTS.release(); }
    }

    private void transferAuthorized(PostSnapshot post, long persona, long id, String kind, long assetId, VerifiedSource source) {
        String lease = UUID.randomUUID().toString();
        if (jdbc.update("""
                UPDATE fr_content_hifly_upload SET import_token=?,import_until=now()+interval '180 seconds'
                 WHERE id=? AND status='ISSUED' AND (import_until IS NULL OR import_until<now())
                """, lease, id) != 1) {
            if ("UPLOADED".equals(owned(post,id).get("status"))) return;
            throw retryable("素材正在确认上传，请稍后重试同一素材");
        }
        try {
            persona(post, persona);
            var grant = provider.uploadGrant("AVATAR".equals(kind) ? "mp4" : "mp3", new ProviderCallAudit(post.orgId(), id));
            provider.uploadFile(grant, source.media().bytes());
            // Recheck the live source and persona after IO; revoked access cannot publish a usable receipt.
            persona(post, persona); assets.contentMetadata(source.actor(), assetId);
            if (jdbc.update("""
                    UPDATE fr_content_hifly_upload SET provider_file_id=?,status='UPLOADED',completed_at=now(),import_token=NULL,import_until=NULL
                     WHERE id=? AND status='ISSUED' AND import_token=?
                    """, grant.fileId(), id, lease) != 1) throw retryable("素材上传结果待确认，请重试同一素材");
        } catch (ContentProviderException unavailable) {
            throw retryable(unavailable.getMessage());
        } finally {
            jdbc.update("UPDATE fr_content_hifly_upload SET import_token=NULL,import_until=NULL WHERE id=? AND status='ISSUED' AND import_token=?", id, lease);
        }
    }

    private List<Map<String,Object>> imports(PostSnapshot post, long persona, String kind, long asset) {
        return jdbc.queryForList("SELECT * FROM fr_content_hifly_upload WHERE company_id=? AND author_user_id=? AND author_post_id=? AND persona_id=? AND kind=? AND source_asset_id=?",
                post.orgId(), post.userId(), post.postId(), persona, kind, asset);
    }
    private static Map<String,Object> importedReceipt(Map<String,Object> row) {
        var result = new LinkedHashMap<>(receipt(((Number) row.get("id")).longValue()));
        if (!"UPLOADED".equals(row.get("status"))) result.put("providerStatus", "NOT_SENT");
        result.put("assetId", row.get("source_asset_id").toString()); result.put("mimeType", row.get("mime_type"));
        result.put("sizeBytes", row.get("size_bytes")); return result;
    }

    private Map<String,Object> checkedUpload(PostSnapshot post,long persona,long id,String kind) {
        var row=owned(post,id);
        if (row.get("source_asset_id") != null && ((Number) row.get("author_post_id")).longValue() != post.postId())
            throw invalid("请使用发起上传的岗位");
        if(((Number)row.get("persona_id")).longValue()!=persona)throw invalid("请使用当前人物的素材");
        if(!kind.equals(row.get("kind")))throw invalid("AVATAR".equals(kind)?"请上传MP4或MOV人物视频":"请上传MP3、M4A或WAV声音样本");
        return row;
    }
    public Uploaded requireUploaded(PostSnapshot post,long persona,long id,String kind) {
        var row=checkedUpload(post,persona,id,kind);
        if(!"UPLOADED".equals(row.get("status")))throw invalid("素材尚未上传完成");
        media(kind,(String)row.get("mime_type"),((Number)row.get("size_bytes")).longValue(),(String)row.get("declared_sha256"));
        return new Uploaded(id,(String)row.get("provider_file_id"),(String)row.get("declared_sha256"));
    }
}
