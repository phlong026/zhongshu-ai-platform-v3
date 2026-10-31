package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.module.franchise.finance.dal.FinBankConfirmDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinBankConfirmMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinBankFlowDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinBankFlowMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptMapper;
import cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.*;

/**
 * 到账核对：模拟流水（dev/e2e 注入，prod 由对账导入替代）→ 财务确认到账 → 待登记应收转为已登记。
 * 唯一入账：bank_flow_id 与 receipt_id 双 UNIQUE；失败尝试独立表留痕（不随确认事务回滚影响流水状态）。
 */
@Service
@Slf4j
public class FinBankService {

    public record ConfirmInput(long flowId, long receiptId) {
        public static ConfirmInput of(long flowId, long receiptId) {
            return new ConfirmInput(flowId, receiptId);
        }
    }


    @Resource
    private FinBankFlowMapper flowMapper;
    @Resource
    private FinBankConfirmMapper confirmMapper;
    @Resource
    private FinAttemptRecorder attemptRecorder;
    @Resource
    private FranchiseCommands commands;
    @Resource
    private FinReceiptMapper receiptMapper;

    /** 流水注入：仅 dev/e2e 环境（控制器上挂 local profile 守卫），prod 由对账导入替代 */
    @Transactional
    public long injectFlow(PostSnapshot post, long companyId, String bankRef, long amountCents, LocalDate bankDate, String source) {  // post 可空（dev 注入无登录态）
        FinBankFlowDO existing = flowMapper.selectOne(
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FinBankFlowDO>()
                        .eq(FinBankFlowDO::getCompanyId, companyId).eq(FinBankFlowDO::getBankRef, bankRef));
        if (existing != null) {
            return existing.getId();
        }
        FinBankFlowDO flow = new FinBankFlowDO().setCompanyId(companyId).setBankRef(bankRef)
                .setAmountCents(amountCents).setBankDate(bankDate).setStatus("待匹配").setSource(source);
        flowMapper.insert(flow);
        return flow.getId();
    }

    /** 确认到账：金额必须一致；receipt 必须待登记且同公司；双 UNIQUE 保证唯一入账 */
    @Transactional
    public long confirm(PostSnapshot post, String idempotencyKey, long flowId, long receiptId) {
        var replayed = commands.replayed(post, idempotencyKey, cn.iocoder.yudao.module.franchise.finance.FinBankService.ConfirmInput.of(flowId, receiptId));
        if (replayed.isPresent()) {
            return replayed.get().getAggregateId();
        }
        FinBankFlowDO flow = flowMapper.selectById(flowId);
        if (flow == null || !"待匹配".equals(flow.getStatus())) {
            throw exception(BANK_FLOW_NOT_FOUND);
        }
        if (flow.getCompanyId() != post.orgId()) {
            throw exception(ORG_MISMATCH);
        }
        FinReceiptDO receipt = receiptMapper.lockById(receiptId);
        if (receipt == null || receipt.getCompanyId() != post.orgId()) {
            throw exception(ORG_MISMATCH);
        }
        if (!FinReceiptService.STATUS_PENDING.equals(receipt.getStatus())) {
            attemptRecorder.record(flowId, receiptId, "应收不处于待登记状态");
            throw exception(BANK_RECEIPT_NOT_PENDING);
        }
        if (flow.getAmountCents().longValue() != receipt.getAmountCents().longValue()) {
            attemptRecorder.record(flowId, receiptId, "流水金额与应收金额不一致");
            throw exception(BANK_CONFIRM_AMOUNT_MISMATCH);
        }
        receiptMapper.updateById(new FinReceiptDO().setId(receiptId).setStatus(FinReceiptService.STATUS_REGISTERED)
                .setRegisteredAt(OffsetDateTime.now()).setReceivedDate(flow.getBankDate())
                .setRegistrarUserId(post.userId()).setTransactionNo(flow.getBankRef()));
        FinBankConfirmDO confirm = new FinBankConfirmDO().setBankFlowId(flowId).setReceiptId(receiptId)
                .setCompanyId(post.orgId()).setAmountCents(flow.getAmountCents())
                .setMatchedByUserId(post.userId()).setMatchedAt(OffsetDateTime.now());
        try {
            confirmMapper.insert(confirm);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发认领同一流水或同一应收：唯一约束兜底
            throw exception(BANK_FLOW_TAKEN);
        }
        flowMapper.updateById(new FinBankFlowDO().setId(flowId).setStatus("已认领"));
        commands.record(post, "fin-bank-confirm", confirm.getId(), 1, "fin-bank-confirm", cn.iocoder.yudao.module.franchise.finance.FinBankService.ConfirmInput.of(flowId, receiptId), idempotencyKey);
        log.info("[confirm][companyId={} flow={} receipt={}]", post.orgId(), flowId, receiptId);
        return confirm.getId();
    }


    /** dev 注入待登记应收（P-6 测试基建；真实生产链随 B2/B3 intercompany 联动） */
    @Transactional
    public long injectPendingReceipt(long companyId, long amountCents, String name) {
        FinReceiptDO receipt = new FinReceiptDO()
                .setCompanyId(companyId).setSourceType("intercompany").setSourceRef("dev:" + System.nanoTime())
                .setName(name).setAmountCents(amountCents).setStatus(FinReceiptService.STATUS_PENDING)
                .setRegisteredAt(OffsetDateTime.now()).setRegistrarUserId(0L).setMethod("bank")
                .setRevisionVersion(1);
        receiptMapper.insert(receipt);
        return receipt.getId();
    }
 }

