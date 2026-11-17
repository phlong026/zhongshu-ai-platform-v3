package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.*;

/** Durable local delivery; never submits a provider request or changes billing facts. */
@Service
public class ContentTaskResultService {
    private final JdbcTemplate jdbc;
    private final ContentTaskStore store;
    private final ContentScriptService scripts;
    public ContentTaskResultService(JdbcTemplate jdbc, ContentTaskStore store, ContentScriptService scripts) {
        this.jdbc=jdbc;this.store=store;this.scripts=scripts;
    }
    public List<Map<String,Object>> pending() {
        return jdbc.queryForList("SELECT id,company_id FROM fr_content_task WHERE status='SUCCEEDED' "
            +"AND kind IN ('copy','asr','link_parse') AND result_state='PENDING' AND result_retry_after<=now() ORDER BY result_retry_after,id LIMIT 30");
    }
    @Transactional(rollbackFor=Exception.class)
    public void deliver(long companyId,long id) {
        var rows=jdbc.queryForList("SELECT id FROM fr_content_task WHERE id=? AND company_id=? AND status='SUCCEEDED' "
            +"AND kind IN ('copy','asr','link_parse') AND result_state='PENDING' AND result_retry_after<=now() FOR UPDATE",id,companyId);
        if(rows.isEmpty())return;
        FrContentTaskDO task=store.byId(companyId,id);
        Map<?,?> result=task.getResultSnapshot()==null?Map.of():JsonUtils.parseObject(task.getResultSnapshot(),Map.class);
        Object value=result.get("text");
        String text=value instanceof String s&&!s.isBlank()?s:null;
        // Link parsing produces media; its optional post caption is not an ASR transcript.
        if(!"link_parse".equals(task.getKind())&&text==null)throw new IllegalStateException("生成结果缺少有效文案");
        switch(task.getKind()) {
            case "copy" -> scripts.recordOptimizeResult(task,text,text(result.get("engine")),task.getModel());
            case "asr" -> scripts.recordExtractedScript(task,"upload",null,null,text,
                result.get("durationSec")==null?null:(int)Double.parseDouble(result.get("durationSec").toString()));
            case "link_parse" -> {
                if(text!=null)scripts.recordExtractedScript(task,"link",text(result.get("videoUrl")),text(result.get("platform")),text,null);
            }
            default -> {return;}
        }
        jdbc.update("UPDATE fr_content_task SET result_state='READY',result_error=NULL WHERE id=? AND company_id=?",id,companyId);
    }
    /** Retry local materialization only. The original provider and billing facts remain immutable. */
    @Transactional(rollbackFor=Exception.class)
    public FrContentTaskDO retrySave(long companyId, long authorUserId, long id) {
        var rows=jdbc.queryForList("SELECT id FROM fr_content_task WHERE id=? AND company_id=? AND author_user_id=? FOR UPDATE",id,companyId,authorUserId);
        if(rows.isEmpty())throw exception(TASK_NOT_FOUND);
        FrContentTaskDO task=store.byId(companyId,id);
        if(!"SUCCEEDED".equals(task.getStatus()) || !java.util.Set.of("copy","asr").contains(task.getKind()))throw exception(INPUT_INVALID);
        jdbc.update("UPDATE fr_content_task SET result_state='PENDING',result_attempts=0,result_error=NULL,result_retry_after=now() "
            +"WHERE id=? AND company_id=? AND author_user_id=? AND result_state='FAILED'",id,companyId,authorUserId);
        return store.byId(companyId,id);
    }
    public void failed(long companyId,long id) {
        jdbc.update("UPDATE fr_content_task SET result_attempts=result_attempts+1, "
            +"result_state=CASE WHEN result_attempts>=4 THEN 'FAILED' ELSE 'PENDING' END, "
            +"result_error='生成完成但保存失败，请重试保存',result_retry_after=now()+interval '30 seconds' "
            +"WHERE id=? AND company_id=? AND result_state='PENDING' AND result_retry_after<=now()",id,companyId);
    }
    private static String text(Object value){return value==null?null:value.toString();}
}
