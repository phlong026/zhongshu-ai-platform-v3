package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.time.LocalDate;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.*;

/** 账本归属及组合登记。沿用原收付命令、流水身份、凭证和审计，不维护第二套现金余额。 */
@Service
public class FinWorkbenchService implements cn.iocoder.yudao.module.franchise.api.FinanceBookProjectPort {
    private final JdbcTemplate jdbc;
    private final FinReceiptService receipts;
    private final FinPaymentService payments;
    private final FranchiseCommands commands;
    private FinProjectAccountingService accounting;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setAccounting(FinProjectAccountingService accounting) { this.accounting=accounting; }
    public FinWorkbenchService(JdbcTemplate jdbc,@org.springframework.context.annotation.Lazy FinReceiptService receipts,
                               @org.springframework.context.annotation.Lazy FinPaymentService payments,FranchiseCommands commands) {
        this.jdbc=jdbc;this.receipts=receipts;this.payments=payments;this.commands=commands;
    }
    public record BookCommand(String name,Long customerId,Long commissionId) {
        public BookCommand(String name){this(name,null,null);}
    }
    private record LegacyBookCommand(String name) {}
    public record BookSource(Long customerId,Long commissionId) {}
    public record PayableAdjustment(long totalCents,String reason,LocalDate dueDate) {}
    public record EntryCommand(String scope,String type,String name,String category,long amountCents,
        LocalDate date,String counterparty,String accountName,String transactionNo,Long voucherFileId,String fundingType,Long payerUserId,String payerName) {
        public EntryCommand(String scope,String type,String name,String category,long amountCents,LocalDate date,String counterparty,String accountName,String transactionNo,Long voucherFileId){this(scope,type,name,category,amountCents,date,counterparty,accountName,transactionNo,voucherFileId,"COMPANY",null,null);}
    }
    record Scope(Long projectId,Long bookId) {}

    public List<Map<String,Object>> books(long org,String keyword) {
        String term="%"+(keyword==null?"":keyword.trim())+"%";
        var rows=jdbc.queryForList("""
            SELECT 'company' AS scope,'公司日常账' AS name,'公司日常' AS status,NULL::text AS project_id,NULL::text AS sale_contract_id,
              NULL::text AS customer_id,NULL::text AS commission_id,NULL::text AS book_id
            UNION ALL SELECT 'project:'||p.id,p.name,p.status,p.id::text,p.sale_contract_id::text,p.customer_id::text,p.source_commission_id::text,
              (SELECT b.id::text FROM fr_fin_book b WHERE b.company_id=p.company_id AND b.project_id=p.id) FROM fr_eng_project p
              WHERE p.company_id=? AND p.name ILIKE ?
            UNION ALL SELECT 'book:'||b.id,b.name,'前期账本',b.project_id::text,NULL::text,b.customer_id::text,b.commission_id::text,b.id::text FROM fr_fin_book b
              WHERE b.company_id=? AND b.project_id IS NULL AND b.name ILIKE ? ORDER BY scope
            """,org,term,org,term);
        return rows;
    }
    @Transactional
    public long createBook(PostSnapshot post,String key,BookCommand cmd) {
        key=commands.requireIdempotencyKey(key);
        lock(post,key);
        Object input=cmd.customerId()==null && cmd.commissionId()==null?new LegacyBookCommand(cmd.name()):cmd;
        var replay=commands.replayed(post,key,input);if(replay.isPresent())return replay.get().getAggregateId();
        if(cmd.name()==null || cmd.name().isBlank() || cmd.name().length()>160)throw exception(REFERENCE_INVALID_FIN);
        var source=requireBookSource(post.orgId(),new BookSource(cmd.customerId(),cmd.commissionId()));
        lockBookCustomer(post.orgId(),source.customerId());
        long id=IdWorker.getId();
        jdbc.update("INSERT INTO fr_fin_book(id,company_id,name,customer_id,commission_id) VALUES(?,?,?,?,?)",id,post.orgId(),cmd.name().trim(),source.customerId(),source.commissionId());
        commands.record(post,"fin-book",id,1,"fin-book-create",input,key);
        var projects=matchingBookProjects(post.orgId(),source,id);
        if(projects.size()==1)linkPendingBooks(post,projects.get(0));
        return id;
    }
    @Transactional
    public void linkProject(PostSnapshot post,long book,long project,String key) {
        linkProject(post,book,project,key,null);
    }
    @Transactional
    public void linkProject(PostSnapshot post,long book,long project,String key,BookSource source) {
        key=commands.requireIdempotencyKey(key);lock(post,key);
        Object cmd=source==null || source.customerId()==null && source.commissionId()==null
                ?Map.of("bookId",book,"projectId",project):Map.of("bookId",book,"projectId",project,"source",source);
        if(commands.replayed(post,key,cmd).isPresent())return;
        applyBookProject(post,book,project,source);
        commands.record(post,"fin-book",book,IdWorker.getId(),"fin-book-link-project",cmd,key);
    }

