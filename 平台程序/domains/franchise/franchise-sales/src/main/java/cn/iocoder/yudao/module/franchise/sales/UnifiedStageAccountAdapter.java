package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleContractMapper;
import cn.iocoder.yudao.module.franchise.sales.dal.SalePaymentStageDO;
import cn.iocoder.yudao.module.franchise.sales.dal.SalePaymentStageMapper;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * M07-T06/I05：统一实收账适配器——franchise-sales 实现。工程收款经
 * {@link SaleContractService#collect} 写通统一合同期次（限额/幂等/资金登记/审计全在统一侧）；
 * 期次累计读 fr_sale_stage_receipt（已收净口径）。「销售工程汇总不重复」的结构保证=工程域
 * 不再自建财务收据，本适配器是唯一写入口。
 */
@Component
public class UnifiedStageAccountAdapter implements UnifiedStageAccountPort {

    private final SaleContractMapper contracts;
    private final SalePaymentStageMapper stages;
    private final SaleContractService collectService;

    public UnifiedStageAccountAdapter(SaleContractMapper contracts, SalePaymentStageMapper stages,
                                      SaleContractService collectService) {
        this.contracts = contracts;
        this.stages = stages;
        this.collectService = collectService;
    }

    @Override
    public Long activeContractId(PostSnapshot post, long customerId) {
        return contracts.signedContractId(post.orgId(), customerId);
    }

    @Override
    public long stageCollectedCents(PostSnapshot post, long contractId, String stage) {
        Map<String, SalePaymentStageDO> byStage = stages.selectByContract(contractId).stream()
                .collect(Collectors.toMap(SalePaymentStageDO::getStage, Function.identity(), (a, b) -> a));
        SalePaymentStageDO stageRow = byStage.get(stage);
        if (stageRow == null) {
            return 0;
        }
        return collectService.stageCollected(post, stageRow.getId());
    }

    @Override
    public StageCollectResult collectStage(PostSnapshot post, long contractId, String stage, CollectInput input) {
        SalePaymentStageDO stageRow = stages.selectByContract(contractId).stream()
                .filter(t -> stage.equals(t.getStage())).findFirst().orElse(null);
        if (stageRow == null) {
            throw new IllegalStateException("统一合同缺少该期约定行: " + stage);
        }
        var result = collectService.collect(post, stageRow.getId(),
                new SaleContractService.CollectCommand(input.amountCents(), input.method(),
                        input.transactionNo(), input.occurredAt(),input.fileAssetId(),null),
                "eng-unified:" + contractId + ":" + stage + ":" + input.transactionNo());
        return new StageCollectResult(((Number) result.get("receiptId")).longValue(),
                ((Number) result.getOrDefault("collectedCents", 0)).longValue(),
                String.valueOf(result.get("stageStatus")),
                Boolean.TRUE.equals(result.get("replayed")));
    }

    @Override
    public java.util.List<StageBalance> stageBalances(PostSnapshot post, long contractId) {
        return collectService.stageSummary(post, contractId).stream().map(row -> new StageBalance(
                String.valueOf(row.get("stage")), ((Number)row.get("amountCents")).longValue(),
                ((Number)row.get("collectedCents")).longValue(),
                (java.time.LocalDate) row.get("dueDate"), ((Number) row.get("stageId")).longValue(),
                ((Number) row.get("refundedCents")).longValue())).toList();
    }
}
