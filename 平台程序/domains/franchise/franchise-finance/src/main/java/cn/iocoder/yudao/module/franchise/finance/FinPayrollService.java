package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.PayrollReadPort;
import cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.*;

/** 薪酬核算端口：人工金额、规则版本和回款依据；没有自动工资公式或新的资金事实。 */
@Service
public class FinPayrollService implements PayrollReadPort {
    private static final long MAX_CENTS=1_000_000_000_000L;
    private final JdbcTemplate jdbc;
    private final FranchiseCommands commands;
    private final FinProjectAccountingAccess access;
    public FinPayrollService(JdbcTemplate jdbc,FranchiseCommands commands,FinProjectAccountingAccess access) {
        this.jdbc=jdbc;this.commands=commands;this.access=access;
    }
    public record RuleCommand(String family,String effectiveMonth,long expectedVersion,String baseSalaryNote,
                              String assessmentNote,String commissionNote,String evidenceNote,boolean managementConfirmed) {}
    public record CommissionLine(long projectId,Long stageId,long amountCents,String basis) {}
    public record RecordCommand(long userId,String family,String month,long expectedVersion,Long ruleId,long baseSalaryCents,
                                long adjustmentCents,List<CommissionLine> commissions,String note,boolean manuallyConfirmed) {}
    private record RulePayload(String operation,RuleCommand rule) {}
    private record RecordPayload(String operation,RecordCommand record) {}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> context(PostSnapshot post,String period) {
        access.require(post,false);String month=month(period);long tenant=tenant();
        var rules=new ArrayList<Map<String,Object>>();
        for(String family:List.of("BUSINESS","ENGINEERING")) {
            var item=new LinkedHashMap<String,Object>();item.put("family",family);
            var effective=effectiveRule(post.orgId(),family,month);item.put("effectiveRule",effective);
            item.put("status",effective==null?"UNCONFIGURED":"CONFIGURED");
            item.put("latestVersion",lastRuleVersion(post.orgId(),family));rules.add(item);
        }
        var staff=jdbc.queryForList("""
            SELECT DISTINCT u.id::text AS id,u.nickname AS name,
              CASE WHEN p.role_code IN ('sales','sales-lead') THEN 'BUSINESS' ELSE 'ENGINEERING' END AS family
            FROM pf_staff_post p JOIN system_users u ON u.id=p.user_id AND u.tenant_id=? AND u.status=0 AND u.deleted=0
            WHERE p.org_id=? AND p.status='ACTIVE' AND p.role_code IN ('sales','sales-lead','engineer','project-manager')
            ORDER BY name,id,family
            """,tenant,post.orgId());
        var projects=jdbc.queryForList("SELECT id::text,name FROM fr_eng_project WHERE company_id=? ORDER BY name,id",post.orgId());
        access.require(post,false);
        return Map.of("companyId",Long.toString(post.orgId()),"month",month,"manualOnly",true,
            "canWrite",FinProjectAccountingAccess.canConfirm(post),"rules",rules,"staff",staff,"projects",projects);
    }

    @Transactional
    public Map<String,Object> saveRule(PostSnapshot post,String key,RuleCommand cmd) {
        access.require(post,true);key=commands.requireIdempotencyKey(key);
        if(cmd==null)throw invalid("规则内容不能为空");
        String family=family(cmd.family()),month=month(cmd.effectiveMonth());
        if(cmd.expectedVersion()<0 || !cmd.managementConfirmed())throw invalid("请确认规则由管理层提供，不能自动推测薪酬规则");
        lock(post.orgId()+":payroll-rule:"+family);access.require(post,true);
        var payload=new RulePayload("payroll-rule",cmd);
        var replay=commands.replayed(post,key,payload);
        if(replay.isPresent()) {var old=rule(post.orgId(),replay.get().getAggregateId());access.require(post,true);return old;}
        String base=text(cmd.baseSalaryNote(),2000),assessment=text(cmd.assessmentNote(),2000),commission=text(cmd.commissionNote(),2000),evidence=text(cmd.evidenceNote(),1000);
        if(lastRuleVersion(post.orgId(),family)!=cmd.expectedVersion())throw conflict();
        long id=IdWorker.getId(),version=Math.addExact(cmd.expectedVersion(),1);
        jdbc.update("""
            INSERT INTO fr_fin_payroll_rule(id,company_id,job_family,version,effective_month,base_salary_note,assessment_note,commission_note,evidence_note,author_user_id,author_post_id)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """,id,post.orgId(),family,version,month,base,assessment,commission,evidence,post.userId(),post.postId());
        commands.record(post,"fin-payroll-rule",id,version,"fin-payroll-rule-record",payload,key);access.require(post,true);
        return rule(post.orgId(),id);
    }

