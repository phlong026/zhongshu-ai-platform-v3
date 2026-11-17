package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentPersonaDO;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.*;

/** 不完整内容可存草稿，只有明确确认后才写正式人物；所有写入都比较版本。 */
@Service
public class ContentPersonaDraftService {
    private static final Map<String,Integer> LIMITS = Map.ofEntries(
            Map.entry("name",64), Map.entry("who",200), Map.entry("sell",500),
            Map.entry("audience",200), Map.entry("expressionStyle",500),
            Map.entry("audienceNeeds",600), Map.entry("factualBackground",2000),
            Map.entry("sampleScript",2000), Map.entry("forbiddenClaims",600),
            Map.entry("cta",500), Map.entry("ipType",32), Map.entry("track",100), Map.entry("differentiator",500));
    private final JdbcTemplate jdbc;
    private final ContentPersonaService personas;
    public ContentPersonaDraftService(JdbcTemplate jdbc, ContentPersonaService personas) {
        this.jdbc=jdbc; this.personas=personas;
    }
    public record Draft(boolean exists, long revision, Map<String,Object> form,
                        Long publishedRevision, Long publishedPersonaId, Long basePersonaVersion,
                        Long personaVersion, Map<String,Object> personaForm) {}

    public Draft get(PostSnapshot post, String key) {
        authorize(post,key);
        var rows=jdbc.queryForList("SELECT * FROM fr_content_persona_draft WHERE company_id=? AND user_id=? AND draft_key=?",
                post.orgId(),post.userId(),key);
        Draft stored=rows.isEmpty()?new Draft(false,0,Map.of(),null,null,null,null,Map.of()):view(rows.get(0));
        if ("new".equals(key)) return stored;
        var person=personas.requireWritable(post,Long.parseLong(key));
        var form=ContentPersonaService.snapshotMap(person);
        form.replaceAll((k,v)->v==null?"":v); form.put("enabled",person.getEnabled());
        return new Draft(stored.exists(),stored.revision(),stored.form(),stored.publishedRevision(),stored.publishedPersonaId(),
                stored.basePersonaVersion(),person.getVersion(),form);
    }

    @Transactional
    public Draft save(PostSnapshot post, String key, long revision, Map<String,Object> form) {
        return save(post,key,revision,form,null,false);
    }

    @Transactional
    public Draft save(PostSnapshot post, String key, long revision, Map<String,Object> form, Long baseVersion, boolean rebase) {
        authorize(post,key); checkRevision(revision);
        if (baseVersion != null) checkRevision(baseVersion);
        Map<String,Object> clean=validate(form);
        ensure(post,key);
        var current=jdbc.queryForMap("SELECT * FROM fr_content_persona_draft WHERE company_id=? AND user_id=? AND draft_key=? FOR UPDATE",
                post.orgId(),post.userId(),key);
        Long base=number(current.get("base_persona_version"));
        if (!"new".equals(key)) {
            if (current.get("form")==null || rebase) {
                if (baseVersion==null) throw exception(PERSONA_VERSION_CONFLICT);
                if (rebase && !baseVersion.equals(personas.requireWritable(post,Long.parseLong(key)).getVersion()))
                    throw exception(PERSONA_VERSION_CONFLICT);
                base=baseVersion;
            } else if (baseVersion!=null && !baseVersion.equals(base)) throw exception(PERSONA_VERSION_CONFLICT);
        }
        int count=jdbc.update("""
                UPDATE fr_content_persona_draft SET form=CAST(? AS jsonb),revision=revision+1,
                    published_revision=NULL,published_persona_id=NULL,base_persona_version=?,updated_at=now()
                WHERE company_id=? AND user_id=? AND draft_key=? AND revision=?
                """,JsonUtils.toJsonString(clean),base,post.orgId(),post.userId(),key,revision);
        if(count!=1)throw exception(PERSONA_DRAFT_CONFLICT);
        return get(post,key);
    }

    @Transactional
    public FrContentPersonaDO publish(PostSnapshot post, String key, long revision) {
        authorize(post,key); checkRevision(revision);
        var rows=jdbc.queryForList("""
                SELECT * FROM fr_content_persona_draft WHERE company_id=? AND user_id=? AND draft_key=? FOR UPDATE
                """,post.orgId(),post.userId(),key);
        if(rows.isEmpty())throw exception(PERSONA_DRAFT_CONFLICT);
        Draft draft=view(rows.get(0));
        // 确认请求丢失响应后重试，返回同一个人物，不再新建。
        if(draft.publishedRevision()!=null&&draft.publishedRevision()==revision)
            return personas.requireWritable(post,draft.publishedPersonaId());
        if(!draft.exists()||draft.revision()!=revision)throw exception(PERSONA_DRAFT_CONFLICT);
        var body=new LinkedHashMap<>(draft.form());
        if (!"new".equals(key)) {
            if (draft.basePersonaVersion()==null) throw exception(PERSONA_VERSION_CONFLICT);
            body.put("version",draft.basePersonaVersion());
        }
        var person="new".equals(key)?personas.create(post,body):personas.update(post,Long.parseLong(key),body);
        jdbc.update("""
                UPDATE fr_content_persona_draft SET form=NULL,revision=revision+1,
                    published_revision=?,published_persona_id=?,updated_at=now()
                WHERE company_id=? AND user_id=? AND draft_key=?
                """,revision,person.getId(),post.orgId(),post.userId(),key);
        return person;
    }

    private void authorize(PostSnapshot post,String key) {
        if(!ContentPersonaService.canCreate(post))throw exception(CONTENT_FORBIDDEN);
        if("new".equals(key))return;
        if(key==null||!key.matches("[1-9][0-9]{0,18}"))throw exception(INPUT_INVALID);
        try { personas.requireWritable(post,Long.parseLong(key)); }
        catch(NumberFormatException e){throw exception(INPUT_INVALID);}
    }
    private void checkRevision(long value) {if(value<0||value>=9_007_199_254_740_990L)throw exception(INPUT_INVALID);}
    private void ensure(PostSnapshot post,String key) {
        jdbc.update("INSERT INTO fr_content_persona_draft(company_id,user_id,draft_key) VALUES(?,?,?) ON CONFLICT DO NOTHING",
                post.orgId(),post.userId(),key);
    }
    private Map<String,Object> validate(Map<String,Object> form) {
        if(form==null||form.size()>LIMITS.size()+1)throw exception(INPUT_INVALID);
        var result=new LinkedHashMap<String,Object>();
        form.forEach((key,value)->{
            if("enabled".equals(key)) {if(!(value instanceof Boolean))throw exception(INPUT_INVALID);}
            else if(!LIMITS.containsKey(key)||!(value instanceof String)||((String)value).length()>LIMITS.get(key))
                throw exception(INPUT_INVALID);
            result.put(key,value);
        });
        return result;
    }
    @SuppressWarnings("unchecked")
    private Draft view(Map<String,Object> row) {
        Object form=row.get("form");
        return new Draft(form!=null,((Number)row.get("revision")).longValue(),
                form==null?Map.of():JsonUtils.parseObject(form.toString(),Map.class),
                number(row.get("published_revision")),number(row.get("published_persona_id")),number(row.get("base_persona_version")),null,Map.of());
    }
    private Long number(Object value){return value==null?null:((Number)value).longValue();}
}
