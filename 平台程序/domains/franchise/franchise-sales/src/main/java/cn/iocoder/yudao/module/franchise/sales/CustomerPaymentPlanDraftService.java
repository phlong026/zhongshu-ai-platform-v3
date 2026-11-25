package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.api.CustomerPaymentPlanDraftPort;
import cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.REFERENCE_INVALID;

@Service
public class CustomerPaymentPlanDraftService implements CustomerPaymentPlanDraftPort {
    private final JdbcTemplate jdbc;
    private final SalesAccess access;
    private final FranchiseCommands commands;
    private final SaleContractQueryPort contracts;
    public CustomerPaymentPlanDraftService(JdbcTemplate jdbc,SalesAccess access,FranchiseCommands commands,SaleContractQueryPort contracts) {
        this.jdbc=jdbc;this.access=access;this.commands=commands;this.contracts=contracts;
    }
    public record Save(long revision,long totalCents,List<PaymentPlanRules.Input> stages,String note) {}
    public record Draft(long customerId,long revision,Long totalCents,List<Stage> stages,String note) {}

    public Draft read(PostSnapshot post,long customerId) {
        requireCustomer(post,customerId);
        return find(post.orgId(),customerId);
    }

    @Transactional(rollbackFor=Exception.class)
    public Draft save(PostSnapshot post,long customerId,Save command,String key) {
        requireCustomer(post,customerId);
        String normalized=commands.requireIdempotencyKey(key);
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(?)) s",Integer.class,
                FranchiseCommandKeys.lockKey(new FranchiseCommandKeys.PostKeyScope("franchise",post.userId()),normalized));
        var payload=Map.of("customerId",customerId,"paymentPlanDraft",command);
        if(commands.replayed(post,key,payload).isPresent()) return find(post.orgId(),customerId);
        jdbc.queryForObject("SELECT id FROM fr_customer WHERE id=? AND company_id=? FOR UPDATE",Long.class,customerId,post.orgId());
        requireCustomer(post,customerId);
        var previous=find(post.orgId(),customerId);
        if(command.revision()!=previous.revision()) throw exception(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT);
        if(command.note()==null || command.note().isBlank() || command.note().length()>500) throw PaymentPlanRules.invalid("请填写付款草案说明（不超过500字）");
        var normalizedStages=PaymentPlanRules.normalize(command.totalCents(),command.stages());
        if(normalizedStages.stream().anyMatch(s->s.stageId()!=null)) throw PaymentPlanRules.invalid("付款草案不能引用正式收款节点编号");
        var rows=normalizedStages.stream().map(s->new Stage(s.label(),s.amountCents(),s.ratioBps(),s.dueDate(),s.trigger(),s.nodeIndex(),s.sequence())).toList();
        long revision=previous.revision()+1;
        String json=JsonUtils.toJsonString(rows);
        jdbc.update("""
            INSERT INTO fr_customer_payment_plan_draft(customer_id,company_id,revision,total_cents,stages,note,actor_user_id)
            VALUES(?,?,?,?,CAST(? AS jsonb),?,?)
            ON CONFLICT(customer_id) DO UPDATE SET revision=EXCLUDED.revision,total_cents=EXCLUDED.total_cents,
              stages=EXCLUDED.stages,note=EXCLUDED.note,actor_user_id=EXCLUDED.actor_user_id,updated_at=now()
            WHERE fr_customer_payment_plan_draft.company_id=EXCLUDED.company_id
            """,customerId,post.orgId(),revision,command.totalCents(),json,command.note(),post.userId());
        jdbc.update("""
            INSERT INTO fr_customer_payment_plan_draft_version(customer_id,company_id,revision,total_cents,stages,note,actor_user_id)
            VALUES(?,?,?,?,CAST(? AS jsonb),?,?)
            """,customerId,post.orgId(),revision,command.totalCents(),json,command.note(),post.userId());
        commands.record(post,"fr-payment-plan-draft",customerId,revision,"payment-plan-draft-save",payload,key);
        return new Draft(customerId,revision,command.totalCents(),rows,command.note());
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public Snapshot snapshotForEntrust(PostSnapshot post,long customerId) {
        requireCustomer(post,customerId);
        // Use the same contract lock as R11 changes. A READ_COMMITTED caller cannot mix old version with new stages.
        var signedIds=jdbc.queryForList("SELECT id FROM fr_sale_contract WHERE customer_id=? AND company_id=? AND status='SIGNED' AND NOT deleted FOR UPDATE",Long.class,customerId,post.orgId());
        requireCustomer(post,customerId);
        var signed=signedIds.isEmpty()?null:contracts.findPaymentPlanById(post,signedIds.get(0));
        if(signed!=null) return new Snapshot(customerId,post.orgId(),"CONTRACT",signed.revision(),signed.totalCents(),
                signed.contractId(),signed.version(),signed.stages().stream().map(s->new Stage(s.label(),s.amountCents(),s.ratioBps(),s.dueDate(),s.trigger(),s.nodeIndex(),s.sequence())).toList());
        var draft=find(post.orgId(),customerId);
        return draft.revision()==0?null:new Snapshot(customerId,post.orgId(),"DRAFT",draft.revision(),draft.totalCents(),null,null,draft.stages());
    }

    private Draft find(long company,long customer) {
        var rows=jdbc.query("SELECT revision,total_cents,stages::text,note FROM fr_customer_payment_plan_draft WHERE customer_id=? AND company_id=?",
                (rs,n)->new Draft(customer,rs.getLong("revision"),rs.getLong("total_cents"),JsonUtils.parseArray(rs.getString("stages"),Stage.class),rs.getString("note")),customer,company);
        return rows.isEmpty()?new Draft(customer,0,null,List.of(),""):rows.get(0);
    }
    private void requireCustomer(PostSnapshot post,long customerId) {
        if(!"store".equals(post.orgType()) || !java.util.Set.of("sales","sales-lead","boss").contains(post.roleCode())) throw exception(REFERENCE_INVALID);
        Long tenant=cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.getTenantId();
        if(tenant==null || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM system_dept d JOIN pf_org_unit o ON o.dept_id=d.id WHERE d.id=? AND d.tenant_id=? AND d.status=0 AND d.deleted=0 AND o.kind='ORG' AND o.org_type='store' AND o.status='ACTIVE')",Boolean.class,post.orgId(),tenant))) throw exception(REFERENCE_INVALID);
        access.requireOrgCustomer(post,customerId);
    }
}
