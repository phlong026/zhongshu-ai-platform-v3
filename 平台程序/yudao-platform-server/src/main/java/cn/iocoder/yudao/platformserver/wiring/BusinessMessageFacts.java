package cn.iocoder.yudao.platformserver.wiring;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static cn.iocoder.yudao.module.platform.workflow.message.MessageCenterService.*;

/** Explicit event catalog and whitelisted business facts. Never copies command payloads wholesale. */
public class BusinessMessageFacts {
    public record Rule(String title,String module,String route) { }
    public record Recipient(long user,long org) { }
    public record Fact(Rule rule,long object,Map<String,Object> context,Map<String,Object> subject,
                       Map<Recipient,String> recipients) { }
    public static final Map<String,Rule> RULES=new LinkedHashMap<>();
    static {
        rules("customer","customer","customer-create","客户已分配","customer-follow","客户跟进已记录","customer-transfer","客户负责人已变更",
            "lead-link","老客户有新的咨询登记","lead-capture","客户咨询已登记","workflow-complete","客户跟进有新进展");
        rules("design","commission","commission-submit","设计委托已提交","commission-fee-decide","委托费用审批已更新",
            "commission-feedback","收到客户修改意见","design-complete","设计已完成","design-dispatch","正式图纸已下发");
        rules("engineering","commission","packet-submit","业务资料待工程接收","packet-receive","工程已接收业务资料","packet-return","工程移交资料待补充");
        rules("finance","commission-payment","commission-simulate-pay","付款登记完成（模拟）");
        rules("finance","finance","sale-collection-declare","收款资料待财务核实","sale-collection-return","收款资料待补充",
            "fin-receipt-register","收款登记已完成","fin-receipt-register-business","业务收款已确认","fin-payment-register","付款登记完成",
            "fin-payable-register","新增应付款项","design-payable-created","设计费用待办理","fin-payable-adjust","应付款项已调整",
            "fin-refund-register","退款登记完成","fin-expense-refund","退款登记完成","fin-employee-recovery","员工归还款已登记",
            "fin-bank-confirm","到账核对已完成","fin-abnormal-fund-register","资金异常待核对","fin-payment-void","付款记录已作废",
            "design-receipt-void","设计收款记录已作废","fin-receipt-revision","收款记录已更正","fin-refund-void","退款记录已作废");
        rules("customer","sales-contract","sale-contract-create","合同已建立","sale-contract-sign","合同已签订","sale-contract-change","合同金额已调整");
        rules("engineering","engineering","eng-project-manager","工程负责人已变更","eng-start","项目已开工","eng-prepare","开工准备已记录",
            "eng-plan-reschedule","施工计划已调整","eng-node-complete","施工节点已完成","eng-node-reopen","施工节点已撤回",
            "eng-node-correct","施工节点已更正","eng-deliver","工程已交付","eng-settle","项目结算已登记","eng-archive","项目已归档");
        rules("material","engineering","eng-material-terms","材料采购付款依据已登记","eng-material-adjust","材料需求已调整",
            "eng-material-followup","材料到货需跟进","eng-supply-quality","材料质量处置已更新","eng-reship-receive","材料补发已到货",
            "eng-supply-arrival","材料到货已登记","eng-arrival-correct","到货数量已更正","eng-material-movement","材料去向已登记");
        rules("finance","engineering","eng-expense-terms","工程费用付款依据已登记","eng-expense-revise","工程费用已调整","eng-collect","工程收款状态已更新");
        rules("performance","personal-performance","perf-task-create","新绩效任务已派发","perf-task-reassign","绩效任务已改派",
            "perf-task-manual-complete","绩效任务完成说明已提交","perf-cycle-submit","绩效待复核","perf-cycle-review","绩效复核结果已更新",
            "perf-cycle-appeal-resolve","绩效申诉处理结果已更新","perf-commission-release","提成应付已生成","perf-commission-reconcile","提成核对已完成");
    }
    private static void rules(String module,String route,String... pairs){for(int i=0;i<pairs.length;i+=2)RULES.put(pairs[i],new Rule(pairs[i+1],module,route));}
    /** 2026-11-26 随“八步为唯一跟进口径”新增的三条规则：只送达当天及以后的事件，此前的历史事件只补检查点，不补发。 */
    private static final Set<String> FOLLOWUP_RULES=Set.of("lead-link","lead-capture","workflow-complete");
    private static final java.time.OffsetDateTime FOLLOWUP_RULES_SINCE=java.time.OffsetDateTime.parse("2026-11-26T00:00:00+08:00");
    private static final Map<String,String> STEP_LABELS=Map.of("INVITATION","邀约进店","INTRODUCTION","店面讲解","HOUSE_SELECTION","户型选择",
            "SITE_VISIT","带看工地","MEASUREMENT","现场测量","QUOTATION","别墅报价");
    private final JdbcTemplate jdbc;
    private final Map<String,Boolean> tables=new ConcurrentHashMap<>();
    public BusinessMessageFacts(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public boolean exists(String table){return tables.computeIfAbsent(table,t->jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL",Boolean.class,t));}
    public Map<String,Object> one(String table,Object id){
        if(id==null||text(id).isBlank()||!exists(table))return new LinkedHashMap<>();
        long value=number(id);if(value<=0)return new LinkedHashMap<>();
        return query("SELECT to_jsonb(t)::text FROM "+table+" t WHERE id=?",value);
    }
    public Map<String,Object> query(String sql,Object... args){var rows=jdbc.queryForList(sql,String.class,args);return rows.isEmpty()?new LinkedHashMap<>():json(rows.get(0));}
    public Map<String,Object> projectForContract(Object contract,long org){
        if(number(contract)<=0||!exists("fr_eng_project"))return Map.of();
        return query("SELECT to_jsonb(t)::text FROM fr_eng_project t WHERE sale_contract_id=? AND company_id=? ORDER BY id DESC LIMIT 1",number(contract),org);
    }
    public Map<String,Object> projectForCommission(long commission,long org){
        if(commission<=0||!exists("fr_eng_project"))return Map.of();
        return query("SELECT to_jsonb(t)::text FROM fr_eng_project t WHERE source_commission_id=? AND company_id=? ORDER BY id DESC LIMIT 1",commission,org);
    }
    public void commissionLinks(Map<String,Object> context,Map<String,Object> commission,boolean handover,Object designActor){
        var linked=projectForCommission(number(commission.get("id")),number(commission.get("company_id")));
        project(context,linked);
        field(context,"关联项目",linked.isEmpty()?"工程接收后创建":first(linked,"name"));
        var customer=one("fr_customer",commission.get("customer_id"));
        if(handover){
            var packet=exists("fr_commission_handover")?query("SELECT to_jsonb(t)::text FROM fr_commission_handover t WHERE commission_id=? ORDER BY version DESC LIMIT 1",number(commission.get("id"))):Map.<String,Object>of();
            field(context,"移交人",person(packet.get("submitted_by_user_id")));
            field(context,"工程接收人",person(packet.get("manager_user_id")));
            field(context,"业务责任人",person(customer.get("owner_user_id")));
        }else{
            field(context,"业务责任人",person(customer.get("owner_user_id")));
            field(context,number(designActor)>0?"设计经办人":"设计责任岗位",number(designActor)>0?person(designActor):"总部设计");
        }
    }
    public String person(Object id){var row=one("system_users",id);return first(row,"nickname","username");}
    public String organization(Object id){return first(one("system_dept",id),"name");}
    public Map<String,Object> simulation(long commission,Long version){
        if(!exists("fr_commission_simulated_order"))return Map.of();
        return version==null?query("SELECT to_jsonb(t)::text FROM fr_commission_simulated_order t WHERE commission_id=? ORDER BY submission_version DESC LIMIT 1",commission)
            :query("SELECT to_jsonb(t)::text FROM fr_commission_simulated_order t WHERE commission_id=? AND submission_version=?",commission,version);
    }
    public Fact fact(CommandEventDO event) {
        Rule rule=RULES.get(event.getCommand());if(rule==null)return null;
        String command=event.getCommand(),aggregate=text(event.getAggregateType());long object=event.getAggregateId();
        if(FOLLOWUP_RULES.contains(command)&&event.getOccurredAt()!=null&&event.getOccurredAt().isBefore(FOLLOWUP_RULES_SINCE))return null;
        long messageObject=object;
        Map<String,Object> input=payload(event.getPayload()),subject=new LinkedHashMap<>(),context=new LinkedHashMap<>();
        Map<Recipient,String> recipients=new LinkedHashMap<>();
        context.put("module",rule.module());context.put("eventCode",command);context.put("eventId",text(event.getId()));
        context.put("actorName",person(event.getActorUserId()));context.put("actorUserId",text(event.getActorUserId()));
        context.put("objectId",String.valueOf(object));context.put("subjectType",aggregate);
        context.put("fields",new ArrayList<Map<String,String>>());
        if(aggregate.equals("customer")) {
            subject=one("fr_customer",object);customer(context,subject);long org=number(subject.get("company_id"));
            add(recipients,subject.get("owner_user_id"),org,"你是该客户的负责人");
            if(command.equals("customer-transfer"))add(recipients,event.getActorUserId(),org,"你提交了客户责任转交");
            if(Set.of("customer-create","customer-follow").contains(command))recipients.remove(new Recipient(number(event.getActorUserId()),org));
            context.put("actionPath","/pages/workflow/index?view=customer&id="+object);context.put("actionLabel","查看客户");
        } else if(aggregate.equals("commission")) {
            subject=one("fr_commission",object);if(subject.isEmpty())return null;
            long org=number(subject.get("company_id")),designOrg=number(subject.get("design_org_id"));
            customer(context,one("fr_customer",subject.get("customer_id")));
            context.put("commissionId",String.valueOf(object));context.put("businessNo","委托 "+object);
            context.put("customerName",first(subject,"customer_name"));
            commissionLinks(context,subject,command.startsWith("packet-"),command.startsWith("design-")?event.getActorUserId():null);
            context.put("version",first(subject,"submission_version"));field(context,"委托版本",first(subject,"submission_version"));
            Map<String,Object> customer=one("fr_customer",subject.get("customer_id"));
            add(recipients,customer.get("owner_user_id"),org,"你负责该客户的设计委托");
            String state=first(subject,"status");var order=simulation(object,null);
            boolean ready=order.isEmpty()||"PAID".equals(order.get("status"));
            if(command.equals("commission-submit")&&!order.isEmpty())ready=false;
            if(ready&&Set.of("DESIGNING","DESIGN_COMPLETED").contains(state))roles(recipients,designOrg,"由总部设计岗位承接","hq-design-admin");
            if(state.equals("FEE_PENDING"))roles(recipients,org,"该委托费用需要管理者审批","boss","owner");
            if(Set.of("packet-submit","packet-receive","packet-return").contains(command)&&exists("fr_commission_handover")){
                var handover=query("SELECT to_jsonb(t)::text FROM fr_commission_handover t WHERE commission_id=? ORDER BY version DESC LIMIT 1",object);
                add(recipients,handover.get("manager_user_id"),org,"你是本次工程移交的接收人");
                field(context,"图纸版本",first(handover,"drawing_version"));field(context,"退回原因",first(handover,"return_reason","return_note"));
            }
            if(command.equals("commission-simulate-pay")){
                context.put("simulated",true);context.put("notice","模拟付款登记凭据，仅知会，不计入实际收支，也不形成真实报销");
                amount(context,order.get("amount_cents"),"模拟金额");context.put("businessNo",first(order,"order_number"));
                field(context,"模拟登记方式","ALIPAY".equals(order.get("channel"))?"支付宝":"微信");
                field(context,"经办人",person(order.get("paid_by_user_id")));
                roles(recipients,org,"本门店设计费模拟凭据，仅知会","finance","finance-lead");
                roles(recipients,designOrg,"总部设计费模拟凭据，仅知会","finance","finance-lead");
            }
            context.put("actionPath","/pages/workflow/commission/index?view="+(command.equals("commission-simulate-pay")?"receipt":"progress")+"&commissionId="+object);
            context.put("actionLabel",command.equals("commission-simulate-pay")?"查看模拟凭据":command.equals("design-dispatch")?"查看正式成果":"查看委托");
            if(command.equals("packet-receive")){
                boolean linked=number(context.get("projectId"))>0;
                context.put("actionPath",linked?"/pages/build/record/index?view=overview&id="+context.get("projectId"):"");
                context.put("actionLabel",linked?"查看工程项目":"");
            }
        } else if(command.startsWith("eng-")) {
            if(command.equals("eng-collect")){var collection=one("fr_eng_collection",object);object=number(collection.get("project_id"));amount(context,collection.get("amount_cents"),"本次金额");}
            subject=one("fr_eng_project",object);if(subject.isEmpty())return null;
            project(context,subject);long org=number(subject.get("company_id"));
            field(context,"关联项目",first(subject,"name"));field(context,"工程负责人",person(subject.get("manager_user_id")));
            add(recipients,subject.get("manager_user_id"),org,"你是项目工程负责人");
            var customer=one("fr_customer",subject.get("customer_id"));
            field(context,"业务责任人",person(customer.get("owner_user_id")));
            if(!rule.module().equals("material"))add(recipients,customer.get("owner_user_id"),org,"你是项目客户负责人");
            if(Set.of("eng-prepare","eng-node-complete","eng-deliver","eng-settle","eng-material-terms","eng-expense-terms","eng-expense-revise","eng-collect").contains(command))
                roles(recipients,org,"项目发生了与收付款相关的业务变化","finance","finance-lead");
            if(command.equals("eng-project-manager"))roles(recipients,org,"工程责任人已变更","boss","owner");
            String view=rule.module().equals("material")?"materials":command.contains("expense")?"expenses":command.contains("collect")?"collection":"overview";
            context.put("actionPath","/pages/build/record/index?id="+object+"&view="+view);context.put("actionLabel",view.equals("materials")?"查看材料":"查看项目");
            var node=one("fr_eng_plan_node",input.get("nodeId"));field(context,"施工节点",first(node,"name"));
            var supply=one("fr_eng_supply",input.get("supplyId"));field(context,"供货事项",first(supply,"name","supplier"));
        } else if(command.startsWith("perf-")) {
            String table=command.startsWith("perf-task-")?"fr_perf_task":command.startsWith("perf-cycle-")?"fr_perf_cycle":"fr_perf_commission_release";
            subject=one(table,object);long org=number(subject.getOrDefault("company_id",event.getOrgId()));
            context.put("taskName",first(subject,"title"));context.put("period",first(subject,"period"));context.put("dueAt",first(subject,"due"));
            field(context,"任务",first(subject,"title"));field(context,"考核周期",first(subject,"period"));field(context,"截止日期",first(subject,"due"));
            Object assignee=subject.getOrDefault("assignee_user_id",subject.getOrDefault("user_id",input.get("userId")));
            context.put("assigneeUserId",text(assignee));add(recipients,assignee,org,"你是该绩效事项的责任人");
            if(command.startsWith("perf-commission")){
                var policy=one("fr_perf_commission_policy",subject.get("policy_id"));assignee=policy.get("assignee_user_id");context.put("assigneeUserId",text(assignee));add(recipients,assignee,org,"此提成事项与你有关");project(context,one("fr_eng_project",subject.get("project_id")));amount(context,one("fr_fin_payable",subject.get("payable_id")).get("total_cents"),"提成金额");
            }
            if(command.equals("perf-cycle-submit"))roles(recipients,org,"该绩效周期需要复核","boss","owner");
            if(command.startsWith("perf-commission"))roles(recipients,org,"该提成事项需要财务跟进","finance","finance-lead");
            context.put("actionPath","/pages/admin/tasks/index?view=mine"+(first(subject,"period").isBlank()?"":"&period="+first(subject,"period")));context.put("actionLabel","查看绩效事项");
            if(command.startsWith("perf-task-"))context.put("actionPath",context.get("actionPath")+"&messageTask="+object);
            else context.put("actionPath","/pages/account/index?view=personal-performance"+(first(subject,"period").isBlank()?"":"&period="+first(subject,"period")));
        } else if(aggregate.equals("lead")) {
            // 客资关联到已有客户（登记人可见时自动关联，或负责人关联）：只通知原负责人，登记人自己不收。
            subject=one("fr_lead",object);if(subject.isEmpty()||subject.get("customer_id")==null)return null;
            var customer=one("fr_customer",subject.get("customer_id"));if(customer.isEmpty())return null;
            messageObject=number(subject.get("customer_id"));customer(context,customer);long org=number(customer.get("company_id"));
            field(context,"咨询需求",first(subject,"need"));field(context,"客资来源",first(subject,"source"));
            context.put("actionPath","/pages/workflow/index?view=customer&id="+subject.get("customer_id"));context.put("actionLabel","查看客户");
            add(recipients,customer.get("owner_user_id"),org,"你负责这位客户");
            if(command.equals("lead-capture"))recipients.remove(new Recipient(number(event.getActorUserId()),org));
        } else if(aggregate.equals("fr-customer-workflow")&&command.equals("workflow-complete")) {
            // 别人替你完成了客户的一个跟进步骤（如负责人代办）时通知负责人；自己完成不通知。
            subject=one("fr_customer_workflow",object);if(subject.isEmpty()||subject.get("customer_id")==null)return null;
            var customer=one("fr_customer",subject.get("customer_id"));if(customer.isEmpty())return null;
            messageObject=number(subject.get("customer_id"));customer(context,customer);long org=number(customer.get("company_id"));
            field(context,"完成步骤",STEP_LABELS.getOrDefault(text(input.get("stepCode")),text(input.get("stepCode"))));
            context.put("actionPath","/pages/workflow/index?view=customer&id="+subject.get("customer_id"));context.put("actionLabel","查看客户");
            add(recipients,customer.get("owner_user_id"),org,"你负责这位客户");
            recipients.remove(new Recipient(number(event.getActorUserId()),org));
        } else {
            String table=switch(aggregate){
                case "sale-collection-declaration"->"fr_sale_collection_declaration";
                case "fin-receipt","fin-employee-recovery"->"fr_fin_receipt";
                case "fin-payment"->"fr_fin_payment";case "fin-payable"->"fr_fin_payable";
                case "fin-refund","fin-expense-refund"->"fr_fin_refund";
                case "fin-bank-confirm"->"fr_fin_bank_confirm";case "fin-abnormal-fund"->"fr_fin_abnormal_fund";
                case "fr-sale-contract","sale-contract"->"fr_sale_contract";default->"";
            };
            if(table.isBlank())return null;subject=one(table,object);if(subject.isEmpty())return null;
            long org=number(subject.get("company_id"));context.put("sourceTable",table);
            Map<String,Object> source=subject;
            if(table.equals("fr_fin_refund")&&subject.get("payment_id")!=null)source=one("fr_fin_payment",subject.get("payment_id"));
            else if(table.equals("fr_fin_refund"))source=one("fr_fin_receipt",subject.get("receipt_id"));
            else if(table.equals("fr_fin_bank_confirm"))source=one("fr_fin_receipt",subject.get("receipt_id"));
            Map<String,Object> payable=table.equals("fr_fin_payable")?subject:one("fr_fin_payable",source.get("payable_id"));
            project(context,one("fr_eng_project",source.getOrDefault("project_id",payable.get("project_id"))));
            customer(context,one("fr_customer",source.getOrDefault("customer_id",payable.get("customer_id"))));
            context.put("businessNo",objectLabel(table)+" "+object);
            context.put("recordType",table.substring(3));context.put("recordId",String.valueOf(object));
            amount(context,subject.getOrDefault("amount_cents",subject.get("total_cents")),table.equals("fr_sale_collection_declaration")?"申报金额":"本次金额");
            field(context,"款项阶段",stage(first(subject,"stage")));field(context,"收款对象",first(payable,"recipient"));
            field(context,"凭据状态",subject.get("voucher_file_id")==null?"":"已附凭据");
            Object employee=payable.get("payer_user_id");if(number(employee)<=0)employee=source.get("payer_user_id");
            context.put("employeeUserId",text(employee));add(recipients,employee,org,"此款项涉及你的垫付或报销");
            if(table.equals("fr_sale_collection_declaration")){
                var s=one("fr_sale_payment_stage",subject.get("stage_id"));customer(context,one("fr_customer",s.get("customer_id")));project(context,projectForContract(s.get("contract_id"),org));
                context.put("customerId",text(s.get("customer_id")));context.put("declarationId",String.valueOf(object));
                field(context,"款项阶段",stage(first(s,"stage")));field(context,"退回原因",first(subject,"return_reason"));
                context.put("notice",command.equals("sale-collection-declare")?"付款信息已申报，财务核实前不计入实际收款":"请按退回原因补充原申报资料");
                add(recipients,subject.get("applicant_user_id"),org,"你提交了这笔到账资料");
            }
            add(recipients,one("fr_customer",context.get("customerId")).get("owner_user_id"),org,"此收支事项关联你负责的客户");
            roles(recipients,org,"由本组织财务核对、办理","finance","finance-lead");
            var project=one("fr_eng_project",context.get("projectId"));add(recipients,project.get("manager_user_id"),org,"此资金事项关联你负责的项目");
            if(table.equals("fr_sale_contract")){
                var c=one("fr_customer",subject.get("customer_id"));customer(context,c);add(recipients,c.get("owner_user_id"),org,"你是合同客户负责人");
            }
            context.put("actionPath","/pages/admin/finance-desk/index?view=flows&tab="+(table.equals("fr_fin_payable")?"payables":"ledger")+"&messageType="+table.substring(3)+"&messageObject="+object);
            context.put("actionLabel",table.equals("fr_sale_collection_declaration")?"查看到账申报":"查看资金原单");
        }
        long org=number(subject.getOrDefault("company_id",event.getOrgId()));context.put("orgId",String.valueOf(org));context.put("orgName",organization(org));
        context.put("stateAtEvent",first(subject,"status"));
        field(context,"办理说明",first(input,"reason","note"));
        if(command.equals("commission-feedback"))field(context,"修改意见",first(input,"content","note"));
        return new Fact(rule,messageObject,context,subject,recipients);
    }
    public void roles(Map<Recipient,String> target,long org,String reason,String... roles){
        for(String role:roles)for(long user:jdbc.queryForList("SELECT user_id FROM pf_staff_post WHERE org_id=? AND role_code=? AND status='ACTIVE'",Long.class,org,role))add(target,user,org,reason);
    }
    public static void add(Map<Recipient,String> target,Object user,long org,String reason){long id=number(user);if(id>0&&org>0)target.putIfAbsent(new Recipient(id,org),reason);}
    public static void customer(Map<String,Object> c,Map<String,Object> row){
        if(row.isEmpty())return;c.put("customerId",text(row.get("id")));c.put("customerName",first(row,"name","customer_name"));c.put("customerOwnerId",text(row.get("owner_user_id")));
    }
    public static void project(Map<String,Object> c,Map<String,Object> row){
        if(row.isEmpty())return;c.put("projectId",text(row.get("id")));c.put("projectName",first(row,"name"));c.put("projectManagerId",text(row.get("manager_user_id")));
        if(!first(row,"customer_name").isBlank())c.put("customerName",first(row,"customer_name"));
        if(row.get("customer_id")!=null)c.put("customerId",text(row.get("customer_id")));
    }
    public static void amount(Map<String,Object> c,Object cents,String label){if(cents!=null){c.put("amountCents",text(cents));c.put("amountLabel",label);}}
    @SuppressWarnings("unchecked") public static void field(Map<String,Object> c,String label,String value){
        if(value==null||value.isBlank())return;
        ((List<Map<String,String>>)c.computeIfAbsent("fields",k->new ArrayList<>())).add(Map.of("label",label,"value",value));
    }
    public static String first(Map<String,Object> row,String... names){for(String n:names)if(row.get(n)!=null&&!text(row.get(n)).isBlank())return text(row.get(n));return "";}
    public static long number(Object value){try{return Long.parseLong(text(value));}catch(RuntimeException e){return 0;}}
    public static Map<String,Object> payload(String value){try{return json(value);}catch(RuntimeException e){return Map.of();}}
    public static String stage(String s){return switch(s){case "START","SIGNING","SIGN_AFTER"->"签约阶段款";case "BASE_DONE"->"基础阶段款";case "TOPPING","CAP_DONE"->"封顶阶段款";case "FIRST","first"->"设计首款";case "TAIL","tail"->"设计尾款";case "engineering"->"材料采购";case "engineering_expense"->"工程费用";default->s;};}
    public static String objectLabel(String table){return switch(table){case "fr_fin_receipt"->"收款单";case "fr_fin_payment"->"付款单";case "fr_fin_refund"->"退款单";case "fr_fin_payable"->"应付单";case "fr_sale_collection_declaration"->"到账申报";default->"业务单";};}
}
