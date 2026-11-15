package cn.iocoder.yudao.module.franchise.commission;

import cn.hutool.core.util.IdUtil;
import cn.iocoder.yudao.module.franchise.api.*;
import cn.iocoder.yudao.module.franchise.finance.*;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.commission.enums.FranchiseCommissionErrorCodes.COMMISSION_STATE_INVALID;

/** Legacy state-machine regressions now use real net settlement; only external asset bytes are stubbed. */
@Import({DesignPricingService.class,CommissionManifestService.class,CommissionQueryAdapter.class,
        DesignFeeSettlementAdapter.class,DesignFinanceLocks.class,CommissionPgIntegrationTest.Boundaries.class})
abstract class CommissionPgIntegrationTest extends FranchisePgIntegrationTest {
    @TestConfiguration static class Boundaries {
        @Bean AssetAccessPort assets() {
            var port=org.mockito.Mockito.mock(AssetAccessPort.class);
            org.mockito.Mockito.when(port.validateReference(org.mockito.ArgumentMatchers.any(AssetAccessPort.Actor.class),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anySet())).thenReturn(true);
            return port;
        }
        @Bean StoreServiceAccountPort serviceAccounts() { return org.mockito.Mockito.mock(StoreServiceAccountPort.class); }
        @Bean EngineeringProjectContextPort engineeringScope() { return (company,commission)->null; }
    }
    @Resource private JdbcTemplate settlementJdbc;
    protected static String commandKey() { return java.util.UUID.randomUUID().toString(); }
    protected void seedFirst(long id,int version,PostSnapshot actor) { seedPaid(id,version,"FIRST",true); }
    protected void seedTailPaid(long id,int version,PostSnapshot actor) { seedPaid(id,version,"TAIL",false); }
    protected void seedTailReady(long id,int version,PostSnapshot actor) { seedPaid(id,version,"TAIL",true); }
    private void seedPaid(long id,int version,String stage,boolean receive) {
        var jdbc=settlementJdbc;
        int current=jdbc.queryForObject("SELECT submission_version FROM fr_commission WHERE id=?",Integer.class,id);
        if(current!=version) throw exception(COMMISSION_STATE_INVALID);
        String ref=id+":"+version+":"+stage;
        var p=jdbc.queryForMap("SELECT id,company_id,total_cents FROM fr_fin_payable WHERE source_type='design' AND source_ref=?",ref);
        long payable=((Number)p.get("id")).longValue(),company=((Number)p.get("company_id")).longValue(),amount=((Number)p.get("total_cents")).longValue();
        var payments=jdbc.queryForList("SELECT id FROM fr_fin_payment WHERE payable_id=?",Long.class,payable);
        long payment=payments.isEmpty()?IdUtil.getSnowflakeNextId():payments.get(0);
        if(payments.isEmpty()) jdbc.update("INSERT INTO fr_fin_payment(id,payable_id,company_id,amount_cents,status,operator_user_id) VALUES(?,?,?,?,'已核实',1)",payment,payable,company,amount);
        if(receive && jdbc.queryForObject("SELECT count(*) FROM fr_fin_receipt WHERE source_type='design' AND source_ref=?",Long.class,ref)==0) {
            long org=jdbc.queryForObject("SELECT design_org_id FROM fr_commission WHERE id=?",Long.class,id);
            jdbc.update("INSERT INTO fr_fin_receipt(id,company_id,source_type,source_ref,origin_payment_id,name,amount_cents,status,registrar_user_id) VALUES(?,?,'design',?,?,'TEST ONLY设计实收',?,'已登记',1)",IdUtil.getSnowflakeNextId(),org,ref,payment,amount);
        }
    }
    protected void seedManifest(long id,int seq,String drawingVersion) {
        settlementJdbc.update("INSERT INTO fr_commission_dispatch_manifest(id,commission_id,dispatch_seq,submission_version,design_org_id,result_asset_id,drawing_asset_id,review_asset_id,drawing_version,actor_user_id,actor_post_id) SELECT ?,id,?,submission_version,design_org_id,101,102,103,?,1,1 FROM fr_commission WHERE id=?",IdUtil.getSnowflakeNextId(),seq,drawingVersion,id);
    }
}
