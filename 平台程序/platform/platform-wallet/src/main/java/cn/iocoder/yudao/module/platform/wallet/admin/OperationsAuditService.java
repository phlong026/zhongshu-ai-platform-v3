package cn.iocoder.yudao.module.platform.wallet.admin;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.time.Instant;

@Service
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class OperationsAuditService {
    private static final Logger log = LoggerFactory.getLogger(OperationsAuditService.class);
    private final JdbcTemplate jdbc;
    public record Audit(String id, String resourceType, String resourceId, long resourceVersion, String action,
                        String actorId, String reason, String beforeJson, String afterJson, Instant createTime) { }
    public record Review(String id, String anomalyId, String actorId, String note, Instant createTime) { }
    public OperationsAuditService(DataSource source) { jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(10); }

    public void append(String type, String id, long version, String action, String reason, Object before, Object after) {
        long tenant = WalletOperationsService.requireTenant();
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("OPS_AUDIT_REQUIRES_CONFIG_TRANSACTION");
        String validatedReason = OperationsInput.text(reason, "变更原因", 512, true);
        try {
            jdbc.update("""
                INSERT INTO pf_ops_config_audit(id,tenant_id,resource_type,resource_id,resource_version,action,actor_id,reason,before_json,after_json)
                VALUES(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)
                """, IdWorker.getId(), tenant, type, id, version, action, SecurityFrameworkUtils.getLoginUserId(), validatedReason,
                    JsonUtils.toJsonString(before), JsonUtils.toJsonString(after));
        } catch (DataAccessException error) {
            log.error("[opsAudit][tenant={},type={},id={},version={}] 配置审计写入失败", tenant, type, id, version, error);
            throw error;
        }
    }
    public PageResult<Audit> page(int pageNo, int pageSize) {
        long tenant = WalletOperationsService.requireTenant();
        checkPage(pageNo,pageSize);
        try {
            long count=jdbc.queryForObject("SELECT count(*) FROM pf_ops_config_audit WHERE tenant_id=?",Long.class,tenant);
            var rows=jdbc.query("""
                SELECT id,resource_type,resource_id,resource_version,action,actor_id,reason,before_json,after_json,create_time
                FROM pf_ops_config_audit WHERE tenant_id=? ORDER BY create_time DESC,id DESC LIMIT ? OFFSET ?
                """, (rs,row) -> new Audit(rs.getString("id"),rs.getString("resource_type"),rs.getString("resource_id"),
                    rs.getLong("resource_version"),rs.getString("action"),rs.getString("actor_id"),rs.getString("reason"),
                    rs.getString("before_json"),rs.getString("after_json"),rs.getTimestamp("create_time").toInstant()),tenant,pageSize,(long)(pageNo-1)*pageSize);
            return new PageResult<>(rows,count);
        } catch(DataAccessException error) { log.error("[opsAudit][tenant={}] 配置审计查询失败",tenant,error); throw error; }
    }
    public String review(String anomalyId,String note) {
        long tenant=WalletOperationsService.requireTenant();
        long anomaly=WalletOperationsService.parseId(anomalyId,"异常编号无效");
        String text=OperationsInput.text(note,"核对说明",512,true);
        long id=IdWorker.getId();
        try {
            int inserted=jdbc.update("""
                INSERT INTO pf_ops_anomaly_review(id,tenant_id,anomaly_id,actor_id,note)
                SELECT ?,a.tenant_id,a.id,?,? FROM pf_payment_anomaly_audit a WHERE a.tenant_id=? AND a.id=?
                """,id,SecurityFrameworkUtils.getLoginUserId(),text,tenant,anomaly);
            if(inserted!=1) throw new ServiceException(404,"异常记录不存在");
            return Long.toString(id);
        } catch(DataAccessException error) { log.error("[opsReview][tenant={},anomaly={}] 核对说明写入失败",tenant,anomalyId,error); throw error; }
    }
    public PageResult<Review> reviews(String anomalyId,int pageNo,int pageSize) {
        long tenant=WalletOperationsService.requireTenant();
        long anomaly=WalletOperationsService.parseId(anomalyId,"异常编号无效");
        checkPage(pageNo,pageSize);
        try {
            if(jdbc.queryForObject("SELECT count(*) FROM pf_payment_anomaly_audit WHERE tenant_id=? AND id=?",Long.class,tenant,anomaly)!=1)
                throw new ServiceException(404,"异常记录不存在");
            long count=jdbc.queryForObject("SELECT count(*) FROM pf_ops_anomaly_review WHERE tenant_id=? AND anomaly_id=?",Long.class,tenant,anomaly);
            var rows=jdbc.query("""
                SELECT id,anomaly_id,actor_id,note,create_time FROM pf_ops_anomaly_review
                WHERE tenant_id=? AND anomaly_id=? ORDER BY create_time DESC,id DESC LIMIT ? OFFSET ?
                """,(rs,row)->new Review(rs.getString("id"),rs.getString("anomaly_id"),rs.getString("actor_id"),rs.getString("note"),rs.getTimestamp("create_time").toInstant()),tenant,anomaly,pageSize,(long)(pageNo-1)*pageSize);
            return new PageResult<>(rows,count);
        } catch(DataAccessException error) { log.error("[opsReview][tenant={},anomaly={}] 核对说明查询失败",tenant,anomalyId,error); throw error; }
    }
    private static void checkPage(int pageNo,int pageSize) {
        if(pageNo<1 || pageSize<1 || pageSize>100) throw new ServiceException(400,"分页范围无效");
    }
}
