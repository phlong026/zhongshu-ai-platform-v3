package cn.iocoder.yudao.module.platform.headquarters;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowReadService;
import cn.iocoder.yudao.module.franchise.finance.FinWorkbenchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.module.platform.headquarters.HeadquartersReadModel.*;

/** Read models over explicitly tenant-owned companies. No impersonation and no OrgScope bypass. */
@Service
@ConditionalOnProperty(name="platform.legacy-design-bridge.enabled", havingValue="false")
public class HeadquartersReadService {
    static final String COMPANIES = """
            WITH company_brands AS (
              SELECT l.org_id,count(DISTINCT b.id) AS brand_count,min(b.id) AS brand_id,min(b.name) AS brand_name
              FROM pf_store_location l JOIN pf_map_brand b ON b.id=l.brand_id AND b.tenant_id=l.tenant_id
              JOIN pf_franchise_account a ON a.account_id=l.account_id AND a.tenant_id=l.tenant_id
                AND a.org_id=l.org_id AND a.account_type='BOSS'
              WHERE l.tenant_id=:tenant GROUP BY l.org_id),
            companies AS (
              SELECT d.id,d.name,o.org_type,
                CASE WHEN b.brand_count=1 THEN b.brand_id END AS brand_id,
                CASE WHEN b.brand_count=1 THEN b.brand_name END AS brand_name,
                CASE WHEN b.brand_count=1 THEN 'RESOLVED' WHEN b.brand_count>1 THEN 'AMBIGUOUS' ELSE 'UNASSIGNED' END AS brand_state
              FROM system_dept d JOIN pf_org_unit o ON o.dept_id=d.id AND o.org_id=d.id AND o.kind='ORG'
              LEFT JOIN company_brands b ON b.org_id=d.id
              WHERE d.tenant_id=:tenant AND d.status=0 AND d.deleted=0 AND o.status='ACTIVE'
                AND o.org_type IN ('store','headquarters'))
            """;
    private static final String COMPANY_COLUMNS = "c.id::text AS \"companyId\",c.name AS \"companyName\","
            + "c.brand_id::text AS \"brandId\",c.brand_name AS \"brandName\",c.brand_state AS \"brandState\",";

    record Dataset(String table, String columns, String joins, String keyword, String date, String extra) { }
    static final Map<String,Dataset> DATASETS = Map.of(
        "customers",new Dataset("fr_customer", """
            r.id::text AS id,r.name,r.phone,r.wechat,r.source,r.status,r.summary,
            r.province,r.city,r.district,r.town,r.village,r.address_detail AS "addressDetail",
            r.owner_user_id::text AS "ownerUserId",u.nickname AS "ownerName",r.team_id::text AS "teamId",
            r.next_visit_date AS "nextVisitDate",r.last_follow_at AS "lastFollowAt",r.create_time AS "createTime",r.update_time AS "updateTime"
            """," LEFT JOIN system_users u ON u.id=r.owner_user_id AND u.tenant_id=:tenant AND u.deleted=0 ",
            "concat_ws(' ',r.name,r.phone,r.wechat)","r.create_time::date","TRUE"),
        "projects",new Dataset("fr_eng_project", """
            r.id::text AS id,r.name,r.customer_id::text AS "customerId",r.customer_name AS "customerName",r.phone,
            r.status,r.source_type AS "sourceType",r.city,r.area,r.floors,r.manager_user_id::text AS "managerUserId",
            u.nickname AS "managerName",r.assigned_engineer_user_id::text AS "engineerUserId",
            r.contract_total_cents AS "contractTotalCents",r.contract_asset_id::text AS "contractAssetId",
            r.sale_contract_id::text AS "saleContractId",r.source_commission_id::text AS "commissionId",
            r.actual_start_at AS "actualStartAt",r.create_time AS "createTime",r.update_time AS "updateTime"
            """," LEFT JOIN system_users u ON u.id=r.manager_user_id AND u.tenant_id=:tenant AND u.deleted=0 ",
            "concat_ws(' ',r.name,r.customer_name,r.phone)","r.create_time::date","TRUE"),
        "receipts",new Dataset("fr_fin_receipt", """
            r.id::text AS id,r.name,r.project_id::text AS "projectId",r.customer_id::text AS "customerId",
            r.amount_cents AS "amountCents",r.status,r.received_date AS date,r.source_type AS "sourceType",
            r.category,r.counterparty,r.account_name AS "accountName",r.transaction_no AS "transactionNo",
            r.voucher_file_id::text AS "assetId",r.create_time AS "createTime"
            ""","","concat_ws(' ',r.name,r.counterparty,r.transaction_no)","r.received_date","r.deleted=FALSE"),
        "payables",new Dataset("fr_fin_payable", """
            r.id::text AS id,coalesce(r.stage,r.recipient) AS name,r.project_id::text AS "projectId",
            r.total_cents AS "amountCents",r.status,r.business_date AS date,r.due_date AS "dueDate",
            r.recipient AS counterparty,r.category,r.source_type AS "sourceType",r.create_time AS "createTime"
            ""","","concat_ws(' ',r.stage,r.recipient,r.agreement_ref)","r.business_date","r.deleted=FALSE"),
        "payments",new Dataset("fr_fin_payment", """
            r.id::text AS id,coalesce(q.stage,q.recipient,'付款登记') AS name,r.project_id::text AS "projectId",
            r.payable_id::text AS "payableId",r.amount_cents AS "amountCents",r.status,r.pay_date AS date,
            q.recipient AS counterparty,r.funding_type AS "fundingType",r.account_name AS "accountName",
            r.transaction_no AS "transactionNo",r.voucher_file_id::text AS "assetId",r.create_time AS "createTime"
            """," LEFT JOIN fr_fin_payable q ON q.id=r.payable_id AND q.company_id=r.company_id AND q.deleted=FALSE ",
            "concat_ws(' ',q.stage,q.recipient,r.transaction_no)","r.pay_date","r.deleted=FALSE"),
        "refunds",new Dataset("fr_fin_refund", """
            r.id::text AS id,r.reason AS name,r.receipt_id::text AS "receiptId",r.payment_id::text AS "paymentId",
            r.amount_cents AS "amountCents",r.status,r.date,r.refund_direction AS "refundDirection",
            r.funding_type AS "fundingType",r.transaction_no AS "transactionNo",r.voucher_file_id::text AS "assetId",
            r.replaces_id::text AS "replacesId",r.replaced_by_id::text AS "replacedById",r.create_time AS "createTime"
            ""","","concat_ws(' ',r.reason,r.transaction_no)","r.date","r.deleted=FALSE")
    );
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final HeadquartersReadGuard guard;
    private final CustomerWorkflowReadService workflows;
    private final FinWorkbenchService finance;

