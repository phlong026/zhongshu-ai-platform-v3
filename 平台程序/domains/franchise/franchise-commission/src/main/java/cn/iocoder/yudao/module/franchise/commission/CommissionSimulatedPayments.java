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
    static final String HISTORICAL_REVIEW_AGGREGATE = "commission-historical-settlement";
    static final String HISTORICAL_REVIEW_COMMAND = "commission-historical-settlement-prepare";

    public record Order(String number,long commissionId,int submissionVersion,String customerName,
                        Long amountCents,String status,String channel,OffsetDateTime paidAt,boolean simulated,boolean storeFinanceConfigured,boolean headquartersFinanceConfigured) {
        public boolean paid(){return "PAID".equals(status);}
        public Order designView(){return new Order(number,commissionId,submissionVersion,customerName,null,status,null,null,true,storeFinanceConfigured,headquartersFinanceConfigured);}
    }
    private final JdbcTemplate jdbc;
    public CommissionSimulatedPayments(JdbcTemplate jdbc){this.jdbc=jdbc;}
    // The old property is accepted during rolling upgrades, but cannot reopen a retired cashier.
    @org.springframework.beans.factory.annotation.Value("${franchise.commission.simulation-org-ids:}")
    void configureSimulationOrganizations(String ids) { }

    boolean allowed(long storeId,long designOrgId) { return false; }

    boolean blocked(CommissionDO commission) {
        return blocked(commission, commission.getSubmissionVersion());
    }

    boolean blocked(CommissionDO commission, int submissionVersion) {
        return !allowed(commission.getCompanyId(), commission.getDesignOrgId())
                && Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM fr_commission_simulated_order
                        WHERE commission_id=? AND submission_version=?)
                    """, Boolean.class, commission.getId(), submissionVersion));
    }

    boolean reviewedForSettlement(CommissionDO commission) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM pf_command_event
                WHERE domain='franchise' AND aggregate_type=? AND command=?
                  AND org_id=? AND aggregate_id=? AND revision=?)
            """, Boolean.class, HISTORICAL_REVIEW_AGGREGATE, HISTORICAL_REVIEW_COMMAND,
                commission.getCompanyId(), commission.getId(), commission.getSubmissionVersion()));
    }

    public Order read(CommissionDO c){
        return jdbc.query("SELECT o.*,EXISTS(SELECT 1 FROM pf_staff_post p WHERE p.org_id=o.company_id AND p.status='ACTIVE' AND p.role_code IN('finance','finance-lead')) AS store_finance,EXISTS(SELECT 1 FROM pf_staff_post p WHERE p.org_id=o.design_org_id AND p.status='ACTIVE' AND p.role_code IN('finance','finance-lead')) AS headquarters_finance FROM fr_commission_simulated_order o WHERE commission_id=? AND submission_version=?",
            (r,n)->new Order(r.getString("order_number"),c.getId(),c.getSubmissionVersion(),c.getCustomerName(),r.getLong("amount_cents"),r.getString("status"),r.getString("channel"),r.getObject("paid_at",OffsetDateTime.class),true,r.getBoolean("store_finance"),r.getBoolean("headquarters_finance")),c.getId(),c.getSubmissionVersion()).stream().findFirst().orElse(null);
    }
    public void create(CommissionDO c,long total){ throw exception(SIMULATION_FORBIDDEN); }
    public void pay(CommissionDO c,String channel,PostSnapshot actor){ throw exception(SIMULATION_FORBIDDEN); }
}
