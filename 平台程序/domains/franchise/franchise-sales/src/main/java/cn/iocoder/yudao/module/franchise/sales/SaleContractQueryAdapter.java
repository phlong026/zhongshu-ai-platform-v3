package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleContractDO;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleContractMapper;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.stereotype.Component;

/**
 * M06-T03：统一建房合同查询适配器——同 C 唯一活跃合同守卫（selectActiveByCustomer）下取 SIGNED 合同；
 * WAIT_SIGN/VOIDED 不算关联合同（未签订不冻结移交、作废不关联）。
 */
@Component
public class SaleContractQueryAdapter implements SaleContractQueryPort {

    private final SaleContractMapper contracts;
    private final cn.iocoder.yudao.module.franchise.sales.dal.SalePaymentStageMapper stages;

    public SaleContractQueryAdapter(SaleContractMapper contracts,
                                    cn.iocoder.yudao.module.franchise.sales.dal.SalePaymentStageMapper stages) {
        this.contracts = contracts;
        this.stages = stages;
    }

    @Override
    public Long findActiveSignedId(PostSnapshot post, long customerId) {
        SaleContractDO active = contracts.selectActiveByCustomer(customerId);
        if (active == null || active.getCompanyId() != post.orgId()
                || !SaleContractDO.STATUS_SIGNED.equals(active.getStatus())) {
            return null;
        }
        return active.getId();
    }

    /** M07-T06：从统一合同 stage 行读三期约定（0=未约定；无合同返回 null） */
    @Override
    public StageAmounts findStageAmounts(PostSnapshot post, long customerId) {
        SaleContractDO active = contracts.selectActiveByCustomer(customerId);
        if (active == null || active.getCompanyId() != post.orgId()
                || !SaleContractDO.STATUS_SIGNED.equals(active.getStatus())) {
            return null;
        }
        long signAfter = 0, baseDone = 0, capDone = 0;
        for (var stage : stages.selectByContract(active.getId())) {
            if (Boolean.TRUE.equals(stage.getDeleted())) continue;
            long amount = stage.getAmountCents() == null ? 0 : stage.getAmountCents();
            switch (stage.getStage()) {
                case "SIGN_AFTER" -> signAfter = amount;
                case "BASE_DONE" -> baseDone = amount;
                case "CAP_DONE" -> capDone = amount;
                default -> { }
            }
        }
        return new StageAmounts(signAfter, baseDone, capDone);
    }

    @Override
    public PaymentPlan findPaymentPlan(PostSnapshot post, long customerId) {
        var contractId=findActiveSignedId(post,customerId);
        return contractId==null?null:findPaymentPlanById(post,contractId);
    }

    @Override
    public PaymentPlan findPaymentPlanById(PostSnapshot post, long contractId) {
        var contract=contracts.selectById(contractId);
        if (contract==null || contract.getCompanyId()!=post.orgId() || !SaleContractDO.STATUS_SIGNED.equals(contract.getStatus())) return null;
        var plan=stages.selectByContract(contractId).stream().filter(s -> !Boolean.TRUE.equals(s.getDeleted()))
                .map(s -> new PaymentStage(s.getId(),s.getStage(),SaleContractService.displayLabel(s),s.getSequence(),
                        s.getAmountCents(),s.getRatioBps(),s.getDueDate(),s.getTriggerType(),s.getTriggerNodeIndex())).toList();
        return new PaymentPlan(contract.getId(),contract.getRevision(),contract.getPaymentPlanVersion(),contract.getTotalCents(),contract.getAssetId(),plan);
    }
}
