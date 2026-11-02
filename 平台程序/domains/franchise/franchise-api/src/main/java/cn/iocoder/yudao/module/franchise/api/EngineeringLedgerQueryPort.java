package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

import java.util.Collection;
import java.util.Map;

/**
 * 工程财务台账查询端口（阶段 2 / D-7③）：已付款合计读（结算屏与 terms 锁定校验用）。
 * franchise-finance 以 EngineeringLedgerQueryAdapter 实现（读既有 fin_payable/fin_payment，零新迁移）。
 * 已核算收款在工程域 fr_eng_collection 自有（登记即已核算，C4），不经本端口。
 */
public interface EngineeringLedgerQueryPort {

    /** 单个应付的已核实付款合计（分）；应付不存在返回 0 */
    long paidCents(PostSnapshot post, long payableId);

    /** 批量（结算屏/列表汇总用）；入参不含的 payable 不出现在结果 Map */
    Map<Long, Long> paidCentsByPayableIds(PostSnapshot post, Collection<Long> payableIds);
}
