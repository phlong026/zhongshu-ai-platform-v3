package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import cn.iocoder.yudao.module.franchise.content.ContentPermissions;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentPersonaDO;
import cn.iocoder.yudao.module.franchise.content.provider.*;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.CONTENT_FORBIDDEN;

/** 公司人物名下的分身/声音；只按本地就绪编号生成口播，外部标识不会交给客户端。 */
@Service
public class ContentCloneService {
    public static final String CONSENT_VERSION = "digital-clone-v1";
    private static final Set<String> ORGANIZATION_MANAGERS = Set.of("sales-lead", "boss", "owner");
    private final JdbcTemplate jdbc;
    private final SystemDirectory directory;
    private final ContentPersonaService personas;
    private final ContentCloneUploadService uploads;
    private final OrgPointPort points;
    private final HiflyCloneClient provider;
    private final ContentProviderProperties properties;
    private final TransactionTemplate tx;
    public ContentCloneService(JdbcTemplate jdbc, ContentPersonaService personas, ContentCloneUploadService uploads,
            OrgPointPort points, ContentProviderProperties properties,
            ContentProviderTransport transport, ContentProviderCallRecorder recorder, PlatformTransactionManager tm,
            SystemDirectory directory) {
        this.jdbc=jdbc; this.personas=personas; this.uploads=uploads; this.points=points;
        this.properties=properties; this.provider=new HiflyCloneClient(properties.getHifly(),transport,recorder);
        this.tx=new TransactionTemplate(tm);
        this.directory=directory;
    }
    public record CloneRow(long id,long company,long persona,long author,String kind,String title,long asset,
            String hash,String status,String task,String resource,String demo,String failure,long operation,String billing,boolean archived) { }
    public record DigitalSelection(long personaId,String avatar,String voice) { }
    private final org.springframework.jdbc.core.RowMapper<CloneRow> mapper=(r,i)->new CloneRow(r.getLong("id"),r.getLong("company_id"),r.getLong("persona_id"),
            r.getLong("author_user_id"),r.getString("kind"),r.getString("title"),r.getLong("source_asset_id"),r.getString("request_hash"),
            r.getString("status"),r.getString("provider_task_id"),r.getString("provider_resource_id"),r.getString("demo_url"),
            r.getString("failure_reason"),r.getLong("operation_id"),r.getString("billing_state"),r.getObject("archived_at")!=null);
    private static ServiceException invalid(String text) { return new ServiceException(1_114_000_001,text); }
    private static String subject(String kind) {
        if(kind==null)throw invalid("请选择数字分身或克隆声音");
        return switch(kind) { case "AVATAR" -> "avatar_clone"; case "VOICE" -> "voice_clone"; default -> throw invalid("请选择数字分身或克隆声音"); };
    }
    private FrContentPersonaDO requirePersona(long company, long id) {
        var persona = personas.findInCompany(company, id);
        if (persona == null) throw invalid("人物不存在或无权使用");
        return persona;
    }
    private static void requireEnabled(FrContentPersonaDO persona) {
        if (!Boolean.TRUE.equals(persona.getEnabled())) throw invalid("人物已停用，请先启用后使用");
    }
    private record Management(boolean organization, boolean own) {
        boolean managesPersona(PostSnapshot post, FrContentPersonaDO persona) {
            return organization || own && Objects.equals(persona.getAuthorUserId(), post.userId());
        }
        boolean managesResource(PostSnapshot post, FrContentPersonaDO persona, CloneRow row) {
            return managesPersona(post, persona) && (organization || row.author() == post.userId());
        }
    }
    private Management management(PostSnapshot post) {
        if (!ContentPersonaService.canCreate(post)) return new Management(false, false);
        boolean full = directory.roleHasPermission(post.roleCode(), ContentPermissions.PERSONA);
        return new Management(full && ORGANIZATION_MANAGERS.contains(post.roleCode()),
                full || directory.roleHasPermission(post.roleCode(), ContentPermissions.PERSONA_SELF));
    }
    private FrContentPersonaDO requireManageable(PostSnapshot post, CloneRow row) {
        var persona = personas.requireWritable(post, row.persona());
        if (!management(post).managesResource(post, persona, row)) throw exception(CONTENT_FORBIDDEN);
        return persona;
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
    public List<Map<String,Object>> list(PostSnapshot post, long personaId) {
        var persona = requirePersona(post.orgId(), personaId);
        var access = management(post);
        Set<Long> defaults = new HashSet<>(jdbc.queryForList(
                "SELECT clone_id FROM fr_content_clone_default WHERE company_id=? AND persona_id=?", Long.class, post.orgId(), personaId));
        return jdbc.query("SELECT * FROM fr_content_digital_clone WHERE company_id=? AND persona_id=? AND archived_at IS NULL ORDER BY id DESC",
                        mapper, post.orgId(), personaId).stream()
                .map(row -> view(row, defaults.contains(row.id()), access.managesResource(post, persona, row), persona.getEnabled()))
                .toList();
    }
    public Map<String,Object> rename(PostSnapshot post, long id, String title) {
        String clean = title == null ? "" : title.trim();
        if (clean.isEmpty() || clean.length() > 20) throw invalid("请填写20字内的名称");
        return tx.execute(status -> {
            CloneRow row = require(post.orgId(), id, true);
            var persona = requireManageable(post, row);
            requireActive(row);
            jdbc.update("UPDATE fr_content_digital_clone SET title=?,update_time=now() WHERE company_id=? AND id=?",
                    clean, post.orgId(), id);
            CloneRow renamed = require(post.orgId(), id);
            return view(renamed, isDefault(renamed), true, persona.getEnabled());
        });
    }
    public void delete(PostSnapshot post, long id) {
        tx.executeWithoutResult(status -> {
            CloneRow row = require(post.orgId(), id, true);
            requireManageable(post, row);
            if (row.archived()) return;
            if (!canArchive(row)) throw invalid("只能删除制作已结束且积分已结清的分身或声音");
            jdbc.update("DELETE FROM fr_content_clone_default WHERE company_id=? AND persona_id=? AND kind=? AND clone_id=?",
                    post.orgId(), row.persona(), row.kind(), row.id());
            jdbc.update("UPDATE fr_content_digital_clone SET archived_at=now(),update_time=now() WHERE company_id=? AND id=?",
                    post.orgId(), id);
        });
    }
    public Map<String,Object> setDefault(PostSnapshot post, long id) {
        return tx.execute(status -> {
            CloneRow row = require(post.orgId(), id, true);
            var persona = requireManageable(post, row);
            requireActive(row);
            requireEnabled(persona);
            if (!usable(row)) throw invalid("只能将已就绪并完成结算的分身或声音设为默认");
            jdbc.update("INSERT INTO fr_content_clone_default(company_id,persona_id,kind,clone_id,updated_by) VALUES(?,?,?,?,?) ON CONFLICT(company_id,persona_id,kind) DO UPDATE SET clone_id=EXCLUDED.clone_id,updated_by=EXCLUDED.updated_by,update_time=now()",
                    post.orgId(), row.persona(), row.kind(), row.id(), post.userId());
            return view(row, true, true, true);
        });
    }
    private static void requireActive(CloneRow row) {
        if (row.archived()) throw invalid("分身或声音已删除");
    }
    private CloneRow require(long company, long id) {
        return require(company, id, false);
    }
    private CloneRow require(long company, long id, boolean lock) {
        // Mutations lock the same resource row before touching the default pointer.
        String sql = "SELECT * FROM fr_content_digital_clone WHERE company_id=? AND id=?" + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, mapper, company, id).stream().findFirst()
                .orElseThrow(() -> invalid("分身或声音不存在或无权使用"));
    }
    public Map<String,Object> create(PostSnapshot post,long persona,String kind,String title,long source,String consent,String key,long credits) {
        subject(kind);
        var person = personas.requireWritable(post, persona);
        requireEnabled(person);
        var access = management(post);
        String clean=title==null?"":title.trim();
        if(clean.isEmpty()||clean.length()>20||key==null||key.isBlank()||key.length()>100||credits<1)throw invalid("请填写20字内的名称，并确认制作价格");
        if(!CONSENT_VERSION.equals(consent))throw invalid("请确认本人素材或已获授权，允许在本公司制作和使用分身、声音");
        String hash=hash(List.of(persona,kind,clean,source,consent,credits).toString());
        var replay=jdbc.query("SELECT * FROM fr_content_digital_clone WHERE company_id=? AND author_user_id=? AND idempotency_key=?",mapper,post.orgId(),post.userId(),key);
        if(!replay.isEmpty()) { if(!hash.equals(replay.get(0).hash()))throw invalid("请求参数已变化，请重新确认制作"); return viewFor(post, person, replay.get(0), access); }
        if (!access.managesPersona(post, person)) throw exception(CONTENT_FORBIDDEN);
        if(properties.getProvider()!=ContentProviderProperties.ProviderMode.REAL||!provider.configured())throw invalid("数字人制作服务尚未配置");
        var uploaded=uploads.requireUploaded(post,persona,source,kind);
        long id=IdWorker.getId();
        boolean inserted=Boolean.TRUE.equals(tx.execute(status->{
            int count=jdbc.update("INSERT INTO fr_content_digital_clone(id,company_id,persona_id,author_user_id,author_post_id,kind,title,source_upload_id,source_sha256,consent_version,idempotency_key,request_hash,status,operation_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'SUBMITTING',0) ON CONFLICT(company_id,author_user_id,idempotency_key) DO NOTHING",
                    id,post.orgId(),persona,post.userId(),post.postId(),kind,clean,source,uploaded.sha256(),consent,key,hash);
            if(count==0)return false;
            long operation=points.reserveConfirmed(new OrgPointPort.ReserveCommand("content-clone:"+id,post.orgId(),post.userId(),post.postId(),subject(kind),1,"content-clone",id),credits);
            jdbc.update("UPDATE fr_content_digital_clone SET operation_id=? WHERE id=?",operation,id);
            return true;
        }));
        if(!inserted){var row=jdbc.query("SELECT * FROM fr_content_digital_clone WHERE company_id=? AND author_user_id=? AND idempotency_key=?",mapper,post.orgId(),post.userId(),key).get(0);if(!hash.equals(row.hash()))throw invalid("请求参数已变化，请重新确认制作");return viewFor(post, person, row, access);}
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
        return viewFor(post, person, require(post.orgId(),id), access);
    }
    /** 旧版本只允许回读已经提交的同笔制作，禁止新增经平台云存储的制作。 */
    public Map<String,Object> replayLegacy(PostSnapshot post,long persona,String kind,String title,long source,String consent,String key,long credits) {
        var person = requirePersona(post.orgId(), persona);
        if(kind==null||consent==null||key==null)throw invalid("上传方式已更新，请重新上传素材");
        String hash=hash(List.of(persona,kind,title==null?"":title.trim(),source,consent,credits).toString());
        var rows=jdbc.query("SELECT * FROM fr_content_digital_clone WHERE company_id=? AND author_user_id=? AND idempotency_key=?",mapper,post.orgId(),post.userId(),key);
        if(rows.isEmpty()||!hash.equals(rows.get(0).hash()))throw invalid("上传方式已更新，请重新上传素材");
        return viewFor(post, person, rows.get(0), management(post));
    }
    public Map<String,Object> refresh(PostSnapshot post,long id) {
        CloneRow row = require(post.orgId(), id);
        var persona = requirePersona(post.orgId(), row.persona());
        requireActive(row);
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
        CloneRow refreshed = require(post.orgId(), id);
        requireActive(refreshed);
        return viewFor(post, persona, refreshed, management(post));
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
        requireEnabled(requirePersona(post.orgId(), a.persona()));
        requireActive(a);
        requireActive(v);
        if (a.persona() != v.persona() || !"AVATAR".equals(a.kind()) || !"VOICE".equals(v.kind()) || !usable(a) || !usable(v))throw invalid("请选择同一人物名下已就绪的数字分身和克隆声音");
        return new DigitalSelection(a.persona(),a.resource(),v.resource());
    }
    private boolean isDefault(CloneRow row) {
        return !row.archived() && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM fr_content_clone_default WHERE company_id=? AND persona_id=? AND kind=? AND clone_id=?)",
                Boolean.class, row.company(), row.persona(), row.kind(), row.id()));
    }
    private Map<String,Object> viewFor(PostSnapshot post, FrContentPersonaDO persona, CloneRow row, Management access) {
        return view(row, isDefault(row), access.managesResource(post, persona, row), persona.getEnabled());
    }
    private static boolean usable(CloneRow row) {
        return "READY".equals(row.status()) && "SETTLED".equals(row.billing()) && row.resource() != null && !row.resource().isBlank();
    }
    private static boolean canArchive(CloneRow row) {
        return "READY".equals(row.status()) && "SETTLED".equals(row.billing())
                || "FAILED".equals(row.status()) && "RELEASED".equals(row.billing());
    }
    private Map<String,Object> view(CloneRow row, boolean selected, boolean manage, Boolean personaEnabled) {
        boolean active = !row.archived();
        boolean ready = active && usable(row);
        var view = new LinkedHashMap<String,Object>();
        view.put("id", String.valueOf(row.id()));
        view.put("personaId", String.valueOf(row.persona()));
        view.put("kind", row.kind());
        view.put("title", row.title());
        view.put("status", row.status());
        view.put("archived", row.archived());
        view.put("ready", ready);
        view.put("isDefault", selected && ready);
        view.put("canRename", active && manage);
        view.put("canDelete", active && manage && canArchive(row));
        view.put("canSetDefault", manage && ready && !selected && Boolean.TRUE.equals(personaEnabled));
        view.put("failureReason", row.failure() == null ? "" : row.failure());
        // Archived receipts keep the provider status but no playable sample or management options.
        view.put("demoUrl", !active || row.demo() == null ? "" : row.demo());
        return view;
    }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) {throw new IllegalStateException(e);} }
}
