package cn.iocoder.yudao.module.platform.wallet;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.authz.PostPermissionEvaluator;
import cn.iocoder.yudao.module.platform.authz.controller.admin.FranchiseMapController;
import cn.iocoder.yudao.module.platform.authz.service.FranchiseMapService;
import cn.iocoder.yudao.module.platform.authz.service.FranchiseDirectoryService;
import cn.iocoder.yudao.module.platform.authz.service.FranchiseOnboardService;
import cn.iocoder.yudao.module.platform.authz.service.FranchiseRegionCatalog;
import cn.iocoder.yudao.module.platform.org.*;
import cn.iocoder.yudao.module.platform.wallet.admin.*;
import cn.iocoder.yudao.module.platform.wallet.controller.admin.CompanyRechargeController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** Loopback transport for the actual mini-program page, Spring MVC handlers and a disposable PostgreSQL database. */
final class CompanyServicesHttpFixture {
    private final JdbcTemplate jdbc;
    private final RechargePaymentService payment;
    private final PaymentPort channel;
    private final WalletRecoveryJob recovery;
    private final ThreadLocal<PostSnapshot> current;
    private final long tenant;
    private final PostSnapshot alpha,beta,hq;
    private final MockMvc mvc;
    private final ObjectMapper json=new ObjectMapper();
    private long orderId;

    CompanyServicesHttpFixture(JdbcTemplate jdbc, PlatformTransactionManager manager, WalletScope scope,
            RechargePaymentService payment,PaymentPort channel,ThreadLocal<PostSnapshot> current,long tenant,PostSnapshot alpha,PostSnapshot peer,PostSnapshot beta) {
        this.jdbc=jdbc; this.payment=payment; this.current=current; this.tenant=tenant; this.alpha=alpha; this.beta=beta;
        this.channel=channel;
        var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("channel",channel);
        var source=jdbc.getDataSource();
        var refunds=new RefundPaymentService(source,manager,scope,new PointAccountService(source),payment,new PaymentFactValidator());
        this.recovery=new WalletRecoveryJob(source,manager,payment,refunds,new WechatXPayProperties(),beans.getBeanProvider(PaymentPort.class));
        this.hq=new PostSnapshot(peer.postId(),peer.userId(),peer.orgId(),"headquarters","boss",null,false,Set.of());
        jdbc.update("INSERT INTO pf_org_type(code,domain,name) VALUES('store','test','门店') ON CONFLICT DO NOTHING");
        seedStore(alpha,"品牌甲","余杭","甲一店"); seedStore(peer,"品牌甲","西湖","甲二店"); seedStore(beta,"品牌乙","余杭","乙一店");
        var directory=mock(SystemDirectory.class);
        when(directory.roleHasPermission("boss",FranchiseMapService.READ_ALL)).thenReturn(true);
        when(directory.roleHasPermission("owner","platform:wallet:recharge")).thenReturn(true);
        var permissions=new PostPermissionEvaluator(()->Optional.ofNullable(current.get()),directory);
        var tx=new TransactionTemplate(manager);
        var catalog=new CompanyRechargeCatalog(jdbc,scope,permissions,payment) {
            @Override public Catalog read() { return tx.execute(s->super.read()); }
        };
        var regions=new FranchiseRegionCatalog(jdbc);
        var franchiseDirectory=new FranchiseDirectoryService(jdbc,regions,mock(FranchiseOnboardService.class),manager);
        var maps=new FranchiseMapService(()->Optional.ofNullable(current.get()),jdbc,franchiseDirectory,regions);
        var audit=new OperationsAuditService(jdbc.getDataSource());
        var admin=new WalletConfigurationController(new RechargePlanAdminService(jdbc.getDataSource(),manager,audit),audit);
        mvc=MockMvcBuilders.standaloneSetup(new CompanyRechargeController(catalog,payment),new FranchiseMapController(maps),admin).build();
    }

    private void seedStore(PostSnapshot p,String brand,String district,String name) {
        jdbc.update("UPDATE pf_org_unit SET org_type='store' WHERE dept_id=?",p.orgId());
        jdbc.update("UPDATE system_dept SET name=? WHERE id=?",name,p.orgId());
        jdbc.update("""
            INSERT INTO pf_franchise_account(account_id,tenant_id,org_id,user_id,login_name,org_name,owner_name,brand,province,city,area,area_pinyin,owner_pinyin,created_by)
            VALUES(?,?,?,?,?,?,'示例',?,'浙江','杭州',?,'area','owner',1)
            """,UUID.randomUUID().toString(),tenant,p.orgId(),p.userId(),"b-"+p.orgId(),name,brand,district);
    }

