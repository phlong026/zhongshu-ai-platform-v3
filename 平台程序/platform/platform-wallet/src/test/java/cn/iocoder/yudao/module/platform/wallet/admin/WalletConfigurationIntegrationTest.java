package cn.iocoder.yudao.module.platform.wallet.admin;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/platform-wallet")
class WalletConfigurationIntegrationTest extends PlatformPgIntegrationTest {
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager manager;
    private JdbcTemplate jdbc;
    private RechargePlanAdminService service;
    private OperationsAuditService audit;
    private long tenant;
    @BeforeEach void setUp() {
        jdbc=new JdbcTemplate(source);
        tenant=IdWorker.getId();
        login(tenant);
        audit=new OperationsAuditService(source);
        service=new RechargePlanAdminService(source,manager,audit);
    }
    @AfterEach void clear() { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }
    private void login(long owner) {
        TenantContextHolder.setTenantId(owner);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser().setId(99L).setTenantId(owner).setUserType(2),null,List.of()));
    }
    private RechargePlanAdminService.Change change(long version,long price) {
        return new RechargePlanAdminService.Change(version,"本地测试套餐",price,10,2,"ACTIVE",1,"product-test","测试配置变更");
    }
    @Test void versionedWriteIsAuditedAndStaleOrForeignWritesAreRejected() {
        var plan=service.create(change(0,199));
        assertThat(plan.version()).isEqualTo(1);
        var updated=service.update(plan.id(),change(1,299));
        assertThat(updated.version()).isEqualTo(2);
        assertThatThrownBy(() -> service.update(plan.id(),change(1,399))).hasMessageContaining("版本");
        assertThatThrownBy(() -> service.update(plan.id(),change(0,399))).hasMessageContaining("版本");
        assertThat(audit.page(1,20).getList()).hasSize(2);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM pf_ops_config_audit WHERE tenant_id=?",tenant)).hasMessageContaining("append-only");
        login(tenant+1);
        assertThatThrownBy(() -> service.update(plan.id(),change(2,399))).hasMessageContaining("不存在");
        assertThat(audit.page(1,20).getList()).isEmpty();
    }
    @Test void auditFailureRollsBackConfigurationVersionAndValue() {
        var plan=service.create(change(0,199));
        jdbc.update("""
            INSERT INTO pf_ops_config_audit(id,tenant_id,resource_type,resource_id,resource_version,action,actor_id,reason,before_json,after_json)
            VALUES(?,?,'RECHARGE_PLAN',?,2,'UPDATE',99,'audit conflict fixture','{}','{}')
            """,IdWorker.getId(),tenant,plan.id());
        assertThatThrownBy(() -> service.update(plan.id(),change(1,299))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT version FROM pf_recharge_plan WHERE tenant_id=? AND id=?",Long.class,tenant,Long.valueOf(plan.id()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT price_fen FROM pf_recharge_plan WHERE tenant_id=? AND id=?",Long.class,tenant,Long.valueOf(plan.id()))).isEqualTo(199);
    }
    @Test void simultaneousUpdatesHaveOneWinnerAndOneConflict() throws Exception {
        var plan=service.create(change(0,199));
        var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var call=(java.util.concurrent.Callable<Boolean>) () -> {
                login(tenant);
                try {
                    if (!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    service.update(plan.id(),change(1,299));
                    return true;
                } catch(cn.iocoder.yudao.framework.common.exception.ServiceException error) {
                    assertThat(error.getCode()).isEqualTo(409);
                    return false;
                } finally { clear(); }
            };
            var first=pool.submit(call); var second=pool.submit(call); start.countDown();
            assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(audit.page(1,20).getList()).hasSize(2);
        } finally { pool.shutdownNow(); }
    }
    @Test void strictJsonRejectsMissingCoercedFractionalAndUnsafeNumbers() throws Exception {
        var mapper=new ObjectMapper();
        for(String value:List.of("1.5","\"199\"","9007199254740992","null")) {
            var json=mapper.readTree("{\"expectedVersion\":0,\"name\":\"测试\",\"priceFen\":"+value+",\"basePoints\":10,\"bonusPoints\":0,\"status\":\"DISABLED\",\"sort\":0,\"reason\":\"测试\"}");
            assertThatThrownBy(() -> RechargePlanAdminService.Change.from(json)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        }
        assertThatThrownBy(() -> service.create(change(1,199))).hasMessageContaining("版本");
        assertThatThrownBy(() -> service.create(new RechargePlanAdminService.Change(0,"x",199,9007199254740991L,1,"DISABLED",0,null,"测试"))).hasMessageContaining("点数");
        assertThatThrownBy(() -> service.create(new RechargePlanAdminService.Change(0,"x",199,1,0,"ACTIVE",0,null,"测试"))).hasMessageContaining("商品");
    }
}
