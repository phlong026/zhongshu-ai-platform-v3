package cn.iocoder.yudao.module.franchise.design.admin;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Operation;
import cn.iocoder.yudao.module.platform.wallet.admin.OperationsAuditService;
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
import java.util.List;
import java.util.Map;
import static cn.iocoder.yudao.module.platform.wallet.admin.OperationsInput.*;

@Service
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class DesignPriceAdminService {
    private static final Logger log=LoggerFactory.getLogger(DesignPriceAdminService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OperationsAuditService audit;
    public record Change(long expectedVersion,String operation,String resolution,long promptPoints,long imagePoints,
                         long usagePoints,int maxImageUnits,boolean active,String reason) {
        public Change {
            integer(expectedVersion,"版本",0,MAX_INTEGER);
            Operation op;
            try { op=Operation.valueOf(operation); } catch(RuntimeException error) { throw invalid("设计计费能力无效"); }
            if(!op.paid()) throw invalid("不是可计费设计能力");
            resolution=resolution==null?"":resolution.trim();
            integer(promptPoints,"提示词点数",0,MAX_INTEGER);
            integer(imagePoints,"图片点数",0,MAX_INTEGER);
            integer(usagePoints,"调用点数",0,MAX_INTEGER);
            if(generation(op)) {
                if(!List.of("2K","4K").contains(resolution)) throw invalid("分辨率必须为2K或4K");
                integer(maxImageUnits,"最多图片数",1,8);
                if(usagePoints!=0) throw invalid("生图能力不能配置按次调用点数");
                integer(Math.addExact(promptPoints,Math.multiplyExact(imagePoints,maxImageUnits)),"最大点数",0,MAX_INTEGER);
            } else {
                if(!resolution.isEmpty()) throw invalid("此能力不接受分辨率");
                if(promptPoints!=0 || imagePoints!=0 || maxImageUnits!=0) throw invalid("此能力只配置按次调用点数");
            }
            reason=text(reason,"变更原因",512,true);
        }
        public static Change from(JsonNode body) {
            return new Change(integer(body,"expectedVersion",0,MAX_INTEGER),text(body,"operation",64,true),
                    text(body,"resolution",8,false),integer(body,"promptPoints",0,MAX_INTEGER),integer(body,"imagePoints",0,MAX_INTEGER),
                    integer(body,"usagePoints",0,MAX_INTEGER),(int)integer(body,"maxImageUnits",0,8),bool(body,"active"),text(body,"reason",512,true));
        }
    }
    public DesignPriceAdminService(DataSource source,PlatformTransactionManager manager,OperationsAuditService audit) {
        jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(10);
        tx=new TransactionTemplate(manager);tx.setTimeout(10);this.audit=audit;
    }
    public DesignOperationsService.Price create(Change change) {
        if(change.expectedVersion()!=0) throw invalid("新价目版本必须为0");
        return publish(null,change);
    }
    public DesignOperationsService.Price update(String id,Change change) {
        if(change.expectedVersion()==0) throw invalid("更新价目必须提供已有版本");
        try { if(Long.parseLong(id)<=0 || !Long.toString(Long.parseLong(id)).equals(id)) throw new NumberFormatException(); }
        catch(NumberFormatException error) { throw invalid("价目编号无效"); }
        return publish(id,change);
    }
    private DesignOperationsService.Price publish(String id,Change change) {
        long tenant=DesignOperationsService.requireTenant();
        try {
            return tx.execute(status->{
                // 锁键与唯一业务维度相同，串行首次创建和后续版本发布；不使用客户端任意锁键。
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(rs,row)->1,
                        "platform-price:"+tenant+":"+change.operation()+":"+change.resolution());
                var versions=jdbc.query("""
                    SELECT id,version,operation,resolution,unit,prompt_points,image_points,usage_points,max_image_units,active
                    FROM fr_design_price WHERE tenant_id=? AND operation=? AND resolution=? ORDER BY version DESC LIMIT 1 FOR UPDATE
                    """,(rs,row)->new DesignOperationsService.Price(rs.getString("id"),rs.getLong("version"),rs.getString("operation"),
                        rs.getString("resolution"),rs.getString("unit"),rs.getLong("prompt_points"),rs.getLong("image_points"),rs.getLong("usage_points"),
                        rs.getInt("max_image_units"),rs.getBoolean("active")),tenant,change.operation(),change.resolution());
                DesignOperationsService.Price before=versions.isEmpty()?null:versions.get(0);
                if(id!=null && (before==null || !id.equals(before.id()))) throw new ServiceException(404,"价目不存在");
                if((before==null?0:before.version())!=change.expectedVersion()) throw new ServiceException(409,"价目版本已变化，请刷新后重试");
                long next=nextVersion(change.expectedVersion());
                long priceId=before==null?IdWorker.getId():Long.parseLong(before.id());
                if(before!=null) {
                    int changed=jdbc.update("UPDATE fr_design_price SET active=FALSE WHERE tenant_id=? AND id=? AND version=?",tenant,priceId,before.version());
                    if(changed!=1) throw new ServiceException(409,"价目版本冲突");
                }
                boolean generation=generation(Operation.valueOf(change.operation()));
                jdbc.update("""
                    INSERT INTO fr_design_price(tenant_id,id,version,operation,resolution,unit,prompt_points,image_points,usage_points,max_prompt_units,max_image_units,max_usage_units,active)
                    VALUES(?,?,?,?,?,'B_WALLET_POINTS',?,?,?,?,?,?,?)
                    """,tenant,priceId,next,change.operation(),change.resolution(),change.promptPoints(),change.imagePoints(),change.usagePoints(),
                        generation?1:0,change.maxImageUnits(),generation?0:1,change.active());
                var result=new DesignOperationsService.Price(Long.toString(priceId),next,change.operation(),change.resolution(),"B_WALLET_POINTS",
                        change.promptPoints(),change.imagePoints(),change.usagePoints(),change.maxImageUnits(),change.active());
                audit.append("DESIGN_PRICE",result.id(),next,before==null?"CREATE":"UPDATE",change.reason(),before==null?Map.of():before,result);
                return result;
            });
        } catch(DataAccessException error) { log.error("[designPriceAdmin][tenant={},operation={},resolution={}] 价目发布事务失败",tenant,change.operation(),change.resolution(),error);throw error; }
    }
    private static boolean generation(Operation op) {
        return op==Operation.CREATE_FLAT_JOB || op==Operation.CREATE_ELEVATION_JOB || op==Operation.CREATE_REVISION_REQUEST;
    }
}
