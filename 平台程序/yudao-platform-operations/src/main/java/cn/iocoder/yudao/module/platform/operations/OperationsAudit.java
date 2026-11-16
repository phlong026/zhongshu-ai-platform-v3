package cn.iocoder.yudao.module.platform.operations;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class OperationsAudit {
    private final JdbcTemplate jdbc;
    public OperationsAudit(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public String hash(Object payload) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JsonUtils.toJsonString(payload).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    /** 必须在写事务中调用；相同请求仅确认一次，不同参数重用请求号受控拒绝。 */
    public boolean replay(OperationsGuard.Actor actor,long org,String action,String key,String hash) {
        OperationsGuard.text(key,100,"请求编号");
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",actor.tenantId()+":ops:"+key);
        var rows=jdbc.queryForList("SELECT org_id,actor_id,action,request_hash FROM pf_ops_action_audit WHERE tenant_id=? AND request_key=?",actor.tenantId(),key);
        if(rows.isEmpty())return false;
        var old=rows.get(0);
        if(((Number)old.get("org_id")).longValue()!=org||((Number)old.get("actor_id")).longValue()!=actor.userId()
            ||!action.equals(old.get("action"))||!hash.equals(old.get("request_hash")))throw new ServiceException(409,"请求编号已用于其他操作");
        return true;
    }
    public void append(OperationsGuard.Actor actor,long org,String action,String key,String hash,String reason,Object before,Object after) {
        OperationsGuard.text(reason,500,"操作原因");
        jdbc.update("INSERT INTO pf_ops_action_audit(id,tenant_id,org_id,actor_id,action,request_key,request_hash,reason,before_data,after_data) VALUES(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)",
            UUID.randomUUID().toString(),actor.tenantId(),org,actor.userId(),action,key,hash,reason.trim(),JsonUtils.toJsonString(before),JsonUtils.toJsonString(after));
    }
}
