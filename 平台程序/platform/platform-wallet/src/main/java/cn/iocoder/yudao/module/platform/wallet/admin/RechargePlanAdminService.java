package cn.iocoder.yudao.module.platform.wallet.admin;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.Map;
import java.util.function.Supplier;
import static cn.iocoder.yudao.module.platform.wallet.admin.OperationsInput.*;

@Service
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class RechargePlanAdminService {
    private static final Logger log = LoggerFactory.getLogger(RechargePlanAdminService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OperationsAuditService audit;
    public record Change(long expectedVersion,String name,long priceFen,long basePoints,long bonusPoints,
                         String status,int sort,String wxProductId,String reason) {
        public Change {
            integer(expectedVersion,"版本",0,MAX_INTEGER);
            name=text(name,"套餐名称",128,true);
            integer(priceFen,"价格",1,MAX_INTEGER);
            integer(basePoints,"基础点数",1,MAX_INTEGER);
            integer(bonusPoints,"赠送点数",0,MAX_INTEGER);
            integer(Math.addExact(basePoints,bonusPoints),"合计点数",1,MAX_INTEGER);
            if(!"ACTIVE".equals(status) && !"DISABLED".equals(status)) throw invalid("套餐状态无效");
            wxProductId=text(wxProductId,"微信商品编号",128,"ACTIVE".equals(status));
            reason=text(reason,"变更原因",512,true);
        }
        public static Change from(JsonNode body) {
            return new Change(integer(body,"expectedVersion",0,MAX_INTEGER),text(body,"name",128,true),
                    integer(body,"priceFen",1,MAX_INTEGER),integer(body,"basePoints",1,MAX_INTEGER),integer(body,"bonusPoints",0,MAX_INTEGER),
                    text(body,"status",16,true),(int)integer(body,"sort",Integer.MIN_VALUE,Integer.MAX_VALUE),
                    text(body,"wxProductId",128,false),text(body,"reason",512,true));
        }
    }
    public RechargePlanAdminService(DataSource source, PlatformTransactionManager transactions, OperationsAuditService audit) {
        jdbc=new JdbcTemplate(source); jdbc.setQueryTimeout(10);
        tx=new TransactionTemplate(transactions); tx.setTimeout(10);
        this.audit=audit;
    }
    public WalletOperationsService.Plan create(Change change) {
        long tenant=WalletOperationsService.requireTenant();
        if(change.expectedVersion()!=0) throw invalid("新套餐版本必须为0");
        return execute(tenant,"create",()->{
            long id=IdWorker.getId();
            jdbc.update("""
                INSERT INTO pf_recharge_plan(id,tenant_id,name,price_fen,base_points,bonus_points,version,status,sort,wx_product_id)
                VALUES(?,?,?,?,?,?,1,?,?,?)
                """,id,tenant,change.name(),change.priceFen(),change.basePoints(),change.bonusPoints(),change.status(),change.sort(),change.wxProductId());
            var result=read(tenant,id);
            audit.append("RECHARGE_PLAN",result.id(),1,"CREATE",change.reason(),Map.of(),result);
            return result;
        });
    }
    public WalletOperationsService.Plan update(String planId,Change change) {
        long tenant=WalletOperationsService.requireTenant();
        long id=WalletOperationsService.parseId(planId,"套餐编号无效");
        if(change.expectedVersion()==0) throw invalid("更新套餐必须提供已有版本");
        return execute(tenant,"update:"+planId,()->{
            var before=read(tenant,id);
            if(before.version()!=change.expectedVersion()) throw new ServiceException(409,"套餐版本已变化，请刷新后重试");
            long version=nextVersion(before.version());
            int changed=jdbc.update("""
                UPDATE pf_recharge_plan SET name=?,price_fen=?,base_points=?,bonus_points=?,version=?,status=?,sort=?,wx_product_id=?,update_time=now()
                WHERE tenant_id=? AND id=? AND version=?
                """,change.name(),change.priceFen(),change.basePoints(),change.bonusPoints(),version,change.status(),change.sort(),change.wxProductId(),tenant,id,change.expectedVersion());
            if(changed!=1) throw new ServiceException(409,"套餐版本冲突");
            var result=read(tenant,id);
            audit.append("RECHARGE_PLAN",result.id(),version,"UPDATE",change.reason(),before,result);
            return result;
        });
    }
    private WalletOperationsService.Plan read(long tenant,long id) {
        var rows=jdbc.query("SELECT id,name,price_fen,base_points,bonus_points,version,status,sort,wx_product_id FROM pf_recharge_plan WHERE tenant_id=? AND id=? FOR UPDATE",
                (rs,row)->new WalletOperationsService.Plan(rs.getString("id"),rs.getString("name"),rs.getLong("price_fen"),rs.getLong("base_points"),
                        rs.getLong("bonus_points"),rs.getLong("version"),rs.getString("status"),rs.getInt("sort"),rs.getString("wx_product_id")),tenant,id);
        if(rows.isEmpty()) throw new ServiceException(404,"套餐不存在");
        return rows.get(0);
    }
    private <T> T execute(long tenant,String operation,Supplier<T> action) {
        try { return tx.execute(status->action.get()); }
        catch(DataAccessException error) { log.error("[rechargePlanAdmin][tenant={},operation={}] 套餐配置事务失败",tenant,operation,error); throw error; }
    }
}
