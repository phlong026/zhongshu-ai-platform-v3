package cn.iocoder.yudao.module.franchise.finance;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.ORG_MISMATCH;

/** 原单核算投影与不可变确认版本。只记录核对结果，不增加收付款或修改工程结算状态。 */
@Service
public class FinProjectAccountingService {
    private final JdbcTemplate jdbc;
    private final FranchiseCommands commands;
    private final FinProjectAccountingAccess access;
    public FinProjectAccountingService(JdbcTemplate jdbc,FranchiseCommands commands,FinProjectAccountingAccess access) {
        this.jdbc=jdbc;this.commands=commands;this.access=access;
    }
    public record Confirm(String sourceHash,long expectedVersion,boolean costsComplete,String note) {}

    /** 每次只用一个 SQL 快照计算金额、来源摘要和最新确认，不能用明细前 500 行估算。 */
    private static final String ACCOUNTING_SQL="""
        , project_contract AS (
          SELECT c.* FROM fr_sale_contract c JOIN chosen p ON p.sale_contract_id=c.id AND p.company_id=c.company_id
        ), project_closure AS (
          SELECT c.* FROM fr_eng_closure c JOIN chosen p ON p.id=c.project_id AND p.company_id=c.company_id
        ), project_stages AS (
          SELECT s.* FROM fr_sale_payment_stage s JOIN project_contract c ON c.id=s.contract_id AND c.company_id=s.company_id
          WHERE NOT s.deleted AND c.status='SIGNED' AND NOT c.deleted
        ), latest AS (
          SELECT a.* FROM fr_fin_project_accounting a JOIN chosen p ON p.id=a.project_id AND p.company_id=a.company_id
          ORDER BY a.version DESC LIMIT 1
        )
        SELECT p.id::text AS project_id,p.name AS project_name,
          (SELECT id::text FROM project_contract WHERE status='SIGNED' AND NOT deleted) AS contract_id,
          (SELECT total_cents FROM project_contract WHERE status='SIGNED' AND NOT deleted) AS contract_revenue_cents,
          EXISTS(SELECT 1 FROM project_closure WHERE settled_at IS NOT NULL) AS engineering_settled,
          (SELECT cents FROM confirmed_cost) AS confirmed_cost_cents,
          (SELECT cents FROM payable_balance) AS outstanding_cents,
          (SELECT COALESCE(sum(amount_cents),0) FROM rs) AS received_cents,
          (SELECT COALESCE(sum(amount_cents),0) FROM fs WHERE refund_direction='client') AS client_refunded_cents,
          (SELECT COALESCE(sum(amount_cents),0) FROM ms WHERE funding_type='COMPANY') AS paid_cents,
          (SELECT COALESCE(sum(amount_cents),0) FROM fs WHERE refund_direction='expense' AND funding_type='COMPANY') AS expense_refunded_cents,
          (SELECT COALESCE(sum(GREATEST(0,s.amount_cents-COALESCE((SELECT sum(r.amount_cents-COALESCE((SELECT sum(f.amount_cents) FROM fs f WHERE f.receipt_id=r.id),0))
            FROM fr_sale_stage_receipt x JOIN rs r ON r.id=x.receipt_id AND r.company_id=x.company_id WHERE x.stage_id=s.id AND x.company_id=s.company_id),0))),0)
            FROM project_stages s) AS receivable_cents,
          (jsonb_build_object(
            'basis','CONTRACT_CONFIRMED_COST_V1','project',to_jsonb(p),
            'contract',(SELECT to_jsonb(c) FROM project_contract c),
            'closure',(SELECT to_jsonb(c) FROM project_closure c),
            'books',(SELECT COALESCE(jsonb_agg(to_jsonb(b) ORDER BY b.id),'[]') FROM fr_fin_book b WHERE b.company_id=p.company_id AND b.project_id=p.id),
            'stages',(SELECT COALESCE(jsonb_agg(to_jsonb(s) ORDER BY s.id),'[]') FROM project_stages s),
            'stageReceipts',(SELECT COALESCE(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]') FROM fr_sale_stage_receipt x WHERE x.company_id=p.company_id AND x.contract_id=p.sale_contract_id),
            'payables',(SELECT md5(COALESCE(jsonb_agg(to_jsonb(q) ORDER BY q.id)::text,'[]')) FROM qs q),
            'receipts',(SELECT md5(COALESCE(jsonb_agg(to_jsonb(r) ORDER BY r.id)::text,'[]')) FROM rs r),
            'payments',(SELECT md5(COALESCE(jsonb_agg(to_jsonb(m) ORDER BY m.id)::text,'[]')) FROM ms m),
            'refunds',(SELECT md5(COALESCE(jsonb_agg(to_jsonb(f) ORDER BY f.id)::text,'[]')) FROM fs f)
          ) || CASE WHEN EXISTS(SELECT 1 FROM engineering_expenses)
              THEN jsonb_build_object('engineeringExpenses',(SELECT md5(jsonb_agg(to_jsonb(e) ORDER BY e.id)::text) FROM engineering_expenses e))
              ELSE '{}'::jsonb END)::text AS source_basis,
          (SELECT jsonb_build_object('id',a.id::text,'version',a.version,'sourceHash',a.source_hash,
            'snapshot',a.snapshot,'note',a.note,'confirmedBy',a.confirmed_by::text,'confirmedPostId',a.confirmed_post_id::text,
            'confirmedAt',a.confirmed_at)::text FROM latest a) AS latest_snapshot
        FROM fr_eng_project p JOIN chosen c ON c.id=p.id AND c.company_id=p.company_id
        """;

