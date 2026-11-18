package cn.iocoder.yudao.module.platform.wallet;

import cn.iocoder.yudao.module.platform.authz.PostPermissionEvaluator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@ConditionalOnProperty(name="platform.legacy-design-bridge.enabled",havingValue="false")
public class CompanyRechargeCatalog {
    public record Plan(String id,String version,String name,long amountCents,String credits) {}
    public record Order(String id,String status,long amountCents,String credits,boolean quoteCurrent) {
        public static Order from(RechargePaymentService.OrderResponse order) {
            return new Order(order.id(),state(order.status(),order.creditState()),order.priceFen(),
                    String.valueOf(Math.addExact(order.basePoints(),order.bonusPoints())),true);
        }
        static String state(String payment,String credit) {
            if ("BLOCKED".equals(credit)) return "FAILED";
            if ("CREDITED".equals(credit)) return "CREDITED";
            return switch(payment) {
                case "SUCCEEDED" -> "PAID";
                case "PENDING","UNKNOWN" -> "PROCESSING";
                default -> payment;
            };
        }
    }
    public record Catalog(String companyName, boolean enabled, boolean browseOnly, String reason, List<Plan> plans, Order currentOrder) {}
    private final JdbcTemplate jdbc;
    private final WalletScope scope;
    private final PostPermissionEvaluator permissions;
    private final RechargePaymentService payments;

    public CompanyRechargeCatalog(JdbcTemplate jdbc, WalletScope scope, PostPermissionEvaluator permissions, RechargePaymentService payments) {
        this.jdbc=jdbc;
        this.scope=scope;
        this.permissions=permissions;
        this.payments=payments;
    }

    @Transactional
    public Catalog read() {
        var org=scope.requireOrg();
        var plans=jdbc.query("""
                SELECT id,version,name,price_fen,base_points+bonus_points AS credits FROM pf_recharge_plan
                WHERE tenant_id=? AND status='ACTIVE' ORDER BY sort,id
                """,(r,i)->new Plan(r.getString("id"),r.getString("version"),r.getString("name"),
                r.getLong("price_fen"),r.getString("credits")),org.tenantId());
        var orders=jdbc.query("""
                SELECT o.id,o.price_fen,o.base_points+o.bonus_points AS credits,o.credit_state,
                  p.id IS NOT NULL AS quote_current,
                  CASE WHEN o.payment_state='CREATED' AND p.id IS NULL THEN 'CLOSED' ELSE o.payment_state END AS payment_state
                FROM pf_recharge_order o LEFT JOIN pf_recharge_plan p
                  ON p.tenant_id=o.tenant_id AND p.id=o.plan_id AND p.status='ACTIVE' AND p.version=(o.plan_snapshot->>'version')::bigint
                WHERE o.tenant_id=? AND o.org_id=? AND o.actor_id=? AND o.post_id=?
                  AND o.credit_target='COMPANY' ORDER BY o.create_time DESC,o.id DESC LIMIT 1
                """,(r,i)->new Order(r.getString("id"),Order.state(r.getString("payment_state"),r.getString("credit_state")),
                r.getLong("price_fen"),r.getString("credits"),r.getBoolean("quote_current")),org.tenantId(),org.id(),org.actorId(),org.postId());
        String name=jdbc.queryForObject("SELECT name FROM system_dept WHERE tenant_id=? AND id=?",String.class,org.tenantId(),org.id());
        String reason="";
        if (!permissions.has("platform:wallet:recharge")) reason="当前岗位仅可查看充值套餐";
        else if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM org_point_account WHERE org_id=? AND status='ACTIVE')",Boolean.class,org.id())))
            reason="当前公司积分池未启用";
        else if (!payments.companyPaymentsEnabled()) reason="公司积分支付暂未开放";
        return new Catalog(name,reason.isEmpty(),!reason.isEmpty(),reason,plans,orders.isEmpty()?null:orders.get(0));
    }
}
