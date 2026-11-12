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
                                  LocalDate signedDate, Long assetId, Long requirementId) {
    }

    public record SignCommand(LocalDate signedDate, Long assetId) {
    }

    /**
     * M06-T04 分笔核销命令：amountCents 为本笔金额（可小于期约定额；Σ分笔 ≤ 期额）。
     */
    public record CollectCommand(long amountCents, String method, String transactionNo, LocalDate receivedDate) {
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
        // M06-T04：三期金额校验——Σ三期=合同总额；签订后必须>0，其余期≥0（0=未约定，不发明固定比例）
        if (cmd.totalCents() <= 0 || cmd.signAfterCents() <= 0
                || cmd.baseDoneCents() < 0 || cmd.capDoneCents() < 0
                || cmd.signAfterCents() + cmd.baseDoneCents() + cmd.capDoneCents() != cmd.totalCents()
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
                .setTotalCents(cmd.totalCents()).setFirstCents(cmd.signAfterCents()) // 存量兼容列=签订后期
                .setSignedDate(cmd.signedDate()).setAssetId(cmd.assetId())
                .setStatus(STATUS_WAIT_SIGN).setRequirementId(cmd.requirementId())
                .setAuthorUserId(post.userId());
        contracts.insert(contract);
        createStages(post, contract, cmd);
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
                    customerName(contract) + "·" + stageLabel(stage.getStage()) + "回款",
                    cmd.amountCents(), cmd.method(), cmd.transactionNo(), effectiveDate);
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
    public long stageCollected(PostSnapshot post, long stageId) {
        Long net = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(r.amount_cents), 0) - COALESCE((SELECT SUM(g.amount_cents) "
                        + "FROM fr_fin_refund g JOIN fr_sale_stage_receipt r2 ON r2.receipt_id = g.receipt_id "
                        + "WHERE r2.stage_id = ? AND g.status = '已核实' "
                        + "AND g.replaced_by_id IS NULL), 0) "
                        + "FROM fr_sale_stage_receipt r "
                        + "WHERE r.stage_id = ?",
                Long.class, stageId, stageId);
        return net;
    }

    /** collect 事务内净口径（同 stageCollected SQL——Σ分笔-Σ已核实退款） */
    private long stageCollectedInTx(long stageId) {
        Long net = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(r.amount_cents), 0) - COALESCE((SELECT SUM(g.amount_cents) "
                        + "FROM fr_fin_refund g JOIN fr_sale_stage_receipt r2 ON r2.receipt_id = g.receipt_id "
                        + "WHERE r2.stage_id = ? AND g.status = '已核实' "
                        + "AND g.replaced_by_id IS NULL), 0) "
                        + "FROM fr_sale_stage_receipt r "
                        + "WHERE r.stage_id = ?",
                Long.class, stageId, stageId);
        return net;
    }

    @Transactional(rollbackFor = Exception.class)
    public java.util.List<Map<String, Object>> stageSummary(PostSnapshot post, long contractId) {
        // R1 P2：合同行锁（FOR UPDATE）持有至事务结束——读取期次与分笔之间到达的 collect 会等待本锁，
        // 消除「旧状态配新累计」的撕裂读
        lockContract(post, contractId);
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (SalePaymentStageDO stage : stages.selectByContract(contractId)) {
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
            row.put("stage", stage.getStage());
            row.put("label", stageLabel(stage.getStage()));
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
