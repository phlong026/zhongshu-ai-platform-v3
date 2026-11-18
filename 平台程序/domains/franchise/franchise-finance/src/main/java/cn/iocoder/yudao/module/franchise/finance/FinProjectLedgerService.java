package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.time.LocalDate;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.ORG_MISMATCH;

@Service
public class FinProjectLedgerService {
    private final JdbcTemplate jdbc;
    public FinProjectLedgerService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Row(long id,String type,LocalDate occurredOn,long amountCents,String sourceRef,String status) {}
    public record Summary(long receivableCents,long receivedCents,long refundedCents,long paidCents,long expenseRefundedCents,long payableCents) {}
    public record Ledger(Summary summary,PageResult<Row> entries,String projectName) {}
    public record Project(long projectId,String name,String status,Long sourceCommissionId) {}
    public PageResult<Project> projects(long org,int pageNo,int pageSize,String keyword) {
        String term=keyword==null || keyword.isBlank()?null:keyword.trim();
        String filter=" FROM fr_eng_project WHERE company_id=? AND (?::text IS NULL OR name ILIKE ? OR id::text=?)";
        long total=jdbc.queryForObject("SELECT count(*)"+filter,Long.class,org,term,term==null?null:"%"+term+"%",term);
        var rows=jdbc.query("SELECT id,name,status,source_commission_id"+filter+" ORDER BY create_time DESC,id DESC LIMIT ? OFFSET ?",
                (rs,n)->new Project(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getObject(4,Long.class)),org,term,term==null?null:"%"+term+"%",term,pageSize,(long)(pageNo-1)*pageSize);
        return new PageResult<>(rows,total);
    }

