package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

/**
 * 统一建房合同查询端口（M06-T03）：franchise-sales 实现，franchise-commission 移交链经此解析
 * 客户当前活跃的统一建房合同（同 C 唯一活跃合同守卫由 sales 侧保证）。设计委托款独立首尾约定，
 * 不与建房收入合并——本端口只回答「该客户有没有关联的统一合同」，不做资金语义。
 */
public interface SaleContractQueryPort {

    /** 客户在指定组织的活跃（SIGNED）统一建房合同编号；无则返回 null（移交档案 contractId 兼容为空） */
    Long findActiveSignedId(PostSnapshot post, long customerId);

    /**
     * M07-T06：统一合同三期约定金额（签约后/基础完成后/封顶后，分）——工程开工准备的三期固化
     * 与销售统一合同对齐（「每期金额按有效合同，不发明固定比例」）。无活跃合同返回 null（工程侧
     * 沿表单三期，存量兼容）。
     */
    StageAmounts findStageAmounts(PostSnapshot post, long customerId);

    /** 三期约定（分；baseDone/capDone 允许 0=未约定，Σ=合同总额） */
    record StageAmounts(long signAfterCents, long baseDoneCents, long capDoneCents) {
    }
}
