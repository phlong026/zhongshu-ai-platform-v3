package cn.iocoder.yudao.module.franchise.design.remote;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.capability.CapabilityJson;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable B-side command/outbox. Each method ends its transaction before any HTTP exchange. */
public class RemoteDesignCommandStore {
    public record Command(long commandId, long tenantId, String businessKey, Request<?> request, PostSnapshot post, String fingerprint,
                          PlatformPriceSnapshot price, String state, Object result, Long jobId, boolean audited) { }
    public record Lease(Command command, String token, boolean acquired, boolean recovery) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CapabilityJson json;
    private final FranchiseCommands audit;
    private final RemoteDesignChargePort charges;
    /** Same net-generation definition as the original project history, using B's company ledger. */
    public Map<Long,Long> projectPointsSpent(String caller,PostSnapshot post,java.util.Set<Long> projectIds) {
        if(projectIds.isEmpty())return Map.of();
        if(projectIds.size()>50||projectIds.stream().anyMatch(id->id==null||id<=0))throw new IllegalArgumentException("Invalid project billing query");
        String placeholders=String.join(",",java.util.Collections.nCopies(projectIds.size(),"?"));
        String project="CASE WHEN c.operation LIKE 'FLOW_%' THEN split_part(c.payload_json->>'path','/',3) ELSE c.payload_json->>'projectId' END";
        var parameters=new java.util.ArrayList<Object>(List.of(caller,tenant(post),post.orgId()));
        projectIds.forEach(id->parameters.add(String.valueOf(id)));
        var values=new LinkedHashMap<Long,Long>();projectIds.forEach(id->values.put(id,0L));
        jdbc.query("SELECT "+project+" AS project_id,SUM(-r.available_delta-t.refund) AS spent,"
                +"bool_or(t.events>1 OR t.refund<0 OR t.refund > -r.available_delta) AS invalid "
                +"FROM fr_design_remote_command c JOIN org_point_ledger r ON r.org_id=c.org_id AND r.ref_type='design_command' AND r.ref_id=c.command_id AND r.type='RESERVE' "
                +"CROSS JOIN LATERAL(SELECT count(*) AS events,COALESCE(SUM(l.available_delta),0) AS refund FROM org_point_ledger l WHERE l.org_id=c.org_id AND l.operation_id=r.operation_id AND l.type IN ('SETTLE','RELEASE'))t "
                +"WHERE c.caller_app_id=? AND c.tenant_id=? AND c.org_id=? AND c.price_snapshot IS NOT NULL "
                +"AND c.operation IN ('FLOW_FLAT_JOB','FLOW_ELEVATION_JOB','FLOW_REVISION','CREATE_FLAT_JOB','CREATE_ELEVATION_JOB','CREATE_REVISION_REQUEST') "
                +"AND "+project+" IN ("+placeholders+") GROUP BY "+project,
                rs->{if(rs.getBoolean("invalid"))throw new IllegalStateException("Invalid company project settlement");values.put(Long.parseLong(rs.getString("project_id")),rs.getLong("spent"));},parameters.toArray());
        return values;
    }
    public java.util.Map<String,Object> jobPointView(String caller,PostSnapshot post,long jobId) {
        var view=new java.util.LinkedHashMap<String,Object>();
        view.put("totalPointCost",null);view.put("unitPointCost",null);view.put("refundedPointCost",null);view.put("netPointCost",null);view.put("billingFinalized",false);
        var rows=jdbc.queryForList("SELECT command_id,price_snapshot FROM fr_design_remote_command WHERE caller_app_id=? AND tenant_id=? AND org_id=? AND job_id=? AND price_snapshot IS NOT NULL AND state='SUCCEEDED' ORDER BY command_id LIMIT 1",caller,tenant(post),post.orgId(),jobId);
        if(rows.isEmpty())return view;
        long command=((Number)rows.get(0).get("command_id")).longValue();
        var reserved=jdbc.queryForList("SELECT operation_id,available_delta FROM org_point_ledger WHERE org_id=? AND ref_type='design_command' AND ref_id=? AND type='RESERVE'",post.orgId(),command);
        if(reserved.size()!=1)return view;
        long total=Math.negateExact(((Number)reserved.get(0).get("available_delta")).longValue());
        var terminal=jdbc.queryForList("SELECT available_delta FROM org_point_ledger WHERE org_id=? AND operation_id=? AND type IN ('SETTLE','RELEASE')",post.orgId(),reserved.get(0).get("operation_id"));
        PlatformPriceSnapshot price=json.read(rows.get(0).get("price_snapshot").toString(),PlatformPriceSnapshot.class);
        view.put("totalPointCost",total);
        view.put("unitPointCost",price.requestedImageCount()>0&&total%price.requestedImageCount()==0?total/price.requestedImageCount():null);
        if(terminal.size()==1) {
            long refund=((Number)terminal.get(0).get("available_delta")).longValue();
            if(refund<0||refund>total)throw new IllegalStateException("Invalid company point settlement");
            view.put("refundedPointCost",refund);view.put("netPointCost",total-refund);view.put("billingFinalized",true);
        }
        return view;
    }

