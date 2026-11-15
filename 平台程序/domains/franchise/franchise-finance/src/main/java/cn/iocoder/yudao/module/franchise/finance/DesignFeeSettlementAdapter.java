package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.module.franchise.api.CommissionQueryPort;
import cn.iocoder.yudao.module.franchise.api.DesignFeeSettlementPort;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPayableDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPayableMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.*;

/** 仅依赖费用mapper投影，不反向调用委托业务服务。 */
@Component
public class DesignFeeSettlementAdapter implements DesignFeeSettlementPort {
    private final CommissionQueryPort commissions;
    private final FinPayableMapper payables;
    private final JdbcTemplate jdbc;
    private final DesignFinanceLocks locks;
    private final FranchiseCommands commands;

    public DesignFeeSettlementAdapter(CommissionQueryPort commissions, FinPayableMapper payables,
            JdbcTemplate jdbc, DesignFinanceLocks locks, FranchiseCommands commands) {
        this.commissions=commissions; this.payables=payables; this.jdbc=jdbc; this.locks=locks; this.commands=commands;
    }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void ensureInitialPayable(long id,int version,PostSnapshot actor) {
        var fee=requireFee(id,version); ensure(fee,fee.mode()==Mode.AREA_FULL?Stage.FULL:Stage.FIRST,actor);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void ensureTailPayable(long id,int version,PostSnapshot actor) {
        var fee=requireFee(id,version); if(fee.mode()==Mode.LEGACY_SPLIT) ensure(fee,Stage.TAIL,actor);
    }
    private void ensure(CommissionQueryPort.CommissionFeeView fee,Stage stage,PostSnapshot actor) {
        DesignFinanceLocks.activeTransaction();
        if(actor.orgId()!=fee.companyId() && actor.orgId()!=fee.designOrgId()) throw exception(ORG_MISMATCH);
        locks.stage(fee.commissionId(),fee.submissionVersion(),stage.name());
        String ref=ref(fee.commissionId(),fee.submissionVersion(),stage);
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fr_fin_payable WHERE company_id=? AND source_type='design' AND source_ref=?)",Boolean.class,fee.companyId(),ref))) return;
        long amount=fee.stageCents(stage.name());
        if(amount<=0) throw exception(COMMISSION_STAGE_INVALID);
        var payable=new FinPayableDO().setCompanyId(fee.companyId()).setCustomerId(fee.customerId()).setDesignOrgId(fee.designOrgId())
                .setSourceType("design").setSourceRef(ref).setStage(stage.name()).setTotalCents(amount)
                .setRecipient(fee.recipient()==null?"总部设计":fee.recipient()).setStatus("进行中").setAgreementRef(ref);
        payables.insert(payable);
        // 已持阶段锁；衍生事实不再反取命令锁，唯一来源约束负责幂等。
        commands.record(actor,"fin-payable",payable.getId(),1,"design-payable-created",Map.of("sourceRef",ref,"amountCents",amount),null);
    }
    @Override public Settlement read(long id,int version) {
        var fee=commissions.findCurrentFee(id);
        if(fee==null || fee.submissionVersion()!=version) throw exception(COMMISSION_NOT_APPROVED);
        var states=new ArrayList<StageState>();
        for(var stage:fee.stages()) {
            String ref=ref(id,version,stage);
            states.add(readStage(fee.companyId(),fee.designOrgId(),ref,stage,fee.stageCents(stage.name()),fee.feeApproved()));
        }
        return new Settlement(fee.mode(),version,states);
    }

    StageState readStage(long company,long designOrg,String ref,Stage stage,long expected,boolean approved) {
        long paid=sum("SELECT COALESCE(sum(m.amount_cents-COALESCE(m.pending_link_cents,0)),0) FROM fr_fin_payment m JOIN fr_fin_payable p ON p.id=m.payable_id WHERE p.company_id=? AND p.source_type='design' AND p.source_ref=? AND m.status='已核实'",company,ref);
        long expense=sum("SELECT COALESCE(sum(r.amount_cents),0) FROM fr_fin_refund r JOIN fr_fin_payment m ON m.id=r.payment_id JOIN fr_fin_payable p ON p.id=m.payable_id WHERE p.company_id=? AND p.source_type='design' AND p.source_ref=? AND m.status='已核实' AND r.status='已核实'",company,ref);
        long received=sum("SELECT COALESCE(sum(amount_cents),0) FROM fr_fin_receipt WHERE company_id=? AND source_type='design' AND source_ref=? AND status='已登记'",designOrg,ref);
        long refunded=sum("SELECT COALESCE(sum(r.amount_cents),0) FROM fr_fin_refund r JOIN fr_fin_receipt t ON t.id=r.receipt_id WHERE t.company_id=? AND t.source_type='design' AND t.source_ref=? AND t.status='已登记' AND r.status='已核实'",designOrg,ref);
        boolean store=approved && expected>0 && paid-expense>=expected,
                hq=approved && expected>0 && received-refunded>=expected && originSourcesValid(company,designOrg,ref);
        return new StageState(stage,expected,paid,expense,paid-expense,received,refunded,received-refunded,store,hq,store&&hq);
    }

    /** 保留真实核收数字，但不能用挂在已退回/作废原付款上的历史核收放行新补付款。 */
    private boolean originSourcesValid(long company,long designOrg,String ref) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT NOT EXISTS(
              SELECT 1 FROM (
                SELECT r.origin_payment_id,sum(r.amount_cents-COALESCE((SELECT sum(f.amount_cents)
                    FROM fr_fin_refund f WHERE f.receipt_id=r.id AND f.status='已核实'),0)) net
                FROM fr_fin_receipt r WHERE r.company_id=? AND r.source_type='design' AND r.source_ref=?
                    AND r.status='已登记' GROUP BY r.origin_payment_id
              ) collected LEFT JOIN fr_fin_payment m ON m.id=collected.origin_payment_id
              LEFT JOIN fr_fin_payable p ON p.id=m.payable_id
              WHERE collected.net>0 AND (m.status IS DISTINCT FROM '已核实' OR m.company_id IS DISTINCT FROM ?::bigint
                OR p.company_id IS DISTINCT FROM ?::bigint OR p.design_org_id IS DISTINCT FROM ?::bigint
                OR p.source_type IS DISTINCT FROM 'design' OR p.source_ref IS DISTINCT FROM ?::text
                OR collected.net>m.amount_cents-COALESCE(m.pending_link_cents,0)-COALESCE((SELECT sum(f.amount_cents)
                    FROM fr_fin_refund f WHERE f.payment_id=m.id AND f.status='已核实'),0))
            )
            """,Boolean.class,designOrg,ref,company,company,designOrg,ref));
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Settlement lockAndRead(long id,int version,PostSnapshot actor) {
        DesignFinanceLocks.activeTransaction(); var fee=requireFee(id,version);
        if(actor.orgId()!=fee.companyId() && actor.orgId()!=fee.designOrgId()) throw exception(ORG_MISMATCH);
        for(var stage:fee.stages()) locks.stage(id,version,stage.name());
        return read(id,version);
    }
    CommissionQueryPort.CommissionFeeView requireFee(long id,int version) {
        var fee=commissions.findCurrentFee(id);
        if(fee==null || !fee.feeApproved() || fee.submissionVersion()!=version) throw exception(COMMISSION_NOT_APPROVED);
        return fee;
    }
    private long sum(String sql,Object... args) { return jdbc.queryForObject(sql,Long.class,args); }
    static String ref(long id,int version,Stage stage) { return id+":"+version+":"+stage.name(); }
}
