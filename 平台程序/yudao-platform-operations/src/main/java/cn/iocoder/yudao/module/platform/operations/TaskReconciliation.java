package cn.iocoder.yudao.module.platform.operations;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import cn.iocoder.yudao.module.franchise.content.content.ContentTaskService;
import cn.iocoder.yudao.module.franchise.content.content.ContentTaskStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 人工工作台只推进有依据的终态，不根据超时或HTTP错误直接退款。 */
@Service
public class TaskReconciliation {
    private final JdbcTemplate jdbc;
    private final OperationsGuard guard;
    private final OperationsAudit audit;
    private final TaskOperationsQuery query;
    private final ContentTaskStore store;
    private final ContentTaskService tasks;
    private final OrgPointPort points;
    public TaskReconciliation(JdbcTemplate jdbc,OperationsGuard guard,OperationsAudit audit,TaskOperationsQuery query,
          ContentTaskStore store,ContentTaskService tasks,OrgPointPort points){this.jdbc=jdbc;this.guard=guard;this.audit=audit;this.query=query;this.store=store;this.tasks=tasks;this.points=points;}
    public record Review(long expectedVersion,String decision,String reason,String evidence,String requestKey) { }
    @Transactional(rollbackFor=Exception.class)
    @SuppressWarnings("unchecked")
    public Map<String,Object> review(String key,Review input) {
        var actor=guard.require();TaskOperationsQuery.validateKey(key);
        OperationsGuard.text(input.reason(),500,"核对原因");OperationsGuard.text(input.evidence(),1000,"核对依据");
        OperationsGuard.text(input.requestKey(),80,"请求编号");
        if(input.expectedVersion()<0||input.decision()==null||!List.of("WAITING_EXTERNAL","REVIEWED","DELIVERED","NOT_DELIVERED","SYNC_TERMINAL").contains(input.decision()))throw new ServiceException(400,"核对结论无效");
        Map<String,Object> initial=query.detail(key);var first=(Map<String,Object>)initial.get("task");long org=((Number)first.get("org_id")).longValue();
        guard.organization(actor,org,true);
        String hash=audit.hash(List.of(key,input));
        if(audit.replay(actor,org,"task-review",input.requestKey(),hash))return query.detail(key);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",actor.tenantId()+":task-review:"+key);
        var before=(Map<String,Object>)query.detail(key).get("task");
        if(((Number)before.get("case_version")).longValue()!=input.expectedVersion())throw new ServiceException(409,"核对记录已变化，请刷新后重试");
        String status="WAITING_EXTERNAL".equals(input.decision())?"WAITING_EXTERNAL":"RESOLVED";
        if(List.of("DELIVERED","NOT_DELIVERED","SYNC_TERMINAL").contains(input.decision())) {
            boolean sync="SYNC_TERMINAL".equals(input.decision());
            if(!Boolean.TRUE.equals(before.get(sync?"can_sync":"can_verify")))throw new ServiceException(409,"当前任务不满足此处理条件，请刷新后核对");
            long taskId=Long.parseLong(String.valueOf(before.get("task_id")));
            // 与自动恢复扫描共享行锁；锁后重新取服务端任务和计费归属。
            jdbc.queryForList("SELECT id FROM fr_content_task WHERE id=? AND company_id=? FOR UPDATE",taskId,org);
            var task=store.byId(org,taskId);
            if(task==null||task.getPoolBizKey()==null)throw new ServiceException(409,"任务计费依据缺失");
            var reserves=jdbc.queryForList("SELECT actor_user_id,ref_type,ref_id FROM org_point_ledger WHERE org_id=? AND biz_key=? AND type='RESERVE'",org,task.getPoolBizKey());
            if(reserves.size()!=1||!Objects.equals(((Number)reserves.get(0).get("actor_user_id")).longValue(),task.getAuthorUserId())
              ||!"content_task".equals(reserves.get(0).get("ref_type"))||((Number)reserves.get(0).get("ref_id")).longValue()!=taskId)throw new ServiceException(409,"积分记录与任务归属不一致，不能直接处理");
            if(sync) {
                if(!List.of("SUCCEEDED","FAILED","CANCELLED").contains(task.getStatus()))throw new ServiceException(409,"任务终态已变化");
                tasks.reconcilePool(task);
            } else {
                Long units="DELIVERED".equals(input.decision())?task.getPoolReservedUnits():null;
                if("DELIVERED".equals(input.decision())&&(units==null||units<=0))throw new ServiceException(409,"任务冻结用量缺失，不能结算");
                final boolean applied;
                try{applied=store.manualVerifyAsPlatform(taskId,org,actor.userId(),input.decision(),units,input.reason().trim(),input.evidence().trim(),input.requestKey());}
                catch(DuplicateKeyException e){throw new ServiceException(409,"核验请求已用于其他任务");}
                if(!applied)throw new ServiceException(409,"任务已被其他请求处理，请刷新");
                var settlement="DELIVERED".equals(input.decision())?points.settleByBizKey(task.getPoolBizKey(),units):points.releaseByBizKey(task.getPoolBizKey());
                if(settlement==null)throw new ServiceException(409,"缺少真实预扣，核验已取消");
            }
            var refreshed=(Map<String,Object>)query.detail(key).get("task");
            if(!List.of("SETTLED","RELEASED").contains(refreshed.get("billing_state")))throw new ServiceException(409,"积分尚未完成终态同步，请查看任务恢复记录");
        } else if("REVIEWED".equals(input.decision())&&(!List.of("SETTLED","RELEASED").contains(before.get("billing_state"))||!"NONE".equals(before.get("anomaly")))) {
            throw new ServiceException(409,"异常尚未解除，请等待外部确认或完成有依据的处理");
        }
        jdbc.update("""
          INSERT INTO pf_task_reconciliation(tenant_id,org_id,task_key,status,version,decision,reason,evidence,operator_id)
          VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,task_key) DO UPDATE SET status=excluded.status,version=excluded.version,
          decision=excluded.decision,reason=excluded.reason,evidence=excluded.evidence,operator_id=excluded.operator_id,updated_at=now()
          """,actor.tenantId(),org,key,status,input.expectedVersion()+1,input.decision(),input.reason().trim(),input.evidence().trim(),actor.userId());
        audit.append(actor,org,"task-review",input.requestKey(),hash,input.reason(),before,Map.of("taskKey",key,"status",status,"decision",input.decision(),"evidence",input.evidence().trim()));
        return query.detail(key);
    }
}
