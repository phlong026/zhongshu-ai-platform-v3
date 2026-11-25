package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.module.franchise.sales.dal.*;
import cn.iocoder.yudao.module.franchise.api.BusinessReceiptPort;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort.ServiceAccountInactiveException;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED;
import static cn.iocoder.yudao.module.franchise.sales.dal.SaleContractDO.STATUS_SIGNED;
import static cn.iocoder.yudao.module.franchise.sales.dal.SaleContractDO.STATUS_VOIDED;
import static cn.iocoder.yudao.module.franchise.sales.dal.SaleContractDO.STATUS_WAIT_SIGN;
import static cn.iocoder.yudao.module.franchise.sales.dal.SalePaymentStageDO.STATUS_PAID;
import static cn.iocoder.yudao.module.franchise.sales.dal.SalePaymentStageDO.STATUS_PENDING;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.CONTRACT_DUPLICATE;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.REFERENCE_INVALID;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.CONTRACT_NOT_SIGNED;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.STAGE_ALREADY_PAID;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.STAGE_AMOUNT_EXCEED;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.STAGE_AMOUNT_INVALID;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.STAGE_BUSINESS_DATE_FUTURE;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.STAGE_BUSINESS_DATE_REQUIRED;

/**
 * 合同签约/回款（B5/B6/B7 任务，Codex 八轮评审定稿）：
 * 创建即生成两期计划（零尾款不生成 tail）；collect 统一锁序（客户端键 advisory lock → 合同锁 → stage 锁）
 * + 幂等三层（客户端键 token=完整摘要 / 阶段 pay_fingerprint 持久化指纹 / finance source_ref 唯一索引+完整内容比对）；
 * business 收款经 BusinessReceiptPort 内部专用入口（手工 REST 不放宽）；PAID 重放优先（VOIDED 后同内容重放成功）。
 */
@Slf4j
@Service
public class SaleContractService {

    /**
     * M06-T04 三期合同命令：三期金额由合同约定（不设固定比例），Σ三期=totalCents；
     * baseDoneCents/capDoneCents 允许 0=未约定（期行照建，余额自然为 0）。
     */
    public record ContractCommand(long customerId, long totalCents, long signAfterCents,
                                  long baseDoneCents, long capDoneCents,
                                  LocalDate signedDate, Long assetId, Long requirementId,
                                  java.util.List<PaymentPlanRules.Input> paymentStages) {
        public ContractCommand(long customerId, long totalCents, long signAfterCents, long baseDoneCents,
                               long capDoneCents, LocalDate signedDate, Long assetId, Long requirementId) {
            this(customerId, totalCents, signAfterCents, baseDoneCents, capDoneCents,
                    signedDate, assetId, requirementId, null);
        }
    }

    public record PaymentPlanCommand(long totalCents, java.util.List<PaymentPlanRules.Input> stages,
                                     Long revision, String reason) {}
    private record LegacyContractCommand(long customerId,long totalCents,long signAfterCents,long baseDoneCents,
                                         long capDoneCents,LocalDate signedDate,Long assetId,Long requirementId) {}

    public record SignCommand(LocalDate signedDate, Long assetId) {
    }

    /**
     * M06-T04 分笔核销命令：amountCents 为本笔金额（可小于期约定额；Σ分笔 ≤ 期额）。
     */
    public record CollectCommand(long amountCents, String method, String transactionNo, LocalDate receivedDate,Long voucherFileId,String accountName) {
        public CollectCommand(long amountCents,String method,String transactionNo,LocalDate receivedDate){this(amountCents,method,transactionNo,receivedDate,null,null);}
    }

    private final SaleContractMapper contracts;
    private final SalePaymentStageMapper stages;
    private final SaleStageReceiptMapper stageReceipts;
    private final SaleRequirementService requirements;
    private final BusinessReceiptPort businessReceipts;
    private final AssetAccessPort assets;
    private final StoreServiceAccountPort serviceAccounts;
    private final FranchiseCommands commands;
    private final SalesAccess access;
    private final TransactionTemplate txTemplate;
    private final JdbcTemplate jdbcTemplate;