    public HeadquartersReadService(JdbcTemplate jdbc, HeadquartersReadGuard guard,
                                   CustomerWorkflowReadService workflows, FinWorkbenchService finance) {
        this.jdbc=jdbc;this.named=new NamedParameterJdbcTemplate(jdbc);this.guard=guard;
        this.workflows=workflows;this.finance=finance;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Context context() {
        var actor=guard.require();var params=new MapSqlParameterSource("tenant",actor.tenantId());
        var companies=named.queryForList(COMPANIES+"SELECT id::text AS id,name,org_type AS \"orgType\",brand_id::text AS \"brandId\",brand_name AS \"brandName\",brand_state AS \"brandState\" FROM companies ORDER BY name,id",params);
        var brands=named.queryForList("SELECT id::text AS id,name FROM pf_map_brand WHERE tenant_id=:tenant ORDER BY name,id",params);
        return new Context(Long.toString(actor.tenantId()),Long.toString(actor.orgId()),Long.toString(actor.postId()),actor.roleCode(),true,brands,companies);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page page(String dataset,Filter filter) {
        var actor=guard.require();var query=query(actor,dataset,filter);
        long total=count(query);
        var rows=rows(query,filter.pageSize(),(filter.pageNo()-1L)*filter.pageSize());
        if("customers".equals(dataset)) enrichProgress(actor,rows);
        return new Page(rows,total,filter.pageNo(),filter.pageSize(),true);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(String dataset,long id) {
        var actor=guard.require();var row=detail(actor,dataset,id);
        long company=Long.parseLong(row.get("companyId").toString());
        if("customers".equals(dataset)) row.put("workflow",workflows.readAuthorized(actor.tenantId(),company,id));
        if("projects".equals(dataset)) {
            row.put("nodes",jdbc.queryForList("""
                SELECT id::text AS id,name,node_index AS "order",status,start_date AS "startDate",end_date AS "endDate",
                  completed_at AS "completedAt" FROM fr_eng_plan_node WHERE company_id=? AND project_id=? ORDER BY node_index,id
                """,company,id));
            row.put("documents",projectItems(company,id,"documents",1,20));
            row.put("siteRecords",projectItems(company,id,"site-records",1,20));
            row.put("closure",jdbc.queryForList("""
                SELECT id::text AS id,delivered_at AS "deliveredAt",recipient,owner_asset_id::text AS "ownerAssetId",
                  file_asset_id::text AS "fileAssetId",deliver_note AS "deliverNote",settled_at AS "settledAt",
                  settle_note AS "settleNote",settle_asset_id::text AS "settleAssetId",archived_at AS "archivedAt"
                FROM fr_eng_closure WHERE company_id=? AND project_id=?
                """,company,id).stream().findFirst().orElse(Map.of()));
            row.put("saleContract",saleContract(company,row.get("saleContractId")));
            row.put("paymentStages",paymentStages(company,row.get("saleContractId")));
        }
        row.put("readOnly",true);return row;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public CustomerWorkflowModel.View workflow(long customer) {
        var actor=guard.require();var row=detail(actor,"customers",customer);
        return workflows.readAuthorized(actor.tenantId(),Long.parseLong(row.get("companyId").toString()),customer);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResult<CustomerWorkflowModel.RecordView> workflowRecords(long customer,int pageNo,int pageSize) {
        var actor=guard.require();var row=detail(actor,"customers",customer);
        return workflows.recordsAuthorized(actor.tenantId(),Long.parseLong(row.get("companyId").toString()),customer,pageNo,pageSize);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page projectItems(long project,String kind,int pageNo,int pageSize) {
        new Filter(null,null,"","",null,null,pageNo,pageSize);
        var actor=guard.require();var row=detail(actor,"projects",project);
        return projectItems(Long.parseLong(row.get("companyId").toString()),project,kind,pageNo,pageSize);
    }

    private Page projectItems(long company,long project,String kind,int pageNo,int pageSize) {
        String table,columns;
        if("documents".equals(kind)) {
            table="fr_eng_document";
            columns="id::text AS id,name,kind,version,file_ref AS reference,create_time AS \"createTime\"";
        } else if("site-records".equals(kind)) {
            table="fr_eng_site_record";
            columns="id::text AS id,node_id::text AS \"nodeId\",occurred_at AS \"occurredAt\",note,completion,result,"
                    +"review_mode AS \"reviewMode\",file_asset_ids::text AS attachments,author_user_id::text AS \"authorUserId\",create_time AS \"createTime\"";
        } else throw new ServiceException(400,"工程资料类别无效");
        String where=" FROM "+table+" WHERE company_id=? AND project_id=?";
        Long count=jdbc.queryForObject("SELECT count(*)"+where,Long.class,company,project);
        var rows=jdbc.queryForList("SELECT "+columns+where+" ORDER BY create_time DESC,id DESC LIMIT ? OFFSET ?",
                company,project,pageSize,(pageNo-1L)*pageSize);
        for(var row:rows) {
            if("documents".equals(kind)) {
                var reference=HeadquartersDocumentReference.parse(Objects.toString(row.remove("reference"),""));
                row.put("assetId",reference==null?null:Long.toString(reference.assetId()));
                row.put("downloadAvailable",reference!=null);
            } else {
                var ids=JsonUtils.parseArray(Objects.toString(row.remove("attachments"),"[]"),String.class);
                row.put("assetIds",ids==null?List.of():ids.stream().filter(value->value!=null&&value.matches("[1-9][0-9]{0,18}")).toList());
            }
        }
        return new Page(rows,count==null?0:count,pageNo,pageSize,true);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> ledger(long company,String scope) {
        var actor=guard.require();guard.company(actor,company);
        return finance.ledger(company,scope);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Map<String,Object>> books(long company,String keyword) {
        var actor=guard.require();guard.company(actor,company);
        if(keyword!=null&&keyword.length()>100)throw new ServiceException(400,"查询条件过长");
        return finance.books(company,keyword);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> summary(Filter filter) {
        var actor=guard.require();var result=new LinkedHashMap<String,Object>();
        result.put("customers",count(query(actor,"customers",filter)));
        result.put("projects",count(query(actor,"projects",filter)));
        result.put("readOnly",true);return result;
    }

    Map<String,Object> detail(HeadquartersReadGuard.Actor actor,String dataset,long id) {
        if(id<=0)throw HeadquartersReadGuard.missing();
        var query=query(actor,dataset,Filter.all());query.params().addValue("recordId",id);
        var rows=named.queryForList(query.sql()+" AND r.id=:recordId",query.params());
        if(rows.size()!=1)throw HeadquartersReadGuard.missing();
        return new LinkedHashMap<>(rows.get(0));
    }

    record Query(String sql,String countSql,MapSqlParameterSource params) { }
    Query query(HeadquartersReadGuard.Actor actor,String key,Filter filter) {
        Dataset dataset=DATASETS.get(key);
        if(dataset==null)throw new ServiceException(400,"业务类别无效");
        if(filter.companyId()!=null)guard.company(actor,filter.companyId());
        var params=new MapSqlParameterSource("tenant",actor.tenantId());
        var where=new StringBuilder(" WHERE ").append(dataset.extra());
        if(filter.companyId()!=null){where.append(" AND c.id=:company");params.addValue("company",filter.companyId());}
        if(filter.brandId()!=null){where.append(" AND c.brand_id=:brand");params.addValue("brand",filter.brandId());}
        if(!filter.keyword().isBlank()){where.append(" AND ").append(dataset.keyword()).append(" ILIKE :keyword");params.addValue("keyword","%"+filter.keyword().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");}
        if(!filter.status().isBlank()){where.append(" AND r.status=:status");params.addValue("status",filter.status());}
        if(filter.dateFrom()!=null){where.append(" AND ").append(dataset.date()).append(">=:dateFrom");params.addValue("dateFrom",filter.dateFrom());}
        if(filter.dateTo()!=null){where.append(" AND ").append(dataset.date()).append("<=:dateTo");params.addValue("dateTo",filter.dateTo());}
        String from=" FROM "+dataset.table()+" r JOIN companies c ON c.id=r.company_id "+dataset.joins()+where;
        return new Query(COMPANIES+"SELECT "+COMPANY_COLUMNS+dataset.columns()+from,COMPANIES+"SELECT count(*)"+from,params);
    }
    long count(Query query) { Long total=named.queryForObject(query.countSql(),query.params(),Long.class);return total==null?0:total; }
    List<Map<String,Object>> rows(Query query,int limit,long offset) {
        query.params().addValue("limit",limit).addValue("offset",offset);
        return named.queryForList(query.sql()+" ORDER BY r.create_time DESC,r.id DESC LIMIT :limit OFFSET :offset",query.params());
    }
    private void enrichProgress(HeadquartersReadGuard.Actor actor,List<Map<String,Object>> rows) {
        var groups=rows.stream().collect(Collectors.groupingBy(row->Long.parseLong(row.get("companyId").toString())));
        groups.forEach((company,customers)->{
            var ids=customers.stream().map(row->Long.parseLong(row.get("id").toString())).toList();
            var progress=workflows.summariesAuthorized(actor.tenantId(),company,ids);
            customers.forEach(row->row.put("workflow",progress.get(Long.parseLong(row.get("id").toString()))));
        });
    }
    private Map<String,Object> saleContract(long company,Object contract) {
        if(contract==null)return Map.of();
        return jdbc.queryForList("""
            SELECT c.id::text AS id,c.customer_id::text AS "customerId",c.total_cents AS "totalCents",c.status,
              c.signed_date AS "signedDate",c.asset_id::text AS "assetId",c.revision,
              (to_jsonb(c)->>'payment_plan_version')::int AS "paymentPlanVersion",
              to_jsonb(c)->>'payment_plan_mode' AS "paymentPlanMode"
            FROM fr_sale_contract c WHERE c.company_id=? AND c.id=? AND c.deleted=FALSE
            """,company,Long.parseLong(contract.toString())).stream().findFirst().orElse(Map.of());
    }

    private List<Map<String,Object>> paymentStages(long company,Object contract) {
        if(contract==null)return List.of();
        // JSON lookup supports rolling deployment before R11's optional metadata columns arrive.
        // Every real stage is returned; amounts and receipt/refund references remain the source of truth.
        return jdbc.queryForList("""
            SELECT s.id::text AS "stageId",s.stage,coalesce(nullif(to_jsonb(s)->>'label',''),s.stage) AS label,
              (to_jsonb(s)->>'sequence')::int AS sequence,(to_jsonb(s)->>'ratio_bps')::int AS "ratioBps",
              to_jsonb(s)->>'trigger_type' AS trigger,(to_jsonb(s)->>'trigger_node_index')::int AS "nodeIndex",
              (to_jsonb(s)->>'plan_version')::int AS "planVersion",s.due_date AS "dueDate",s.status,
              s.amount_cents AS "amountCents",gross.amount AS "grossCents",refund.amount AS "refundedCents",
              (gross.amount-refund.amount) AS "collectedCents",
              greatest(0,s.amount_cents-gross.amount+refund.amount) AS "balanceCents"
            FROM fr_sale_payment_stage s JOIN fr_sale_contract c ON c.id=s.contract_id AND c.company_id=s.company_id
            CROSS JOIN LATERAL (SELECT coalesce(sum(x.amount_cents),0)::bigint AS amount FROM fr_sale_stage_receipt x
              WHERE x.stage_id=s.id AND x.company_id=s.company_id) gross
            CROSS JOIN LATERAL (SELECT coalesce(sum(g.amount_cents),0)::bigint AS amount FROM fr_fin_refund g
              JOIN fr_sale_stage_receipt x ON x.receipt_id=g.receipt_id AND x.company_id=g.company_id
              WHERE x.stage_id=s.id AND x.company_id=s.company_id AND g.status='已核实' AND g.replaced_by_id IS NULL) refund
            WHERE s.company_id=? AND s.contract_id=? AND s.deleted=FALSE AND c.deleted=FALSE
            ORDER BY (to_jsonb(s)->>'sequence')::int NULLS LAST,s.create_time,s.id
            """,company,Long.parseLong(contract.toString()));
    }
}
