package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.module.franchise.sales.dal.*;
import cn.iocoder.yudao.module.franchise.api.BusinessReceiptPort;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
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
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.CONTRACT_NOT_SIGNED;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.STAGE_ALREADY_PAID;

/**
 * 合同签约/回款（B5/B6/B7 任务，Codex 八轮评审定稿）：
 * 创建即生成两期计划（零尾款不生成 tail）；collect 统一锁序（客户端键 advisory lock → 合同锁 → stage 锁）
 * + 幂等三层（客户端键 token=完整摘要 / 阶段 pay_fingerprint 持久化指纹 / finance source_ref 唯一索引+完整内容比对）；
 * business 收款经 BusinessReceiptPort 内部专用入口（手工 REST 不放宽）；PAID 重放优先（VOIDED 后同内容重放成功）。
 */
@Slf4j
@Service
public class SaleContractService {

    public record ContractCommand(long customerId, long totalCents, long firstCents, LocalDate signedDate,
                                  Long assetId, Long requirementId) {
    }

    public record SignCommand(LocalDate signedDate, Long assetId) {
    }

    public record CollectCommand(String method, String transactionNo, LocalDate receivedDate) {
    }

    private final SaleContractMapper contracts;
    private final SalePaymentStageMapper stages;
    private final SaleRequirementService requirements;
    private final BusinessReceiptPort businessReceipts;
    private final DesignServicePort designService;
    private final StoreServiceAccountPort serviceAccounts;
    private final FranchiseCommands commands;
    private final SalesAccess access;
    private final TransactionTemplate txTemplate;
    private final JdbcTemplate jdbcTemplate;

    public SaleContractService(SaleContractMapper contracts, SalePaymentStageMapper stages,
                               SaleRequirementService requirements, BusinessReceiptPort businessReceipts,
                               DesignServicePort designService, StoreServiceAccountPort serviceAccounts,
                               FranchiseCommands commands, SalesAccess access,
                               PlatformTransactionManager transactionManager, DataSource dataSource) {
        this.contracts = contracts;
        this.stages = stages;
        this.requirements = requirements;
        this.businessReceipts = businessReceipts;
        this.designService = designService;
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
        if (cmd.totalCents() <= 0 || cmd.firstCents() <= 0 || cmd.firstCents() > cmd.totalCents()
                || cmd.totalCents() > SaleQuoteMath.MAX_AMOUNT) {
            throw exception(CONTRACT_DUPLICATE); // 金额非法复用合同码段——由 AC 断言区分（金额校验独立见下）
        }
        requirements.requireOfCustomer(cmd.customerId(), cmd.requirementId());
        validateAsset(post, cmd.assetId());
        var input = baseInput("contract-create", cmd.customerId(), cmd);
        // 幂等重放先于有效合同检查（S1-21：原顺序「先查 006 再查重放」使响应丢失后的原键重试
        // 恒撞 CONTRACT_DUPLICATE，永远到不了重放分支）
        var replayed = commands.replayed(post, idempotencyKey, input);
        if (replayed.isPresent()) {
            return contracts.selectById(replayed.get().getAggregateId());
        }
        var existing = contracts.selectActiveByCustomer(cmd.customerId());
        if (existing != null) {
            throw exception(CONTRACT_DUPLICATE);
        }
        SaleContractDO contract = new SaleContractDO()
                .setCompanyId(post.orgId()).setCustomerId(cmd.customerId())
                .setTotalCents(cmd.totalCents()).setFirstCents(cmd.firstCents())
                .setSignedDate(cmd.signedDate()).setAssetId(cmd.assetId())
                .setStatus(STATUS_WAIT_SIGN).setRequirementId(cmd.requirementId())
                .setAuthorUserId(post.userId());
        contracts.insert(contract);
        createStages(post, contract);
        commands.record(post, "fr-sale-contract", contract.getId(), 1, "sale-contract-create", input, idempotencyKey);
        return contract;
    }

    private void createStages(PostSnapshot post, SaleContractDO contract) {
        insertStage(contract, "first", contract.getFirstCents());
        long tail = contract.getTotalCents() - contract.getFirstCents();
        if (tail > 0) { // 零尾款免收：不生成 tail stage
            insertStage(contract, "tail", tail);
        }
    }