    /** 调用方必须先验证公司范围；工作台已按当前公司 / HQ 守卫授权，绝不修改全局 OrgScope。 */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> readAuthorized(long companyId,long projectId) {
        var rows=jdbc.queryForList(FinWorkbenchService.cte()+ACCOUNTING_SQL,
                FinWorkbenchService.args(companyId,new FinWorkbenchService.Scope(projectId,null),false));
        if(rows.size()!=1)throw exception(ORG_MISMATCH);
        var row=rows.get(0);var result=new LinkedHashMap<String,Object>();
        result.put("projectId",row.get("project_id"));result.put("projectName",row.get("project_name"));
        result.put("basisCode","CONTRACT_CONFIRMED_COST_V1");
        result.put("basisLabel","合同金额减已确认项目费用；员工垫付与报销只计一次费用，不含未归属费用和公司公共费用分摊");
        result.put("contractId",row.get("contract_id"));
        Long revenue=row.get("contract_revenue_cents")==null?null:amount(row.get("contract_revenue_cents"));
        long cost=amount(row.get("confirmed_cost_cents"));
        long netReceived=Math.subtractExact(amount(row.get("received_cents")),amount(row.get("client_refunded_cents")));
        long netPaid=Math.subtractExact(amount(row.get("paid_cents")),amount(row.get("expense_refunded_cents")));
        // 金额统一输出十进制分字符串，避免累计金额和长编号在 JavaScript 中丢失精度。
        result.put("contractRevenueCents",revenue==null?null:revenue.toString());
        result.put("confirmedCostCents",Long.toString(cost));
        result.put("netReceivedCents",Long.toString(netReceived));result.put("companyNetPaidCents",Long.toString(netPaid));
        result.put("cashNetCents",Long.toString(Math.subtractExact(netReceived,netPaid)));
        result.put("grossProfitCents",revenue==null?null:Long.toString(Math.subtractExact(revenue,cost)));
        result.put("receivableCents",Long.toString(amount(row.get("receivable_cents"))));
        result.put("payableOutstandingCents",Long.toString(amount(row.get("outstanding_cents"))));
        result.put("engineeringSettled",row.get("engineering_settled"));
        String hash=DigestUtil.sha256Hex(String.valueOf(row.get("source_basis")));
        result.put("sourceHash",hash);
        Map<String,Object> latest=row.get("latest_snapshot")==null?null:parse(String.valueOf(row.get("latest_snapshot")));
        result.put("latestSnapshot",latest);result.put("version",latest==null?0:latest.get("version"));
        result.put("state",latest==null?"UNCONFIRMED":hash.equals(latest.get("sourceHash"))?"CURRENT":"STALE");
        result.put("ready",revenue!=null && Boolean.TRUE.equals(row.get("engineering_settled")));
        result.put("canConfirm",false); // 总部账本直接复用此投影时始终只读。
        return result;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> get(PostSnapshot post,long projectId) {
        access.require(post,false);
        var result=readAuthorized(post.orgId(),projectId);
        access.require(post,false);
        result.put("canConfirm",FinProjectAccountingAccess.canConfirm(post) && Boolean.TRUE.equals(result.get("ready")));
        return result;
    }

    @Transactional
    public Map<String,Object> confirm(PostSnapshot post,long projectId,String key,Confirm command) {
        access.require(post,true);
        key=commands.requireIdempotencyKey(key);
        // READ_COMMITTED 下先串行化同一项目，再以一个 SQL 读取全部来源；后续补录会使当前版本标为待复核。
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,post.orgId()+":project-accounting:"+projectId);
        access.require(post,true);
        var payload=new LinkedHashMap<String,Object>();payload.put("projectId",projectId);payload.put("confirmation",command);
        var replay=commands.replayed(post,key,payload);
        if(replay.isPresent()) {var result=snapshot(post.orgId(),projectId,replay.get().getAggregateId());access.require(post,true);return result;}
        if(command==null || command.sourceHash()==null || !command.sourceHash().matches("[0-9a-f]{64}")
                || command.expectedVersion()<0 || !command.costsComplete() || command.note()==null
                || command.note().isBlank() || command.note().length()>1000)
            throw new ServiceException(400,"请确认项目费用已完整归集，并填写不超过1000字的核算说明");
        var current=readAuthorized(post.orgId(),projectId);
        if(!Boolean.TRUE.equals(current.get("ready")))throw new ServiceException(409,"需有本项目已签合同且工程已结算，才能确认项目核算");
        if(!command.sourceHash().equals(current.get("sourceHash")) || command.expectedVersion()!=amount(current.get("version")))
            throw new ServiceException(409,"项目账或核算版本已有变化，请刷新后重新核对");
        long id=IdWorker.getId(),version=Math.addExact(command.expectedVersion(),1);
        var facts=new LinkedHashMap<>(current);facts.remove("latestSnapshot");facts.remove("version");facts.remove("state");facts.remove("canConfirm");
        jdbc.update("""
            INSERT INTO fr_fin_project_accounting(id,company_id,project_id,version,source_hash,snapshot,note,confirmed_by,confirmed_post_id)
            VALUES(?,?,?,?,?,?::jsonb,?,?,?)
            """,id,post.orgId(),projectId,version,command.sourceHash(),JsonUtils.toJsonString(facts),command.note().trim(),post.userId(),post.postId());
        commands.record(post,"fin-project-accounting",id,version,"fin-project-accounting-confirm",payload,key);
        access.require(post,true);
        return snapshot(post.orgId(),projectId,id);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> history(PostSnapshot post,long projectId,int pageNo,int pageSize) {
        access.require(post,false);requireProject(post.orgId(),projectId);
        if(pageNo<1 || pageNo>100000 || pageSize<1 || pageSize>100)throw new ServiceException(400,"核算版本分页参数无效");
        var rows=jdbc.queryForList("""
            SELECT id::text,version,source_hash,snapshot::text,note,confirmed_by::text,confirmed_post_id::text,confirmed_at
            FROM fr_fin_project_accounting WHERE company_id=? AND project_id=? ORDER BY version DESC LIMIT ? OFFSET ?
            """,post.orgId(),projectId,pageSize,(long)(pageNo-1)*pageSize).stream().map(this::historyRow).toList();
        long count=jdbc.queryForObject("SELECT count(*) FROM fr_fin_project_accounting WHERE company_id=? AND project_id=?",Long.class,post.orgId(),projectId);
        access.require(post,false);
        return Map.of("list",rows,"total",count,"pageNo",pageNo,"pageSize",pageSize);
    }
    private Map<String,Object> snapshot(long company,long project,long id) {
        requireProject(company,project);
        var rows=jdbc.queryForList("""
            SELECT id::text,version,source_hash,snapshot::text,note,confirmed_by::text,confirmed_post_id::text,confirmed_at
            FROM fr_fin_project_accounting WHERE company_id=? AND project_id=? AND id=?
            """,company,project,id);
        if(rows.isEmpty())throw exception(ORG_MISMATCH);
        return historyRow(rows.get(0));
    }
    private Map<String,Object> historyRow(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();
        result.put("id",row.get("id"));result.put("version",row.get("version"));result.put("sourceHash",row.get("source_hash"));
        result.put("snapshot",parse(String.valueOf(row.get("snapshot"))));result.put("note",row.get("note"));
        result.put("confirmedBy",row.get("confirmed_by"));result.put("confirmedPostId",row.get("confirmed_post_id"));result.put("confirmedAt",row.get("confirmed_at"));
        return result;
    }
    private void requireProject(long company,long project) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fr_eng_project WHERE company_id=? AND id=?)",Boolean.class,company,project)))throw exception(ORG_MISMATCH);
    }
    private static long amount(Object value) {return value==null?0:new BigDecimal(value.toString()).longValueExact();}
    @SuppressWarnings("unchecked") private static Map<String,Object> parse(String json) {return JsonUtils.parseObject(json,Map.class);}
}
