package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

import java.time.LocalDate;

/**
 * M07-T06/I05：统一实收账端口——「同一甲方建房只对应一份合同与统一实收账；销售与工程是同一合同的
 * 办理视图，不得各收一遍」。franchise-sales 实现（统一合同期次的资金事实唯一在
 * fr_fin_receipt + fr_sale_stage_receipt），franchise-engineering 收款经此写通，不再自建财务收据镜像。
 * 工程侧留痕（fr_eng_collection 行）仅引用统一 receiptId，不参与余额累计（读侧同样经本端口）。
 */
public interface UnifiedStageAccountPort {

    /** 客户当前活跃（SIGNED）统一合同编号；无则 null（工程收款将拒绝——I05 规则从严） */
    Long activeContractId(PostSnapshot post, long customerId);

    /** 期次累计实收（分；Σ 该期全部分笔 = 已收-退回的净口径由 sales 侧保证） */
    long stageCollectedCents(PostSnapshot post, long contractId, String stage);

    /** 当前合同约定和净实收，包含变更新增的尾款。 */
    default java.util.List<StageBalance> stageBalances(PostSnapshot post, long contractId) { return java.util.List.of(); }
    record StageBalance(String stage, long expectedCents, long collectedCents) {}

    /** 按期分笔收款（转调 sales 收款链：限额/幂等/审计/资金登记全在统一侧） */
    StageCollectResult collectStage(PostSnapshot post, long contractId, String stage, CollectInput input);

    record CollectInput(long amountCents, String method, String transactionNo,
                        LocalDate occurredAt, Long fileAssetId) {
    }

    record StageCollectResult(long receiptId, long collectedCents, String stageStatus, boolean replayed) {
    }
}
