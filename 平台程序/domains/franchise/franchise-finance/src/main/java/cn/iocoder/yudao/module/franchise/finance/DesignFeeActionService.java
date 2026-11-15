package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.franchise.api.CommissionQueryPort;
import cn.iocoder.yudao.module.franchise.api.DesignFeeSettlementPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

/** 财务通过本组织应付来源查询，不借用设计管理员的委托列表权限。 */
@Service
public class DesignFeeActionService {
    private final JdbcTemplate jdbc;
    private final CommissionQueryPort commissions;
    private final DesignFeeSettlementAdapter settlement;
    public DesignFeeActionService(JdbcTemplate jdbc,CommissionQueryPort commissions,DesignFeeSettlementAdapter settlement) {
        this.jdbc=jdbc;this.commissions=commissions;this.settlement=settlement;
    }
    public record Payment(long paymentId,long amountCents,long netPaidCents,long receivedNetCents,long receivableCents) {}
    public record Item(long commissionId,int submissionVersion,long customerId,long companyId,long designOrgId,
            String mode,String stage,long payableId,long expectedCents,long storePaidNetCents,long hqReceivedNetCents,
            boolean storeReady,boolean hqReady,List<Payment> payments,List<String> allowedActions,boolean financialException,
            long originPaymentOverReceivedCents) {}

    public PageResult<Item> page(long org,int pageNo,int pageSize) {
        String filter=" FROM fr_fin_payable WHERE source_type='design' AND (company_id=? OR design_org_id=?)";
        long total=jdbc.queryForObject("SELECT count(*)"+filter,Long.class,org,org);
        var rows=jdbc.queryForList("SELECT id,source_ref,company_id,design_org_id,customer_id,total_cents"+filter+" ORDER BY create_time DESC,id DESC LIMIT ? OFFSET ?",org,org,pageSize,(long)(pageNo-1)*pageSize);
        var result=new ArrayList<Item>();
        for(var row:rows) {
            String[] ref=((String)row.get("source_ref")).split(":");
            long id=Long.parseLong(ref[0]); int version=Integer.parseInt(ref[1]);
            var fee=commissions.findCurrentFee(id);
            boolean current=fee!=null && fee.submissionVersion()==version && fee.feeApproved();
            long company=((Number)row.get("company_id")).longValue(),designOrg=((Number)row.get("design_org_id")).longValue();
            var stage=settlement.readStage(company,designOrg,(String)row.get("source_ref"),DesignFeeSettlementPort.Stage.valueOf(ref[2]),((Number)row.get("total_cents")).longValue(),true);
            long payable=((Number)row.get("id")).longValue();
            List<Payment> payments=jdbc.query("""
                SELECT m.id,m.amount_cents,
                  m.amount_cents-COALESCE(m.pending_link_cents,0)-COALESCE((SELECT sum(amount_cents) FROM fr_fin_refund WHERE payment_id=m.id AND status='已核实'),0) net,
                  COALESCE((SELECT sum(r.amount_cents-COALESCE((SELECT sum(f.amount_cents) FROM fr_fin_refund f WHERE f.receipt_id=r.id AND f.status='已核实'),0)) FROM fr_fin_receipt r WHERE r.origin_payment_id=m.id AND r.status='已登记'),0) received
                FROM fr_fin_payment m WHERE payable_id=? AND status='已核实' ORDER BY m.pay_date,m.id
                """,(rs,n)->new Payment(rs.getLong("id"),rs.getLong("amount_cents"),rs.getLong("net"),rs.getLong("received"),Math.max(0,rs.getLong("net")-rs.getLong("received"))),payable);
            var actions=new ArrayList<String>();
            if(current && org==company && !stage.storeReady()) actions.add("PAYMENT_REGISTER");
            if(current && org==designOrg && payments.stream().anyMatch(p->p.receivableCents()>0)) actions.add("RECEIPT_REGISTER");
            long overReceived=payments.stream().mapToLong(p->Math.max(0,p.receivedNetCents()-p.netPaidCents())).sum();
            boolean exception=overReceived>0 || (current && List.of("DISPATCHED","HANDOVER_SUBMITTED","HANDOVER_RETURNED","ACCEPTED").contains(fee.status()) && !stage.fullyReady());
            String mode=stage.stage()==DesignFeeSettlementPort.Stage.FULL?"AREA_FULL":"LEGACY_SPLIT";
            result.add(new Item(id,version,((Number)row.get("customer_id")).longValue(),company,designOrg,mode,ref[2],payable,
                    stage.expectedCents(),stage.storePaidNetCents(),stage.headquartersNetCents(),stage.storeReady(),stage.headquartersReady(),payments,actions,exception,overReceived));
        }
        return new PageResult<>(result,total);
    }
}
