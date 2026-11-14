package cn.iocoder.yudao.module.franchise.design.remote;

import cn.iocoder.yudao.framework.common.exception.ServiceException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import javax.sql.DataSource;

/** 仅B持久化资产映射；事务不跨HTTP。租约令牌阻止过期执行者发布结果。 */
public final class ReferenceMirrorStore {
    public record Scope(
            String caller, long tenant, long org, long user, long post, long asset, String sha) {}

    public record Lease(
            Scope scope, String token, String attempt, boolean recovery, Long acceptedId) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ReferenceMirrorStore(DataSource source, PlatformTransactionManager transactions) {
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(15);
    }

    private Object[] key(Scope s) {
        return new Object[] {
            s.caller(), s.tenant(), s.org(), s.user(), s.post(), s.asset(), s.sha()
        };
    }

    private static final String WHERE =
            "caller_app_id=? AND tenant_id=? AND org_id=? AND user_id=? AND post_id=? AND"
                    + " source_asset_id=? AND source_sha256=?";

    public Lease claim(Scope s) {
        return tx.execute(
                status -> {
                    jdbc.update(
                            "INSERT INTO"
                                + " fr_design_reference_mirror(caller_app_id,tenant_id,org_id,user_id,post_id,source_asset_id,source_sha256,attempt_key)"
                                + " VALUES(?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                            s.caller(),
                            s.tenant(),
                            s.org(),
                            s.user(),
                            s.post(),
                            s.asset(),
                            s.sha(),
                            UUID.randomUUID().toString());
                    var row =
                            jdbc.queryForMap(
                                    "SELECT *,lease_until>clock_timestamp() AS busy FROM"
                                            + " fr_design_reference_mirror WHERE "
                                            + WHERE
                                            + " FOR UPDATE",
                                    key(s));
                    if (row.get("accepted_asset_id") instanceof Number id)
                        return new Lease(s, null, null, false, id.longValue());
                    if (Boolean.TRUE.equals(row.get("busy")))
                        throw new ServiceException(429, "参考图正在同步，请稍后重试");
                    String token = UUID.randomUUID().toString();
                    jdbc.update(
                            "UPDATE fr_design_reference_mirror SET"
                                    + " lease_token=?,lease_until=clock_timestamp()+interval '10"
                                    + " minutes',attempt_started=TRUE WHERE "
                                    + WHERE,
                            concat(new Object[] {token}, key(s)));
                    return new Lease(
                            s,
                            token,
                            (String) row.get("attempt_key"),
                            Boolean.TRUE.equals(row.get("attempt_started")),
                            null);
                });
    }

    public Lease restart(Lease lease) {
        String attempt = UUID.randomUUID().toString();
        mutate(lease, "attempt_key=?,attempt_started=TRUE", attempt);
        return new Lease(lease.scope(), lease.token(), attempt, false, null);
    }

    public void renew(Lease lease) {
        mutate(lease, "lease_until=clock_timestamp()+interval '10 minutes'");
    }

    public void accept(Lease lease, long remoteId) {
        if (remoteId <= 0) throw new IllegalArgumentException("Remote asset required");
        mutate(lease, "accepted_asset_id=?,lease_until=NULL", remoteId);
    }

    public void release(Lease lease) {
        if (lease.token() == null) return;
        jdbc.update(
                "UPDATE fr_design_reference_mirror SET lease_until=NULL WHERE "
                        + WHERE
                        + " AND lease_token=?",
                concat(key(lease.scope()), new Object[] {lease.token()}));
    }

    private void mutate(Lease l, String assignment, Object... values) {
        int count =
                jdbc.update(
                        "UPDATE fr_design_reference_mirror SET "
                                + assignment
                                + " WHERE "
                                + WHERE
                                + " AND lease_token=? AND lease_until>clock_timestamp() AND"
                                + " accepted_asset_id IS NULL",
                        concat(values, key(l.scope()), new Object[] {l.token()}));
        if (count != 1) throw new ServiceException(409, "参考图同步身份已过期，请重试");
    }

    private static Object[] concat(Object[]... arrays) {
        return java.util.Arrays.stream(arrays).flatMap(java.util.Arrays::stream).toArray();
    }
}
