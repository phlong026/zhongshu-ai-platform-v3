package cn.iocoder.yudao.module.platform.operations;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointPriceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class OrganizationCapabilities {
    private final JdbcTemplate jdbc;
    private final OperationsGuard guard;
    private final OperationsAudit audit;
    private final OrgPointPriceService prices;
    public OrganizationCapabilities(JdbcTemplate jdbc,OperationsGuard guard,OperationsAudit audit,OrgPointPriceService prices) {
        this.jdbc=jdbc;this.guard=guard;this.audit=audit;this.prices=prices;
    }
    public record Item(String subject,String label,String unit,boolean enabled,boolean priced) { }
    public record Policy(long version,List<Item> items) { }
    public record Update(long expectedVersion,List<String> enabledSubjects,String reason,String requestKey) { }
    @Transactional(readOnly=true)
    public Policy get(long org) {
        var actor=guard.require();guard.organization(actor,org,false);return read(actor.tenantId(),org);
    }
    private Policy read(long tenant,long org) {
        var rows=jdbc.queryForList("SELECT version,allowed_subjects::text AS subjects FROM pf_org_capability_policy WHERE tenant_id=? AND org_id=?",tenant,org);
        Set<String> allowed=rows.isEmpty()?Set.of():new HashSet<>(JsonUtils.parseArray(String.valueOf(rows.get(0).get("subjects")),String.class));
        long version=rows.isEmpty()?0:((Number)rows.get(0).get("version")).longValue();
        return new Policy(version,prices.catalog().stream().map(p->new Item(p.subject(),p.label(),p.unit(),allowed.contains(p.subject()),p.priceMicros()>0)).toList());
    }
    @Transactional(rollbackFor=Exception.class)
    public Policy update(long org,Update input) {
        var actor=guard.require();guard.organization(actor,org,true);
        OperationsGuard.text(input.reason(),500,"操作原因");
        if(input.expectedVersion()<0||input.enabledSubjects()==null||input.enabledSubjects().size()>100)throw new ServiceException(400,"能力配置无效");
        var subjects=input.enabledSubjects().stream().filter(Objects::nonNull).distinct().sorted().toList();
        if(subjects.size()!=input.enabledSubjects().size())throw new ServiceException(400,"能力配置包含重复或无效项目");
        String hash=audit.hash(List.of(org,input.expectedVersion(),subjects,input.reason().trim()));
        if(audit.replay(actor,org,"capabilities",input.requestKey(),hash))return read(actor.tenantId(),org);
        // 排他锁与新调用入口的组织共享锁互斥；停用提交后不会再有新预扣。
        jdbc.queryForList("SELECT dept_id FROM pf_org_unit WHERE dept_id=? FOR UPDATE",org);
        guard.organization(actor,org,true);
        var before=read(actor.tenantId(),org);
        if(before.version()!=input.expectedVersion())throw new ServiceException(409,"能力配置已被修改，请刷新后重试");
        Set<String> priced=new HashSet<>();
        before.items().stream().filter(Item::priced).forEach(p->priced.add(p.subject()));
        if(!priced.containsAll(subjects))throw new ServiceException(400,"请先为所选能力配置有效价格");
        jdbc.update("""
          INSERT INTO pf_org_capability_policy(tenant_id,org_id,allowed_subjects,version,updated_by)
          VALUES(?,?,?::jsonb,?,?) ON CONFLICT(tenant_id,org_id) DO UPDATE
          SET allowed_subjects=excluded.allowed_subjects,version=excluded.version,updated_by=excluded.updated_by,updated_at=now()
          """,actor.tenantId(),org,JsonUtils.toJsonString(subjects),before.version()+1,actor.userId());
        var after=read(actor.tenantId(),org);
        audit.append(actor,org,"capabilities",input.requestKey(),hash,input.reason(),before,after);
        return after;
    }
    /** 在与新预扣相同的事务内执行；无配置行默认关闭。后台结算、释放不经过此入口。 */
    public void assertEnabled(long org,String subject,String replayKey) {
        var rows=jdbc.queryForList("""
          SELECT d.tenant_id,d.deleted,d.status AS dept_status,o.status
          FROM pf_org_unit o JOIN system_dept d ON d.id=o.dept_id
          WHERE o.dept_id=? AND o.kind='ORG' FOR SHARE OF o,d
          """,org);
        if(rows.isEmpty())throw new ServiceException(403,"组织不可用");
        var row=rows.get(0);long tenant=((Number)row.get("tenant_id")).longValue();
        Long current=TenantContextHolder.getTenantId();
        if(current!=null&&current!=tenant)throw new ServiceException(403,"组织范围无效");
        if(((Number)row.get("deleted")).intValue()!=0||((Number)row.get("dept_status")).intValue()!=0||!"ACTIVE".equals(row.get("status")))throw new ServiceException(403,"组织已关闭");
        if(replayKey!=null&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM org_point_ledger WHERE org_id=? AND biz_key=? AND type='RESERVE')",Boolean.class,org,replayKey)))return;
        Boolean enabled=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pf_org_capability_policy WHERE tenant_id=? AND org_id=? AND allowed_subjects @> ?::jsonb)",Boolean.class,tenant,org,JsonUtils.toJsonString(List.of(subject)));
        if(!Boolean.TRUE.equals(enabled))throw new ServiceException(403,"该组织尚未开通此能力，请联系平台管理员");
    }
}