    @Transactional
    public Map<String,Object> saveRecord(PostSnapshot post,String key,RecordCommand cmd) {
        access.require(post,true);key=commands.requireIdempotencyKey(key);
        if(cmd==null || cmd.userId()<=0 || cmd.expectedVersion()<0 || !cmd.manuallyConfirmed())throw invalid("请核对员工、月份并确认人工核算金额");
        String family=family(cmd.family()),month=month(cmd.month());String note=text(cmd.note(),1000);
        if(cmd.baseSalaryCents()<0 || cmd.baseSalaryCents()>MAX_CENTS || cmd.adjustmentCents() < -MAX_CENTS || cmd.adjustmentCents()>MAX_CENTS)
            throw invalid("底薪或调整金额超出允许范围");
        if(cmd.commissions()==null || cmd.commissions().size()>30)throw invalid("提成明细最多30项，没有提成时请提交空明细");
        lock(post.orgId()+":payroll-rule:"+family);
        lock(post.orgId()+":payroll-record:"+cmd.userId()+":"+family+":"+month);access.require(post,true);
        var payload=new RecordPayload("payroll-record",cmd);var replay=commands.replayed(post,key,payload);
        if(replay.isPresent()) {var old=record(post.orgId(),replay.get().getAggregateId());access.require(post,true);return old;}
        var member=member(post.orgId(),cmd.userId(),family);var applied=effectiveRule(post.orgId(),family,month);
        String actualRule=applied==null?null:(String)applied.get("id");
        if(!Objects.equals(actualRule,cmd.ruleId()==null?null:cmd.ruleId().toString()))throw new ServiceException(409,"当前月份适用规则已有变化，请重新加载后核对");
        long previous=jdbc.queryForObject("SELECT COALESCE(max(version),0) FROM fr_fin_payroll_record WHERE company_id=? AND user_id=? AND job_family=? AND month=?",Long.class,post.orgId(),cmd.userId(),family,month);
        if(previous!=cmd.expectedVersion())throw conflict();
        var lines=new ArrayList<Map<String,Object>>();long commission=0;
        for(var line:cmd.commissions()) {
            if(line==null || line.projectId()<=0 || line.amountCents()<=0 || line.amountCents()>MAX_CENTS)throw invalid("请填写有效提成项目及人工核定金额");
            var evidence=projectEvidence(post.orgId(),line.projectId());
            var fact=new LinkedHashMap<String,Object>();fact.put("projectId",Long.toString(line.projectId()));fact.put("projectName",evidence.get("projectName"));
            fact.put("amountCents",Long.toString(line.amountCents()));fact.put("basis",text(line.basis(),1000));
            fact.put("stage",line.stageId()==null?null:stage(evidence,line.stageId()));
            lines.add(fact);commission=Math.addExact(commission,line.amountCents());
        }
        long total=Math.addExact(Math.addExact(cmd.baseSalaryCents(),commission),cmd.adjustmentCents());
        if(total<0 || total>MAX_CENTS || commission>MAX_CENTS)throw invalid("本月核算合计须在0至100亿元之间");
        var snapshot=new LinkedHashMap<String,Object>();snapshot.put("companyId",Long.toString(post.orgId()));
        snapshot.put("userId",Long.toString(cmd.userId()));snapshot.put("userName",member.get("name"));snapshot.put("family",family);snapshot.put("month",month);
        snapshot.put("baseSalaryCents",Long.toString(cmd.baseSalaryCents()));snapshot.put("commissionCents",Long.toString(commission));
        snapshot.put("adjustmentCents",Long.toString(cmd.adjustmentCents()));snapshot.put("totalCents",Long.toString(total));
        snapshot.put("rule",applied);snapshot.put("ruleStatus",applied==null?"UNCONFIGURED":"CONFIGURED");
        snapshot.put("commissions",lines);snapshot.put("calculationMode","MANUAL_ONLY");snapshot.put("cashFact",false);
        long id=IdWorker.getId(),version=Math.addExact(previous,1);
        jdbc.update("""
            INSERT INTO fr_fin_payroll_record(id,company_id,user_id,job_family,month,version,rule_id,snapshot,note,author_user_id,author_post_id)
            VALUES(?,?,?,?,?,?,?,?::jsonb,?,?,?)
            """,id,post.orgId(),cmd.userId(),family,month,version,cmd.ruleId(),JsonUtils.toJsonString(snapshot),note,post.userId(),post.postId());
        commands.record(post,"fin-payroll-record",id,version,"fin-payroll-manual-confirm",payload,key);
        member(post.orgId(),cmd.userId(),family);access.require(post,true);
        return record(post.orgId(),id);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> project(PostSnapshot post,long project) {
        access.require(post,false);var result=projectEvidence(post.orgId(),project);access.require(post,false);return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> rules(PostSnapshot post,String family,int page,int size) {
        access.require(post,false);var result=rulesAuthorized(tenant(),post.orgId(),family,page,size);access.require(post,false);return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> records(PostSnapshot post,String month,String family,int page,int size) {
        access.require(post,false);var result=recordsAuthorized(tenant(),post.orgId(),month,family,page,size);access.require(post,false);return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> get(PostSnapshot post,long id) {
        access.require(post,false);var result=recordAuthorized(tenant(),post.orgId(),id);access.require(post,false);return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> latest(PostSnapshot post,long user,String selectedFamily,String selectedMonth) {
        access.require(post,false);String family=family(selectedFamily),month=month(selectedMonth);
        var rows=jdbc.queryForList("SELECT *,snapshot::text AS facts FROM fr_fin_payroll_record WHERE company_id=? AND user_id=? AND job_family=? AND month=? ORDER BY version DESC LIMIT 1",post.orgId(),user,family,month);
        var result=new LinkedHashMap<String,Object>();result.put("record",rows.isEmpty()?null:recordRow(rows.get(0)));
        access.require(post,false);return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> history(PostSnapshot post,long id,int page,int size) {
        access.require(post,false);var result=historyAuthorized(tenant(),post.orgId(),id,page,size);access.require(post,false);return result;
    }

    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> rulesAuthorized(long tenant,long company,String selectedFamily,int page,int size) {
        access.company(tenant,company);page(page,size);String family=optionalFamily(selectedFamily);
        String from=" FROM fr_fin_payroll_rule WHERE company_id=? AND (?::text IS NULL OR job_family=?)";
        long total=jdbc.queryForObject("SELECT count(*)"+from,Long.class,company,family,family);
        var rows=jdbc.queryForList("SELECT *"+from+" ORDER BY version DESC,id DESC LIMIT ? OFFSET ?",company,family,family,size,(long)(page-1)*size).stream().map(this::ruleRow).toList();
        access.company(tenant,company);return page(rows,total,page,size);
    }
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> recordsAuthorized(long tenant,long company,String selectedMonth,String selectedFamily,int page,int size) {
        access.company(tenant,company);page(page,size);String month=selectedMonth==null?null:month(selectedMonth),family=optionalFamily(selectedFamily);
        String cte="WITH latest AS (SELECT DISTINCT ON(user_id,job_family,month) * FROM fr_fin_payroll_record WHERE company_id=? AND (?::text IS NULL OR month=?) AND (?::text IS NULL OR job_family=?) ORDER BY user_id,job_family,month,version DESC) ";
        long total=jdbc.queryForObject(cte+"SELECT count(*) FROM latest",Long.class,company,month,month,family,family);
        var rows=jdbc.queryForList(cte+"SELECT *,snapshot::text AS facts FROM latest ORDER BY month DESC,user_id,job_family LIMIT ? OFFSET ?",company,month,month,family,family,size,(long)(page-1)*size).stream().map(this::recordRow).toList();
        access.company(tenant,company);return page(rows,total,page,size);
    }
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> recordAuthorized(long tenant,long company,long id) {
        access.company(tenant,company);var result=record(company,id);access.company(tenant,company);return result;
    }
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> historyAuthorized(long tenant,long company,long id,int page,int size) {
        access.company(tenant,company);page(page,size);var anchor=record(company,id);
        String from=" FROM fr_fin_payroll_record WHERE company_id=? AND user_id=? AND job_family=? AND month=?";
        long user=Long.parseLong((String)anchor.get("userId"));String family=(String)anchor.get("family"),month=(String)anchor.get("month");
        long total=jdbc.queryForObject("SELECT count(*)"+from,Long.class,company,user,family,month);
        var rows=jdbc.queryForList("SELECT *,snapshot::text AS facts"+from+" ORDER BY version DESC LIMIT ? OFFSET ?",company,user,family,month,size,(long)(page-1)*size).stream().map(this::recordRow).toList();
        access.company(tenant,company);return page(rows,total,page,size);
    }

    private Map<String,Object> projectEvidence(long company,long project) {
        var projects=jdbc.queryForList("SELECT id::text,name FROM fr_eng_project WHERE company_id=? AND id=?",company,project);
        if(projects.isEmpty())throw new ServiceException(404,"工程不属于当前公司");
        var stages=jdbc.queryForList("""
            SELECT s.id::text AS "stageId",s.contract_id::text AS "contractId",s.stage AS "stageKey",COALESCE(s.label,s.stage) AS label,
              s.sequence,s.trigger_type AS trigger,s.trigger_node_index AS "nodeIndex",s.plan_version AS "planVersion",s.amount_cents::text AS "expectedCents",
              COALESCE(n.collected,0)::text AS "netCollectedCents"
            FROM fr_eng_project p JOIN fr_sale_contract c ON c.id=p.sale_contract_id AND c.company_id=p.company_id AND c.status='SIGNED' AND NOT c.deleted
            JOIN fr_sale_payment_stage s ON s.contract_id=c.id AND s.company_id=c.company_id AND NOT s.deleted
            LEFT JOIN (%s) n ON n.stage_id=s.id AND n.company_id=s.company_id
            WHERE p.company_id=? AND p.id=? ORDER BY s.sequence,s.id
            """.formatted(UnifiedStageAccountPort.STAGE_NET),company,project);
        return Map.of("projectId",Long.toString(project),"projectName",projects.get(0).get("name"),"stages",stages);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> stage(Map<String,Object> project,long id) {
        return ((List<Map<String,Object>>)project.get("stages")).stream().filter(row->Long.toString(id).equals(row.get("stageId"))).findFirst()
            .orElseThrow(()->invalid("付款节点已变更或不属于所选工程，请重新加载"));
    }
    private Map<String,Object> member(long company,long user,String family) {
        String roles="BUSINESS".equals(family)?"'sales','sales-lead'":"'engineer','project-manager'";
        var rows=jdbc.queryForList("SELECT DISTINCT u.id,u.nickname AS name FROM pf_staff_post p JOIN system_users u ON u.id=p.user_id AND u.tenant_id=? AND u.status=0 AND u.deleted=0 WHERE p.org_id=? AND p.user_id=? AND p.status='ACTIVE' AND p.role_code IN ("+roles+")",tenant(),company,user);
        if(rows.size()!=1)throw new ServiceException(403,"员工不属于本公司当前业务/工程岗位，请重新选择");
        return rows.get(0);
    }
    private long lastRuleVersion(long company,String family) {return jdbc.queryForObject("SELECT COALESCE(max(version),0) FROM fr_fin_payroll_rule WHERE company_id=? AND job_family=?",Long.class,company,family);}
    private Map<String,Object> effectiveRule(long company,String family,String month) {
        var rows=jdbc.queryForList("SELECT * FROM fr_fin_payroll_rule WHERE company_id=? AND job_family=? AND effective_month<=? ORDER BY effective_month DESC,version DESC LIMIT 1",company,family,month);
        return rows.isEmpty()?null:ruleRow(rows.get(0));
    }
    private Map<String,Object> rule(long company,long id) {
        var rows=jdbc.queryForList("SELECT * FROM fr_fin_payroll_rule WHERE company_id=? AND id=?",company,id);
        if(rows.isEmpty())throw new ServiceException(404,"薪酬规则不存在或无权查看");return ruleRow(rows.get(0));
    }
    private Map<String,Object> ruleRow(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();result.put("id",row.get("id").toString());result.put("companyId",row.get("company_id").toString());
        result.put("family",row.get("job_family"));result.put("version",row.get("version"));result.put("effectiveMonth",row.get("effective_month"));
        result.put("baseSalaryNote",row.get("base_salary_note"));result.put("assessmentNote",row.get("assessment_note"));
        result.put("commissionNote",row.get("commission_note"));result.put("evidenceNote",row.get("evidence_note"));
        result.put("calculationMode","MANUAL_ONLY");result.put("createdAt",row.get("create_time"));return result;
    }
    private Map<String,Object> record(long company,long id) {
        var rows=jdbc.queryForList("SELECT *,snapshot::text AS facts FROM fr_fin_payroll_record WHERE company_id=? AND id=?",company,id);
        if(rows.isEmpty())throw new ServiceException(404,"薪酬核算记录不存在或无权查看");return recordRow(rows.get(0));
    }
    private Map<String,Object> recordRow(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();result.put("id",row.get("id").toString());result.put("companyId",row.get("company_id").toString());
        result.put("userId",row.get("user_id").toString());result.put("family",row.get("job_family"));result.put("month",row.get("month"));
        result.put("version",row.get("version"));result.put("note",row.get("note"));result.put("createdAt",row.get("create_time"));
        result.put("snapshot",JsonUtils.parseObject(row.get("facts").toString(),Map.class));return result;
    }
    private void lock(String key){jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,key);}
    private static long tenant(){Long value=TenantContextHolder.getTenantId();if(value==null)throw new ServiceException(401,"缺少当前租户");return value;}
    private static String family(String value){if(!Set.of("BUSINESS","ENGINEERING").contains(value==null?"":value))throw invalid("请选择业务或工程岗位");return value;}
    private static String optionalFamily(String value){return value==null?null:family(value);}
    private static String month(String value){try{if(value==null || !value.matches("[0-9]{4}-(0[1-9]|1[0-2])"))throw new IllegalArgumentException();return YearMonth.parse(value).toString();}catch(Exception e){throw invalid("月份须为有效的YYYY-MM");}}
    private static String text(String value,int max){if(value==null || value.isBlank() || value.length()>max)throw invalid("请填写完整说明，并控制在"+max+"字以内");return value.trim();}
    private static void page(int page,int size){if(page<1 || page>100000 || size<1 || size>100)throw invalid("分页参数无效");}
    private static Map<String,Object> page(List<Map<String,Object>> rows,long total,int page,int size){return Map.of("list",rows,"total",total,"pageNo",page,"pageSize",size,"readOnly",true);}
    private static ServiceException invalid(String message){return new ServiceException(400,message);}
    private static ServiceException conflict(){return new ServiceException(409,"薪酬规则或核算版本已有变化，请刷新后重新核对");}
}