    public RemoteDesignCommandStore(DataSource source, PlatformTransactionManager transactions, ObjectMapper mapper,
                                   FranchiseCommands audit, RemoteDesignChargePort charges) {
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(15);
        json = new CapabilityJson(mapper);
        this.audit = audit;
        this.charges = charges;
    }

    public Command prepare(PostSnapshot post, Request<?> request) {
        return tx.execute(status -> {
            if (request.orgId() != post.orgId() || request.actor().actorUserId() != post.userId()
                    || request.actor().actorPostId() != post.postId() || !request.actor().actorRoleCode().equals(post.roleCode())) {
                throw new ServiceException(403, "命令操作者与已验证任职不匹配");
            }
            long tenant = tenant(post);
            String fingerprint = json.fingerprint(request);
            int inserted = jdbc.update("INSERT INTO fr_design_remote_command(caller_app_id,tenant_id,org_id,operation,request_key,fingerprint,actor_json,payload_json,state) "
                            + "VALUES(?,?,?,?,?,?,?::jsonb,?::jsonb,'NEW') ON CONFLICT(caller_app_id,tenant_id,org_id,operation,request_key) DO NOTHING",
                    request.callerAppId(), tenant, request.orgId(), request.operation().name(), request.requestKey(), fingerprint,
                    json.write(post), json.write(request.payload()));
            var row = jdbc.queryForMap("SELECT * FROM fr_design_remote_command WHERE caller_app_id=? AND tenant_id=? AND org_id=? AND operation=? AND request_key=? FOR UPDATE",
                    request.callerAppId(), tenant, request.orgId(), request.operation().name(), request.requestKey());
            if (!fingerprint.equals(row.get("fingerprint"))) throw new ServiceException(409, "同一业务键不能用于不同内容或操作者");
            if (inserted == 1 && request.operation().paid()) {
                if (charges == null) throw new IllegalStateException("Platform design wallet charging is required");
                var quote = charges.quote(post, request.operation(), request.payload());
                charges.reserve(((Number) row.get("command_id")).longValue(), post, quote);
                jdbc.update("UPDATE fr_design_remote_command SET price_snapshot=?::jsonb WHERE command_id=?", json.write(quote), row.get("command_id"));
                row.put("price_snapshot", json.write(quote));
            }
            return command(row);
        });
    }