    void run(long planId,String runner) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",this::handle);
        server.start();
        Process child=null;
        var log=java.nio.file.Files.createTempFile("franchise-ui-", ".log");
        try {
            child=new ProcessBuilder("node",runner,"http://127.0.0.1:"+server.getAddress().getPort(),Long.toString(planId))
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertTrue(child.waitFor(150,TimeUnit.SECONDS),"front-end integration timed out");
            String output=java.nio.file.Files.readString(log);
            assertEquals(0,child.exitValue(),output);
            System.out.print(output);
            assertEquals(20L,jdbc.queryForObject("SELECT balance FROM org_point_account WHERE org_id=?",Long.class,alpha.orgId()));
            assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM org_point_ledger WHERE ref_id=? AND type='COMPANY_RECHARGE'",Long.class,orderId));
        } finally {
            if(child!=null && child.isAlive()) child.destroyForcibly();
            server.stop(0);
            java.nio.file.Files.deleteIfExists(log);
        }
    }

    private void handle(HttpExchange exchange) {
        try(exchange) {
            var path=exchange.getRequestURI().getPath();
            String auth=Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"),"");
            var actor=switch(auth) {case "Bearer alpha"->alpha; case "Bearer beta"->beta; case "Bearer hq"->hq; default->null;};
            if(actor==null || ((path.startsWith("/admin-api/platform/ops")||path.startsWith("/fixture/")) && actor!=hq)) {
                exchange.sendResponseHeaders(403,-1); return;
            }
            current.set(actor); TenantContextHolder.setTenantId(tenant);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    new LoginUser().setId(actor.userId()).setTenantId(tenant).setUserType(2),null,List.of()));
            int status=200; byte[] body;
            try {
                if(path.equals("/fixture/paid")) {
                    var row=jdbc.queryForMap("SELECT * FROM pf_recharge_order WHERE tenant_id=? AND org_id=? AND credit_target='COMPANY' AND payment_state IN ('PENDING','SUCCEEDED')",tenant,alpha.orgId());
                    orderId=((Number)row.get("id")).longValue();
                    long wallet=((Number)row.get("wallet_id")).longValue();
                    var fact=new PaymentPort.ChannelQueryResult("SUCCEEDED",(String)row.get("order_no"),"XPAY","wallet-app","wallet-offer",0,
                            (String)row.get("payer_openid"),"integration-"+orderId,((Number)row.get("price_fen")).longValue(),"FEN",Instant.now(),0);
                    if ("SUCCEEDED".equals(row.get("payment_state"))) {
                        payment.processPaymentFact(tenant,wallet,orderId,fact);
                    } else {
                        doReturn(fact).when(channel).queryOrder(argThat(identity -> identity!=null && identity.orderId()==orderId));
                    }
                    recovery.runOnce(100);
                    recovery.runOnce(100);
                    body=json.writeValueAsBytes(Map.of("code",0,"data",true));
                } else {
                    var req=request(HttpMethod.valueOf(exchange.getRequestMethod()),exchange.getRequestURI())
                            .contentType("application/json").content(exchange.getRequestBody().readAllBytes());
                    var key=exchange.getRequestHeaders().getFirst("Idempotency-Key");
                    if(key!=null) req.header("Idempotency-Key",key);
                    var res=mvc.perform(req).andReturn().getResponse();status=res.getStatus();body=res.getContentAsByteArray();
                }
            } catch(Exception e) {
                Throwable cause=e;
                while(cause.getCause()!=null && !(cause instanceof ServiceException)) cause=cause.getCause();
                if(!(cause instanceof ServiceException)) cause.printStackTrace();
                body=json.writeValueAsBytes(Map.of("code",cause instanceof ServiceException s ? s.getCode():500,"msg",Objects.toString(cause.getMessage(),"error")));
            }
            exchange.getResponseHeaders().add("Content-Type","application/json;charset=utf-8");
            exchange.sendResponseHeaders(status,body.length); exchange.getResponseBody().write(body);
        } catch(Exception e) { throw new RuntimeException(e); }
        finally { current.remove(); TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }
    }
}
