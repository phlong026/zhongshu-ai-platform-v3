package cn.iocoder.yudao.module.platform.wallet.orgpoint;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort.ReserveCommand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class WalletProbe {
    public static void main(String[] args) throws Exception {
        var ds = new DriverManagerDataSource("jdbc:postgresql://postgres-b:5432/zhongshu_platform_b",
                "platform_app", Files.readString(Path.of("/fixture-pass")).trim());
        var tx = new DataSourceTransactionManager(ds);
        var points = new OrgPointService(ds, tx, message -> 0L, new OrgPointPriceService(ds, tx));
        var jdbc = new JdbcTemplate(ds);
        TenantContextHolder.setTenantId(1L);
        long org = 981234L, plan = 981235L;
        points.ensurePoolAccount(org);
        jdbc.update("INSERT INTO pf_recharge_plan(id,tenant_id,name,price_fen,base_points,bonus_points,status) VALUES (?,1,'演练',990,100,20,'ACTIVE')", plan);
        if (!points.simulatedPackages(1,org).isEmpty()) throw new AssertionError("Unconfigured simulation enabled");
        points.configurePurchaseOrgs(Long.toString(org));
        if (!points.simulatedPackages(1,org).isEmpty()) throw new AssertionError("Retired simulation was re-enabled");
        var before = points.findAccount(org);
        long receipts = jdbc.queryForObject("SELECT count(*) FROM org_point_simulated_purchase WHERE org_id=?",Long.class,org);
        long ledger = jdbc.queryForObject("SELECT count(*) FROM org_point_ledger WHERE org_id=?",Long.class,org);
        for (String key : List.of("rehearsal-replay", "rehearsal-replay", "rehearsal-new")) {
            try {
                points.purchaseSimulated(1,org,711,811,plan,1,key);
                throw new AssertionError("Retired simulated purchase succeeded");
            } catch (ServiceException expected) {
                if (expected.getCode()!=403) throw expected;
            }
            if (!before.equals(points.findAccount(org))
                    || jdbc.queryForObject("SELECT count(*) FROM org_point_simulated_purchase WHERE org_id=?",Long.class,org)!=receipts
                    || jdbc.queryForObject("SELECT count(*) FROM org_point_ledger WHERE org_id=?",Long.class,org)!=ledger)
                throw new AssertionError("Rejected purchase changed the balance or created a receipt/ledger row");
        }
        // run.py already migrated this reviewed historical receipt: 100 formal + 105 isolated test points.
        // A charge above the formal amount but within their sum must still be rejected.
        var legacy = points.findAccount(118L);
        long testPoints = jdbc.queryForObject("SELECT sum(test_points) FROM org_point_simulated_purchase WHERE org_id=118",Long.class);
        long required = points.quoteCredits("digital",3000);
        if (legacy.balance()!=100L || testPoints!=105L || required<=legacy.balance() || required>legacy.balance()+testPoints)
            throw new AssertionError("Historical isolation fixture does not exercise the combined-balance boundary");
        try {
            points.reserve(new ReserveCommand("rehearsal-legacy-isolation",118L,711,811L,"digital",3000,"content_task",plan));
            throw new AssertionError("Historical simulated points funded a formal task");
        } catch (ServiceException expected) {
            if (expected.getCode()!=1072000011) throw expected;
        }
        if (!legacy.equals(points.findAccount(118L))
                || jdbc.queryForObject("SELECT sum(test_points) FROM org_point_simulated_purchase WHERE org_id=118",Long.class)!=testPoints)
            throw new AssertionError("Rejected reservation changed historical balances");
        var task = new ReserveCommand("rehearsal-task",org,711,811L,"digital",300,"content_task",plan);
        try {
            points.reserve(task);
            throw new AssertionError("Simulation funded a formal task");
        } catch (ServiceException expected) {
            if (expected.getCode()!=1072000011) throw expected;
        }
        points.adjustBalance(org,120,"rehearsal-formal","正式来源演练",711L);
        points.reserve(task);
        if (points.findAccount(org).balance()!=105L) throw new AssertionError("Formal positive control failed");
    }
}
