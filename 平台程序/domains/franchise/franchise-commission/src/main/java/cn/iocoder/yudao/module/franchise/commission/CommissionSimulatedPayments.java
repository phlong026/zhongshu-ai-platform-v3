package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.module.franchise.commission.dal.CommissionDO;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.commission.enums.FranchiseCommissionErrorCodes.*;

/** Persisted simulation receipts never enter the actual finance ledger. */
@Component
public class CommissionSimulatedPayments {
    public record Order(String number,long commissionId,int submissionVersion,String customerName,
                        Long amountCents,String status,String channel,OffsetDateTime paidAt,boolean simulated,boolean storeFinanceConfigured,boolean headquartersFinanceConfigured) {
        public boolean paid(){return "PAID".equals(status);}
        public Order designView(){return new Order(number,commissionId,submissionVersion,customerName,null,status,null,null,true,storeFinanceConfigured,headquartersFinanceConfigured);}
    }
    private final JdbcTemplate jdbc;
    public CommissionSimulatedPayments(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private Set<Long> simulationOrgIds = Set.of();

    @org.springframework.beans.factory.annotation.Value("${franchise.commission.simulation-org-ids:}")
    void configureSimulationOrganizations(String ids) {
        simulationOrgIds = Arrays.stream(ids.split(",")).map(String::trim).filter(id -> !id.isEmpty())
                .map(Long::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    boolean allowed(long storeId, long designOrgId) {
        return simulationOrgIds.contains(storeId) && simulationOrgIds.contains(designOrgId);
    }

    boolean blocked(CommissionDO commission) {
        return !allowed(commission.getCompanyId(), commission.getDesignOrgId())
                && Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM fr_commission_simulated_order
                        WHERE commission_id=? AND submission_version=?)
                    """, Boolean.class, commission.getId(), commission.getSubmissionVersion()));
    }

    public Order read(CommissionDO c){
        return jdbc.query("SELECT o.*,EXISTS(SELECT 1 FROM pf_staff_post p WHERE p.org_id=o.company_id AND p.status='ACTIVE' AND p.role_code IN('finance','finance-lead')) AS store_finance,EXISTS(SELECT 1 FROM pf_staff_post p WHERE p.org_id=o.design_org_id AND p.status='ACTIVE' AND p.role_code IN('finance','finance-lead')) AS headquarters_finance FROM fr_commission_simulated_order o WHERE commission_id=? AND submission_version=?",
            (r,n)->new Order(r.getString("order_number"),c.getId(),c.getSubmissionVersion(),c.getCustomerName(),r.getLong("amount_cents"),r.getString("status"),r.getString("channel"),r.getObject("paid_at",OffsetDateTime.class),true,r.getBoolean("store_finance"),r.getBoolean("headquarters_finance")),c.getId(),c.getSubmissionVersion()).stream().findFirst().orElse(null);
    }
    public void create(CommissionDO c,long total){
        jdbc.update("INSERT INTO fr_commission_simulated_order(commission_id,submission_version,company_id,design_org_id,order_number,amount_cents,status) VALUES(?,?,?,?,?,?,'PENDING')",
            c.getId(),c.getSubmissionVersion(),c.getCompanyId(),c.getDesignOrgId(),"SIM-"+c.getId()+"-V"+c.getSubmissionVersion(),total);
    }
    public void pay(CommissionDO c,String channel,PostSnapshot actor){
        if(!Set.of("WECHAT","ALIPAY").contains(Objects.toString(channel,"")))throw exception(SUBMISSION_INPUT_INVALID);
        jdbc.queryForObject("SELECT 1 FROM fr_commission_simulated_order WHERE commission_id=? AND submission_version=? FOR UPDATE",Integer.class,c.getId(),c.getSubmissionVersion());
        Order order=read(c);
        if(order==null||order.paid())throw exception(COMMISSION_STATE_INVALID);
        if(jdbc.update("UPDATE fr_commission_simulated_order SET status='PAID',channel=?,paid_at=CURRENT_TIMESTAMP,paid_by_user_id=? WHERE commission_id=? AND submission_version=? AND status='PENDING'",
                channel,actor.userId(),c.getId(),c.getSubmissionVersion())!=1)throw exception(COMMISSION_STATE_INVALID);
    }
}
