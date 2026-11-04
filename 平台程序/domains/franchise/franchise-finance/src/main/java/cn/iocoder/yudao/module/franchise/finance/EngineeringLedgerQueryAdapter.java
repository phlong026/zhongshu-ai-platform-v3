package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.module.franchise.api.EngineeringLedgerQueryPort;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPayableDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPayableMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentMapper;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工程台账查询适配器（阶段 2 / D-7③）：已付款合计读（Σ已核实 per payable，行内直接聚合；
 * 量级=单工程材料数十行，无分页需求）。读既有表，零新迁移。
 */
@Component
public class EngineeringLedgerQueryAdapter implements EngineeringLedgerQueryPort {

    private final FinPayableMapper payables;
    private final FinPaymentMapper paymentMapper;

    public EngineeringLedgerQueryAdapter(FinPayableMapper payables, FinPaymentMapper paymentMapper) {
        this.payables = payables;
        this.paymentMapper = paymentMapper;
    }

    @Override
    public long paidCents(PostSnapshot post, long payableId) {
        return paymentMapper.selectActiveByPayable(payableId).stream()
                // R2 P1-4：有效账口径=Σ(amount-pending_link_cents)——PENDING_LINK 超额不计入
                .mapToLong(p -> FinPaymentService.amount(p)
                        - (p.getPendingLinkCents() == null ? 0 : p.getPendingLinkCents())).sum();
    }

    @Override
    public Map<Long, Long> paidCentsByPayableIds(PostSnapshot post, Collection<Long> payableIds) {
        Map<Long, Long> result = new HashMap<>();
        if (payableIds == null || payableIds.isEmpty()) {
            return result;
        }
        List<Long> ids = List.copyOf(payableIds);
        for (int i = 0; i < ids.size(); i += 100) {
            List<FinPaymentDO> rows = paymentMapper.selectList(new LambdaQueryWrapper<FinPaymentDO>()
                    .in(FinPaymentDO::getPayableId, ids.subList(i, Math.min(i + 100, ids.size())))
                    .eq(FinPaymentDO::getStatus, "已核实"));
            rows.forEach(p -> result.merge(p.getPayableId(), FinPaymentService.amount(p), Long::sum));
        }
        // 应付本身不存在的入参以 0 出现（调用方区分「无应付」用 payableId==null 判断）
        ids.forEach(id -> result.putIfAbsent(id, 0L));
        return result;
    }
}
