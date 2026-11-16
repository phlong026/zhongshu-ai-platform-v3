package cn.iocoder.yudao.module.platform.training;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.operations.OperationsGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.iocoder.yudao.module.platform.training.TrainingModel.*;

@Service
public class TrainingLearning {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.iocoder.yudao.framework.tenant.core.service.TenantFrameworkService tenants;
    private final JdbcTemplate jdbc;private final TrainingCatalog catalog;private final TrainingFiles files;
    private final OperationsGuard guard;private final CurrentPost posts;private final TransactionTemplate tx;private final ObjectMapper json;
    public TrainingLearning(JdbcTemplate jdbc,TrainingCatalog catalog,TrainingFiles files,OperationsGuard guard,CurrentPost posts,PlatformTransactionManager tm,ObjectMapper json){this.jdbc=jdbc;this.catalog=catalog;this.files=files;this.guard=guard;this.posts=posts;tx=new TransactionTemplate(tm);this.json=json;}
    private Map<String,Object> ticket(long tenant,long user,Long post,String course,String release,String version,String purpose){
        var v=files.version(version,tenant);check("READY".equals(v.get("state")),"资料尚未可用");String key=key(v,purpose);check(key!=null,"该资料不支持此操作");
        String token=id()+id();long seconds="play".equals(purpose)?Math.min(14400,Math.max(900,((Number)v.get("duration")).longValue()*2+300)):300;
        jdbc.update("INSERT INTO pf_training_ticket(token_hash,tenant_id,actor_id,post_id,course_id,release_id,version_id,purpose,expires_at) VALUES(?,?,?,?,?,?,?,?,now()+(?*interval '1 second'))",TrainingFiles.digest(token.getBytes(StandardCharsets.UTF_8)),tenant,user,post,course,release,version,purpose,seconds);
        return Map.of("path","/training/media/"+token,"expiresIn",seconds,"fileName",v.get("file_name"),"extension",v.get("extension"));
    }
    public Map<String,Object> adminTicket(String version,String purpose){var a=guard.require();check(Set.of("preview","play","poster","download").contains(purpose),"操作无效");return ticket(a.tenantId(),a.userId(),null,null,null,version,purpose);}
    public Map<String,Object> learnerTicket(String course,String version,String purpose){var p=posts.require();long tenant=catalog.tenant();var c=catalog.released(course,p,tenant);catalog.materialAllowed(c,version,purpose);return ticket(tenant,p.userId(),p.postId(),course,catalog.row(course,tenant,false).get("active_release").toString(),version,purpose);}
    static String key(Map<String,Object> v,String purpose){Object key=switch(purpose){case "play"->v.get("media_key");case "preview"->v.get("preview_key");case "poster"->v.get("poster_key");case "download"->v.get("original_key");default->null;};return key==null?null:key.toString();}
    public record Media(java.nio.file.Path file,String name,String mime,boolean download) { }
    public Media media(String token){
        check(token!=null&&token.matches("[a-f0-9-]{72}"),"访问凭据无效");String hash=TrainingFiles.digest(token.getBytes(StandardCharsets.UTF_8));
        var rows=jdbc.queryForList("SELECT * FROM pf_training_ticket WHERE token_hash=? AND expires_at>now()",hash);if(rows.isEmpty())throw new ServiceException(403,"访问凭据已失效，请重新打开课程");var t=rows.get(0);long tenant=((Number)t.get("tenant_id")).longValue(),user=((Number)t.get("actor_id")).longValue();
        if(tenants!=null)tenants.validTenant(tenant);
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM system_users WHERE id=? AND tenant_id=? AND status=0 AND deleted=0)",Boolean.class,user,tenant)),"账号已失效");
        String version=t.get("version_id").toString(),purpose=t.get("purpose").toString();
        if(t.get("post_id")==null){check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM system_user_role ur JOIN system_role r ON r.id=ur.role_id AND r.tenant_id=ur.tenant_id WHERE ur.user_id=? AND ur.tenant_id=? AND ur.deleted=0 AND r.deleted=0 AND r.status=0 AND r.code='super_admin')",Boolean.class,user,tenant)),"管理权限已失效");}
        else{
            var actor=jdbc.queryForList("SELECT p.*,o.org_type FROM pf_staff_post p JOIN pf_org_unit o ON o.dept_id=p.org_id JOIN system_dept d ON d.id=o.dept_id WHERE p.id=? AND p.user_id=? AND p.status='ACTIVE' AND o.status='ACTIVE' AND d.tenant_id=? AND d.deleted=0 AND d.status=0",t.get("post_id"),user,tenant);
            check(!actor.isEmpty(),"工作身份已失效");var p=actor.get(0);var post=new PostSnapshot(((Number)p.get("id")).longValue(),user,((Number)p.get("org_id")).longValue(),p.get("org_type").toString(),p.get("role_code").toString(),null,false,Set.of());
            // Always recheck current release scope. The bytes remain pinned to the issued release.
            catalog.released(t.get("course_id").toString(),post,tenant);
            var release=jdbc.queryForList("SELECT content FROM pf_training_release WHERE id=? AND tenant_id=? AND course_id=?",t.get("release_id"),tenant,t.get("course_id"));check(!release.isEmpty(),"课程版本不存在");catalog.materialAllowed(catalog.decode(release.get(0).get("content")),version,purpose);
        }
        var v=files.version(version,tenant);check("READY".equals(v.get("state")),"资料不可用");String key=key(v,purpose);check(key!=null,"资料不支持此操作");
        String extension=key.substring(key.lastIndexOf('.')+1);String mime=switch(extension){case "pdf"->"application/pdf";case "mp4"->"video/mp4";case "mp3"->"audio/mpeg";case "jpg","jpeg"->"image/jpeg";case "png"->"image/png";case "webp"->"image/webp";default->"application/octet-stream";};
        return new Media(files.readPath(key),v.get("file_name").toString(),mime,"download".equals(purpose));
    }
    public Map<String,Object> begin(String course,String lesson){var p=posts.require();long tenant=catalog.tenant();var c=catalog.released(course,p,tenant);var l=catalog.lesson(c,lesson);var v=files.version(l.mainVersionId(),tenant);String play=id(),release=catalog.row(course,tenant,false).get("active_release").toString();
        var old=jdbc.queryForList("SELECT position FROM pf_training_progress WHERE tenant_id=? AND org_id=? AND user_id=? AND course_id=? AND lesson_id=? AND version_id=?",tenant,p.orgId(),p.userId(),course,lesson,l.mainVersionId());double position=old.isEmpty()?0:((Number)old.get(0).get("position")).doubleValue();
        jdbc.update("INSERT INTO pf_training_play(id,tenant_id,org_id,user_id,course_id,lesson_id,version_id,release_id,position) VALUES(?,?,?,?,?,?,?,?,?)",play,tenant,p.orgId(),p.userId(),course,lesson,l.mainVersionId(),release,position);
        String purpose=v.get("media_key")==null?"preview":"play";var result=new LinkedHashMap<>(ticket(tenant,p.userId(),p.postId(),course,release,l.mainVersionId(),purpose));result.put("playId",play);result.put("position",position);result.put("duration",v.get("duration"));result.put("document",v.get("media_key")==null);return result;
    }
    public Map<String,Object> heartbeat(Beat input,boolean confirmRead){check(input!=null&&Double.isFinite(input.position())&&input.position()>=0,"播放位置无效");var post=posts.require();long tenant=catalog.tenant();return tx.execute(status->{
        var rows=jdbc.queryForList("SELECT * FROM pf_training_play WHERE id=? AND tenant_id=? AND org_id=? AND user_id=? AND expires_at>now() FOR UPDATE",input.playId(),tenant,post.orgId(),post.userId());check(!rows.isEmpty(),"学习会话已失效，请重新打开课时");var s=rows.get(0);String course=s.get("course_id").toString(),lesson=s.get("lesson_id").toString(),version=s.get("version_id").toString();var c=catalog.released(course,post,tenant);var l=catalog.lesson(c,lesson);check(l.mainVersionId().equals(version),"课件已更新，请重新打开课时");var v=files.version(version,tenant);
        jdbc.update("INSERT INTO pf_training_progress(tenant_id,org_id,user_id,course_id,lesson_id,version_id) VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",tenant,post.orgId(),post.userId(),course,lesson,version);
        var progress=jdbc.queryForMap("SELECT * FROM pf_training_progress WHERE tenant_id=? AND org_id=? AND user_id=? AND course_id=? AND lesson_id=? AND version_id=? FOR UPDATE",tenant,post.orgId(),post.userId(),course,lesson,version);
        List<double[]> ranges;try{ranges=json.readValue(progress.get("ranges").toString(),new TypeReference<List<double[]>>(){});}catch(Exception e){throw new IllegalStateException(e);}
        Instant now=Instant.now();double duration=((Number)v.get("duration")).doubleValue(),previous=((Number)s.get("position")).doubleValue(),elapsed=Duration.between(instant(s.get("last_ping")),now).toMillis()/1000.0,position=Math.min(input.position(),duration);
        boolean complete=Boolean.TRUE.equals(progress.get("completed"));
        if(confirmRead){check(v.get("media_key")==null,"视频与音频需要实际播放后完成");check(Duration.between(instant(s.get("created_at")),now).getSeconds()>=30,"请先阅读至少30秒后确认已读");complete=true;}
        else if(v.get("media_key")!=null&&elapsed>=0.8&&elapsed<=45&&position>previous&&position-previous<=elapsed*2.0+1.5){ranges=merge(ranges,new double[]{previous,position});double covered=ranges.stream().mapToDouble(r->r[1]-r[0]).sum();complete=complete||duration>0&&covered>=duration*l.completionPercent()/100.0;}
        jdbc.update("UPDATE pf_training_play SET position=?,last_ping=? WHERE id=?",position,java.sql.Timestamp.from(now),input.playId());
        jdbc.update("UPDATE pf_training_progress SET ranges=?::jsonb,completed=?,position=?,updated_at=now() WHERE tenant_id=? AND org_id=? AND user_id=? AND course_id=? AND lesson_id=? AND version_id=?",catalog.encode(ranges),complete,position,tenant,post.orgId(),post.userId(),course,lesson,version);
        return Map.of("completed",complete,"position",position,"coveredSeconds",ranges.stream().mapToDouble(r->r[1]-r[0]).sum());
    });}
    static Instant instant(Object t){return t instanceof java.sql.Timestamp ts?ts.toInstant():((OffsetDateTime)t).toInstant();}
    static List<double[]> merge(List<double[]> old,double[] added){var sorted=new ArrayList<>(old);sorted.add(added);sorted.sort(Comparator.comparingDouble(x->x[0]));var out=new ArrayList<double[]>();for(var r:sorted){if(out.isEmpty()||out.get(out.size()-1)[1]<r[0])out.add(r.clone());else out.get(out.size()-1)[1]=Math.max(out.get(out.size()-1)[1],r[1]);}return out;}
}