    /** 汇总与明细始终使用相同范围，前置设计费用沿工程真实来源委托，不回填虚构project_id。 */
    private static final String SCOPE="""
        WITH project AS (SELECT id,company_id,source_commission_id,sale_contract_id FROM fr_eng_project WHERE id=? AND company_id=?),
        expected_stages AS (SELECT s.id,s.company_id,s.contract_id,s.stage,s.amount_cents,s.status,c.signed_date
          FROM project x JOIN fr_sale_contract c ON c.id=x.sale_contract_id AND c.company_id=x.company_id AND c.status='SIGNED' AND NOT c.deleted
          JOIN fr_sale_payment_stage s ON s.contract_id=c.id AND s.company_id=c.company_id
          WHERE NOT s.deleted AND s.amount_cents>0 AND s.stage IN('SIGN_AFTER','BASE_DONE','CAP_DONE','tail')),
        payables AS (SELECT p.* FROM fr_fin_payable p JOIN project x ON p.company_id=x.company_id
          WHERE p.book_id IN(SELECT id FROM fr_fin_book WHERE company_id=x.company_id AND project_id=x.id) OR p.project_id=x.id OR (p.project_id IS NULL AND p.book_id IS NULL AND p.source_type='design' AND split_part(p.source_ref,':',1)=x.source_commission_id::text) OR (p.project_id IS NULL AND p.book_id IS NULL AND p.source_type='reimbursement' AND EXISTS(SELECT 1 FROM fr_fin_payment m JOIN fr_fin_payable b ON b.id=m.payable_id AND b.company_id=m.company_id WHERE m.id::text=p.source_ref AND m.company_id=x.company_id AND b.source_type='design' AND split_part(b.source_ref,':',1)=x.source_commission_id::text))),
        payments AS (SELECT m.*,p.source_ref ledger_source_ref FROM fr_fin_payment m JOIN project x ON m.company_id=x.company_id
          LEFT JOIN fr_fin_payable p ON p.id=m.payable_id AND p.company_id=x.company_id
          WHERE m.status='已核实' AND (m.project_id=x.id OR m.payable_id IN(SELECT id FROM payables))),
        receipts AS (SELECT r.* FROM fr_fin_receipt r JOIN project x ON r.company_id=x.company_id
          WHERE r.status IN('已登记','待登记') AND (r.book_id IN(SELECT id FROM fr_fin_book WHERE company_id=x.company_id AND project_id=x.id) OR r.project_id=x.id
            OR EXISTS(SELECT 1 FROM fr_sale_stage_receipt s WHERE s.company_id=x.company_id AND s.contract_id=x.sale_contract_id AND s.receipt_id=r.id)
            OR EXISTS(SELECT 1 FROM fr_eng_collection c WHERE c.company_id=x.company_id AND c.project_id=x.id AND c.receipt_id=r.id))),
        ledger AS (
          SELECT id,'PAYABLE'::text type,create_time::date occurred_on,total_cents amount_cents,source_ref,status FROM payables
          UNION ALL SELECT id,CASE WHEN funding_type='EMPLOYEE' THEN 'ADVANCE' ELSE 'PAYMENT' END,pay_date,amount_cents,ledger_source_ref,status FROM payments
          UNION ALL SELECT id,'RECEIVABLE',signed_date,amount_cents,'contract:'||contract_id||':'||stage,status FROM expected_stages
          UNION ALL SELECT r.id,CASE WHEN r.status='待登记' THEN 'PENDING_RECEIPT' ELSE 'RECEIPT' END,r.received_date,r.amount_cents,r.source_ref,r.status FROM receipts r
            WHERE r.status='已登记' OR NOT EXISTS(SELECT 1 FROM fr_sale_stage_receipt s JOIN expected_stages e ON e.id=s.stage_id AND e.company_id=s.company_id WHERE s.receipt_id=r.id)
          UNION ALL SELECT f.id,CASE WHEN f.refund_direction='expense' AND f.funding_type='EMPLOYEE' THEN 'EMPLOYEE_REFUND' WHEN f.refund_direction='expense' THEN 'EXPENSE_REFUND' ELSE 'REFUND' END,f.date,f.amount_cents,
            CASE WHEN f.refund_direction='expense' THEN (SELECT ledger_source_ref FROM payments WHERE id=f.payment_id)
              ELSE (SELECT source_ref FROM receipts WHERE id=f.receipt_id) END,f.status
            FROM fr_fin_refund f WHERE f.status='已核实' AND f.company_id=? AND
              (f.payment_id IN(SELECT id FROM payments) OR f.receipt_id IN(SELECT id FROM receipts WHERE status='已登记'))
        )
        """;
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Ledger page(long org,long project,int pageNo,int pageSize) {
        var projectNames=jdbc.queryForList("SELECT name FROM fr_eng_project WHERE id=? AND company_id=?",String.class,project,org);
        if(projectNames.isEmpty()) throw exception(ORG_MISMATCH);
        Summary summary=jdbc.queryForObject(SCOPE+"""
            SELECT COALESCE(sum(amount_cents) FILTER(WHERE type='RECEIVABLE'),0),
              COALESCE(sum(amount_cents) FILTER(WHERE type='RECEIPT'),0),
              COALESCE(sum(amount_cents) FILTER(WHERE type='REFUND'),0),
              COALESCE(sum(amount_cents) FILTER(WHERE type='PAYMENT'),0),
              COALESCE(sum(amount_cents) FILTER(WHERE type='EXPENSE_REFUND'),0),
              COALESCE(sum(amount_cents) FILTER(WHERE type='PAYABLE' AND id NOT IN(SELECT id FROM payables WHERE source_type='reimbursement')),0) FROM ledger
            """,(rs,n)->new Summary(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getLong(6)),project,org,org);
        long total=jdbc.queryForObject(SCOPE+"SELECT count(*) FROM ledger",Long.class,project,org,org);
        var rows=jdbc.query(SCOPE+"SELECT * FROM ledger ORDER BY occurred_on DESC NULLS LAST,id DESC,type DESC LIMIT ? OFFSET ?",
                (rs,n)->new Row(rs.getLong("id"),rs.getString("type"),rs.getObject("occurred_on",LocalDate.class),rs.getLong("amount_cents"),rs.getString("source_ref"),rs.getString("status")),project,org,org,pageSize,(long)(pageNo-1)*pageSize);
        return new Ledger(summary,new PageResult<>(rows,total),projectNames.get(0));
    }
}
