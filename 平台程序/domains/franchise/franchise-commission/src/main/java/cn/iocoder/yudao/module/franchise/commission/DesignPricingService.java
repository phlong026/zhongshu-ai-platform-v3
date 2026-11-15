package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.hutool.core.util.IdUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.commission.enums.FranchiseCommissionErrorCodes.*;

/** Headquarters area pricing is independent of a store boss's historical split policy. */
@Service
public class DesignPricingService {
    public record Policy(long id, int version, long rateCents, String scope, String recipient,
                         boolean active, OffsetDateTime createTime) { }
    public record Save(long rateCents, String scope, String recipient, boolean active) { }
    private final JdbcTemplate jdbc;
    private final FranchiseCommands commands;
    public DesignPricingService(JdbcTemplate jdbc, FranchiseCommands commands) { this.jdbc=jdbc; this.commands=commands; }

    public Policy current(long designOrgId) {
        List<Policy> rows=jdbc.query("SELECT * FROM fr_design_pricing_policy WHERE design_org_id=? ORDER BY version DESC LIMIT 1",
                (rs,n)->new Policy(rs.getLong("id"),rs.getInt("version"),rs.getLong("rate_cents"),rs.getString("scope"),
                        rs.getString("recipient"),rs.getBoolean("active"),rs.getObject("create_time",OffsetDateTime.class)),designOrgId);
        return rows.isEmpty() || !rows.get(0).active() ? null : rows.get(0);
    }
    public Policy current(PostSnapshot post) { requireAdmin(post); return current(post.orgId()); }
    @Transactional(rollbackFor=Exception.class)
    public Policy save(PostSnapshot post, Save input, String key) {
        requireAdmin(post);
        if(input.rateCents()<=0 || input.rateCents()>DesignPolicyService.TOTAL_MAX_CENTS || input.scope()==null
                || input.scope().isBlank() || input.scope().length()>500 || input.recipient()==null
                || input.recipient().isBlank() || input.recipient().length()>64) throw exception(POLICY_INPUT_INVALID);
        key=commands.requireIdempotencyKey(key);
        long commandLock=cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys.lockKey(
                new cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys.PostKeyScope("franchise",post.userId()),key);
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(?)) s",Integer.class,commandLock);
        var replay=commands.replayed(post,key,input);
        if(replay.isPresent()) return jdbc.queryForObject("SELECT * FROM fr_design_pricing_policy WHERE id=? AND design_org_id=?",
                (rs,n)->new Policy(rs.getLong("id"),rs.getInt("version"),rs.getLong("rate_cents"),rs.getString("scope"),
                        rs.getString("recipient"),rs.getBoolean("active"),rs.getObject("create_time",OffsetDateTime.class)),replay.get().getAggregateId(),post.orgId());
        lock("design-pricing:"+post.orgId());
        Integer version=jdbc.queryForObject("SELECT coalesce(max(version),0)+1 FROM fr_design_pricing_policy WHERE design_org_id=?",Integer.class,post.orgId());
        long id=IdUtil.getSnowflakeNextId();
        jdbc.update("INSERT INTO fr_design_pricing_policy(id,design_org_id,version,rate_cents,scope,recipient,active,actor_user_id,actor_post_id) VALUES(?,?,?,?,?,?,?,?,?)",
                id,post.orgId(),version,input.rateCents(),input.scope().trim(),input.recipient().trim(),input.active(),post.userId(),post.postId());
        commands.record(post,"design-pricing-policy",id,version,"design-pricing-save",input,key);
        return new Policy(id,version,input.rateCents(),input.scope().trim(),input.recipient().trim(),input.active(),OffsetDateTime.now());
    }
    public static long totalCents(BigDecimal area,long rateCents) {
        if(area==null || area.signum()<=0 || rateCents<=0) throw exception(SUBMISSION_INPUT_INVALID);
        try {
            long total=area.multiply(BigDecimal.valueOf(rateCents)).setScale(0,RoundingMode.HALF_UP).longValueExact();
            if(total<=0 || total>DesignPolicyService.TOTAL_MAX_CENTS) throw exception(SUBMISSION_AMOUNT_INVALID);
            return total;
        } catch(ArithmeticException ex) { throw exception(SUBMISSION_AMOUNT_INVALID); }
    }
    private static void requireAdmin(PostSnapshot post) {
        if(!FranchiseRoles.atHeadquarters(post) || !FranchiseRoles.DESIGN_ADMIN.equals(post.roleCode())) throw exception(COMMISSION_FORBIDDEN);
    }
    private void lock(String key) { jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?,0))) s",Integer.class,key); }
}