    public long tenant(PostSnapshot post) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            return tx.execute(status -> tenant(post));
        }
        var rows = jdbc.queryForList("SELECT d.tenant_id FROM pf_org_unit o JOIN system_dept d ON d.id=o.dept_id "
                + "WHERE o.org_id=? AND o.dept_id=o.org_id AND o.kind='ORG' AND o.status='ACTIVE' "
                + "AND d.status=0 AND d.deleted::text IN ('0','false','f') FOR SHARE OF o,d", Long.class, post.orgId());
        if (rows.size() != 1 || rows.get(0) <= 0) throw new ServiceException(403, "任职组织缺少有效租户绑定");
        return rows.get(0);
    }

    public Lease claim(Command expected) {
        return tx.execute(status -> {
            var row = lock(expected);
            Command command = command(row);
            long commandId = command.commandId();
            String token = UUID.randomUUID().toString();
            int changed = jdbc.update("UPDATE fr_design_remote_command SET state='SENT',lease_token=?,lease_until=clock_timestamp()+interval '60 seconds', "
                            + "next_attempt_at=clock_timestamp()+interval '60 seconds',attempts=attempts+1,updated_at=now() "
                            + "WHERE command_id=? AND state IN ('NEW','SENT','UNKNOWN') "
                            + "AND (lease_until IS NULL OR lease_until<clock_timestamp())", token, commandId);
            return new Lease(command, token, changed == 1, !"NEW".equals(command.state()));
        });
    }

    public void unknown(Lease lease) {
        tx.executeWithoutResult(status -> jdbc.update("UPDATE fr_design_remote_command SET state='UNKNOWN',lease_until=clock_timestamp(), "
                        + "next_attempt_at=clock_timestamp()+interval '5 seconds',updated_at=now() "
                        + "WHERE command_id=? AND lease_token=? AND state='SENT'", lease.command().commandId(), lease.token()));
    }

    public void pending(Lease lease) {
        tx.executeWithoutResult(status -> jdbc.update("UPDATE fr_design_remote_command SET state='UNKNOWN',lease_until=clock_timestamp()+interval '5 seconds', "
                        + "next_attempt_at=clock_timestamp()+interval '5 seconds',updated_at=now() "
                        + "WHERE command_id=? AND lease_token=? AND state='SENT'", lease.command().commandId(), lease.token()));
    }

    /** The scoped row lock is acquired before checking the wall clock, including after a lock wait. */
    public Lease claimSettlement(Command expected) {
        return tx.execute(status -> {
            Command command = command(lock(expected));
            String token = UUID.randomUUID().toString();
            int changed = jdbc.update("UPDATE fr_design_remote_command SET lease_token=?,lease_until=clock_timestamp()+interval '60 seconds' "
                            + "WHERE command_id=? AND state='SUCCEEDED' AND job_id IS NOT NULL AND price_snapshot IS NOT NULL "
                            + "AND settled_terminal=false AND next_attempt_at<=clock_timestamp() "
                            + "AND (lease_until IS NULL OR lease_until<clock_timestamp())", token, command.commandId());
            return new Lease(command, token, changed == 1, true);
        });
    }

    /** Poll scheduling is independent of business timestamps and advances even on unchanged facts or HTTP failure. */
    public void finishSettlementPoll(Lease lease) {
        tx.executeWithoutResult(status -> {
            var row = lock(lease.command());
            if (!lease.token().equals(row.get("lease_token"))) return;
            jdbc.update("UPDATE fr_design_remote_command SET next_attempt_at=clock_timestamp()+interval '10 seconds', "
                    + "lease_token=NULL,lease_until=NULL WHERE command_id=? AND lease_token=?", lease.command().commandId(), lease.token());
        });
    }

    public Object complete(Lease lease, Receipt receipt) {
        return tx.execute(status -> {
            var row = lock(lease.command());
            Command command = command(row);
            if ("SUCCEEDED".equals(command.state()) && command.audited()) return command.result();
            if (!lease.token().equals(row.get("lease_token"))) throw new ServiceException(409, "命令已由其他恢复任务接管");
            if (receipt.status() != ReceiptStatus.SUCCEEDED || receipt.operation() != command.request().operation()
                    || !receipt.requestKey().equals(command.request().requestKey()) || !receipt.fingerprint().equals(command.fingerprint())) {
                throw new ServiceException(409, "远端收据与本地命令不一致");
            }
            if (command.request().operation() == Operation.COMPLETE_UPLOAD) {
                var asset = (cn.iocoder.yudao.module.franchise.api.DesignServicePort.AssetCompleted) json.result(receipt.result(), Operation.COMPLETE_UPLOAD);
                if (asset == null || asset.assetId() != ((Asset) command.request().payload()).assetId()
                        || !("ACCEPTED".equals(asset.uploadStatus()) || "REJECTED".equals(asset.uploadStatus()))) {
                    throw new ServiceException(409, "上传收据缺少匹配资产的终态");
                }
            }
            if (command.price() != null && receipt.jobId() == null) {
                charges.completeSynchronous(command.commandId(), command.post(), command.price(), command.request().operation());
            }
            // A replay is still a first local success after a lost response or audit outage.
            var input = new LinkedHashMap<String, Object>();
            input.put("commandId", command.commandId());
            input.put("tenantId", command.tenantId());
            input.put("orgId", command.post().orgId());
            input.put("operation", command.request().operation());
            input.put("fingerprint", command.fingerprint());
            input.put("receiptId", receipt.receiptId());
            input.put("result", receipt.result());
            String key = "design-remote:" + command.commandId();
            if (audit.replayed(command.post(), key, input).isEmpty()) {
                audit.record(command.post(), "fr-design-remote", command.commandId(), 1,
                        "design-" + command.request().operation().action(), input, key);
            }
            jdbc.update("UPDATE fr_design_remote_command SET state='SUCCEEDED',response_json=?::jsonb,receipt_id=?,job_id=?,audited=true, "
                            + "lease_token=NULL,lease_until=NULL,next_attempt_at=clock_timestamp(),updated_at=now() WHERE command_id=?",
                    json.write(receipt.result()), receipt.receiptId(), receipt.jobId(), command.commandId());
            return json.result(receipt.result(), command.request().operation());
        });
    }

    public void reject(Lease lease, int code, String message) {
        tx.executeWithoutResult(status -> {
            var row = lock(lease.command());
            if (!lease.token().equals(row.get("lease_token")) || "SUCCEEDED".equals(row.get("state"))) return;
            Command command = command(row);
            if (command.price() != null) charges.reject(command.commandId(), command.post(), command.price(), code);
            jdbc.update("UPDATE fr_design_remote_command SET state='REJECTED',error_code=?,error_message=?,lease_token=NULL,lease_until=NULL,updated_at=now() WHERE command_id=?",
                    code, message, command.commandId());
        });
    }

    public void settle(String caller, long tenant, long org, Settlement facts) {
        tx.executeWithoutResult(status -> {
            var rows = jdbc.queryForList("SELECT * FROM fr_design_remote_command WHERE caller_app_id=? AND tenant_id=? AND org_id=? AND job_id=? AND state='SUCCEEDED' FOR UPDATE",
                    caller, tenant, org, facts.jobId());
            for (var row : rows) applySettlement(row, facts);
        });
    }

    public void settle(Lease lease, Settlement facts) {
        tx.executeWithoutResult(status -> {
            var row = lock(lease.command());
            if (!lease.token().equals(row.get("lease_token"))) throw new ServiceException(409, "结算轮询已由其他恢复任务接管");
            Command command = command(row);
            if (!"SUCCEEDED".equals(command.state()) || command.jobId() == null || command.jobId() != facts.jobId()) {
                throw new ServiceException(409, "结算事实与命令任务不一致");
            }
            applySettlement(row, facts);
        });
    }

    private void applySettlement(Map<String, Object> row, Settlement facts) {
        Command command = command(row);
        if (command.price() == null) return;
        long oldVersion = ((Number) row.get("settlement_version")).longValue();
        if (facts.version() < oldVersion) throw new ServiceException(409, "结算版本倒退");
        Settlement previous = row.get("settlement_json") == null ? null : json.read(row.get("settlement_json").toString(), Settlement.class);
        if (facts.version() == oldVersion) {
            if (!facts.equals(previous)) throw new ServiceException(409, "同一结算版本事实不同");
            return;
        }
        if (previous != null && (facts.cumulativeChargedCostMicros() < previous.cumulativeChargedCostMicros()
                || facts.cumulativeRefundedCostMicros() < previous.cumulativeRefundedCostMicros()
                || facts.cumulativePromptUnits() < previous.cumulativePromptUnits()
                || facts.cumulativeImageUnits() < previous.cumulativeImageUnits()
                || facts.cumulativeUsageUnits() < previous.cumulativeUsageUnits())) {
            throw new ServiceException(409, "累计结算事实倒退");
        }
        charges.settle(command.commandId(), command.post(), command.price(), facts);
        jdbc.update("UPDATE fr_design_remote_command SET settlement_version=?,settlement_json=?::jsonb,settled_terminal=?,updated_at=now() WHERE command_id=?",
                facts.version(), json.write(facts), facts.terminal(), command.commandId());
    }

    public ServiceException rejection(Command expected) {
        var row = scopedRow(expected, false);
        return new ServiceException(((Number) row.get("error_code")).intValue(), row.get("error_message").toString());
    }

    public Command get(Command expected) { return command(scopedRow(expected, false)); }

    private Map<String, Object> lock(Command expected) { return scopedRow(expected, true); }

    private Map<String, Object> scopedRow(Command expected, boolean lock) {
        return jdbc.queryForMap("SELECT * FROM fr_design_remote_command WHERE command_id=? AND caller_app_id=? AND tenant_id=? AND org_id=?"
                        + (lock ? " FOR UPDATE" : ""), expected.commandId(), expected.request().callerAppId(), expected.tenantId(), expected.post().orgId());
    }

    public List<Command> recoverable(int limit) {
        return jdbc.queryForList("SELECT * FROM fr_design_remote_command WHERE next_attempt_at<=clock_timestamp() "
                        + "AND (lease_until IS NULL OR lease_until<clock_timestamp()) AND (state IN ('NEW','SENT','UNKNOWN') "
                        + "OR (state='SUCCEEDED' AND job_id IS NOT NULL AND price_snapshot IS NOT NULL AND settled_terminal=false)) "
                        + "ORDER BY next_attempt_at,command_id LIMIT ?", Math.min(50, Math.max(1, limit)))
                .stream().map(this::command).toList();
    }

    private Command command(Map<String, Object> row) {
        var operation = Operation.valueOf(row.get("operation").toString());
        PostSnapshot post = json.read(row.get("actor_json").toString(), PostSnapshot.class);
        var actor = new cn.iocoder.yudao.module.franchise.api.DesignServicePort.Actor(post.userId(), post.postId(), post.roleCode());
        var request = new Request<>(row.get("caller_app_id").toString(), ((Number) row.get("org_id")).longValue(), actor,
                operation, row.get("remote_request_key").toString(), "stored", json.read(row.get("payload_json").toString(), operation.payloadType()));
        if (post.orgId() != ((Number) row.get("org_id")).longValue()) throw new IllegalStateException("Persisted command organization mismatch");
        return new Command(((Number) row.get("command_id")).longValue(), ((Number) row.get("tenant_id")).longValue(),
                row.get("request_key").toString(), request, post, row.get("fingerprint").toString(),
                row.get("price_snapshot") == null ? null : json.read(row.get("price_snapshot").toString(), PlatformPriceSnapshot.class),
                row.get("state").toString(), row.get("response_json") == null ? null : json.result(json.read(row.get("response_json").toString(), Object.class), operation),
                row.get("job_id") == null ? null : ((Number) row.get("job_id")).longValue(), Boolean.TRUE.equals(row.get("audited")));
    }
}
