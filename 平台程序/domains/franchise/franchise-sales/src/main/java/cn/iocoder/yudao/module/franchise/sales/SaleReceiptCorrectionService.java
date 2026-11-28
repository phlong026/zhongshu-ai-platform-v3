package cn.iocoder.yudao.module.franchise.sales;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.REFERENCE_INVALID;

/** Correct an existing cash fact without creating a refund or changing signed payment terms. */
@Service
public class SaleReceiptCorrectionService {
    private static final String COMMAND = "sale-receipt-correct";
    private final JdbcTemplate jdbc;
    private final FranchiseCommands commands;

    public SaleReceiptCorrectionService(JdbcTemplate jdbc, FranchiseCommands commands) {
        this.jdbc = jdbc;
        this.commands = commands;
    }

    public record Correction(Integer baseRevisionVersion, Long amountCents, LocalDate receivedDate,
                             Long stageId, String reason) { }

    private record RefundFacts(long amountCents, LocalDate firstDate) { }

    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> registrationResult(PostSnapshot actor, long receiptId, String key) {
        requireFinance(actor);
        String token=lockCommand(actor,key);
        var prior=commands.previous(actor,token,COMMAND,receiptId);
        return prior.<Map<String,Object>>map(event -> result(receiptId,event.getRevision().intValue(),true))
                .orElseGet(() -> Map.of("state","NOT_REGISTERED"));
    }