    public SaleContractService(SaleContractMapper contracts, SalePaymentStageMapper stages,
                               SaleStageReceiptMapper stageReceipts,
                               SaleRequirementService requirements, BusinessReceiptPort businessReceipts,
                               AssetAccessPort assets, StoreServiceAccountPort serviceAccounts,
                               FranchiseCommands commands, SalesAccess access,
                               PlatformTransactionManager transactionManager, DataSource dataSource) {
        this.contracts = contracts;
        this.stages = stages;
        this.stageReceipts = stageReceipts;
        this.requirements = requirements;
        this.businessReceipts = businessReceipts;
        this.assets = assets;
        this.serviceAccounts = serviceAccounts;
        this.commands = commands;
        this.access = access;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** 合同已签且未作废；作废后 PAID 重放优先（不要求 SIGNED） */
    private SaleContractDO requireSIGNED(SaleContractDO contract) {
        if (contract == null) {
            throw exception(CONTRACT_NOT_SIGNED);
        }
        if (!STATUS_SIGNED.equals(contract.getStatus())) {
            throw exception(CONTRACT_NOT_SIGNED);
        }
        return contract;
    }

    // ========== 创建（WAIT_SIGN + 两期计划） ==========

    @Transactional(rollbackFor = Exception.class)
    public SaleContractDO create(PostSnapshot post, ContractCommand cmd, String idempotencyKey) {
        access.requireOrgCustomer(post, cmd.customerId());
        keyGuard(post, idempotencyKey);
        var customPlan = cmd.paymentStages() == null ? null : PaymentPlanRules.normalize(cmd.totalCents(), cmd.paymentStages());
        if (customPlan != null && customPlan.stream().anyMatch(s -> s.stageId() != null)) {
            throw PaymentPlanRules.invalid("新合同付款节点不应指定已有编号");
        }
        // M06-T04：三期金额校验——Σ三期=合同总额；签订后必须>0，其余期≥0（0=未约定，不发明固定比例）
        if (customPlan == null && (cmd.totalCents() <= 0 || cmd.signAfterCents() <= 0
                || cmd.baseDoneCents() < 0 || cmd.capDoneCents() < 0
                || cmd.signAfterCents() + cmd.baseDoneCents() + cmd.capDoneCents() != cmd.totalCents()
                || cmd.totalCents() > SaleQuoteMath.MAX_AMOUNT)) {
            throw exception(CONTRACT_DUPLICATE); // 金额非法复用合同码段——由 AC 断言区分（金额校验独立见下）
        }
        requirements.requireOfCustomer(cmd.customerId(), cmd.requirementId());
        validateAsset(post, cmd.assetId());
        var input = baseInput("contract-create", cmd.customerId(), cmd);
        // 幂等重放先于有效合同检查（S1-21：原顺序「先查 006 再查重放」使响应丢失后的原键重试
        // 恒撞 CONTRACT_DUPLICATE，永远到不了重放分支）
        Object legacyInput = cmd.paymentStages()==null ? baseInput("contract-create",cmd.customerId(),
                new LegacyContractCommand(cmd.customerId(),cmd.totalCents(),cmd.signAfterCents(),cmd.baseDoneCents(),
                        cmd.capDoneCents(),cmd.signedDate(),cmd.assetId(),cmd.requirementId())) : null;
        var replayed = commands.replayed(post, idempotencyKey, input, legacyInput);
        if (replayed.isPresent()) {
            return contracts.selectById(replayed.get().getAggregateId());
        }
        var existing = contracts.selectActiveByCustomer(cmd.customerId());
        if (existing != null) {
            throw exception(CONTRACT_DUPLICATE);
        }
        SaleContractDO contract = new SaleContractDO()
                .setCompanyId(post.orgId()).setCustomerId(cmd.customerId())
                .setTotalCents(cmd.totalCents()).setFirstCents(customPlan == null ? cmd.signAfterCents() : beforeStart(customPlan))
                .setPaymentPlanVersion(1)
                .setPaymentPlanMode(customPlan == null ? "LEGACY" : "CUSTOM")
                .setSignedDate(cmd.signedDate()).setAssetId(cmd.assetId())
                .setStatus(STATUS_WAIT_SIGN).setRequirementId(cmd.requirementId())
                .setAuthorUserId(post.userId());
        contracts.insert(contract);
        if (customPlan == null) createStages(post, contract, cmd);
        else for (var stage : customPlan) insertPlanStage(contract, stage, 1);
        snapshotPlan(post, contract.getId(), 1, 1, "合同付款计划登记");
        commands.record(post, "fr-sale-contract", contract.getId(), 1, "sale-contract-create", input, idempotencyKey);
        return contract;
    }

    /** M06-T04：三期期行全建（0=未约定），期额唯一事实；Σ期=合同总额由 create 校验保证 */
    private void createStages(PostSnapshot post, SaleContractDO contract, ContractCommand cmd) {
        insertStage(contract, SalePaymentStageDO.STAGE_SIGN_AFTER, cmd.signAfterCents());
        insertStage(contract, SalePaymentStageDO.STAGE_BASE_DONE, cmd.baseDoneCents());
        insertStage(contract, SalePaymentStageDO.STAGE_CAP_DONE, cmd.capDoneCents());
    }

    private void insertStage(SaleContractDO contract, String stage, long amountCents) {
        int sequence = "SIGN_AFTER".equals(stage) ? 1 : "BASE_DONE".equals(stage) ? 2 : 3;
        stages.insert(new SalePaymentStageDO()
                .setCompanyId(contract.getCompanyId()).setCustomerId(contract.getCustomerId())
                .setContractId(contract.getId()).setStage(stage).setAmountCents(amountCents)
                .setLabel(stageLabel(stage)).setSequence(sequence).setPlanVersion(1)
                .setTriggerType("SIGN_AFTER".equals(stage) ? "BEFORE_START"
                        : "BASE_DONE".equals(stage) ? "LEGACY_BASE_DONE" : "LEGACY_TOPPING")
                .setStatus(STATUS_PENDING).setDeleted(false));
    }

    private static long beforeStart(java.util.List<PaymentPlanRules.Stage> plan) {
        return plan.stream().filter(s -> "BEFORE_START".equals(s.trigger()) || "ON_SIGN".equals(s.trigger()))
                .mapToLong(PaymentPlanRules.Stage::amountCents).sum();
    }

    private void insertPlanStage(SaleContractDO contract, PaymentPlanRules.Stage stage, int version) {
        long id = IdWorker.getId();
        stages.insert(new SalePaymentStageDO().setId(id).setCompanyId(contract.getCompanyId())
                .setCustomerId(contract.getCustomerId()).setContractId(contract.getId()).setStage("STAGE_" + id)
                .setLabel(stage.label()).setSequence(stage.sequence()).setAmountCents(stage.amountCents())
                .setRatioBps(stage.ratioBps()).setDueDate(stage.dueDate()).setTriggerType(stage.trigger())
                .setTriggerNodeIndex(stage.nodeIndex()).setAfterDelivery("ON_FINISH".equals(stage.trigger()))
                .setPlanVersion(version).setStatus(STATUS_PENDING).setDeleted(false));
    }

    /** Keeps the stable stage ID/key. Receipts, declarations and refunds never move to a new node. */
    @Transactional(rollbackFor = Exception.class)
    public SaleContractDO replacePaymentPlan(PostSnapshot post, long contractId, PaymentPlanCommand cmd, String key) {
        keyGuard(post, key);
        requirePlanContract(post,contractId);
        SaleContractDO contract = lockContract(post, contractId);
        var input = baseInput("contract-payment-plan", contract.getCustomerId(), cmd, contractId, cmd.revision());
        if (commands.replayed(post, key, input).isPresent()) return contracts.selectById(contractId);
        if (cmd.revision() == null || !cmd.revision().equals(contract.getRevision())) {
            throw exception(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT);
        }
        if (STATUS_VOIDED.equals(contract.getStatus())) throw PaymentPlanRules.invalid("已作废合同不能变更付款计划");
        if (STATUS_SIGNED.equals(contract.getStatus()) && !java.util.Set.of("boss", "finance", "finance-lead").contains(post.roleCode())) {
            throw PaymentPlanRules.invalid("已签约付款计划由公司负责人或财务办理变更");
        }
        if (cmd.reason() == null || cmd.reason().isBlank() || cmd.reason().length() > 500) {
            throw PaymentPlanRules.invalid("请填写付款计划调整原因（不超过 500 字）");
        }
        var plan = PaymentPlanRules.normalize(cmd.totalCents(), cmd.stages());
        var old = stages.selectByContract(contractId).stream().filter(s -> !Boolean.TRUE.equals(s.getDeleted()))
                .collect(java.util.stream.Collectors.toMap(SalePaymentStageDO::getId, java.util.function.Function.identity()));
        var retained = new java.util.HashSet<Long>();
        for (var row : plan) {
            if (row.stageId() != null) {
                var previous = old.get(row.stageId());
                if (previous == null || previous.getCompanyId() != post.orgId()) throw exception(REFERENCE_INVALID);
                if (row.trigger().startsWith("LEGACY_") && !("BASE_DONE".equals(previous.getStage()) && "LEGACY_BASE_DONE".equals(row.trigger()))
                        && !("CAP_DONE".equals(previous.getStage()) && "LEGACY_TOPPING".equals(row.trigger()))) {
                    throw PaymentPlanRules.invalid("历史到期条件只能保留在原合同节点");
                }
                if (row.amountCents() < stageCollectedInTx(row.stageId())) throw PaymentPlanRules.invalid("节点金额不能小于已确认净收款，请先处理退款");
                retained.add(row.stageId());
            }
        }
        for (var removed : old.values()) if (!retained.contains(removed.getId())) {
            long references = jdbcTemplate.queryForObject("""
                SELECT (SELECT count(*) FROM fr_sale_stage_receipt WHERE stage_id=?)
                     + (SELECT count(*) FROM fr_sale_collection_declaration WHERE stage_id=?)
                """, Long.class, removed.getId(), removed.getId());
            if (references > 0) throw PaymentPlanRules.invalid("已有收款或到账登记的付款节点不能删除，请保留原节点编号");
        }
        int version = contract.getPaymentPlanVersion() + 1;
        long revision = contract.getRevision() + 1;
        LocalDate effectiveOn = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        for (var previous : old.values()) {
            jdbcTemplate.update("""
                INSERT INTO fr_sale_stage_before_change(company_id,stage_id,contract_revision,effective_on,amount_cents,due_date,deleted)
                VALUES(?,?,?,?,?,?,false)
                """,post.orgId(),previous.getId(),revision,effectiveOn,previous.getAmountCents(),previous.getDueDate());
            if (!retained.contains(previous.getId())) {
                jdbcTemplate.update("UPDATE fr_sale_payment_stage SET deleted=true,plan_version=? WHERE id=? AND company_id=?", version, previous.getId(),post.orgId());
            }
        }
        for (var row : plan) {
            if (row.stageId() == null) {
                insertPlanStage(contract, row, version);
                jdbcTemplate.update("""
                    INSERT INTO fr_sale_stage_before_change(company_id,stage_id,contract_revision,effective_on,amount_cents,deleted)
                    SELECT company_id,id,?,?,0,true FROM fr_sale_payment_stage
                    WHERE contract_id=? AND company_id=? AND plan_version=? AND sequence=? AND NOT deleted
                    """,revision,effectiveOn,contractId,post.orgId(),version,row.sequence());
            } else {
                long net = stageCollectedInTx(row.stageId());
                jdbcTemplate.update("""
                    UPDATE fr_sale_payment_stage SET label=?,sequence=?,amount_cents=?,ratio_bps=?,due_date=?,
                      trigger_type=?,trigger_node_index=?,plan_version=?,status=?,after_delivery=?
                    WHERE id=? AND company_id=? AND contract_id=? AND NOT deleted
                    """,row.label(),row.sequence(),row.amountCents(),row.ratioBps(),row.dueDate(),row.trigger(),row.nodeIndex(),version,
                        net >= row.amountCents() ? "SETTLED" : net > 0 ? "OPEN" : "PENDING","ON_FINISH".equals(row.trigger()),row.stageId(),post.orgId(),contractId);
            }
        }
        contracts.updateById(new SaleContractDO().setId(contractId).setTotalCents(cmd.totalCents())
                .setFirstCents(beforeStart(plan)).setPaymentPlanVersion(version).setPaymentPlanMode("CUSTOM").setRevision(revision));
        snapshotPlan(post, contractId, version, revision, cmd.reason());
        commands.record(post,"fr-sale-contract",contractId,revision,"sale-contract-payment-plan",input,key);
        return contracts.selectById(contractId);
    }

    private void snapshotPlan(PostSnapshot post, long contractId, int version, long revision, String reason) {
        jdbcTemplate.update("""
            INSERT INTO fr_sale_payment_plan_version(contract_id,company_id,version,contract_revision,total_cents,stages,reason,actor_user_id)
            SELECT c.id,c.company_id,?,?,c.total_cents,
                COALESCE((SELECT jsonb_agg(to_jsonb(s) ORDER BY s.sequence,s.id) FROM fr_sale_payment_stage s
                    WHERE s.contract_id=c.id AND s.company_id=c.company_id AND NOT s.deleted),'[]'::jsonb),?,?
            FROM fr_sale_contract c WHERE c.id=? AND c.company_id=?
            """,version,revision,reason,post.userId(),contractId,post.orgId());
    }

    public java.util.List<Map<String,Object>> paymentPlanHistory(PostSnapshot post, long contractId) {
        requirePlanContract(post,contractId);
        var rows = jdbcTemplate.queryForList("""
            SELECT version,contract_revision,total_cents,stages::text AS stages,reason,actor_user_id,created_at
            FROM fr_sale_payment_plan_version WHERE contract_id=? AND company_id=? ORDER BY version DESC
            """,contractId,post.orgId());
        rows.forEach(row -> row.put("stages",cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(
                String.valueOf(row.get("stages")),java.util.List.class)));
        return rows;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> paymentPlanDetail(PostSnapshot post,long contractId) {
        requirePlanContract(post,contractId);
        var contract=lockContract(post,contractId);
        var result=new LinkedHashMap<String,Object>();
        result.put("contract",contract);
        // Use the same lock as collection commands so terms and confirmed cash are consistent.
        result.put("stages",stageSummary(post,contractId));
        result.put("customerName",customerName(contract));
        result.put("canEdit",STATUS_WAIT_SIGN.equals(contract.getStatus()) || STATUS_SIGNED.equals(contract.getStatus())
                && java.util.Set.of("boss","finance","finance-lead").contains(post.roleCode()));
        return result;
    }

    private SaleContractDO requirePlanContract(PostSnapshot post,long contractId) {
        if (!"store".equals(post.orgType()) || !java.util.Set.of("boss","sales","sales-lead","finance","finance-lead").contains(post.roleCode())) {
            throw exception(REFERENCE_INVALID);
        }
        var contract=contracts.selectById(contractId);
        if(contract==null || contract.getCompanyId()!=post.orgId()) throw exception(REFERENCE_INVALID);
        // Finance has a narrow contract read; it does not gain general CRM customer permissions.
        if(!java.util.Set.of("finance","finance-lead").contains(post.roleCode())) access.requireOrgCustomer(post,contract.getCustomerId());
        return contract;
    }

    /** 合同列表（组织内协作：经 SalesAccess 校验客户组织一致） */
    public java.util.List<SaleContractDO> listByCustomer(PostSnapshot post, long customerId) {
        access.requireOrgCustomer(post, customerId);
        return contracts.selectByCustomer(customerId);
    }

    // ========== 签订 / 作废 ==========

    @Transactional(rollbackFor = Exception.class)
    public SaleContractDO sign(PostSnapshot post, long contractId, SignCommand cmd, Long revision, String idempotencyKey) {
        SaleContractDO contract = lockContract(post, contractId);
        // 指纹含 contractId+revision（S1-23：作废重签场景下同客户异合同的 sign/void 命令不得共享指纹）
        var input = baseInput("contract-sign", contract.getCustomerId(), cmd, contractId, revision);
        var replayed = commands.replayed(post, idempotencyKey, input);
        if (replayed.isPresent()) {
            return contracts.selectById(replayed.get().getAggregateId());
        }
        if (!STATUS_WAIT_SIGN.equals(contract.getStatus())) {
            // 已签/作废：幂等返回当前状态（异状态非错误）
            commands.record(post, "fr-sale-contract", contract.getId(), contract.getRevision(),
                    "sale-contract-sign", input, idempotencyKey);
            return contract;
        }
        if (revision == null || !java.util.Objects.equals(revision, contract.getRevision())) { // Long 引用比较越界即恒真（评审基线强制清单 #12）
            throw exception(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT);
        }
        if (cmd.signedDate() != null) contract.setSignedDate(cmd.signedDate());
        if (cmd.assetId() != null) {
            validateAsset(post, cmd.assetId());
            contract.setAssetId(cmd.assetId());
        }
        contract.setStatus(STATUS_SIGNED);
        RevisionsUpdate(contract);
        commands.record(post, "fr-sale-contract", contract.getId(), contract.getRevision(),
                "sale-contract-sign", input, idempotencyKey);
        return contract;
    }

    @Transactional(rollbackFor = Exception.class)
    public SaleContractDO void_(PostSnapshot post, long contractId, String reason, Long revision, String idempotencyKey) {
        SaleContractDO contract = lockContract(post, contractId);
        var input = baseInput("contract-void", contract.getCustomerId(),
                Map.of("reason", reason == null ? "" : reason), contractId, revision);
        var replayed = commands.replayed(post, idempotencyKey, input);
        if (replayed.isPresent()) {
            return contracts.selectById(replayed.get().getAggregateId());
        }
        if (STATUS_VOIDED.equals(contract.getStatus())) {
            commands.record(post, "fr-sale-contract", contract.getId(), contract.getRevision(),
                    "sale-contract-void", input, idempotencyKey);
            return contract;
        }
        if (revision == null || !java.util.Objects.equals(revision, contract.getRevision())) { // Long 引用比较越界即恒真（评审基线强制清单 #12）
            throw exception(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT);
        }
        contract.setStatus(STATUS_VOIDED);
        RevisionsUpdate(contract);
        // PENDING stage 软删；PAID 不动（资金事实不回滚，退款走 B1 退款链）
        stages.selectByContract(contractId).stream()
                .filter(s -> STATUS_PENDING.equals(s.getStatus()))
                .forEach(s -> stages.updateById(new SalePaymentStageDO().setId(s.getId()).setDeleted(true)));
        commands.record(post, "fr-sale-contract", contract.getId(), contract.getRevision(),
                "sale-contract-void", input, idempotencyKey);
        return contract;
    }

    // ========== 回款办理（collect，写通 B1） ==========

    /**
     * 锁序：客户端键 advisory lock（token=sha256(键) 完整摘要，锁/存储同源）→ 锁内 replay →
     * 合同锁 → stage 锁 → PAID 重放优先（pay_fingerprint 比对，不要求 SIGNED）→
     * PENDING 首次核销：SIGNED 校验 → finance 登记（REQUIRED 同事务，来源重放优先）→ 核销 CAS → record。
     */
    /**
     * M06-T04 分笔核销：amountCents 为本笔金额（≤期剩余可收额——正常归单限额），资金唯一事实=
     * fr_fin_receipt（经 BusinessReceiptPort），期↔笔关联落 fr_sale_stage_receipt。
     * 幂等骨架与两期版一致（advisory 锁→token replay→合同锁→期锁）；PAID 指纹重放段移除——
     * 存量整期核销经迁移落为 SETTLED+首笔关联行，由「收满拒绝」兜底。
     */
    public Map<String, Object> collect(PostSnapshot post, long stageId, CollectCommand cmd, String idempotencyKey) {
        if(!java.util.Set.of("finance","finance-lead").contains(post.roleCode())) return declareCollection(post,stageId,cmd,idempotencyKey);
        String clientKey = FranchiseCommandKeys.normalize(idempotencyKey);
        String token = "sale-collect:" + cn.hutool.crypto.digest.DigestUtil.sha256Hex(clientKey);
        var scope = new FranchiseCommandKeys.PostKeyScope("franchise", post.userId());
        // R1 P1：replay 与 record 必须共用同一份规范化输入（含金额），否则同键同内容重试摘要不匹配被误判键复用；
        // R2：LinkedHashMap 固定键序——Map.of 无序，跨 JVM 序列化字段顺序漂移同样会破坏摘要
        var canonical = new java.util.LinkedHashMap<String, Object>();
        canonical.put("stageId", stageId);
        canonical.put("amountCents", cmd.amountCents());
        canonical.put("method", nullSafe(cmd.method()));
        canonical.put("txn", nullSafe(cmd.transactionNo()));
        canonical.put("date", cmd.receivedDate() == null ? "" : cmd.receivedDate().toString());
        if(cmd.voucherFileId()!=null)canonical.put("voucherFileId",cmd.voucherFileId());if(cmd.accountName()!=null)canonical.put("accountName",cmd.accountName());
        long fingerprint = canonical.hashCode();
        return txTemplate.execute(status -> {
            jdbcTemplate.queryForMap("SELECT pg_advisory_xact_lock(?)",
                    FranchiseCommandKeys.lockKey(new FranchiseCommandKeys.PostKeyScope("franchise", post.userId()), token));
            // ①客户端键 replay（token 作键；请求内容作指纹——跨合同共键异内容在此拒绝）
            var existing = commands.replayed(post, token, canonical);
            if (existing.isPresent()) {
                CommandEventDO event = existing.get();
                return Map.of("receiptId", event.getAggregateId(), "replayed", true);
            }
            // ②合同锁 → ③期锁（锁序对齐 void_ 的「合同锁→行更新」，防死锁窗口）
            SalePaymentStageDO stagePeek = stages.selectById(stageId);
            if (stagePeek == null || Boolean.TRUE.equals(stagePeek.getDeleted())
                    || stagePeek.getCompanyId() != post.orgId()) {
                throw exception(STAGE_ALREADY_PAID); // 引用不可见统一句柄——AC13 断言跨组织在 access 层先行拒绝
            }
            SaleContractDO contract = contracts.lockById(stagePeek.getContractId());
            SalePaymentStageDO stage = stages.lockById(stageId);
            if (stage == null || Boolean.TRUE.equals(stage.getDeleted())
                    || stage.getCompanyId() != post.orgId()
                    // M06-T04/R1：Long 与 Long 比较须拆值（雪花 id 超出缓存区间，引用比较恒不等——
                    // 既有 bug，两期版测试未覆盖 collect 故未暴露）
                    || stage.getContractId().longValue() != contract.getId().longValue()) {
                throw exception(STAGE_ALREADY_PAID); // 锁间隙期归属变化——保守拒绝
            }
            // ④收满拒绝（M09-T03/R1 净口径）：退款后净额 < 期额 → 期次回到可收（SETTLED 状态恢复补收——
            // 「资金门槛按已核实净实收」；补收后仍 SETTLED）
            long collected = stageCollectedInTx(stageId);
            if (collected >= stage.getAmountCents()) {
                throw exception(STAGE_ALREADY_PAID);
            }
            // ⑤分笔金额校验：>0 且不超期剩余（正常归单限额；退多收不伪造新欠款由退款契约保证）
            if (cmd.amountCents() <= 0) {
                throw exception(STAGE_AMOUNT_INVALID);
            }
            if (collected + cmd.amountCents() > stage.getAmountCents()) {
                throw exception(STAGE_AMOUNT_EXCEED);
            }
            // ⑥合同必须 SIGNED 且未 VOIDED
            requireSIGNED(contract);
            // ⑦finance 登记（receipt 级 sourceRef；同号防重由 fr_fin_txn_no 保证）
            // R1 P1：与 M08-T05/R04 BusinessDateGuard 同契约——正常归单日期必填拒空、拒未来
            //（未来收付由计划表达；真实日期未知走 M08-T08 异常登记），「今天」按 Asia/Shanghai 判定
            if (cmd.receivedDate() == null) {
                throw exception(STAGE_BUSINESS_DATE_REQUIRED);
            }
            if (cmd.receivedDate().isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")))) {
                throw exception(STAGE_BUSINESS_DATE_FUTURE);
            }
            LocalDate effectiveDate = cmd.receivedDate();
            var cmd2 = new BusinessReceiptPort.BusinessReceipt(
                    contract.getCustomerId(),
                    "sale:" + contract.getId() + ":" + stage.getStage() + ":" + IdWorker.getId(),
                    customerName(contract) + "·" + displayLabel(stage) + "回款",
                    cmd.amountCents(), cmd.method(), cmd.transactionNo(), effectiveDate,cmd.voucherFileId(),cmd.accountName());
            var saved = businessReceipts.registerBusinessReceipt(post, cmd2);
            // ⑧分笔关联 + 期状态推进（R2 P1：SETTLED 补收保持 SETTLED——存量行 paid_* 非空，
            // 转 OPEN 违反 CHECK；仅 PENDING/OPEN 流转状态）
            stageReceipts.insert(new SaleStageReceiptDO()
                    .setCompanyId(stage.getCompanyId()).setContractId(contract.getId())
                    .setStageId(stageId).setReceiptId(saved.receiptId())
                    .setAmountCents(cmd.amountCents()));
            long after = collected + cmd.amountCents();
            boolean wasSettled = SalePaymentStageDO.STATUS_SETTLED.equals(stage.getStatus());
            String nextStatus = wasSettled ? SalePaymentStageDO.STATUS_SETTLED
                    : (after >= stage.getAmountCents()
                            ? SalePaymentStageDO.STATUS_SETTLED : SalePaymentStageDO.STATUS_OPEN);
            jdbcTemplate.update(
                    "UPDATE fr_sale_payment_stage SET status=? WHERE id=? AND status IN ('PENDING','OPEN','SETTLED')",
                    nextStatus, stageId);
            // ⑨键绑定+审计（aggregateId=receiptId；stageId 在 payload 留痕）
            commands.record(post, "fr-sale-stage", saved.receiptId(), IdWorker.getId(),
                    "sale-stage-collect", canonical, token);
            log.info("[collect][stage={} contract={} receipt={} amount={} status={}]", stageId,
                    contract.getId(), saved.receiptId(), cmd.amountCents(), nextStatus);
            return Map.of("receiptId", saved.receiptId(), "stageStatus", nextStatus,
                    "collectedCents", after, "replayed", false);
        });
    }

    /**
     * M06-T04 统一视图契约（M06-T02 双端/M10 统计引用冻结）：各期约定额/累计分笔/余额/状态。
     * refundedCents 预留退款净额（关联随 M08 退款契约接入，当前恒 0——退款只改净实收不改期约定额）。
     */
    /**
     * M07-T06/M09-T03：期累计实收净口径（统一账读侧——Adapter 经此汇总）。
     * 净额 = Σ有效分笔 - Σ已核实退款（fr_fin_refund，替代链 closed 行不计——replaced_by_id IS NULL）。
     * 「退回减已付现金、不因退款自动减合同额；资金门槛按已核实净实收」。
     */
    /** 业务/工程提交到账线索；资金事实只在财务确认时产生。 */
    public Map<String,Object> declareCollection(PostSnapshot post,long stageId,CollectCommand cmd,String key) {
        return txTemplate.execute(status -> {
            String token="sale-declare:"+cn.hutool.crypto.digest.DigestUtil.sha256Hex(FranchiseCommandKeys.normalize(key));
            jdbcTemplate.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?,0))) s",Integer.class,post.orgId()+":"+token);
            var input=new java.util.LinkedHashMap<String,Object>();input.put("stageId",stageId);input.put("command",cmd);
            var prior=commands.replayed(post,token,input);
            if(prior.isPresent())return Map.of("receiptId",0,"declarationId",prior.get().getAggregateId(),"stageStatus","PENDING_CONFIRMATION","replayed",true);
            var row=stages.selectById(stageId);
            if(row==null || row.getCompanyId()!=post.orgId() || Boolean.TRUE.equals(row.getDeleted()))throw exception(STAGE_ALREADY_PAID);
            requireSIGNED(contracts.lockById(row.getContractId()));
            if(cmd.amountCents()<=0 || cmd.receivedDate()==null || cmd.receivedDate().isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))))throw exception(STAGE_AMOUNT_INVALID);
            jdbcTemplate.update("UPDATE fr_sale_collection_declaration SET status='SUPERSEDED' WHERE company_id=? AND stage_id=? AND applicant_user_id=? AND status='RETURNED'",post.orgId(),stageId,post.userId());
            long id=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
            jdbcTemplate.update("INSERT INTO fr_sale_collection_declaration(id,company_id,stage_id,amount_cents,method,transaction_no,received_date,applicant_user_id,voucher_file_id,account_name) VALUES(?,?,?,?,?,?,?,?,?,?)",id,post.orgId(),stageId,cmd.amountCents(),cmd.method(),cmd.transactionNo(),cmd.receivedDate(),post.userId(),cmd.voucherFileId(),cmd.accountName());
            commands.record(post,"sale-collection-declaration",id,1,"sale-collection-declare",input,token);
            return Map.of("receiptId",0,"declarationId",id,"stageStatus","PENDING_CONFIRMATION","replayed",false);
        });
    }
    public java.util.List<Map<String,Object>> financialStages(PostSnapshot post) {
        return jdbcTemplate.queryForList("""
            SELECT s.id::text,s.contract_id::text,c.customer_id::text,customer.name AS customer_name,
              s.stage,s.label,s.sequence,s.trigger_type,s.trigger_node_index,s.plan_version,s.amount_cents,s.due_date,s.after_delivery,
              COALESCE((SELECT sum(r.amount_cents-COALESCE((SELECT sum(f.amount_cents) FROM fr_fin_refund f WHERE f.receipt_id=r.id AND f.status='已核实'),0)) FROM fr_sale_stage_receipt x JOIN fr_fin_receipt r ON r.id=x.receipt_id AND r.company_id=x.company_id WHERE x.stage_id=s.id AND x.company_id=s.company_id AND r.status='已登记'),0) AS collected_cents
            FROM fr_sale_payment_stage s JOIN fr_sale_contract c ON c.id=s.contract_id AND c.company_id=s.company_id LEFT JOIN fr_customer customer ON customer.id=c.customer_id AND customer.company_id=c.company_id
            WHERE s.company_id=? AND c.status='SIGNED' AND NOT c.deleted AND NOT s.deleted ORDER BY c.id,s.sequence,s.id
            """,post.orgId());
    }
    public record ContractChange(long signAfterCents,long baseDoneCents,long capDoneCents,long tailCents,LocalDate tailDueDate,String reason){}
    public void changeAmounts(PostSnapshot post,long contractId,ContractChange cmd,String key){
        txTemplate.executeWithoutResult(status->{
            keyGuard(post,key);
            var input=Map.of("contractId",contractId,"change",cmd);
            if(commands.replayed(post,key,input).isPresent())return;
            var c=contracts.lockById(contractId);if(c==null || c.getCompanyId()!=post.orgId())throw exception(REFERENCE_INVALID);requireSIGNED(c);
            if (!"store".equals(post.orgType()) || !java.util.Set.of("boss","finance","finance-lead").contains(post.roleCode())) {
                throw PaymentPlanRules.invalid("已签约付款计划由公司负责人或财务办理变更");
            }
            if ("CUSTOM".equals(c.getPaymentPlanMode())) {
                throw PaymentPlanRules.invalid("请通过自定义付款计划办理变更，保留原节点编号");
            }
            if(cmd.reason()==null || cmd.reason().isBlank() || cmd.reason().length()>500 || cmd.signAfterCents()<0 || cmd.baseDoneCents()<0 || cmd.capDoneCents()<0 || cmd.tailCents()<0)throw exception(STAGE_AMOUNT_INVALID);
            long total=Math.addExact(Math.addExact(cmd.signAfterCents(),cmd.baseDoneCents()),Math.addExact(cmd.capDoneCents(),cmd.tailCents()));if(total<=0 || total>SaleQuoteMath.MAX_AMOUNT)throw exception(STAGE_AMOUNT_INVALID);
            String[] names = {"SIGN_AFTER", "BASE_DONE", "CAP_DONE", "tail"};
            long[] values = {cmd.signAfterCents(), cmd.baseDoneCents(), cmd.capDoneCents(), cmd.tailCents()};
            LocalDate effectiveOn = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
            long contractRevision = c.getRevision() + 1;
            int planVersion = c.getPaymentPlanVersion() + 1;
            var previousStages=stages.selectByContract(contractId).stream().collect(java.util.stream.Collectors.toMap(
                    SalePaymentStageDO::getStage,java.util.function.Function.identity()));
            for (int i = 0; i < names.length; i++) {
                LocalDate dueDate = i == 3 ? cmd.tailDueDate() : null;
                String trigger=i==0?"BEFORE_START":i==1?"LEGACY_BASE_DONE":i==2?"LEGACY_TOPPING":dueDate==null?"ON_FINISH":"DATE";
                var previous=previousStages.get(names[i]);
                long collected=previous==null?0:stageCollectedInTx(previous.getId());
                if (values[i]<collected) throw PaymentPlanRules.invalid("节点金额不能小于已确认净收款，请先处理退款");
                String nextStatus=collected>=values[i]?"SETTLED":collected>0?"OPEN":"PENDING";
                // 保存本次生效前的期次。合同锁与收款、变更使用同一顺序。
                jdbcTemplate.update("""
                    INSERT INTO fr_sale_stage_before_change
                        (company_id,stage_id,contract_revision,effective_on,amount_cents,due_date,deleted)
                    SELECT company_id,id,?,?,amount_cents,due_date,deleted
                    FROM fr_sale_payment_stage WHERE contract_id=? AND company_id=? AND stage=?
                    """,contractRevision,effectiveOn,contractId,post.orgId(),names[i]);
                int updated = jdbcTemplate.update("""
                    UPDATE fr_sale_payment_stage SET amount_cents=?,due_date=?,after_delivery=?,deleted=false,
                        status=?,trigger_type=?,trigger_node_index=NULL,sequence=?
                    WHERE contract_id=? AND company_id=? AND stage=?
                    """,values[i],dueDate,i==3,nextStatus,trigger,i+1,contractId,post.orgId(),names[i]);
                if (updated == 0 && values[i] > 0) {
                    long stageId = IdWorker.getId();
                    stages.insert(new SalePaymentStageDO().setId(stageId).setCompanyId(post.orgId())
                            .setCustomerId(c.getCustomerId()).setContractId(contractId).setStage(names[i])
                            .setAmountCents(values[i]).setLabel(stageLabel(names[i])).setSequence(i+1).setPlanVersion(planVersion)
                            .setTriggerType(trigger)
                            .setStatus("PENDING").setDeleted(false));
                    if (i == 3) jdbcTemplate.update("UPDATE fr_sale_payment_stage SET after_delivery=true,due_date=? WHERE id=?", dueDate, stageId);
                    jdbcTemplate.update("""
                        INSERT INTO fr_sale_stage_before_change
                            (company_id,stage_id,contract_revision,effective_on,amount_cents,deleted)
                        VALUES (?,?,?,?,0,true)
                        """,post.orgId(),stageId,contractRevision,effectiveOn);
                }
            }
            jdbcTemplate.update("UPDATE fr_sale_payment_stage SET plan_version=? WHERE contract_id=? AND company_id=? AND NOT deleted",planVersion,contractId,post.orgId());
            contracts.updateById(new SaleContractDO().setId(contractId).setTotalCents(total).setFirstCents(cmd.signAfterCents()).setRevision(c.getRevision()+1).setPaymentPlanVersion(planVersion));
            snapshotPlan(post,contractId,planVersion,contractRevision,cmd.reason());
            long revision=jdbcTemplate.queryForObject("SELECT COALESCE(max(revision),0)+1 FROM pf_command_event WHERE domain='franchise' AND aggregate_type='sale-contract' AND aggregate_id=?",Long.class,contractId);
            commands.record(post,"sale-contract",contractId,revision,"sale-contract-change",input,key);
        });
    }
    private void keyGuard(PostSnapshot post,String key) {
        String normalized=commands.requireIdempotencyKey(key);
        long lock=FranchiseCommandKeys.lockKey(new FranchiseCommandKeys.PostKeyScope("franchise",post.userId()),normalized);
        jdbcTemplate.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(?)) s",Integer.class,lock);
    }
    /** 前期已到账定金认领到一期；只新增关联，不再创建实收。 */
    public void allocateReceipt(PostSnapshot post,long stageId,long receiptId,String key){
        txTemplate.executeWithoutResult(status->{
            keyGuard(post,key);var input=Map.of("stageId",stageId,"receiptId",receiptId);
            if(commands.replayed(post,key,input).isPresent())return;
            var peek=stages.selectById(stageId);if(peek==null||peek.getCompanyId()!=post.orgId())throw exception(REFERENCE_INVALID);
            var c=contracts.lockById(peek.getContractId());requireSIGNED(c);var stage=stages.lockById(stageId);
            var rows=jdbcTemplate.queryForList("SELECT * FROM fr_fin_receipt WHERE id=? AND company_id=? FOR UPDATE",receiptId,post.orgId());
            if(rows.isEmpty())throw exception(REFERENCE_INVALID);var r=rows.get(0);
            if(!"manual".equals(r.get("source_type"))||!"已登记".equals(r.get("status"))||r.get("source_ref")!=null)throw exception(REFERENCE_INVALID);
            if(r.get("customer_id")!=null && ((Number)r.get("customer_id")).longValue()!=c.getCustomerId())throw exception(REFERENCE_INVALID);
            long gross=((Number)r.get("amount_cents")).longValue();
            long refunded=jdbcTemplate.queryForObject("SELECT COALESCE(sum(amount_cents),0) FROM fr_fin_refund WHERE receipt_id=? AND status='已核实'",Long.class,receiptId);
            if(gross-refunded<=0 || gross-refunded>stage.getAmountCents()-stageCollectedInTx(stageId))throw exception(STAGE_AMOUNT_EXCEED);
            if(jdbcTemplate.queryForObject("SELECT count(*) FROM fr_sale_stage_receipt WHERE receipt_id=?",Long.class,receiptId)>0)throw exception(STAGE_ALREADY_PAID);
            stageReceipts.insert(new SaleStageReceiptDO().setCompanyId(post.orgId()).setContractId(c.getId()).setStageId(stageId).setReceiptId(receiptId).setAmountCents(gross));
            jdbcTemplate.update("UPDATE fr_fin_receipt SET customer_id=?,source_type='business',source_ref=? WHERE id=? AND company_id=?",c.getCustomerId(),"sale-allocation:"+c.getId()+":"+stageId+":"+receiptId,receiptId,post.orgId());
            long net=stageCollectedInTx(stageId);
            jdbcTemplate.update("UPDATE fr_sale_payment_stage SET status=? WHERE id=?", "SETTLED".equals(stage.getStatus())||net>=stage.getAmountCents()?"SETTLED":"OPEN",stageId);
            commands.record(post,"sale-receipt-allocation",receiptId,1,"sale-receipt-allocation",input,key);
        });
    }

    public java.util.List<Map<String,Object>> contractCollectionDeclarations(PostSnapshot post, long contractId) {
        return jdbcTemplate.queryForList("""
            SELECT d.id::text,d.stage_id::text,d.amount_cents,d.received_date,d.transaction_no,d.method,d.account_name,d.status,d.return_reason
            FROM fr_sale_collection_declaration d
            JOIN fr_sale_payment_stage s ON s.id=d.stage_id AND s.company_id=d.company_id
            WHERE d.company_id=? AND s.contract_id=? AND d.applicant_user_id=?
              AND d.status IN ('PENDING','RETURNED') ORDER BY d.id DESC
            """,post.orgId(),contractId,post.userId());
    }
    public java.util.List<Map<String,Object>> collectionDeclarations(PostSnapshot post) {
        return jdbcTemplate.queryForList("""
            SELECT d.id::text,d.stage_id::text,d.amount_cents,d.received_date,d.transaction_no,d.status,d.return_reason,
              d.account_name,d.voucher_file_id::text,s.contract_id::text,s.stage,c.customer_id::text,customer.name AS customer_name
            FROM fr_sale_collection_declaration d
            JOIN fr_sale_payment_stage s ON s.id=d.stage_id AND s.company_id=d.company_id
            JOIN fr_sale_contract c ON c.id=s.contract_id AND c.company_id=s.company_id
            LEFT JOIN fr_customer customer ON customer.id=c.customer_id AND customer.company_id=c.company_id
            WHERE d.company_id=? AND d.status='PENDING' ORDER BY d.received_date,d.id
            """,post.orgId());
    }
    public void returnDeclaration(PostSnapshot post,long id,String reason,String key){
        if(!java.util.Set.of("finance","finance-lead").contains(post.roleCode()) || reason==null||reason.isBlank()||reason.length()>500)throw exception(REFERENCE_INVALID);
        txTemplate.executeWithoutResult(status->{keyGuard(post,key);var input=Map.of("id",id,"reason",reason);if(commands.replayed(post,key,input).isPresent())return;
            int updated=jdbcTemplate.update("UPDATE fr_sale_collection_declaration SET status='RETURNED',return_reason=? WHERE id=? AND company_id=? AND status='PENDING'",reason,id,post.orgId());
            if(updated!=1)throw exception(REFERENCE_INVALID);
            commands.record(post,"sale-collection-declaration",id,2,"sale-collection-return",input,key);
        });
    }
    public Map<String,Object> confirmDeclaration(PostSnapshot post,long id,String key) {
        if(!java.util.Set.of("finance","finance-lead").contains(post.roleCode()))throw exception(REFERENCE_INVALID);
        return txTemplate.execute(status -> {
            var rows=jdbcTemplate.queryForList("SELECT * FROM fr_sale_collection_declaration WHERE id=? AND company_id=? FOR UPDATE",id,post.orgId());
            if(rows.isEmpty())throw exception(REFERENCE_INVALID);var d=rows.get(0);
            if("CONFIRMED".equals(d.get("status")))return Map.of("receiptId",d.get("receipt_id"),"replayed",true);
            if(!"PENDING".equals(d.get("status")))throw exception(REFERENCE_INVALID);
            var result=collect(post,((Number)d.get("stage_id")).longValue(),new CollectCommand(((Number)d.get("amount_cents")).longValue(),(String)d.get("method"),(String)d.get("transaction_no"),((java.sql.Date)d.get("received_date")).toLocalDate(),(Long)d.get("voucher_file_id"),(String)d.get("account_name")),key);
            jdbcTemplate.update("UPDATE fr_sale_collection_declaration SET status='CONFIRMED',receipt_id=? WHERE id=?",result.get("receiptId"),id);return result;
        });
    }

    public long stageCollected(PostSnapshot post, long stageId) {
        var stage = stages.selectById(stageId);
        if (stage == null || stage.getCompanyId() != post.orgId()) throw exception(REFERENCE_INVALID);
        return stageCollectedInTx(stageId);
    }

    private long stageCollectedInTx(long stageId) {
        return jdbcTemplate.queryForObject("SELECT COALESCE((SELECT collected FROM ("
                + cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.STAGE_NET
                + ") amounts WHERE stage_id=?),0)",Long.class,stageId);
    }

    @Transactional(rollbackFor = Exception.class)
    public java.util.List<Map<String, Object>> stageSummary(PostSnapshot post, long contractId) {
        // R1 P2：合同行锁（FOR UPDATE）持有至事务结束——读取期次与分笔之间到达的 collect 会等待本锁，
        // 消除「旧状态配新累计」的撕裂读
        lockContract(post, contractId);
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (SalePaymentStageDO stage : stages.selectByContract(contractId)) {
            if (Boolean.TRUE.equals(stage.getDeleted())) continue;
            // R2 P2-4：四列对账——gross 毛额（Σ全部分笔）/ refunded 退款（已核实）/ net 净额 / 剩余按净额
            Long grossQ = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(r.amount_cents), 0) FROM fr_sale_stage_receipt r WHERE r.stage_id = ?",
                    Long.class, stage.getId());
            Long refundedQ = jdbcTemplate.queryForObject(
                    "SELECT COALESCE((SELECT SUM(g.amount_cents) FROM fr_fin_refund g "
                            + "JOIN fr_sale_stage_receipt r2 ON r2.receipt_id = g.receipt_id "
                            + "WHERE r2.stage_id = ? AND g.status = '已核实' AND g.replaced_by_id IS NULL), 0)",
                    Long.class, stage.getId());
            long gross = grossQ == null ? 0 : grossQ;
            long refunded = refundedQ == null ? 0 : refundedQ;
            long collected = stageCollected(post, stage.getId()); // 净口径（gross-refunded）
            Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
            row.put("stageId", stage.getId());
            row.put("dueDate",stage.getDueDate());
            row.put("stage", stage.getStage());
            row.put("label", displayLabel(stage));
            row.put("sequence", stage.getSequence());
            row.put("ratioBps", stage.getRatioBps());
            row.put("trigger", stage.getTriggerType());
            row.put("nodeIndex", stage.getTriggerNodeIndex());
            row.put("planVersion", stage.getPlanVersion());
            row.put("amountCents", stage.getAmountCents());
            row.put("collectedCents", collected);
            row.put("grossCents", gross);
            row.put("refundedCents", refunded); // M09-T03：真实退款（已核实）——毛额/退款/净额可对账
            row.put("balanceCents", Math.max(0, stage.getAmountCents() - collected));
            row.put("status", stage.getStatus());
            rows.add(row);
        }
        return rows;
    }

    /** 三期显示文案（存量两期值保留兼容文案） */
    private static String stageLabel(String stage) {
        return switch (stage) {
            case SalePaymentStageDO.STAGE_SIGN_AFTER -> "签约后";
            case SalePaymentStageDO.STAGE_BASE_DONE -> "基础完成后";
            case SalePaymentStageDO.STAGE_CAP_DONE -> "封顶后";
            case SalePaymentStageDO.STAGE_FIRST -> "首款";
            case SalePaymentStageDO.STAGE_TAIL -> "尾款";
            default -> stage;
        };
    }

    static String displayLabel(SalePaymentStageDO stage) {
        return stage.getLabel() == null || stage.getLabel().isBlank() ? stageLabel(stage.getStage()) : stage.getLabel();
    }

    private long fingerprintOf(CollectCommand cmd) {
        String canonical = nullSafe(cmd.method()) + "|" + nullSafe(cmd.transactionNo()) + "|"
                + (cmd.receivedDate() == null ? "" : cmd.receivedDate().toString());
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(canonical).hashCode();
    }

    private String customerName(SaleContractDO contract) {
        var rows = jdbcTemplate.queryForList(
                "SELECT name FROM fr_customer WHERE id=?", contract.getCustomerId());
        return rows.isEmpty() ? ("客户#" + contract.getCustomerId()) : String.valueOf(rows.get(0).get("name"));
    }

    private void validateAsset(PostSnapshot post, Long assetId) {
        if (assetId == null) {
            return;
        }
        try {
            long serviceAccount = serviceAccounts.requireServiceAccount(post.orgId());
            var actor = new AssetAccessPort.Actor(post.orgId(), serviceAccount, post.userId(), post.postId());
            if (!assets.validateReference(actor, assetId, java.util.Set.of("CONTRACT_ATTACHMENT"))) {
                throw exception(cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.REFERENCE_INVALID);
            }
        } catch (ServiceAccountInactiveException e) {
            throw exception(STAGE_ALREADY_PAID); // 不可达路径：服务账号停用在建合同时无意义——防御
        }
    }

    private SaleContractDO lockContract(PostSnapshot post, long contractId) {
        SaleContractDO contract = contracts.lockById(contractId);
        if (contract == null || contract.getCompanyId() != post.orgId()) {
            throw exception(cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.REFERENCE_INVALID);
        }
        return contract;
    }

    private void RevisionsUpdate(SaleContractDO contract) {
        // 乐观推进：锁内 revision 校验已在 sign/void 完成，这里仅落库
        contracts.updateById(new SaleContractDO().setId(contract.getId())
                .setStatus(contract.getStatus()).setSignedDate(contract.getSignedDate())
                .setAssetId(contract.getAssetId())
                .setRevision(contract.getRevision() + 1)
                .setUpdateTime(java.time.OffsetDateTime.now()));
        contract.setRevision(contract.getRevision() + 1);
    }

    private LinkedHashMap<String, Object> baseInput(String operation, long customerId, Object cmd) {
        var input = new LinkedHashMap<String, Object>();
        input.put("operation", operation);
        input.put("customerId", customerId);
        input.put("cmd", cmd);
        return input;
    }

    /** sign/void 专用：指纹含 contractId 与 revision（S1-23——同客户异合同/同合同异版本的命令不得共享指纹） */
    private LinkedHashMap<String, Object> baseInput(String operation, long customerId, Object cmd,
                                                    long contractId, Long revision) {
        var input = baseInput(operation, customerId, cmd);
        input.put("contractId", contractId);
        input.put("revision", revision);
        return input;
    }

    private static String nullSafe(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
