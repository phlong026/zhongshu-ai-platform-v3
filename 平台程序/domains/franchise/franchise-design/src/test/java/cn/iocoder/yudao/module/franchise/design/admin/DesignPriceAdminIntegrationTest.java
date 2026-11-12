package cn.iocoder.yudao.module.franchise.design.admin;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import cn.iocoder.yudao.module.platform.wallet.admin.OperationsAuditService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
@TestPropertySource(properties="platform.test.extra-flyway-locations=,classpath:db/migration/platform-wallet,classpath:db/migration/franchise-design-remote")
class DesignPriceAdminIntegrationTest extends PlatformPgIntegrationTest {
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager manager;
    private JdbcTemplate jdbc; private DesignPriceAdminService service; private long tenant;
    @BeforeEach void init() {
        jdbc=new JdbcTemplate(source); tenant=IdWorker.getId(); login(tenant);
        service=new DesignPriceAdminService(source,manager,new OperationsAuditService(source));
    }
    private void login(long id) {
        TenantContextHolder.setTenantId(id);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser().setId(99L).setTenantId(id).setUserType(2),null,List.of()));
    }
    @AfterEach void clear(){ TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }
    private DesignPriceAdminService.Change change(long version,long image,boolean active) {
        return new DesignPriceAdminService.Change(version,"CREATE_FLAT_JOB","2K",2,image,0,8,active,"更新设计报价");
    }
    @Test void newVersionKeepsOldPriceAndDisabledVersionsRemainInCasHistory() {
        var first=service.create(change(0,3,true));
        var second=service.update(first.id(),change(1,7,false));
        assertThat(second.version()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT image_points FROM fr_design_price WHERE tenant_id=? AND id=? AND version=1",Long.class,tenant,Long.valueOf(first.id()))).isEqualTo(3);
        assertThatThrownBy(()->service.create(change(0,10,true))).hasMessageContaining("版本");
        assertThatThrownBy(()->service.update(first.id(),change(1,10,true))).hasMessageContaining("版本");
        assertThat(service.update(first.id(),change(2,9,true)).version()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_design_price WHERE tenant_id=? AND active",Long.class,tenant)).isEqualTo(1);
        assertThat(new OperationsAuditService(source).page(1,20).getList()).hasSize(3);
        login(tenant+1);
        assertThatThrownBy(()->service.update(first.id(),change(3,11,true))).hasMessageContaining("不存在");
    }
    @Test void concurrentFirstPublishHasOneWinner() throws Exception {
        var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            Callable<Boolean> action=()->{
                login(tenant);
                try {
                    if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("timeout");
                    service.create(change(0,3,true)); return true;
                }catch(cn.iocoder.yudao.framework.common.exception.ServiceException error){assertThat(error.getCode()).isEqualTo(409);return false;}
                finally{clear();}
            };
            var a=pool.submit(action);var b=pool.submit(action);start.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }finally{pool.shutdownNow();}
    }
    @Test void rejectNonbillableWrongResolutionAndUnsafeMaximum() {
        assertThatThrownBy(()->new DesignPriceAdminService.Change(0,"LIST_CASES","",0,0,0,0,true,"测试")).hasMessageContaining("计费");
        assertThatThrownBy(()->new DesignPriceAdminService.Change(0,"NOTE_POLISH","2K",0,0,1,0,true,"测试")).hasMessageContaining("分辨率");
        assertThatThrownBy(()->change(0,9007199254740991L,true)).hasMessageContaining("最大点数");
    }
}
