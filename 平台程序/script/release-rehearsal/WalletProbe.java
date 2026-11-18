package cn.iocoder.yudao.module.platform.wallet.orgpoint;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort.ReserveCommand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;

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
        var first = points.purchaseSimulated(1,org,711,811,plan,1,"rehearsal-replay");
        var replay = points.purchaseSimulated(1,org,711,811,plan,1,"rehearsal-replay");
        if (!first.equals(replay) || points.findAccount(org).balance()!=0L) throw new AssertionError("Formal balance changed");
        if (jdbc.queryForObject("SELECT sum(test_points) FROM org_point_simulated_purchase WHERE org_id=?",Long.class,org)!=120L)
            throw new AssertionError("Wrong simulated balance");
        if (jdbc.queryForObject("SELECT sum(price_fen) FROM org_point_simulated_purchase WHERE org_id=?",Long.class,org)!=990L)
            throw new AssertionError("Wrong simulated revenue");
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
