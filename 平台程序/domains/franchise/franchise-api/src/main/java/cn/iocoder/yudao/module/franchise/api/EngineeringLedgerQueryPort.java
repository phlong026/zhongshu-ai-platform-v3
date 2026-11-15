package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

import java.util.Collection;
import java.util.Map;

/**
 * 工程财务台账查询端口：金额净付用于结算屏，原付款事实单独用于 terms 锁定，退款不解锁。
 * franchise-finance 以 EngineeringLedgerQueryAdapter 实现（读既有 fin_payable/fin_payment，零新迁移）。
 * 已核算收款在工程域 fr_eng_collection 自有（登记即已核算，C4），不经本端口。
 */
public interface EngineeringLedgerQueryPort {

    /** 单个应付的有效付款减有效支出退回净额（分）；应付不存在返回 0，不用于条款锁定 */
    long paidCents(PostSnapshot post, long payableId);

    /** 批量（结算屏/列表汇总用）；入参不含的 payable 不出现在结果 Map */
    Map<Long, Long> paidCentsByPayableIds(PostSnapshot post, Collection<Long> payableIds);

    /** 条款锁定事实：本组织存在未作废的已核实付款；退回净额不解锁。旧实现缺能力时拒绝编辑。 */
    default boolean hasActivePayment(PostSnapshot post,long payableId) { return true; }

    default Map<Long,Boolean> activePaymentsByPayableIds(PostSnapshot post,Collection<Long> payableIds) {
        var result=new java.util.HashMap<Long,Boolean>();
        if(payableIds!=null) for(Long id:payableIds) result.put(id,hasActivePayment(post,id));
        return result;
    }
}