    private void insertStage(SaleContractDO contract, String stage, long amountCents) {
        stages.insert(new SalePaymentStageDO()
                .setCompanyId(contract.getCompanyId()).setCustomerId(contract.getCustomerId())
                .setContractId(contract.getId()).setStage(stage).setAmountCents(amountCents)
                .setStatus(STATUS_PENDING).setDeleted(false));
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
        if (revision == null || contract.getRevision() != revision) {
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
        if (revision == null || contract.getRevision() != revision) {
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
    public Map<String, Object> collect(PostSnapshot post, long stageId, CollectCommand cmd, String idempotencyKey) {
        String clientKey = FranchiseCommandKeys.normalize(idempotencyKey);
        String token = "sale-collect:" + cn.hutool.crypto.digest.DigestUtil.sha256Hex(clientKey);
        var scope = new FranchiseCommandKeys.PostKeyScope("franchise", post.userId());
        long fingerprint = fingerprintOf(cmd);
        return txTemplate.execute(status -> {
            jdbcTemplate.queryForMap("SELECT pg_advisory_xact_lock(?)",
                    FranchiseCommandKeys.lockKey(new FranchiseCommandKeys.PostKeyScope("franchise", post.userId()), token));
            // ①客户端键 replay（token 作键；请求内容作指纹——跨合同共键异内容在此拒绝）
            var existing = commands.replayed(post, token, Map.of("stageId", stageId, "fp", fingerprint));
            if (existing.isPresent()) {
                CommandEventDO event = existing.get();
                return Map.of("receiptId", event.getAggregateId(), "stageStatus", STATUS_PAID, "replayed", true);
            }
            // ②合同锁 → ③stage 锁
            SalePaymentStageDO stage = stages.lockById(stageId);
            if (stage == null || Boolean.TRUE.equals(stage.getDeleted())
                    || stage.getCompanyId() != post.orgId()) {
                throw exception(STAGE_ALREADY_PAID); // 引用不可见统一句柄——AC13 断言跨组织在 access 层先行拒绝
            }
            SaleContractDO contract = contracts.lockById(stage.getContractId());
            // ④PAID 重放优先：比对持久化指纹（禁止用本次请求重建首次内容）
            if (STATUS_PAID.equals(stage.getStatus())) {
                String requestFp = payFingerprint(stage, cmd);
                if (!requestFp.equals(stage.getPayFingerprint())) {
                    throw exception(STAGE_ALREADY_PAID);
                }
                var saved = businessReceipts.replayBySourceRef(post, sourceRefOf(contract, stage));
                commands.record(post, "fr-sale-stage", saved.receiptId(), IdWorker.getId(),
                        "sale-stage-collect", Map.of("stageId", stageId, "fp", fingerprint), token);
                return Map.of("receiptId", saved.receiptId(), "stageStatus", STATUS_PAID, "replayed", true);
            }
            // ⑤PENDING 首次核销：合同必须 SIGNED 且未 VOIDED
            requireSIGNED(contract);
            // ⑥finance 登记（内部专用入口；同事务 REQUIRED）
            // 缺省日期首次固化当日（V8：finance 侧日期以首次收据为准；指纹中缺省日期不参与）
            LocalDate effectiveDate = cmd.receivedDate() != null ? cmd.receivedDate() : LocalDate.now();
            var cmd2 = new BusinessReceiptPort.BusinessReceipt(
                    contract.getCustomerId(), sourceRefOf(contract, stage),
                    customerName(contract) + "·" + ("first".equals(stage.getStage()) ? "首款" : "尾款") + "回款",
                    stage.getAmountCents(), cmd.method(), cmd.transactionNo(), effectiveDate);
            var saved = businessReceipts.registerBusinessReceipt(post, cmd2);
            // ⑦核销 CAS（DB CHECK 保证 PAID 与 receipt/paid_at/fingerprint 同置）
            int updated = jdbcTemplate.update(
                    "UPDATE fr_sale_payment_stage SET status='PAID', paid_receipt_id=?, paid_at=now(), "
                            + "pay_fingerprint=? WHERE id=? AND status='PENDING'",
                    saved.receiptId(), payFingerprint(stage, cmd), stageId);
            if (updated != 1) {
                throw exception(STAGE_ALREADY_PAID);
            }
            // ⑧键绑定+审计（aggregateId=receiptId——同键重放经事件读回首收据；stageId 在 payload 留痕）
            commands.record(post, "fr-sale-stage", saved.receiptId(), IdWorker.getId(),
                    "sale-stage-collect", Map.of("stageId", stageId, "fp", fingerprint), token);
            log.info("[collect][stage={} contract={} receipt={}]", stageId, contract.getId(), saved.receiptId());
            return Map.of("receiptId", saved.receiptId(), "stageStatus", STATUS_PAID, "replayed", false);
        });
    }

    private String sourceRefOf(SaleContractDO contract, SalePaymentStageDO stage) {
        return "sale:" + contract.getId() + ":" + stage.getStage();
    }

    private String payFingerprint(SalePaymentStageDO stage, CollectCommand cmd) {
        String canonical = stage.getAmountCents() + "|" + nullSafe(cmd.method() == null ? "bank" : cmd.method())
                + "|" + nullSafe(cmd.transactionNo()) + "|" + (cmd.receivedDate() == null ? "" : cmd.receivedDate().toString());
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(canonical);
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
            designService.validateAsset(serviceAccount, assetId);
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