    public Map<String,Object> bookSources(long org,String keyword,Long customerId) {
        String term="%"+(keyword==null?"":keyword.trim())+"%";
        var rows=jdbc.queryForList("""
            SELECT c.id::text,c.name,c.phone,m.id::text AS commission_id FROM fr_customer c
            LEFT JOIN fr_commission m ON m.company_id=c.company_id AND m.customer_id=c.id
            WHERE c.company_id=? AND (?::bigint IS NULL OR c.id=?) AND (c.name ILIKE ? OR c.phone ILIKE ?)
            ORDER BY c.name,c.id LIMIT 51
            """,org,customerId,customerId,term,term);
        return Map.of("items",rows.stream().limit(50).toList(),"hasMore",rows.size()>50);
    }

    private BookSource requireBookSource(long org,BookSource source) {
        if(source==null || source.customerId()==null || source.customerId()<=0)throw new cn.iocoder.yudao.framework.common.exception.ServiceException(400,"请明确前期账本的来源客户");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fr_customer WHERE company_id=? AND id=?)",Boolean.class,org,source.customerId())))throw exception(ORG_MISMATCH);
        if(source.commissionId()!=null && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fr_commission WHERE company_id=? AND id=? AND customer_id=?)",Boolean.class,org,source.commissionId(),source.customerId())))throw exception(ORG_MISMATCH);
        return source;
    }

    private void lockBookCustomer(long org,long customer) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"fin-book-customer:"+org+":"+customer);
    }

    /** No date/status heuristic: a source or already allocated original contract must identify one project. */
    private List<Long> matchingBookProjects(long org,BookSource source,long book) {
        return jdbc.queryForList("""
            SELECT p.id FROM fr_eng_project p WHERE p.company_id=? AND p.customer_id=?
              AND (?::bigint IS NULL OR p.source_commission_id=?)
              AND NOT EXISTS(SELECT 1 FROM fr_fin_receipt r JOIN fr_sale_stage_receipt s
                ON s.company_id=r.company_id AND s.receipt_id=r.id
                WHERE r.company_id=p.company_id AND r.book_id=? AND s.contract_id IS DISTINCT FROM p.sale_contract_id)
            ORDER BY p.id
            """,Long.class,org,source.customerId(),source.commissionId(),source.commissionId(),book);
    }

    /** Called by both filing and new-book commands; the customer lock prevents the two from missing each other. */
    @Override @Transactional
    public void linkPendingBooks(PostSnapshot post,long project) {
        var projects=jdbc.queryForList("SELECT customer_id,source_commission_id FROM fr_eng_project WHERE company_id=? AND id=?",post.orgId(),project);
        if(projects.isEmpty() || projects.get(0).get("customer_id")==null)return;
        long customer=((Number)projects.get(0).get("customer_id")).longValue();
        lockBookCustomer(post.orgId(),customer);
        var pending=jdbc.queryForList("SELECT id,customer_id,commission_id FROM fr_fin_book WHERE company_id=? AND customer_id=? AND project_id IS NULL ORDER BY id",post.orgId(),customer);
        var eligible=new ArrayList<Map<String,Object>>();
        for(var book:pending){
            var source=new BookSource((Long)book.get("customer_id"),(Long)book.get("commission_id"));
            var matches=matchingBookProjects(post.orgId(),source,((Number)book.get("id")).longValue());
            if(matches.size()==1 && matches.get(0)==project)eligible.add(book);
        }
        // Existing schema permits one preliminary book per project. Never pick one arbitrarily.
        if(eligible.size()!=1)return;
        var book=eligible.get(0);long id=((Number)book.get("id")).longValue();
        try {
            applyBookProject(post,id,project,new BookSource((Long)book.get("customer_id"),(Long)book.get("commission_id")));
        } catch(cn.iocoder.yudao.framework.common.exception.ServiceException conflict) {
            return; // An inconsistent historical book stays available for explicit source verification.
        }
        commands.record(post,"fin-book",id,IdWorker.getId(),"fin-book-auto-link",Map.of("bookId",id,"projectId",project),"fin-book-auto:"+id+":"+project);
    }

    private void applyBookProject(PostSnapshot post,long book,long project,BookSource supplied) {
        // Project advisory lock precedes book/receipt locks; never take a contract lock after the book.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"fin-book-project:"+post.orgId()+":"+project);
        var rows=jdbc.queryForList("SELECT * FROM fr_fin_book WHERE id=? AND company_id=? FOR UPDATE",book,post.orgId());
        if(rows.isEmpty() || (rows.get(0).get("project_id")!=null && ((Number)rows.get(0).get("project_id")).longValue()!=project))throw exception(ORG_MISMATCH);
        var prior=rows.get(0);
        Long customer=supplied!=null && supplied.customerId()!=null?supplied.customerId():(Long)prior.get("customer_id");
        Long commission=supplied!=null && supplied.commissionId()!=null?supplied.commissionId():(Long)prior.get("commission_id");
        if(prior.get("customer_id")!=null && !Objects.equals(prior.get("customer_id"),customer)
                || prior.get("commission_id")!=null && !Objects.equals(prior.get("commission_id"),commission))throw exception(ORG_MISMATCH);
        var source=requireBookSource(post.orgId(),new BookSource(customer,commission));
        var projects=jdbc.queryForList("SELECT customer_id,source_commission_id,sale_contract_id FROM fr_eng_project WHERE company_id=? AND id=?",post.orgId(),project);
        if(projects.isEmpty() || !Objects.equals(projects.get(0).get("customer_id"),source.customerId())
                || source.commissionId()!=null && !Objects.equals(projects.get(0).get("source_commission_id"),source.commissionId()))throw exception(ORG_MISMATCH);
        Long contract=(Long)projects.get(0).get("sale_contract_id");
        boolean conflict=Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM fr_fin_book WHERE company_id=? AND project_id=? AND id<>?)
              OR EXISTS(SELECT 1 FROM fr_fin_receipt r WHERE r.company_id=? AND r.book_id=? AND (
                (r.project_id IS NOT NULL AND r.project_id<>?) OR (r.customer_id IS NOT NULL AND r.customer_id<>?)
                OR EXISTS(SELECT 1 FROM fr_sale_stage_receipt s WHERE s.company_id=r.company_id AND s.receipt_id=r.id AND s.contract_id IS DISTINCT FROM ?)))
              OR EXISTS(SELECT 1 FROM fr_fin_payable q WHERE q.company_id=? AND q.book_id=? AND q.project_id IS NOT NULL AND q.project_id<>?)
              OR EXISTS(SELECT 1 FROM fr_fin_payment m JOIN fr_fin_payable q ON q.company_id=m.company_id AND q.id=m.payable_id
                WHERE q.company_id=? AND q.book_id=? AND m.project_id IS NOT NULL AND m.project_id<>?)
            """,Boolean.class,post.orgId(),project,book,post.orgId(),book,project,customer,contract,
                post.orgId(),book,project,post.orgId(),book,project));
        if(conflict)throw new cn.iocoder.yudao.framework.common.exception.ServiceException(409,"原账已关联其他项目或合同，请核对原单，不能搬移账目");
        jdbc.update("UPDATE fr_fin_book SET project_id=?,customer_id=?,commission_id=? WHERE id=?",project,customer,commission,book);
        jdbc.update("UPDATE fr_fin_receipt SET project_id=?,customer_id=COALESCE(customer_id,?) WHERE company_id=? AND book_id=?",project,customer,post.orgId(),book);
        jdbc.update("UPDATE fr_fin_payable SET project_id=? WHERE company_id=? AND book_id=?",project,post.orgId(),book);
        jdbc.update("UPDATE fr_fin_payment SET project_id=? WHERE company_id=? AND payable_id IN(SELECT id FROM fr_fin_payable WHERE company_id=? AND book_id=?)",project,post.orgId(),post.orgId(),book);
    }
    Scope scope(long org,String value) {
        if("company".equals(value))return new Scope(null,null);
        if(value==null || !value.matches("(project|book):[0-9]+"))throw exception(REFERENCE_INVALID_FIN);
        long id=Long.parseLong(value.substring(value.indexOf(':')+1));
        if(value.startsWith("project:")) {
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fr_eng_project WHERE id=? AND company_id=?)",Boolean.class,id,org)))throw exception(ORG_MISMATCH);
            return new Scope(id,null);
        }
        var rows=jdbc.queryForList("SELECT project_id FROM fr_fin_book WHERE id=? AND company_id=?",id,org);
        if(rows.isEmpty())throw exception(ORG_MISMATCH);
        return new Scope((Long)rows.get(0).get("project_id"),id);
    }
    private Scope writeScope(long org,String value) {
        // Filing and allocation also lock the original book before touching its cash rows.
        if(value!=null && value.matches("book:[0-9]+"))
            jdbc.queryForList("SELECT id FROM fr_fin_book WHERE company_id=? AND id=? FOR UPDATE",org,Long.parseLong(value.substring(5)));
        return scope(org,value);
    }
    @Transactional
    public Map<String,String> registrationResult(PostSnapshot post,String key) {
        key=commands.requireIdempotencyKey(key);lock(post,key);
        var ids=jdbc.queryForList("""
            SELECT aggregate_id FROM pf_command_event WHERE domain='franchise' AND aggregate_type='fin-workbench-entry'
              AND command='fin-workbench-register' AND org_id=? AND actor_user_id=? AND actor_post_id=? AND idempotency_key=?
            """,Long.class,post.orgId(),post.userId(),post.postId(),"franchise:"+post.userId()+":"+key);
        return ids.isEmpty()?Map.of("state","NOT_REGISTERED"):Map.of("id",Long.toString(ids.get(0)));
    }
    @Transactional
    public long register(PostSnapshot post,String key,EntryCommand cmd) {
        key=commands.requireIdempotencyKey(key);lock(post,key);
        var replay=commands.replayed(post,key,cmd);if(replay.isPresent())return replay.get().getAggregateId();
        Scope scope=writeScope(post.orgId(),cmd.scope());
        if(cmd.amountCents()<=0 || cmd.name()==null || cmd.name().isBlank() || cmd.category()==null || cmd.category().isBlank()
            || (!"payable".equals(cmd.type()) && (cmd.accountName()==null || cmd.accountName().isBlank())))throw exception(REFERENCE_INVALID_FIN);
        BusinessDateGuard.requireValid(cmd.date());
        String inner="wb-"+cn.hutool.crypto.digest.DigestUtil.sha256Hex(key);
        long id;
        if("receipt".equals(cmd.type())) {
            id=receipts.register(post,inner,new FinReceiptService.RegisterCommand("manual",null,cmd.name(),cmd.amountCents(),cmd.date(),"other",cmd.transactionNo(),cmd.voucherFileId(),scope.projectId(),null,null,null));
            jdbc.update("UPDATE fr_fin_receipt SET book_id=?,category=?,account_name=?,counterparty=? WHERE id=? AND company_id=?",scope.bookId(),cmd.category(),cmd.accountName(),cmd.counterparty(),id,post.orgId());
        } else if("expense".equals(cmd.type()) || "payable".equals(cmd.type())) {
            if(cmd.counterparty()==null || cmd.counterparty().isBlank())throw exception(REFERENCE_INVALID_FIN);
            long payable=payments.registerPayable(post,inner+"-q",new FinPaymentService.PayableCommand(post.orgId(),scope.projectId(),cmd.name(),cmd.amountCents(),cmd.counterparty(),cmd.name()));
            jdbc.update("UPDATE fr_fin_payable SET book_id=?,category=?,business_date=?,source_type='manual' WHERE id=? AND company_id=?",scope.bookId(),cmd.category(),cmd.date(),payable,post.orgId());
            id="payable".equals(cmd.type())?payable:payments.register(post,inner+"-p",new FinPaymentService.PaymentCommand(payable,cmd.amountCents(),cmd.date(),cmd.transactionNo(),cmd.voucherFileId(),cmd.fundingType(),cmd.payerUserId(),cmd.payerName(),cmd.accountName()));
        } else throw exception(FIN_SOURCE_FORBIDDEN);
        commands.record(post,"fin-workbench-entry",id,1,"fin-workbench-register",cmd,key);return id;
    }
    private void lock(PostSnapshot post,String key) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,post.orgId()+":fin-wb:"+key);
    }
    @Transactional
    public void adjustPayable(PostSnapshot post,long id,String key,PayableAdjustment cmd) {
        key=commands.requireIdempotencyKey(key);lock(post,key);
        var input=Map.of("payableId",id,"adjustment",cmd);
        if(commands.replayed(post,key,input).isPresent())return;
        var rows=jdbc.queryForList("SELECT * FROM fr_fin_payable WHERE id=? AND company_id=? FOR UPDATE",id,post.orgId());
        if(rows.isEmpty())throw exception(ORG_MISMATCH);
        var q=rows.get(0);
        if(Set.of("design","reimbursement").contains(String.valueOf(q.get("source_type"))) || cmd.totalCents()<0 || cmd.reason()==null || cmd.reason().isBlank())throw exception(FIN_SOURCE_FORBIDDEN);
        jdbc.update("UPDATE fr_fin_payable SET total_cents=?,due_date=? WHERE id=?",cmd.totalCents(),cmd.dueDate(),id);
        long revision=jdbc.queryForObject("SELECT COALESCE(max(revision),0)+1 FROM pf_command_event WHERE domain='franchise' AND aggregate_type='fin-payable' AND aggregate_id=?",Long.class,id);
        commands.record(post,"fin-payable",id,revision,"fin-payable-adjust",input,key);
    }
    public record Assignment(String scope,String reason) {}
    @Transactional
    public void assignPayable(PostSnapshot post,long id,String key,Assignment cmd){
        key=commands.requireIdempotencyKey(key);lock(post,key);
        var input=Map.of("payableId",id,"assignment",cmd);
        if(commands.replayed(post,key,input).isPresent())return;
        Scope s=writeScope(post.orgId(),cmd.scope());
        var rows=jdbc.queryForList("SELECT * FROM fr_fin_payable WHERE id=? AND company_id=? AND NOT deleted FOR UPDATE",id,post.orgId());
        if(rows.isEmpty() || cmd.reason()==null || cmd.reason().isBlank())throw exception(REFERENCE_INVALID_FIN);
        var source=rows.get(0);
        // Original engineering obligations stay with their source, including rows predating source_type.
        boolean engineering=Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM fr_eng_supply WHERE company_id=? AND payable_id=?)
                OR EXISTS(SELECT 1 FROM fr_eng_expense WHERE company_id=? AND payable_id=?)
            """,Boolean.class,post.orgId(),id,post.orgId(),id));
        // Only a true manual payable without an upstream source may be assigned or corrected.
        // Existing project/book attribution is correctable; engineering-linked rows are rejected above.
        Object sourceRef=source.get("source_ref");
        if(engineering || !"manual".equals(source.get("source_type"))
                || sourceRef!=null && !String.valueOf(sourceRef).isBlank()
                || "已作废".equals(source.get("status")))throw exception(FIN_SOURCE_FORBIDDEN);
        jdbc.update("UPDATE fr_fin_payable SET book_id=?,project_id=? WHERE company_id=? AND (id=? OR (source_type='reimbursement' AND source_ref IN(SELECT id::text FROM fr_fin_payment WHERE payable_id=? AND company_id=?)))",s.bookId(),s.projectId(),post.orgId(),id,id,post.orgId());
        jdbc.update("UPDATE fr_fin_payment SET project_id=? WHERE company_id=? AND payable_id IN(SELECT id FROM fr_fin_payable WHERE id=? OR (company_id=? AND source_type='reimbursement' AND source_ref IN(SELECT id::text FROM fr_fin_payment WHERE payable_id=? AND company_id=?)))",s.projectId(),post.orgId(),id,post.orgId(),id,post.orgId());
        // Carry the original employee recovery when finance assigns its previously unassigned advance.
        jdbc.update("""
            UPDATE fr_fin_receipt r SET book_id=?,project_id=?
            FROM fr_fin_payable q JOIN fr_fin_payment m ON m.company_id=q.company_id AND m.id::text=q.source_ref
            WHERE r.company_id=q.company_id AND q.company_id=? AND m.payable_id=?
              AND q.source_type='reimbursement' AND NOT q.deleted
              AND m.funding_type='EMPLOYEE' AND r.source_type='manual' AND NOT r.deleted
              AND r.book_id IS NULL AND r.project_id IS NULL AND r.source_ref='employee-recovery:'||q.id
              AND EXISTS (SELECT 1 FROM pf_command_event e WHERE e.domain='franchise' AND e.org_id=r.company_id
                AND e.aggregate_type='fin-employee-recovery' AND e.command='fin-employee-recovery' AND e.aggregate_id=r.id)
            """,s.bookId(),s.projectId(),post.orgId(),id);
        long revision=jdbc.queryForObject("SELECT COALESCE(max(revision),0)+1 FROM pf_command_event WHERE domain='franchise' AND aggregate_type='fin-payable' AND aggregate_id=?",Long.class,id);
        commands.record(post,"fin-payable",id,revision,"fin-payable-assign",input,key);
    }
    /** 三种范围共享原单集合。原费用确认即计成本，挂应付后由原应付承接；现金明细继续取实际收付原单。 */
    static String cte() { return """
        WITH chosen AS (SELECT id,company_id,source_commission_id,sale_contract_id FROM fr_eng_project WHERE company_id=? AND id=?),
        qs AS (SELECT q.* FROM fr_fin_payable q WHERE q.company_id=? AND (
          q.book_id IN(SELECT id FROM fr_fin_book WHERE company_id=q.company_id AND (id=? OR project_id=?))
          OR q.project_id=? OR (q.project_id IS NULL AND q.book_id IS NULL AND q.source_type='design' AND split_part(q.source_ref,':',1) IN(SELECT source_commission_id::text FROM chosen))
          OR (q.project_id IS NULL AND q.book_id IS NULL AND q.source_type='reimbursement' AND EXISTS(SELECT 1 FROM fr_fin_payment a JOIN fr_fin_payable b ON b.id=a.payable_id AND b.company_id=a.company_id WHERE a.id::text=q.source_ref AND a.company_id=q.company_id AND b.source_type='design' AND split_part(b.source_ref,':',1) IN(SELECT source_commission_id::text FROM chosen)))
          OR (? AND q.project_id IS NULL AND q.book_id IS NULL AND (q.source_type='manual' OR (q.source_type='reimbursement' AND EXISTS(SELECT 1 FROM fr_fin_payment a JOIN fr_fin_payable b ON b.id=a.payable_id AND b.company_id=a.company_id WHERE a.id::text=q.source_ref AND a.company_id=q.company_id AND b.project_id IS NULL AND b.book_id IS NULL AND b.source_type='manual')))))),
        engineering_expenses AS (SELECT e.* FROM fr_eng_expense e JOIN chosen p ON p.company_id=e.company_id AND p.id=e.project_id),
        confirmed_cost AS (
          SELECT (SELECT COALESCE(sum(total_cents),0) FROM qs WHERE NOT deleted AND source_type IS DISTINCT FROM 'reimbursement' AND status<>'已作废')
            + (SELECT COALESCE(sum(amount_cents),0) FROM engineering_expenses WHERE status='REGISTERED' AND payable_id IS NULL) AS cents),
        rs AS (SELECT r.* FROM fr_fin_receipt r WHERE r.company_id=? AND NOT r.deleted AND r.status='已登记' AND (
          r.book_id IN(SELECT id FROM fr_fin_book WHERE company_id=r.company_id AND (id=? OR project_id=?)) OR r.project_id=?
          OR EXISTS(SELECT 1 FROM fr_sale_stage_receipt s JOIN chosen c ON c.sale_contract_id=s.contract_id WHERE s.receipt_id=r.id AND s.company_id=r.company_id)
          OR (? AND r.project_id IS NULL AND r.book_id IS NULL AND r.category IS NOT NULL
            AND NOT EXISTS(SELECT 1 FROM fr_sale_stage_receipt s WHERE s.company_id=r.company_id AND s.receipt_id=r.id)))),
        ms AS (SELECT m.*,q.stage,q.recipient,q.category,q.source_type,q.source_ref FROM fr_fin_payment m JOIN qs q ON q.id=m.payable_id AND q.company_id=m.company_id WHERE m.status='已核实' AND NOT m.deleted),
        fs AS (SELECT f.* FROM fr_fin_refund f WHERE f.company_id=? AND f.status='已核实' AND NOT f.deleted AND (f.payment_id IN(SELECT id FROM ms) OR f.receipt_id IN(SELECT id FROM rs))),
        entries AS (
          SELECT r.id,'RECEIPT' AS type,r.received_date AS date,r.amount_cents,r.name,r.category,r.source_ref,r.status FROM rs r
          UNION ALL SELECT m.id,CASE WHEN m.funding_type='EMPLOYEE' THEN 'ADVANCE' ELSE 'PAYMENT' END,m.pay_date,m.amount_cents,COALESCE(m.stage,m.recipient),m.category,m.source_ref,m.status FROM ms m
          UNION ALL SELECT f.id,CASE WHEN f.refund_direction='expense' THEN 'EXPENSE_REFUND' ELSE 'REFUND' END,f.date,f.amount_cents,f.reason,NULL,NULL,f.status FROM fs f
          UNION ALL SELECT q.id,'PAYABLE',q.business_date,q.total_cents,COALESCE(q.stage,q.recipient),q.category,q.source_ref,q.status FROM qs q WHERE NOT q.deleted),
        payable_balance AS (
          SELECT
            (SELECT COALESCE(sum(GREATEST(0,q.total_cents-COALESCE((
               SELECT sum(LEAST(m.amount_cents-COALESCE(m.pending_link_cents,0),
                                m.amount_cents-COALESCE((SELECT sum(f.amount_cents) FROM fs f WHERE f.payment_id=m.id),0)))
               FROM ms m WHERE m.payable_id=q.id),0))),0)
             FROM qs q WHERE NOT q.deleted AND q.source_type IS DISTINCT FROM 'reimbursement' AND q.status<>'已作废')
            + (SELECT COALESCE(sum(GREATEST(0,m.amount_cents
                 - COALESCE((SELECT sum(f.amount_cents) FROM fs f WHERE f.payment_id=m.id AND f.funding_type='EMPLOYEE'),0)
                 - COALESCE((SELECT sum(p.amount_cents-COALESCE((SELECT sum(f.amount_cents) FROM fs f WHERE f.payment_id=p.id),0))
                             FROM ms p WHERE p.payable_id=q.id),0)
                 + COALESCE((SELECT sum(r.amount_cents) FROM rs r WHERE r.source_ref='employee-recovery:'||q.id),0))),0)
               FROM qs q JOIN fr_fin_payment m ON m.id::text=q.source_ref AND m.company_id=q.company_id
               WHERE NOT q.deleted AND q.source_type='reimbursement' AND q.status<>'已作废'
                 AND m.status='已核实' AND m.funding_type='EMPLOYEE') AS cents)
        """; }
    static Object[] args(long org,Scope s,boolean company) {
        return new Object[]{org,s.projectId(),org,s.bookId(),s.projectId(),s.projectId(),company,org,s.bookId(),s.projectId(),s.projectId(),company,org};
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> ledger(long org,String value) {
        Scope s=scope(org,value);Object[] args=args(org,s,"company".equals(value));
        var summary=jdbc.queryForMap(cte()+"""
            SELECT (SELECT COALESCE(sum(amount_cents),0) FROM rs) AS received_cents,
              (SELECT COALESCE(sum(amount_cents),0) FROM ms WHERE funding_type='COMPANY') AS paid_cents,
              (SELECT COALESCE(sum(amount_cents),0) FROM fs WHERE refund_direction='client') AS refunded_cents,
              (SELECT COALESCE(sum(amount_cents),0) FROM fs WHERE refund_direction='expense' AND funding_type='COMPANY') AS expense_refunded_cents,
              (SELECT cents FROM confirmed_cost) AS committed_cents,
              (SELECT cents FROM payable_balance) AS outstanding_cents
            """,args);
        var entries=jdbc.queryForList(cte()+"SELECT e.id::text,e.type,e.date,e.amount_cents,e.name,e.category,e.source_ref,e.status,q.id::text AS payable_id,             COALESCE(q.source_type,r.source_type) source_type,COALESCE(q.recipient,r.counterparty) counterparty,             COALESCE(m.account_name,r.account_name) account_name,COALESCE(m.transaction_no,r.transaction_no,f.transaction_no) transaction_no,             COALESCE(m.voucher_file_id,r.voucher_file_id,f.voucher_file_id)::text voucher_file_id,             COALESCE(m.funding_type,f.funding_type,'COMPANY') funding_type, (SELECT c.payload::jsonb#>>'{supplement,note}' FROM pf_command_event c WHERE c.domain='franchise' AND c.org_id=COALESCE(m.company_id,r.company_id,f.company_id) AND c.aggregate_id=e.id AND c.aggregate_type='fin-supplement-'||CASE WHEN e.type='RECEIPT' THEN 'receipt' WHEN e.type IN ('PAYMENT','ADVANCE') THEN 'payment' ELSE 'refund' END AND NULLIF(trim(c.payload::jsonb#>>'{supplement,note}'),'') IS NOT NULL ORDER BY c.revision DESC LIMIT 1) note             FROM entries e LEFT JOIN ms m ON e.type IN ('PAYMENT','ADVANCE') AND m.id=e.id             LEFT JOIN qs q ON q.id=CASE WHEN e.type='PAYABLE' THEN e.id ELSE m.payable_id END             LEFT JOIN rs r ON e.type='RECEIPT' AND r.id=e.id             LEFT JOIN fs f ON e.type IN ('REFUND','EXPENSE_REFUND') AND f.id=e.id             ORDER BY e.date DESC NULLS LAST,e.id DESC LIMIT 500",args);
        // 历史工程应付曾共用 engineering 阶段，按原材料/费用事项补齐显示名称。
        // 只修饰读取结果，不改金额、原单或付款事实；组织隔离保持与账本一致。
        if(entries.stream().anyMatch(e -> Set.of("engineering", "engineering_expense").contains(String.valueOf(e.get("name"))))) {
            var names = new HashMap<String,String>();
            jdbc.queryForList("""
                SELECT payable_id::text AS id,name FROM fr_eng_expense WHERE company_id=? AND payable_id IS NOT NULL
                UNION ALL
                SELECT s.payable_id::text AS id,m.name FROM fr_eng_supply s
                JOIN fr_eng_material m ON m.id=s.material_id AND m.company_id=s.company_id
                WHERE s.company_id=? AND s.payable_id IS NOT NULL
                """, org, org).forEach(row -> names.put(String.valueOf(row.get("id")), String.valueOf(row.get("name"))));
            entries.forEach(e -> {
                String name=names.get(String.valueOf(e.get("payable_id")));
                if(name!=null && Set.of("PAYABLE","PAYMENT","ADVANCE").contains(String.valueOf(e.get("type")))) e.put("name",name);
            });
        }
        long receivable=0;
        if(s.projectId()!=null)receivable=jdbc.queryForObject("""
            SELECT COALESCE(sum(GREATEST(0,t.amount_cents-COALESCE((SELECT sum(r.amount_cents-COALESCE((SELECT sum(f.amount_cents) FROM fr_fin_refund f WHERE f.receipt_id=r.id AND f.company_id=r.company_id AND f.status='已核实' AND NOT f.deleted),0))
              FROM fr_sale_stage_receipt x JOIN fr_fin_receipt r ON r.id=x.receipt_id AND r.company_id=x.company_id WHERE x.stage_id=t.id AND x.company_id=t.company_id AND r.status='已登记' AND NOT r.deleted),0))),0)
            FROM fr_sale_payment_stage t JOIN fr_eng_project p ON p.sale_contract_id=t.contract_id AND p.company_id=t.company_id
            JOIN fr_sale_contract c ON c.id=t.contract_id AND c.company_id=t.company_id AND c.status='SIGNED' AND NOT c.deleted
            WHERE p.id=? AND p.company_id=? AND NOT t.deleted
            """,Long.class,s.projectId(),org);
        summary.put("receivable_cents",receivable);
        var result=new LinkedHashMap<String,Object>();
        result.put("summary",summary);result.put("entries",entries);result.put("scope",value);result.put("truncated",entries.size()==500);
        if(s.bookId()!=null)result.put("book",jdbc.queryForMap("SELECT id::text,name,customer_id::text,commission_id::text,project_id::text FROM fr_fin_book WHERE company_id=? AND id=?",org,s.bookId()));
        // Employee balances use the complete scoped obligations, independently of the recent cash-entry window.
        result.put("reimbursementIds",jdbc.queryForList(cte()+"""
            SELECT q.id::text FROM qs q JOIN fr_fin_payment m ON m.company_id=q.company_id AND m.id::text=q.source_ref
            WHERE q.source_type='reimbursement' AND NOT q.deleted AND q.status<>'已作废'
              AND NOT m.deleted AND m.status='已核实' AND m.funding_type='EMPLOYEE'
            ORDER BY m.pay_date,q.id
            """,String.class,args));
        if(s.projectId()!=null && accounting!=null)result.put("accounting",accounting.readAuthorized(org,s.projectId()));
        return result;
    }
    public List<Map<String,Object>> reimbursements(long org) {
        return jdbc.queryForList("""
            SELECT q.id::text AS payable_id,q.project_id::text,q.book_id::text,q.recipient,q.stage,m.id::text AS origin_payment_id,
              m.amount_cents-COALESCE((SELECT sum(f.amount_cents) FROM fr_fin_refund f WHERE f.payment_id=m.id AND f.status='已核实' AND f.funding_type='EMPLOYEE'),0) AS eligible_cents,
              COALESCE((SELECT sum(LEAST(p.amount_cents-COALESCE(p.pending_link_cents,0),p.amount_cents-COALESCE((SELECT sum(f.amount_cents) FROM fr_fin_refund f WHERE f.payment_id=p.id AND f.status='已核实'),0))) FROM fr_fin_payment p WHERE p.payable_id=q.id AND p.status='已核实'),0) AS reimbursed_cents,
              COALESCE((SELECT sum(r.amount_cents) FROM fr_fin_receipt r WHERE r.company_id=q.company_id AND r.source_ref='employee-recovery:'||q.id AND r.status='已登记'),0) AS recovered_cents,
              m.pay_date,m.payer_name,m.status AS origin_status
            FROM fr_fin_payable q JOIN fr_fin_payment m ON m.company_id=q.company_id AND m.id::text=q.source_ref
            WHERE q.company_id=? AND q.source_type='reimbursement' AND q.status<>'已作废' AND m.status='已核实' AND m.funding_type='EMPLOYEE' ORDER BY m.pay_date,q.id
            """,org);
    }
    public record Recovery(long amountCents,LocalDate date,String accountName,String transactionNo,Long voucherFileId) {}
    public record Supplement(String type,String note,Long voucherFileId) {}
    @Transactional
    public void supplement(PostSnapshot post,long id,String key,Supplement cmd){
        key=commands.requireIdempotencyKey(key);lock(post,key);
        var input=Map.of("id",id,"supplement",cmd);if(commands.replayed(post,key,input).isPresent())return;
        String table=switch(cmd.type()){case "receipt"->"fr_fin_receipt";case "payment"->"fr_fin_payment";case "refund"->"fr_fin_refund";default->throw exception(FIN_SOURCE_FORBIDDEN);};
        var rows=jdbc.queryForList("SELECT id FROM "+table+" WHERE id=? AND company_id=? FOR UPDATE",id,post.orgId());
        if(rows.isEmpty())throw exception(ORG_MISMATCH);
        if((cmd.note()==null||cmd.note().isBlank()) && cmd.voucherFileId()==null)throw exception(REFERENCE_INVALID_FIN);
        receipts.requireVoucherAsset(post,cmd.voucherFileId());
        if(cmd.voucherFileId()!=null)jdbc.update("UPDATE "+table+" SET voucher_file_id=? WHERE id=? AND company_id=?",cmd.voucherFileId(),id,post.orgId());
        long revision=jdbc.queryForObject("SELECT COALESCE(max(revision),0)+1 FROM pf_command_event WHERE domain='franchise' AND aggregate_type=? AND aggregate_id=?",Long.class,"fin-supplement-"+cmd.type(),id);
        commands.record(post,"fin-supplement-"+cmd.type(),id,revision,"fin-supplement",input,key);
    }
    @Transactional
    public long recoverEmployee(PostSnapshot post,long id,String key,Recovery cmd){
        key=commands.requireIdempotencyKey(key);lock(post,key);
        var input=Map.of("payableId",id,"recovery",cmd);var prior=commands.replayed(post,key,input);if(prior.isPresent())return prior.get().getAggregateId();
        var qs=jdbc.queryForList("SELECT * FROM fr_fin_payable WHERE id=? AND company_id=? AND source_type='reimbursement' FOR UPDATE",id,post.orgId());
        if(qs.isEmpty())throw exception(ORG_MISMATCH);var q=qs.get(0);
        if ("已作废".equals(q.get("status"))) throw exception(PAYABLE_VOIDED);
        jdbc.queryForList("SELECT id FROM fr_fin_payment WHERE id::text=? AND company_id=? FOR UPDATE",q.get("source_ref"),post.orgId());
        var row=reimbursements(post.orgId()).stream().filter(r->String.valueOf(id).equals(r.get("payable_id")))
                .findFirst().orElseThrow(() -> exception(PAYABLE_NOT_FOUND));
        long recoverable=((Number)row.get("reimbursed_cents")).longValue()-((Number)row.get("eligible_cents")).longValue()-((Number)row.get("recovered_cents")).longValue();
        if(cmd.amountCents()<=0 || cmd.amountCents()>recoverable || cmd.accountName()==null || cmd.accountName().isBlank())throw exception(PAYMENT_EXCEED_PAYABLE);
        long receipt=receipts.register(post,"recover-"+cn.hutool.crypto.digest.DigestUtil.sha256Hex(key),new FinReceiptService.RegisterCommand("manual","employee-recovery:"+id,"员工归还 · "+q.get("recipient"),cmd.amountCents(),cmd.date(),"other",cmd.transactionNo(),cmd.voucherFileId(),(Long)q.get("project_id"),null,null,null));
        jdbc.update("UPDATE fr_fin_receipt SET book_id=?,category='员工往来',account_name=? WHERE id=?",q.get("book_id"),cmd.accountName(),receipt);
        commands.record(post,"fin-employee-recovery",receipt,1,"fin-employee-recovery",input,key);return receipt;
    }
    public List<Map<String,Object>> staff(long org) {
        return jdbc.queryForList("SELECT DISTINCT p.user_id::text AS id,COALESCE(u.nickname,p.user_id::text) AS name FROM pf_staff_post p LEFT JOIN system_users u ON u.id=p.user_id WHERE p.org_id=? AND p.status='ACTIVE' ORDER BY name",org);
    }
}

