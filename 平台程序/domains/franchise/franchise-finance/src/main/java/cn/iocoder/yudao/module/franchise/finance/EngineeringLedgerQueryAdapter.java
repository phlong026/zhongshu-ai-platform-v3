package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.module.franchise.api.EngineeringLedgerQueryPort;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinRefundDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinRefundMapper;
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

    private final FinPaymentMapper paymentMapper;
    private final FinRefundMapper refunds;

    public EngineeringLedgerQueryAdapter(FinPaymentMapper paymentMapper,
            FinRefundMapper refunds) {
        this.paymentMapper = paymentMapper;
        this.refunds=refunds;
    }

    @Override
    public long paidCents(PostSnapshot post, long payableId) {
        return paidCentsByPayableIds(post,List.of(payableId)).getOrDefault(payableId,0L);
    }

    @Override
    public boolean hasActivePayment(PostSnapshot post,long payableId) {
        return activePaymentsByPayableIds(post,List.of(payableId)).getOrDefault(payableId,true);
    }

    @Override
    public Map<Long,Boolean> activePaymentsByPayableIds(PostSnapshot post,Collection<Long> payableIds) {
        var result=new HashMap<Long,Boolean>();
        if(payableIds==null || payableIds.isEmpty()) return result;
        var ids=List.copyOf(payableIds);ids.forEach(id->result.put(id,false));
        for(int i=0;i<ids.size();i+=100) {
            paymentMapper.selectList(new LambdaQueryWrapper<FinPaymentDO>()
                    .in(FinPaymentDO::getPayableId,ids.subList(i,Math.min(i+100,ids.size())))
                    .eq(FinPaymentDO::getCompanyId,post.orgId()).eq(FinPaymentDO::getStatus,"已核实"))
                    .forEach(p->result.put(p.getPayableId(),true));
        }
        return result;
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
                    .eq(FinPaymentDO::getCompanyId,post.orgId())
                    .eq(FinPaymentDO::getStatus, "已核实"));
            // R3 P2：有效账口径=Σ(amount-pending_link_cents)——PENDING_LINK 超额不冲减其他应付
            rows.forEach(p -> result.merge(p.getPayableId(), FinPaymentService.amount(p)
                    - (p.getPendingLinkCents() == null ? 0 : p.getPendingLinkCents()), Long::sum));
            if(!rows.isEmpty()) {
                var returned=refunds.selectList(new LambdaQueryWrapper<FinRefundDO>()
                        .in(FinRefundDO::getPaymentId,rows.stream().map(FinPaymentDO::getId).toList())
                        .eq(FinRefundDO::getCompanyId,post.orgId())
                        .eq(FinRefundDO::getStatus,"已核实"));
                var paymentSources=new HashMap<Long,Long>();rows.forEach(p->paymentSources.put(p.getId(),p.getPayableId()));
                returned.forEach(r->result.merge(paymentSources.get(r.getPaymentId()),-r.getAmountCents(),Long::sum));
            }
        }
        // 应付本身不存在的入参以 0 出现（调用方区分「无应付」用 payableId==null 判断）
        ids.forEach(id -> result.putIfAbsent(id, 0L));
        return result;
    }
}
