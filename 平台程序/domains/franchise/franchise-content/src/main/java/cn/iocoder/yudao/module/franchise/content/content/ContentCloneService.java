package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import cn.iocoder.yudao.module.franchise.content.provider.*;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 公司人物名下的分身/声音；只按本地就绪编号生成口播，外部标识不会交给客户端。 */
@Service
public class ContentCloneService {
    public static final String CONSENT_VERSION = "digital-clone-v1";
    private final JdbcTemplate jdbc;
    private final ContentPersonaService personas;
    private final ContentCloneUploadService uploads;
    private final OrgPointPort points;
    private final HiflyCloneClient provider;
    private final ContentProviderProperties properties;
    private final TransactionTemplate tx;
    public ContentCloneService(JdbcTemplate jdbc, ContentPersonaService personas, ContentCloneUploadService uploads,
            OrgPointPort points, ContentProviderProperties properties,
            ContentProviderTransport transport, ContentProviderCallRecorder recorder, PlatformTransactionManager tm) {
        this.jdbc=jdbc; this.personas=personas; this.uploads=uploads; this.points=points;
        this.properties=properties; this.provider=new HiflyCloneClient(properties.getHifly(),transport,recorder);
        this.tx=new TransactionTemplate(tm);
    }
    public record CloneRow(long id,long company,long persona,long author,String kind,String title,long asset,
            String hash,String status,String task,String resource,String demo,String failure,long operation,String billing) { }
    public record DigitalSelection(long personaId,String avatar,String voice) { }
    private final org.springframework.jdbc.core.RowMapper<CloneRow> mapper=(r,i)->new CloneRow(r.getLong("id"),r.getLong("company_id"),r.getLong("persona_id"),
            r.getLong("author_user_id"),r.getString("kind"),r.getString("title"),r.getLong("source_asset_id"),r.getString("request_hash"),
            r.getString("status"),r.getString("provider_task_id"),r.getString("provider_resource_id"),r.getString("demo_url"),
            r.getString("failure_reason"),r.getLong("operation_id"),r.getString("billing_state"));
    private static ServiceException invalid(String text) { return new ServiceException(1_114_000_001,text); }
    private static String subject(String kind) {
        if(kind==null)throw invalid("请选择数字分身或克隆声音");
        return switch(kind) { case "AVATAR" -> "avatar_clone"; case "VOICE" -> "voice_clone"; default -> throw invalid("请选择数字分身或克隆声音"); };
    }
    private void requirePersona(long company,long id,boolean enabled) {
        var persona=personas.list(company).stream().filter(p->Objects.equals(p.getId(),id)).findFirst().orElseThrow(()->invalid("人物不存在或无权使用"));
        if (enabled && Boolean.FALSE.equals(persona.getEnabled())) throw invalid("人物已停用，请先启用后使用");
    }
    public void assertAccess(PostSnapshot post,String kind) {
        points.assertSubjectAllowed(post.orgId(),post.userId(),subject(kind));
    }
    public Map<String,Object> quote(PostSnapshot post,String kind) {
        try { assertAccess(post,kind); }
        catch (ServiceException e) { return Map.of("available",false,"message",e.getMessage()); }
        if (properties.getProvider()!=ContentProviderProperties.ProviderMode.REAL || !provider.configured()) return Map.of("available",false,"message","数字人制作服务尚未配置，请联系平台");
        try { return Map.of("available",true,"credits",points.quoteCredits(subject(kind),1),"consentVersion",CONSENT_VERSION); }
        catch (IllegalArgumentException e) { return Map.of("available",false,"message","该制作功能尚未配置积分价格，请联系平台"); }
    }
    public List<Map<String,Object>> list(PostSnapshot post,long persona) {
        requirePersona(post.orgId(),persona,false);
        Set<Long> defaults=new HashSet<>(jdbc.queryForList("SELECT clone_id FROM fr_content_clone_default WHERE company_id=? AND persona_id=?",Long.class,post.orgId(),persona));
        return jdbc.query("SELECT * FROM fr_content_digital_clone WHERE company_id=? AND persona_id=? ORDER BY id DESC",mapper,post.orgId(),persona)
                .stream().map(row->view(row,defaults.contains(row.id()))).toList();
    }
    public Map<String,Object> setDefault(PostSnapshot post,long id) {
        if(!Set.of("sales-lead","boss","owner").contains(post.roleCode()))throw invalid("当前岗位不能设置人物默认资源");
        return tx.execute(status->{
            CloneRow row=require(post.orgId(),id);requirePersona(post.orgId(),row.persona(),true);
            if(!usable(row))throw invalid("只能将已就绪并完成结算的分身或声音设为默认");
            jdbc.update("INSERT INTO fr_content_clone_default(company_id,persona_id,kind,clone_id,updated_by) VALUES(?,?,?,?,?) ON CONFLICT(company_id,persona_id,kind) DO UPDATE SET clone_id=EXCLUDED.clone_id,updated_by=EXCLUDED.updated_by,update_time=now()",
                    post.orgId(),row.persona(),row.kind(),row.id(),post.userId());
            return view(row,true);
        });
    }
    private CloneRow require(long company,long id) {
        return jdbc.query("SELECT * FROM fr_content_digital_clone WHERE company_id=? AND id=?",mapper,company,id).stream().findFirst().orElseThrow(()->invalid("分身或声音不存在或无权使用"));
    }
    public static void requireConsent(String consent) {
        if(!CONSENT_VERSION.equals(consent))throw invalid("请在最新版小程序确认本人素材或已获授权，再提交飞影制作");
    }
    public Map<String,Object> create(PostSnapshot post,long persona,String kind,String title,long source,String consent,String key,long credits) {
        subject(kind); personas.requireWritable(post,persona); requirePersona(post.orgId(),persona,true);
        String clean=title==null?"":title.trim();
        if(clean.isEmpty()||clean.length()>20||key==null||key.isBlank()||key.length()>100||credits<1)throw invalid("请填写20字内的名称，并确认制作价格");
        requireConsent(consent);
        String hash=hash(List.of(persona,kind,clean,source,consent,credits).toString());
        var replay=jdbc.query("SELECT * FROM fr_content_digital_clone WHERE company_id=? AND author_user_id=? AND idempotency_key=?",mapper,post.orgId(),post.userId(),key);
        if(!replay.isEmpty()) { if(!hash.equals(replay.get(0).hash()))throw invalid("请求参数已变化，请重新确认制作"); return view(replay.get(0)); }
        if(properties.getProvider()!=ContentProviderProperties.ProviderMode.REAL||!provider.configured())throw invalid("数字人制作服务尚未配置");
        assertAccess(post,kind);
        var uploaded=uploads.requireAuthorizedUpload(post,persona,source,kind,consent);
        long id=IdWorker.getId();
        boolean inserted=Boolean.TRUE.equals(tx.execute(status->{
            int count=jdbc.update("INSERT INTO fr_content_digital_clone(id,company_id,persona_id,author_user_id,author_post_id,kind,title,source_upload_id,source_sha256,consent_version,idempotency_key,request_hash,status,operation_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'SUBMITTING',0) ON CONFLICT(company_id,author_user_id,idempotency_key) DO NOTHING",
                    id,post.orgId(),persona,post.userId(),post.postId(),kind,clean,source,uploaded.sha256(),consent,key,hash);
            if(count==0)return false;
            long operation=points.reserveConfirmed(new OrgPointPort.ReserveCommand("content-clone:"+id,post.orgId(),post.userId(),post.postId(),subject(kind),1,"content-clone",id),credits);
            jdbc.update("UPDATE fr_content_digital_clone SET operation_id=? WHERE id=?",operation,id);
            return true;
        }));
        if(!inserted){var row=jdbc.query("SELECT * FROM fr_content_digital_clone WHERE company_id=? AND author_user_id=? AND idempotency_key=?",mapper,post.orgId(),post.userId(),key).get(0);if(!hash.equals(row.hash()))throw invalid("请求参数已变化，请重新确认制作");return view(row);}
        // 预扣和提交意图已提交；外呼绝不能放进事务或自动重试。
        try {
            String task=provider.createByFile(kind,clean,uploaded.fileId(),new ProviderCallAudit(post.orgId(),id));
            jdbc.update("UPDATE fr_content_digital_clone SET provider_task_id=?,status='RUNNING',update_time=now() WHERE id=? AND status='SUBMITTING'",task,id);
        } catch(ContentProviderException.Rejection|ContentProviderException.Unavailable e) {
            jdbc.update("UPDATE fr_content_digital_clone SET status='FAILED',failure_reason=?,update_time=now() WHERE id=?", e.getMessage(),id);
        } catch(RuntimeException e) {
            jdbc.update("UPDATE fr_content_digital_clone SET status='UNKNOWN',failure_reason=?,update_time=now() WHERE id=?", "提交结果待核实，请勿重复制作，请联系管理员核查",id);
        }
        settle(require(post.orgId(),id));
        return view(require(post.orgId(),id));
    }
    /** 旧版本只允许回读已经提交的同笔制作，禁止新增经平台云存储的制作。 */
    public Map<String,Object> replayLegacy(PostSnapshot post,long persona,String kind,String title,long source,String consent,String key,long credits) {
        requirePersona(post.orgId(),persona,false);
        if(kind==null||consent==null||key==null)throw invalid("上传方式已更新，请重新上传素材");
        String hash=hash(List.of(persona,kind,title==null?"":title.trim(),source,consent,credits).toString());
        var rows=jdbc.query("SELECT * FROM fr_content_digital_clone WHERE company_id=? AND author_user_id=? AND idempotency_key=?",mapper,post.orgId(),post.userId(),key);
        if(rows.isEmpty()||!hash.equals(rows.get(0).hash()))throw invalid("上传方式已更新，请重新上传素材");
        return view(rows.get(0));
    }
    public Map<String,Object> refresh(PostSnapshot post,long id) {
        CloneRow row=require(post.orgId(),id); requirePersona(post.orgId(),row.persona(),false);
        // CAS 限制多设备重复刷新；进程中断后租约到期即可继续只读查询。
        if(row.task()!=null&&!row.task().isBlank()&&!Set.of("READY","FAILED").contains(row.status())
                &&jdbc.update("UPDATE fr_content_digital_clone SET refresh_until=now()+interval '60 seconds' WHERE id=? AND (refresh_until IS NULL OR refresh_until<now())",id)==1){
            try {
                var receipt=provider.poll(row.kind(),row.task(),new ProviderCallAudit(post.orgId(),id));
                jdbc.update("UPDATE fr_content_digital_clone SET status=?,provider_resource_id=?,demo_url=?,failure_reason=?,update_time=now() WHERE id=? AND status NOT IN('READY','FAILED')",
                        receipt.status(),receipt.resourceId().isBlank()?null:receipt.resourceId(),receipt.demoUrl(),"FAILED".equals(receipt.status())?"制作失败，请检查素材后重新制作":null,id);
            } catch(ContentProviderException e) { /* 查询失败保留在途事实和预扣，禁止重新制作。 */ }
            finally { jdbc.update("UPDATE fr_content_digital_clone SET refresh_until=NULL WHERE id=?",id); }
        }
        settle(require(post.orgId(),id));
        return view(require(post.orgId(),id));
    }
    private void settle(CloneRow row) {
        if(!"PENDING".equals(row.billing()))return;
        String result;
        if("READY".equals(row.status())) { points.settle(row.operation(),1); result="SETTLED"; }
        else if("FAILED".equals(row.status())) { points.release(row.operation()); result="RELEASED"; }
        else return;
        jdbc.update("UPDATE fr_content_digital_clone SET billing_state=? WHERE id=? AND billing_state='PENDING'",result,row.id());
    }
    public DigitalSelection select(PostSnapshot post,long avatar,long voice) {
        CloneRow a=require(post.orgId(),avatar),v=require(post.orgId(),voice);
        requirePersona(post.orgId(),a.persona(),true);
        if(a.persona()!=v.persona()||!"AVATAR".equals(a.kind())||!"VOICE".equals(v.kind())||!"READY".equals(a.status())||!"READY".equals(v.status())
                ||!"SETTLED".equals(a.billing())||!"SETTLED".equals(v.billing()))throw invalid("请选择同一人物名下已就绪的数字分身和克隆声音");
        return new DigitalSelection(a.persona(),a.resource(),v.resource());
    }
    public Map<String,Object> view(CloneRow row) {
        boolean selected=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fr_content_clone_default WHERE company_id=? AND persona_id=? AND kind=? AND clone_id=?)",Boolean.class,row.company(),row.persona(),row.kind(),row.id()));
        return view(row,selected);
    }
    private static boolean usable(CloneRow row) {
        return "READY".equals(row.status())&&"SETTLED".equals(row.billing())&&row.resource()!=null&&!row.resource().isBlank();
    }
    private Map<String,Object> view(CloneRow row,boolean selected) {
        var view=new LinkedHashMap<String,Object>();
        view.put("id",String.valueOf(row.id()));view.put("personaId",String.valueOf(row.persona()));view.put("kind",row.kind());view.put("title",row.title());
        view.put("status",row.status());view.put("ready",usable(row));view.put("isDefault",selected&&usable(row));
        view.put("failureReason",row.failure()==null?"":row.failure());
        // 供应商试听短链仅供当前页使用，不作为声音永久标识。
        view.put("demoUrl",row.demo()==null?"":row.demo());
        return view;
    }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) {throw new IllegalStateException(e);} }
}
