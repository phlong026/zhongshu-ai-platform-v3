package cn.iocoder.yudao.platformserver.wiring;

import cn.iocoder.yudao.module.franchise.org.FranchiseCommandRecorded;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;
import static cn.iocoder.yudao.platformserver.wiring.BusinessMessageFacts.*;
import static cn.iocoder.yudao.module.platform.workflow.message.MessageCenterService.text;

/** Authoritative business rows determine recipients. No client-generated notifications. */
@Component
public class FranchiseBusinessMessages {
    private final JdbcTemplate jdbc;
    private final BusinessMessageFacts facts;
    public FranchiseBusinessMessages(JdbcTemplate jdbc) { this.jdbc = jdbc; this.facts=new BusinessMessageFacts(jdbc); }
    // Synchronous delivery participates in the command's transaction. A failed insert
    // rolls back with the command; ON CONFLICT cannot abort a PostgreSQL transaction.
    @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT)
    public void onCommand(FranchiseCommandRecorded notice) {
        deliver(notice.event(),false);
    }
    private void deliver(CommandEventDO event,boolean recovered) {
        var fact=facts.fact(event);
        if(fact==null){if(RULES.containsKey(event.getCommand()))checkpoint(event.getId());return;}
        for(var recipient:fact.recipients().entrySet()) {
            var who=recipient.getKey();var context=new LinkedHashMap<>(fact.context());
            context.put("recipientReason",recipient.getValue());
            if(recovered){context.remove("version");context.remove("amountCents");context.put("fields",List.of());context.put("historyNote","恢复送达的通知：业务名称与责任人按当前原单补充，历史金额和版本请查原单记录");}
            String route=fact.rule().route();
            if("commission-simulate-pay".equals(event.getCommand())) {
                boolean finance=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pf_staff_post WHERE user_id=? AND org_id=? AND role_code IN('finance','finance-lead') AND status='ACTIVE')",Boolean.class,who.user(),who.org());
                if(!finance){route="commission";context.put("actionPath","/pages/workflow/commission/index?view=progress&commissionId="+fact.object());context.put("actionLabel","查看委托");}
            }
            String description=String.join(" · ",List.of(text(context.get("customerName")),text(context.get("projectName")),text(context.get("businessNo"))).stream().filter(v->!v.isBlank()).toList());
            if(description.isBlank())description=fact.rule().title();
            send(who.user(),who.org(),route,fact.object(),fact.rule().title(),description,
                "command:"+event.getId()+":"+who.org()+":"+who.user(),context,event.getOccurredAt());
        }
        checkpoint(event.getId());
    }

    private void checkpoint(Long id){jdbc.update("INSERT INTO pf_message_delivery_checkpoint(event_id) VALUES(?) ON CONFLICT DO NOTHING",id);}

    // Recover committed commands whose delivery component was temporarily
    // unavailable. Activation excludes old history; message keys checkpoint delivery.
    @Scheduled(fixedDelay=5000,initialDelay=5000)
    @Transactional(rollbackFor=Exception.class)
    public void deliverBusinessCommands(){
        var names=RULES.keySet().stream().sorted().toList();
        String placeholders=String.join(",",Collections.nCopies(names.size(),"?"));
        var rows=jdbc.queryForList("SELECT to_jsonb(e)::text FROM pf_command_event e CROSS JOIN pf_message_center_activation a WHERE e.occurred_at>=a.activated_at AND e.command IN("+placeholders+") AND NOT EXISTS(SELECT 1 FROM pf_message_delivery_checkpoint c WHERE c.event_id=e.id) ORDER BY e.occurred_at,e.id LIMIT 100",String.class,names.toArray());
        for(String raw:rows){
            var r=cn.iocoder.yudao.module.platform.workflow.message.MessageCenterService.json(raw);
            var event=new CommandEventDO().setId(number(r.get("id"))).setAggregateType(text(r.get("aggregate_type")))
                .setAggregateId(number(r.get("aggregate_id"))).setCommand(text(r.get("command"))).setOrgId(number(r.get("org_id")))
                .setActorUserId(number(r.get("actor_user_id"))).setRevision(number(r.get("revision")))
                .setPayload(r.get("payload") instanceof Map?JsonUtils.toJsonString(r.get("payload")):text(r.get("payload")));
            if(r.get("occurred_at")!=null)event.setOccurredAt(java.time.OffsetDateTime.parse(text(r.get("occurred_at"))));
            deliver(event,true);
        }
    }

    // Terminal task rows are a durable outbox. The message uniqueness key is the
    // delivery checkpoint; failures retry next tick, without rerunning AI or billing.
    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    @Transactional(rollbackFor = Exception.class)
    public void deliverTaskResults() {
        var rows = jdbc.queryForList("""
            SELECT t.id, t.company_id, t.author_user_id, t.kind, t.status
            FROM fr_content_task t CROSS JOIN pf_business_message_activation a
            WHERE t.create_time >= a.activated_at AND t.status IN ('SUCCEEDED','FAILED')
              AND NOT EXISTS (SELECT 1 FROM pf_message m WHERE m.sender_domain='fr-business'
                AND m.dedupe_key=concat('task:',t.id,':',t.status,':',t.author_user_id))
              AND EXISTS (SELECT 1 FROM pf_staff_post p JOIN pf_org_unit o ON o.dept_id=p.org_id
                WHERE p.user_id=t.author_user_id AND p.org_id=t.company_id AND p.status='ACTIVE' AND o.status='ACTIVE')
            ORDER BY t.create_time, t.id LIMIT 200
            """);
        Map<String, String> kinds = Map.of("copy", "文案改写", "asr", "语音转写", "link_parse", "链接解析", "digital", "数字人口播", "ai_video", "AI视频");
        for (var row : rows) {
            long id = number(row.get("id")), user = number(row.get("author_user_id"));
            String state = String.valueOf(row.get("status")), kind = kinds.get(String.valueOf(row.get("kind")));
            if (kind == null) continue;
            String title = kind + ("SUCCEEDED".equals(state) ? "已完成" : "未成功完成");
            var task=facts.one("fr_content_task",id);
            Map<String,Object> context=new LinkedHashMap<>();context.put("module","creation");context.put("eventCode","content-"+state);
            context.put("taskName",first(task,"title","name"));context.put("businessNo","创作任务 "+id);context.put("stateAtEvent",state);
            context.put("orgName",facts.organization(row.get("company_id")));context.put("recipientReason","你发起了该创作任务");
            context.put("actionPath","/pages/studio/index?view=result&task="+id);context.put("actionLabel","查看任务结果");
            context.put("notice","FAILED".equals(state)?"查看失败原因及结算记录，积分退回以实际流水为准":"成果已生成，可打开任务查看");
            send(user,number(row.get("company_id")),"content-task",id,title,kind+" · 任务 "+id,"task:"+id+":"+state+":"+user,context,null);
        }
    }

    void send(long user,long org,String route,long object,String title,String description,String key,Map<String,Object> context,java.time.OffsetDateTime occurredAt) {
        if(user<=0||org<=0)return;
        jdbc.update("""
            INSERT INTO pf_message(id,user_id,org_id,sender_domain,type,route,object_id,title,description,dedupe_key,read,business_context,create_time)
            SELECT ?,?,?,'fr-business','BUSINESS',?,?,?,?,?,FALSE,CAST(? AS jsonb),COALESCE(?,now())
            WHERE EXISTS(SELECT 1 FROM pf_staff_post p JOIN pf_org_unit o ON o.dept_id=p.org_id
              WHERE p.user_id=? AND p.org_id=? AND p.status='ACTIVE' AND o.status='ACTIVE')
            ON CONFLICT(sender_domain,dedupe_key) DO NOTHING
            """,IdWorker.getId(),user,org,route,object,title,description,key,JsonUtils.toJsonString(context),occurredAt,user,org);
    }
}