    private String lockCommand(PostSnapshot actor,String key) {
        String token="sale-correct:"+DigestUtil.sha256Hex(commands.requireIdempotencyKey(key));
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,
                FranchiseCommandKeys.lockKey(new FranchiseCommandKeys.PostKeyScope("franchise",actor.userId()),token));
        return token;
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(PostSnapshot actor, long receiptId) {
        requireFinance(actor);
        var receipt = receipt(actor, receiptId, false);
        var result = snapshot(receipt);
        result.put("stages", jdbc.queryForList("""
                SELECT id::text AS "stageId",COALESCE(label,stage) AS label,amount_cents AS "amountCents"
                FROM fr_sale_payment_stage WHERE company_id=? AND contract_id=? AND NOT deleted
                ORDER BY sequence,id
                """, actor.orgId(), receipt.get("contract_id")));
        result.put("history", jdbc.queryForList("""
                SELECT revision,actor_user_id::text AS "operatorUserId",occurred_at AS "createdAt",payload
                FROM pf_command_event WHERE org_id=? AND aggregate_type='sale-receipt-correction'
                AND aggregate_id=? AND command=? ORDER BY revision DESC
                """, actor.orgId(), receiptId, COMMAND).stream().map(row -> {
                    var payload = JsonUtils.parseObject(String.valueOf(row.get("payload")), Map.class);
                    var history = new LinkedHashMap<String,Object>(row);
                    history.remove("payload");
                    history.putAll(payload);
                    return history;
                }).toList());
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> correct(PostSnapshot actor, long receiptId, Correction change, String key) {
        requireFinance(actor);
        String token = lockCommand(actor,key);
        var request = new LinkedHashMap<String,Object>();
        request.put("receiptId", Long.toString(receiptId));
        request.put("change", change);
        var prior = commands.previous(actor, token, COMMAND, receiptId);
        if (prior.isPresent()) {
            var recorded = JsonUtils.parseObject(prior.get().getPayload(), Map.class);
            if (!Objects.equals(JsonUtils.parseObject(JsonUtils.toJsonString(request), Map.class), recorded.get("request")))
                throw exception(IDEMPOTENCY_KEY_REUSED);
            return result(receiptId, prior.get().getRevision().intValue(), true);
        }
        validate(change);
        var peek = receipt(actor, receiptId, false);
        long contractId = ((Number)peek.get("contract_id")).longValue();
        // Same order as collection: command -> contract -> sorted stages -> original receipt.
        var contracts = jdbc.queryForList("SELECT customer_id,status FROM fr_sale_contract WHERE id=? AND company_id=? FOR UPDATE",
                contractId, actor.orgId());
        if (contracts.isEmpty() || !Set.of("SIGNED","VOIDED").contains(contracts.get(0).get("status"))) throw exception(REFERENCE_INVALID);
        long oldStageId = ((Number)peek.get("stage_id")).longValue();
        var lockedStages = jdbc.queryForList("""
                SELECT id,company_id,contract_id,deleted,amount_cents FROM fr_sale_payment_stage
                WHERE id IN (?,?) ORDER BY id FOR UPDATE
                """, oldStageId, change.stageId());
        var target = lockedStages.stream().filter(row -> Objects.equals(row.get("id"), change.stageId())).findFirst()
                .orElseThrow(() -> exception(REFERENCE_INVALID));
        if (!Objects.equals(target.get("company_id"), actor.orgId()) || !Objects.equals(target.get("contract_id"), contractId)
                || Boolean.TRUE.equals(target.get("deleted"))) throw exception(REFERENCE_INVALID);
        var receipt = receipt(actor, receiptId, true);
        if (!Objects.equals(receipt.get("contract_id"), contractId) || !Objects.equals(receipt.get("stage_id"), oldStageId)
                || !Objects.equals(receipt.get("customer_id"), contracts.get(0).get("customer_id"))) throw exception(REFERENCE_INVALID);
        int revision = ((Number)receipt.get("revision_version")).intValue();
        if (revision != change.baseRevisionVersion()) throw PaymentPlanRules.invalid("原收款已被修改，请刷新核对后重试");
        // Read the amount and first effective refund date from the same snapshot after locking the receipt.
        var refunds = jdbc.queryForObject("""
                SELECT COALESCE(sum(amount_cents),0) AS total, MIN(date) AS first_date FROM fr_fin_refund
                WHERE receipt_id=? AND company_id=? AND status='已核实' AND replaced_by_id IS NULL
                """, (rs, row) -> new RefundFacts(rs.getLong("total"), rs.getObject("first_date", LocalDate.class)),
                receiptId, actor.orgId());
        long refunded = refunds.amountCents();
        if (change.amountCents() < refunded) throw PaymentPlanRules.invalid("更正金额不能少于这笔收款已经退回的金额");
        if (refunds.firstDate() != null && change.receivedDate().isAfter(refunds.firstDate()))
            throw PaymentPlanRules.invalid("更正后的收款日期不能晚于这笔收款最早的有效退款日期");
        long existingNet = net(change.stageId());
        long ownNet = oldStageId == change.stageId() ? ((Number)receipt.get("amount_cents")).longValue() - refunded : 0;
        if (existingNet - ownNet + change.amountCents() - refunded > ((Number)target.get("amount_cents")).longValue())
            throw PaymentPlanRules.invalid("更正后的收款超过该合同期次可收余额");
        var before = snapshot(receipt);
        jdbc.update("UPDATE fr_fin_receipt SET amount_cents=?,received_date=?,revision_version=? WHERE id=? AND company_id=?",
                change.amountCents(), change.receivedDate(), revision+1, receiptId, actor.orgId());
        jdbc.update("UPDATE fr_sale_stage_receipt SET stage_id=?,amount_cents=? WHERE receipt_id=? AND company_id=? AND contract_id=?",
                change.stageId(), change.amountCents(), receiptId, actor.orgId(), contractId);
        // Original declarations, source_ref and engineering snapshots remain the original submitted evidence.
        for (var stage : lockedStages) {
            long id = ((Number)stage.get("id")).longValue();
            long collected = net(id);
            String status = collected >= ((Number)stage.get("amount_cents")).longValue() ? "SETTLED" : collected > 0 ? "OPEN" : "PENDING";
            jdbc.update("UPDATE fr_sale_payment_stage SET status=? WHERE id=?", status, id);
        }
        jdbc.update("""
                INSERT INTO fr_fin_receipt_revision(id,receipt_id,base_revision_version,original_amount_cents,
                new_amount_cents,note,operator_user_id,tenant_id) VALUES(?,?,?,?,?,?,?,?)
                """, IdWorker.getId(), receiptId, revision, receipt.get("amount_cents"), change.amountCents(),
                change.reason().trim(), actor.userId(), receipt.get("tenant_id"));
        var audit = new LinkedHashMap<String,Object>();
        audit.put("request", request);
        audit.put("before", before);
        audit.put("after", snapshot(receipt(actor, receiptId, false)));
        commands.record(actor, "sale-receipt-correction", receiptId, revision+1, COMMAND, audit, token);
        return result(receiptId, revision+1, false);
    }

    private Map<String,Object> receipt(PostSnapshot actor, long id, boolean lock) {
        var rows = jdbc.queryForList("""
                SELECT r.id,r.customer_id,r.tenant_id,r.amount_cents,r.received_date,r.revision_version,s.contract_id,s.stage_id
                FROM fr_fin_receipt r JOIN fr_sale_stage_receipt s ON s.receipt_id=r.id AND s.company_id=r.company_id
                WHERE r.id=? AND r.company_id=? AND r.source_type='business' AND r.status='已登记'
                """ + (lock ? " FOR UPDATE OF r,s" : ""), id, actor.orgId());
        if (rows.size()!=1) throw exception(REFERENCE_INVALID);
        return rows.get(0);
    }

    private long net(long stageId) {
        return jdbc.queryForObject("SELECT COALESCE((SELECT collected FROM (" + UnifiedStageAccountPort.STAGE_NET
                + ") amounts WHERE stage_id=?),0)", Long.class, stageId);
    }

    private static Map<String,Object> snapshot(Map<String,Object> row) {
        var view = new LinkedHashMap<String,Object>();
        view.put("receiptId", String.valueOf(row.get("id")));
        view.put("contractId", String.valueOf(row.get("contract_id")));
        view.put("stageId", String.valueOf(row.get("stage_id")));
        view.put("amountCents", row.get("amount_cents"));
        view.put("receivedDate", row.get("received_date") == null ? null : row.get("received_date").toString());
        view.put("revisionVersion", row.get("revision_version"));
        return view;
    }

    private static Map<String,Object> result(long id,int revision,boolean replayed) {
        return Map.of("receiptId",Long.toString(id),"revisionVersion",revision,"replayed",replayed);
    }

    private static void requireFinance(PostSnapshot actor) {
        if (!"store".equals(actor.orgType()) || !Set.of("finance","finance-lead").contains(actor.roleCode())) throw exception(REFERENCE_INVALID);
    }

    private static void validate(Correction c) {
        if (c==null || c.baseRevisionVersion()==null || c.baseRevisionVersion()<1 || c.stageId()==null
                || c.amountCents()==null || c.amountCents()<=0 || c.amountCents()>SaleQuoteMath.MAX_AMOUNT)
            throw PaymentPlanRules.invalid("请填写有效的原单版本、收款金额和合同期次");
        if (c.receivedDate()==null || c.receivedDate().isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
            throw PaymentPlanRules.invalid("请填写真实收款日期，不能晚于今天");
        if (c.reason()==null || c.reason().isBlank() || c.reason().trim().length()>255)
            throw PaymentPlanRules.invalid("请填写更正原因，最多255字");
    }
}
