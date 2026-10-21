package cn.iocoder.yudao.module.design.budget;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.infra.zhongshu.api.AccountStatePort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.*;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.BUDGET_INPUT_INVALID;

/** Independent, account-owned editing state. Imported design inputs are copies, never project writes. */
@Service
public class BudgetDraftService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DesignProjectService projects;
    private final AccountStatePort accounts;
    public BudgetDraftService(DataSource ds, PlatformTransactionManager manager, DesignProjectService projects, AccountStatePort accounts) {
        jdbc = new JdbcTemplate(ds); tx = new TransactionTemplate(manager); this.projects = projects; this.accounts = accounts;
    }
    public record Draft(String draftId, String name, int version, Map<String,Object> state,
                        String sourceProjectId, String sourceVersionId, Map<String,Object> importedValues,
                        Map<String,String> sources, List<String> missingFields, List<String> warnings) {}
    public Draft create(long user, String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,64}")) throw exception(BUDGET_INPUT_INVALID);
        return tx.execute(status -> {
            accounts.requireActiveForWrite(user);
            jdbc.update("INSERT INTO budget_draft(id,user_id,create_key) VALUES(?,?,?) ON CONFLICT(user_id,create_key) DO NOTHING", IdWorker.getId(), user, key);
            long id = jdbc.queryForObject("SELECT id FROM budget_draft WHERE user_id=? AND create_key=? AND deleted=FALSE", Long.class,user,key);
            return get(user,id);
        });
    }
    public List<Draft> list(long user, Long cursor, int limit) {
        if (limit < 1 || limit > 50) throw exception(BUDGET_INPUT_INVALID);
        return jdbc.queryForList("SELECT * FROM budget_draft WHERE tenant_id=0 AND user_id=? AND deleted=FALSE AND (?::bigint IS NULL OR id<?) ORDER BY id DESC LIMIT ?",user,cursor,cursor,limit)
                .stream().map(this::view).toList();
    }
    public Draft get(long user,long id) { return view(require(user,id,false)); }
    public Draft save(long user,long id, int expected, Map<String,Object> state) {
        Map<String,Object> clean = validateState(state);
        return tx.execute(status -> {
            accounts.requireActiveForWrite(user);
            var row = require(user,id,true);
            int version = ((Number)row.get("version")).intValue();
            // Lost response retry is safe; competing edits cannot silently overwrite each other.
            if (expected != version) {
                if (expected == version - 1 && clean.equals(BudgetInputs.readJson(row.get("state").toString()))) return view(row);
                throw exception(STATE_VERSION_CONFLICT);
            }
            jdbc.update("UPDATE budget_draft SET state=CAST(? AS jsonb),version=version+1,update_time=now() WHERE id=?",JsonUtils.toJsonString(clean),id);
            return get(user,id);
        });
    }
    /** 改名只影响展示名，不参与 state 版本管理：允许与参数编辑并发，不做版本校验。 */
    public Draft rename(long user,long id,String name) {
        if(name==null) throw exception(BUDGET_INPUT_INVALID);
        final String clean = name.trim();
        if(clean.isEmpty()||clean.length()>100) throw exception(BUDGET_INPUT_INVALID);
        return tx.execute(status -> {
            accounts.requireActiveForWrite(user);
            require(user,id,false);
            jdbc.update("UPDATE budget_draft SET name=?,update_time=now() WHERE id=?",clean,id);
            return get(user,id);
        });
    }
    public Draft importProject(long user,long id,int expected,long project,Long resultVersion) {
        return tx.execute(status -> {
            accounts.requireActiveForWrite(user);
            var row=require(user,id,true);
            if (((Number)row.get("version")).intValue()!=expected) throw exception(STATE_VERSION_CONFLICT);
            var snapshot=projects.getBudgetInputSnapshot(user,project,resultVersion);
            jdbc.update("UPDATE budget_draft SET source_project_id=?,source_version_id=?,imported_inputs=CAST(? AS jsonb),source_snapshot_ids=CAST(? AS jsonb),state='{\"inputOverrides\":{},\"optionIds\":[]}'::jsonb,version=version+1,update_time=now() WHERE id=?",
                    project,resultVersion,JsonUtils.toJsonString(snapshot.inputs()),JsonUtils.toJsonString(snapshot.requirementSnapshotIds()),id);
            return get(user,id);
        });
    }
    public Map<String,Object> require(long user,long id,boolean lock) {
        if (user<=0 || id<=0) throw exception(RESOURCE_FORBIDDEN);
        var rows=jdbc.queryForList("SELECT * FROM budget_draft WHERE tenant_id=0 AND user_id=? AND id=? AND deleted=FALSE"+(lock?" FOR UPDATE":""),user,id);
        if(rows.isEmpty()) throw exception(RESOURCE_FORBIDDEN);
        return rows.get(0);
    }
    public void requireActive(long user) { accounts.requireActiveForWrite(user); }
    public static Map<String,Object> validateState(Map<String,Object> state) {
        if(state==null || !Set.of("inputOverrides","optionIds").equals(state.keySet()) || !(state.get("inputOverrides") instanceof Map<?,?>) || !(state.get("optionIds") instanceof List<?> ids) || ids.size()>100) throw exception(BUDGET_INPUT_INVALID);
        var overrides=BudgetInputs.readJson(JsonUtils.toJsonString(state.get("inputOverrides")));
        BudgetInputs.resolve(Map.of(),overrides);
        var options=new ArrayList<String>();
        for(Object id:ids) { if(!(id instanceof String value)) throw exception(BUDGET_INPUT_INVALID); BudgetInputs.positiveId(value);options.add(value); }
        if(new HashSet<>(options).size()!=options.size() || JsonUtils.toJsonString(state).length()>16000) throw exception(BUDGET_INPUT_INVALID);
        options.sort(String::compareTo);
        return Map.of("inputOverrides",overrides,"optionIds",List.copyOf(options));
    }
    private Draft view(Map<String,Object> row) {
        var input=BudgetInputs.resolve(BudgetInputs.readJson(row.get("imported_inputs").toString()),Map.of());
        return new Draft(row.get("id").toString(),row.get("name").toString(),((Number)row.get("version")).intValue(),BudgetInputs.readJson(row.get("state").toString()),
                id(row.get("source_project_id")),id(row.get("source_version_id")),input.publicValues(),input.publicSources(),input.missingFields(),input.warnings());
    }
    private String id(Object value) { return value==null?null:value.toString(); }
}
