package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.content.provider.*;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** 只管理飞影文件编号；客户端持短期签名凭证直接PUT，平台不接收媒体字节。 */
@Service
public class ContentCloneUploadService {
    private final JdbcTemplate jdbc;
    private final ContentPersonaService personas;
    private final ContentProviderProperties properties;
    private final HiflyCloneClient provider;
    public record Uploaded(long id,String fileId,String sha256) { }
    public ContentCloneUploadService(JdbcTemplate jdbc,ContentPersonaService personas,ContentProviderProperties properties,
                                    ContentProviderTransport transport,ContentProviderCallRecorder recorder) {
        this.jdbc=jdbc;this.personas=personas;this.properties=properties;
        this.provider=new HiflyCloneClient(properties.getHifly(),transport,recorder);
    }
    private static ServiceException invalid(String message){return new ServiceException(1_114_000_001,message);}
    private void persona(PostSnapshot post,long id) {
        if(!Boolean.TRUE.equals(personas.requireWritable(post,id).getEnabled()))throw invalid("人物不存在、已停用或无权使用");
    }
    private static void media(String kind,String mime,long size,String sha) {
        if(!"AVATAR".equals(kind)&&!"VOICE".equals(kind))throw invalid("请选择分身或声音");
        boolean video="AVATAR".equals(kind);
        if(!(video?"video/mp4":"audio/mpeg").equals(mime))throw invalid(video?"请上传MP4人物视频":"请上传MP3声音样本");
        if(size<=0||size>(video?50L:20L)*1024*1024)throw invalid(video?"人物视频须在50MB以内":"声音样本须在20MB以内");
        if(sha==null||!sha.matches("[a-fA-F0-9]{64}"))throw invalid("素材校验信息无效，请重新选择");
    }
    public Map<String,Object> issue(PostSnapshot post,long persona,String kind,String mime,long size,String sha) {
        persona(post,persona);media(kind,mime,size,sha);
        if(properties.getProvider()!=ContentProviderProperties.ProviderMode.REAL||!provider.configured())throw invalid("数字人制作服务尚未配置");
        long id=IdWorker.getId();
        var grant=provider.uploadGrant("AVATAR".equals(kind)?"mp4":"mp3",new ProviderCallAudit(post.orgId(),id));
        if(("AVATAR".equals(kind))!=grant.contentType().startsWith("video/"))throw invalid("素材上传类型不匹配，请重试");
        Instant expires=Instant.now().plusSeconds(900);
        jdbc.update("INSERT INTO fr_content_hifly_upload(id,company_id,persona_id,author_user_id,author_post_id,kind,provider_file_id,mime_type,size_bytes,declared_sha256,status,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,'ISSUED',?)",
                id,post.orgId(),persona,post.userId(),post.postId(),kind,grant.fileId(),mime,size,sha.toLowerCase(Locale.ROOT),Timestamp.from(expires));
        return Map.of("uploadId",String.valueOf(id),"uploadUrl",grant.url(),"contentType",grant.contentType(),"expiresAt",expires.toEpochMilli());
    }
    private Map<String,Object> owned(PostSnapshot post,long id) {
        return jdbc.queryForList("SELECT * FROM fr_content_hifly_upload WHERE id=? AND company_id=? AND author_user_id=?",id,post.orgId(),post.userId())
                .stream().findFirst().orElseThrow(()->invalid("上传记录不存在或无权使用"));
    }
    public Map<String,Object> complete(PostSnapshot post,long id) {
        var row=owned(post,id);persona(post,((Number)row.get("persona_id")).longValue());
        if("UPLOADED".equals(row.get("status")))return Map.of("uploadId",String.valueOf(id),"status","UPLOADED");
        int changed=jdbc.update("UPDATE fr_content_hifly_upload SET status='UPLOADED',completed_at=now() WHERE id=? AND company_id=? AND author_user_id=? AND status='ISSUED' AND expires_at>now()",id,post.orgId(),post.userId());
        if(changed!=1)throw invalid("上传凭证已过期，请重新上传");
        return Map.of("uploadId",String.valueOf(id),"status","UPLOADED");
    }
    public Uploaded requireUploaded(PostSnapshot post,long persona,long id,String kind) {
        var row=owned(post,id);
        if(((Number)row.get("persona_id")).longValue()!=persona)throw invalid("请使用当前人物的素材");
        if(!kind.equals(row.get("kind")))throw invalid("AVATAR".equals(kind)?"请上传MP4人物视频":"请上传MP3声音样本");
        if(!"UPLOADED".equals(row.get("status")))throw invalid("素材尚未上传完成");
        media(kind,(String)row.get("mime_type"),((Number)row.get("size_bytes")).longValue(),(String)row.get("declared_sha256"));
        return new Uploaded(id,(String)row.get("provider_file_id"),(String)row.get("declared_sha256"));
    }
}
