package cn.iocoder.yudao.platformserver.wiring;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import cn.iocoder.yudao.module.franchise.engineering.EngineeringPermissions;
import cn.iocoder.yudao.module.platform.workflow.TodoItem;
import cn.iocoder.yudao.module.platform.workflow.message.MessageCenterResolver;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.iocoder.yudao.platformserver.wiring.BusinessMessageFacts.*;
import static cn.iocoder.yudao.module.platform.workflow.message.MessageCenterService.*;

/** One domain adapter for stored event snapshots and the existing authoritative workbench tasks. */
@Component
public class FranchiseMessageResolver implements MessageCenterResolver {
    private final JdbcTemplate jdbc;
    private final BusinessMessageFacts facts;
    private final SystemDirectory systemDirectory;
    public FranchiseMessageResolver(JdbcTemplate jdbc,SystemDirectory systemDirectory){this.jdbc=jdbc;this.facts=new BusinessMessageFacts(jdbc);this.systemDirectory=systemDirectory;}
    @Override public void resolve(PostSnapshot post,Map<String,Object> row,List<TodoItem> todos) {
        Map<String,Object> stored=json(row.get("context"));
        final Map<String,Object> context=stored.isEmpty()?legacy(row):stored;
        String route=text(row.get("route"));long object=number(row.get("objectId"));
        String event=first(context,"eventCode");
        boolean received=event.equals("packet-receive")||(route.equals("commission")&&text(row.get("title")).equals("工程已接收业务资料"));
        // Resolve from the source commission in the active organization, not a stored URL or project ID.
        var receivedProject=received?facts.projectForCommission(object,post.orgId()):Map.<String,Object>of();
        row.put("module",first(context,"module").isBlank()?routeModule(route):context.get("module"));
        row.put("context",context);
        if(!(received&&!receivedProject.isEmpty()?allowed(post,"engineering",number(receivedProject.get("id")),context):allowed(post,route,object,context))){
            row.put("title","业务通知权限已变更");row.put("description","当前工作身份无法查看该业务，请联系负责人或切换有权岗位");
            row.put("context",Map.of());row.put("stateLabel","权限已变更");row.put("actionPath","");row.put("actionLabel","");return;
        }
        context.putIfAbsent("orgName",facts.organization(post.orgId()));
        context.putIfAbsent("recipientReason","此通知与你负责或参与的业务有关");
        row.put("actionPath",first(context,"actionPath"));row.put("actionLabel",first(context,"actionLabel"));
        // Apply the registration wording to already-delivered notices as well.
        String previousTitle=text(row.get("title"));
        String registrationTitle=switch(event){
            case "fin-payment-register"->"付款登记完成";
            case "fin-refund-register","fin-expense-refund"->"退款登记完成";
            case "commission-simulate-pay"->"付款登记完成（模拟）";
            default->switch(previousTitle){
                case "设计费模拟支付已完成","设计费模拟支付完成"->"付款登记完成（模拟）";
                case "付款已登记","付款完成","付款成功"->route.equals("finance")?"付款登记完成":previousTitle;
                case "客户退款已登记","退款已登记","支出退回已登记","退款完成","退款成功"->route.equals("finance")?"退款登记完成":previousTitle;
                default->previousTitle;
            };
        };
        row.put("title",registrationTitle);
        if(text(row.get("description")).equals(previousTitle))row.put("description",registrationTitle);
        if(Set.of("fin-payment-register","fin-refund-register","fin-expense-refund").contains(event))
            context.put("notice","仅登记小程序外已发生的资金收付，不发起扣款、转账或原路退款");
        if(Set.of("fin-refund-register","fin-expense-refund").contains(event)){
            var fields=new ArrayList<>((List<Map<String,String>>)context.getOrDefault("fields",List.of()));
            context.put("fields",fields);
            if(fields.stream().noneMatch(f->"退款方向".equals(f.get("label"))))
                field(context,"退款方向",event.equals("fin-expense-refund")?"供应商 / 劳务退回":"退给客户");
        }
        boolean simulation=Boolean.TRUE.equals(context.get("simulated"));
        row.put("kind",event.contains("return")||event.contains("FAILED")||event.contains("abnormal")?"EXCEPTION":"NOTICE");
        row.put("stateLabel",simulation?"模拟 · 仅知会":"仅知会");
        if(!simulation&&!received){
            var match=todos.stream().filter(TodoItem::actionable).filter(t->matches(t,route,object,context)).findFirst();
            if(match.isPresent()){
                Map<String,Object> task=new LinkedHashMap<>();task.put("context",new LinkedHashMap<>());task.put("actionPath",match.get().appPath());task.put("actionLabel","去办理");
                resolveTodo(post,match.get(),task);
                row.put("actionable",true);row.put("kind","TODO");row.put("stateLabel",label(match.get().status()));
                row.put("actionPath",task.get("actionPath"));row.put("actionLabel",task.get("actionLabel"));
            }
        }
        // Financial recipients may include the employee/project owner. Only financial roles get funds-operation links.
        if(route.equals("finance")&&!Set.of("finance","finance-lead").contains(post.roleCode())){
            if(number(context.get("projectId"))>0&&Set.of("engineer","project-manager").contains(post.roleCode())){row.put("actionPath","/pages/build/record/index?view=collection&id="+context.get("projectId"));row.put("actionLabel","查看项目收款");}
            else if(number(context.get("customerId"))>0){row.put("actionPath","/pages/workflow/index?view=contract&id="+context.get("customerId"));row.put("actionLabel","查看关联业务");}
            else{row.put("actionPath","");row.put("actionLabel","");}
        }
        if(route.equals("sales-contract")){row.put("actionPath","/pages/workflow/index?view=contract&id="+context.get("customerId"));row.put("actionLabel","查看合同");}
        if(received){
            row.put("actionable",false);row.put("kind","NOTICE");
            boolean canOpen=!receivedProject.isEmpty()&&systemDirectory.roleHasPermission(post.roleCode(),EngineeringPermissions.QUERY);
            row.put("actionPath",canOpen?"/pages/build/record/index?view=overview&id="+receivedProject.get("id"):"");
            row.put("actionLabel",canOpen?"查看工程项目":"");
            if(receivedProject.isEmpty()){row.put("stateLabel","项目暂不可用");context.put("notice","未找到关联工程项目，请联系工程负责人核对");}
            else if(!canOpen)context.put("notice","当前岗位无工程项目查看权限，请切换有权岗位查看");
        }
    }
    @Override public boolean allowTodo(PostSnapshot p,TodoItem todo){
        String path=text(todo.appPath());var q=params(path);
        if(todo.key().startsWith("fr-sale-finance:returned:")&&Set.of("engineer","project-manager").contains(p.roleCode())){
            var d=facts.one("fr_sale_collection_declaration",lastId(todo.key()));var stage=facts.one("fr_sale_payment_stage",d.get("stage_id"));var project=facts.projectForContract(stage.get("contract_id"),p.orgId());
            return number(d.get("company_id"))==p.orgId()&&number(d.get("applicant_user_id"))==p.userId()&&number(project.get("manager_user_id"))==p.userId();
        }
        if(path.startsWith("/pages/workflow/index")&&q.containsKey("id"))return customerAllowed(p,facts.one("fr_customer",q.get("id")));
        if(q.containsKey("commissionId"))return allowed(p,"commission",number(q.get("commissionId")),Map.of());
        if(path.startsWith("/pages/build/")&&q.containsKey("id"))return allowed(p,"engineering",number(q.get("id")),Map.of());
        if(path.startsWith("/pages/admin/finance-desk/"))return Set.of("finance","finance-lead").contains(p.roleCode());
        return true;
    }
    @Override public void resolveTodo(PostSnapshot post,TodoItem todo,Map<String,Object> row){
        Map<String,Object> c=json(row.get("context"));row.put("context",c);
        c.put("orgName",facts.organization(post.orgId()));c.put("recipientReason","由你当前的工作岗位办理");
        row.put("stateLabel",label(todo.status()));
        String path=todo.appPath()==null?"":todo.appPath(),key=todo.key();var query=params(path);
        if(key.startsWith("fr-sale-finance:")){
            long id=lastId(key);var declaration=facts.one("fr_sale_collection_declaration",id);var stage=facts.one("fr_sale_payment_stage",declaration.get("stage_id"));
            customer(c,facts.one("fr_customer",stage.get("customer_id")));c.put("businessNo","到账申报 "+id);c.put("declarationId",String.valueOf(id));
            field(c,"款项阶段",stage(first(stage,"stage")));field(c,"申报人",facts.person(declaration.get("applicant_user_id")));
            field(c,"退回原因",first(declaration,"return_reason"));c.put("notice","到账资料由财务核实后才计入实际收款");
            row.put("module","finance");row.put("actionLabel",Set.of("finance","finance-lead").contains(post.roleCode())?"核实收款":"补充资料");
            if(Set.of("finance","finance-lead").contains(post.roleCode()))row.put("actionPath","/pages/admin/finance-desk/index?view=flows&tab=ledger&messageType=sale_collection_declaration&messageObject="+id);
            else if(Set.of("engineer","project-manager").contains(post.roleCode())){var project=facts.projectForContract(stage.get("contract_id"),post.orgId());project(c,project);row.put("actionPath","/pages/build/record/index?view=collection&id="+project.get("id"));}
        } else if((key.startsWith("fr-fin-workbench:")||key.startsWith("fr-design-fin:"))){
            long id=lastId(key);var payable=facts.one("fr_fin_payable",id);project(c,facts.one("fr_eng_project",payable.get("project_id")));
            customer(c,facts.one("fr_customer",payable.get("customer_id")));c.put("businessNo","应付单 "+id);
            field(c,"收款对象",first(payable,"recipient"));field(c,"款项阶段",stage(first(payable,"stage")));
            row.put("module","finance");row.put("actionLabel",key.contains(":reimburse:")?"办理报销往来":"登记付款");
            row.put("actionPath","/pages/admin/finance-desk/index?view="+(key.contains(":reimburse:")?"reimbursements":"flows&tab=payables")+"&messageType=fin_payable&messageObject="+id);
            if(key.startsWith("fr-design-fin:")){
                row.put("actionPath","/pages/admin/finance-desk/index?view=flows&tab=ledger&messageType=design_fee&messageObject="+id);row.put("actionLabel","核对设计款项");
                String[] ref=first(payable,"source_ref").split(":");if(ref.length>0){var commission=facts.one("fr_commission",ref[0]);if(commission.isEmpty()&&ref.length>1)commission=facts.one("fr_commission",ref[1]);c.put("customerName",first(commission,"customer_name"));}
            }
        } else if(query.containsKey("commissionId")){
            long id=number(query.get("commissionId"));var commission=facts.one("fr_commission",id);
            c.put("customerName",first(commission,"customer_name"));c.put("commissionId",String.valueOf(id));c.put("businessNo","委托 "+id);
            facts.commissionLinks(c,commission,Set.of("receive","handover").contains(query.get("view")),null);
            c.put("version",first(commission,"submission_version"));row.put("module",Set.of("receive","handover").contains(query.get("view"))?"engineering":"design");
            row.put("actionLabel",switch(text(query.get("view"))){case "receive"->"核对接收";case "handover"->"办理移交";case "dispatch"->"下发成果";case "complete"->"登记设计完成";case "pay"->"登记付款";default->"办理委托";});
            if(key.contains("simulation")){c.put("simulated",true);c.put("notice","模拟付款登记不计入实际收支");}
        } else if(path.startsWith("/pages/build/")&&query.containsKey("id")){
            project(c,facts.one("fr_eng_project",query.get("id")));row.put("module",path.contains("material")?"material":"engineering");row.put("actionLabel","办理项目事项");
        } else if(path.startsWith("/pages/workflow/index")&&query.containsKey("id")){
            customer(c,facts.one("fr_customer",query.get("id")));row.put("module","customer");row.put("actionLabel",path.contains("view=contract")?"办理合同事项":"跟进客户");
        } else if(todo.domain().contains("perf")){
            row.put("module","performance");row.put("actionLabel","办理绩效事项");
            var task=facts.one("fr_perf_task",lastId(key));c.put("taskName",first(task,"title"));field(c,"截止日期",first(task,"due"));
            if(key.startsWith("perf-manual:"))row.put("actionPath",path+"&messageTask="+lastId(key));
        }
        if(row.get("actionLabel")==null)row.put("actionLabel","去办理");
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=10000,initialDelay=15000)
    @org.springframework.transaction.annotation.Transactional
    public void enrichLegacyMessages(){
        var rows=jdbc.queryForList("SELECT id::text,route,object_id::text, dedupe_key FROM pf_message WHERE business_context='{}'::jsonb ORDER BY pf_message.id LIMIT 100");
        for(var raw:rows){
            Map<String,Object> row=new LinkedHashMap<>();row.put("route",raw.get("route"));row.put("objectId",raw.get("object_id"));row.put("dedupeKey",raw.get("dedupe_key"));
            var c=legacy(row);c.putIfAbsent("historyNote","历史消息：关联名称按当前原单补充，事件信息以原业务记录为准");
            jdbc.update("UPDATE pf_message SET business_context=CAST(? AS jsonb) WHERE id=? AND business_context='{}'::jsonb",cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(c),number(raw.get("id")));
        }
    }
    private Map<String,Object> legacy(Map<String,Object> row){
        String key=text(row.get("dedupeKey"));Map<String,Object> c=new LinkedHashMap<>();
        if(key.startsWith("command:")&&facts.exists("pf_command_event")){
            var event=facts.one("pf_command_event",key.split(":")[1]);
            if(!event.isEmpty()){
                var value=new CommandEventDO().setId(number(event.get("id"))).setAggregateId(number(event.get("aggregate_id")))
                    .setAggregateType(text(event.get("aggregate_type"))).setCommand(text(event.get("command"))).setOrgId(number(event.get("org_id")))
                    .setActorUserId(number(event.get("actor_user_id"))).setPayload(event.get("payload") instanceof Map?cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(event.get("payload")):text(event.get("payload")));
                var fact=facts.fact(value);if(fact!=null)c.putAll(fact.context());
                // Do not silently present today's mutable amount/version as a historical fact.
                c.remove("version");c.remove("amountCents");c.put("fields",new ArrayList<>());
                c.put("historyNote","历史消息：关联名称来自当前原单，金额仅在可核对原始凭据时补充");
                if(text(row.get("route")).equals("commission-payment")&&event.get("occurred_at")!=null){
                    var order=facts.query("SELECT to_jsonb(t)::text FROM fr_commission_simulated_order t WHERE commission_id=? AND paid_at<=CAST(? AS timestamptz) ORDER BY paid_at DESC LIMIT 1",number(row.get("objectId")),text(event.get("occurred_at")));
                    amount(c,order.get("amount_cents"),"模拟金额");c.put("businessNo",first(order,"order_number"));
                }
            }
        }
        if(c.isEmpty()){
            String route=text(row.get("route")),id=text(row.get("objectId"));c.put("module",routeModule(route));
            if(route.equals("customer")){customer(c,facts.one("fr_customer",id));c.put("actionPath","/pages/workflow/index?view=customer&id="+id);c.put("actionLabel","查看客户");}
            if(route.equals("commission-payment")){c.put("simulated",true);c.put("notice","模拟付款登记凭据，仅知会，不计入实际收支");}
            if(route.startsWith("commission")){var commission=facts.one("fr_commission",id);c.put("customerName",first(commission,"customer_name"));c.put("businessNo","委托 "+id);c.put("actionPath","/pages/workflow/commission/index?view="+(route.equals("commission-payment")?"receipt":"progress")+"&commissionId="+id);c.put("actionLabel","查看委托");}
            if(route.equals("content-task")){c.put("actionPath","/pages/studio/index?view=result&task="+id);c.put("actionLabel","查看任务结果");}
            if(route.equals("course")){c.put("actionPath","/pages/account/training/index?id="+id);c.put("actionLabel","查看课程");}
            if(route.equals("personal-performance")){c.put("actionPath","/pages/admin/tasks/index?view=mine");c.put("actionLabel","查看绩效事项");}
            if(route.equals("privacy")){c.put("actionPath","/pages/account/index?view=privacy");c.put("actionLabel","查看账号设置");}
        }
        return c;
    }
    private boolean allowed(PostSnapshot p,String route,long id,Map<String,Object> c){
        if(route.equals("customer"))return customerAllowed(p,facts.one("fr_customer",id));
        if(route.startsWith("commission")){
            var s=facts.one("fr_commission",id);if(s.isEmpty())return false;
            if(p.orgId()==number(s.get("design_org_id"))&&(p.roleCode().equals("hq-design-admin")||route.equals("commission-payment")&&financial(p)))return true;
            if(p.orgId()!=number(s.get("company_id")))return false;
            if(route.equals("commission-payment")&&financial(p))return true;
            if(customerAllowed(p,facts.one("fr_customer",s.get("customer_id"))))return true;
            if(Set.of("engineer","project-manager").contains(p.roleCode())&&facts.exists("fr_commission_handover")){
                var h=facts.query("SELECT to_jsonb(t)::text FROM fr_commission_handover t WHERE commission_id=? ORDER BY version DESC LIMIT 1",id);
                return number(h.get("manager_user_id"))==p.userId();
            }
            return false;
        }
        if(route.equals("engineering")){
            var project=facts.one("fr_eng_project",id);
            return number(project.get("company_id"))==p.orgId()&&(financial(p)||number(project.get("manager_user_id"))==p.userId()||number(project.get("assigned_engineer_user_id"))==p.userId()||customerAllowed(p,facts.one("fr_customer",project.get("customer_id"))));
        }
        if(route.equals("finance")){
            if(financial(p))return true;
            if(number(c.get("employeeUserId"))==p.userId())return true;
            if(customerAllowed(p,facts.one("fr_customer",c.get("customerId"))))return true;
            var project=facts.one("fr_eng_project",c.get("projectId"));return number(project.get("company_id"))==p.orgId()&&number(project.get("manager_user_id"))==p.userId();
        }
        if(route.equals("sales-contract"))return customerAllowed(p,facts.one("fr_customer",c.get("customerId")))||financial(p);
        if(route.equals("content-task")){var task=facts.one("fr_content_task",id);return number(task.get("company_id"))==p.orgId()&&number(task.get("author_user_id"))==p.userId();}
        if(route.equals("personal-performance")&&first(c,"eventCode").startsWith("perf-task-")){
            var task=facts.one("fr_perf_task",id);return number(task.get("company_id"))==p.orgId()&&(number(task.get("assignee_user_id"))==p.userId()||Set.of("boss","owner").contains(p.roleCode()));
        }
        if(route.equals("personal-performance")&&number(c.get("assigneeUserId"))>0)return number(c.get("assigneeUserId"))==p.userId()||Set.of("boss","owner","finance","finance-lead").contains(p.roleCode());
        return true; // Course/system/own-performance notices still enforce their own target-page permissions.
    }
    private static boolean customerAllowed(PostSnapshot p,Map<String,Object> c){
        if(number(c.get("company_id"))!=p.orgId())return false;
        if(Set.of("boss","owner").contains(p.roleCode()))return true;
        if(number(c.get("owner_user_id"))==p.userId())return true;
        return p.roleCode().equals("sales-lead")&&((p.teamId()!=null&&p.teamId()==number(c.get("team_id")))||(p.managedTeamIds()!=null&&p.managedTeamIds().contains(number(c.get("team_id")))));
    }
    private static boolean financial(PostSnapshot p){return Set.of("finance","finance-lead","boss","owner").contains(p.roleCode());}
    private static boolean matches(TodoItem t,String route,long id,Map<String,Object> c){
        var q=params(t.appPath());
        if(route.equals("engineering")&&"eng-progress-overdue".equals(c.get("eventCode")))return t.key().equals("fr-eng:delay:"+text(c.get("nodeId")));
        if(route.startsWith("commission"))return number(q.get("commissionId"))==id;
        if(route.equals("customer"))return text(t.appPath()).contains("view=customer")&&number(q.get("id"))==id;
        if(route.equals("finance")&&t.key().startsWith("fr-sale-finance:"))return text(c.get("sourceTable")).equals("fr_sale_collection_declaration")&&lastId(t.key())==id;
        if(route.equals("finance")&&t.key().startsWith("fr-fin-workbench:"))return text(c.get("sourceTable")).equals("fr_fin_payable")&&lastId(t.key())==id;
        if(route.equals("personal-performance"))return t.key().equals("perf-manual:"+id);
        return false;
    }
    public static Map<String,String> params(String path){
        Map<String,String> out=new HashMap<>();if(path==null||!path.contains("?"))return out;
        for(String item:path.substring(path.indexOf('?')+1).split("&")){var p=item.split("=",2);if(p.length==2)out.put(p[0],URLDecoder.decode(p[1],StandardCharsets.UTF_8));}return out;
    }
    private static long lastId(String key){var a=key.split(":");return number(a[a.length-1]);}
    private static String routeModule(String route){return switch(route){case "commission-payment","finance"->"finance";case "customer","sales-contract"->"customer";case "commission","design-progress","design-dispatch"->"design";case "engineering"->"engineering";case "content-task"->"creation";case "course"->"learning";case "personal-performance"->"performance";default->"system";};}
    private static String label(String s){return switch(text(s)){case "DESIGNING"->"待登记设计完成";case "DESIGN_COMPLETED"->"待下发";case "HANDOVER_SUBMITTED"->"待核对接收";case "FEE_PENDING"->"费用待审批";case "FEE_RETURNED"->"待修改重提";case "SIMULATION_PENDING"->"待模拟付款登记";case "FOLLOWING"->"待回访";case "BUDGET_READY"->"待提交委托";case "pending","PENDING"->"待处理";case "shortfall"->"资金待补足";case "overdue"->"已逾期";case "RETURNED"->"待补充资料";default->s;};}
}
