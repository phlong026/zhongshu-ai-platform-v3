package cn.iocoder.yudao.module.design.capability;

import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.capability.CapabilityJson;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** One PostgreSQL transaction owns both local business mutation and its replayable receipt. */
@Slf4j
public class CapabilityReceiptStore {
    private final JdbcTemplate jdbc;
    private final DataSource source;
    private final TransactionTemplate tx;
    private final CapabilityJson json;

    public CapabilityReceiptStore(DataSource source, PlatformTransactionManager transactions, ObjectMapper mapper) {
        this.source = source;
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(15);
        json = new CapabilityJson(mapper);
    }

    public Reply<Object> executeAtomic(Request<?> request, Supplier<Object> business) {
        return executeAtomic(request, ignored -> business.get());
    }

    public Reply<Object> executeAtomic(Request<?> request, Function<String, Object> business) {
        return tx.execute(status -> {
            Map<String, Object> row = lockOrCreate(request);
            if ("SUCCEEDED".equals(row.get("state"))) return reply(row, true);
            Object result = business.apply(row.get("receipt_id").toString());
            return finish(row, result, false);
        });
    }

    public record Lease(Receipt receipt, String token, boolean acquired) { }

    /** Persist intent before external work; never keep a transaction open while waiting on a channel. */
    public Lease claim(Request<?> request) {
        return tx.execute(status -> {
            var row = lockOrCreate(request);
            if ("SUCCEEDED".equals(row.get("state")) || "FAILED".equals(row.get("state"))) return new Lease(receipt(row), null, false);
            String token = UUID.randomUUID().toString();
            int changed = jdbc.update("UPDATE design_capability_receipt SET lease_token=?, lease_until=now()+interval '60 seconds', "
                            + "updated_at=now() WHERE receipt_id=? AND lease_token IS NULL AND state='PROCESSING'",
                    token, row.get("receipt_id"));
            return new Lease(receipt(row), changed == 1 ? token : null, changed == 1);
        });
    }

    /** Reconciliation claims expired leases only; it queries facts before any additional mutation. */
    public Lease claimRecovery(String caller, long org, ReceiptQuery query) {
        return tx.execute(status -> {
            var rows = jdbc.queryForList("SELECT * FROM design_capability_receipt WHERE caller_app_id=? AND org_id=? "
                    + "AND operation=? AND request_key=? FOR UPDATE", caller, org, query.operation().name(), query.requestKey());
            if (rows.isEmpty()) return new Lease(notFound(query), null, false);
            var row = rows.get(0);
            String token = UUID.randomUUID().toString();
            int count = jdbc.update("UPDATE design_capability_receipt SET lease_token=?, lease_until=now()+interval '60 seconds' "
                            + "WHERE receipt_id=? AND state IN ('PROCESSING','UNKNOWN') AND (lease_until IS NULL OR lease_until<now())",
                    token, row.get("receipt_id"));
            return new Lease(receipt(row), count == 1 ? token : null, count == 1);
        });
    }

    /** Fencing prevents an expired worker from publishing over a newer recovery owner. */
    public Reply<Object> completePrepared(Lease lease, Function<String, Object> localBusiness) {
        return tx.execute(status -> completeInCurrentTransaction(lease, localBusiness));
    }

    public Reply<Object> completeInCurrentTransaction(Lease lease, Function<String, Object> localBusiness) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(source)) {
            throw new IllegalStateException("Receipt completion requires the resource business transaction");
        }
        var row = jdbc.queryForMap("SELECT * FROM design_capability_receipt WHERE receipt_id=? FOR UPDATE", lease.receipt().receiptId());
        if ("SUCCEEDED".equals(row.get("state"))) return reply(row, true);
        if (lease.token() == null || !lease.token().equals(row.get("lease_token"))) {
            throw new CapabilityException(409, 1_112_100_002, "请求正在恢复，请查询原收据");
        }
        return finish(row, localBusiness.apply(lease.receipt().receiptId()), false);
    }

    public Optional<Reply<Object>> resolveFailure(Lease lease, Supplier<Optional<ServiceDesignBillingPort.Rejection>> evidence) {
        return tx.execute(status -> {
            var row = jdbc.queryForMap("SELECT * FROM design_capability_receipt WHERE receipt_id=? FOR UPDATE", lease.receipt().receiptId());
            if ("SUCCEEDED".equals(row.get("state")) || "FAILED".equals(row.get("state"))) return Optional.of(reply(row, true));
            if (!lease.token().equals(row.get("lease_token"))) return Optional.empty();
            var rejection = evidence.get();
            if (rejection.isEmpty()) return Optional.empty();
            var reason = rejection.get();
            jdbc.update("UPDATE design_capability_receipt SET state='FAILED',error_code=?,error_message=?,lease_token=NULL,lease_until=NULL,updated_at=now() WHERE receipt_id=?",
                    reason.code(), reason.message(), lease.receipt().receiptId());
            row.put("state", "FAILED");
            row.put("error_code", reason.code());
            row.put("error_message", reason.message());
            return Optional.of(reply(row, false));
        });
    }

    public void unknown(Lease lease, RuntimeException failure) {
        tx.executeWithoutResult(status -> jdbc.update("UPDATE design_capability_receipt SET state='UNKNOWN', "
                        + "lease_until=now(), updated_at=now() WHERE receipt_id=? AND lease_token=? AND state<>'SUCCEEDED'",
                lease.receipt().receiptId(), lease.token()));
        log.warn("[design-capability][uncertain receipt={} action={}]", lease.receipt().receiptId(),
                lease.receipt().operation(), failure);
    }

    public void pending(Lease lease) {
        tx.executeWithoutResult(status -> jdbc.update("UPDATE design_capability_receipt SET state='UNKNOWN', "
                        + "lease_until=now()+interval '5 seconds', updated_at=now() WHERE receipt_id=? AND lease_token=? AND state<>'SUCCEEDED'",
                lease.receipt().receiptId(), lease.token()));
    }

    public Request<?> storedRequest(Receipt receipt) {
        var row = jdbc.queryForMap("SELECT caller_app_id,org_id,actor_json::text,payload_json::text FROM design_capability_receipt WHERE receipt_id=?",
                receipt.receiptId());
        return new Request<>(String.valueOf(row.get("caller_app_id")), ((Number) row.get("org_id")).longValue(),
                json.read(row.get("actor_json").toString(), DesignServicePort.Actor.class), receipt.operation(),
                receipt.requestKey(), "recovery", json.read(row.get("payload_json").toString(), receipt.operation().payloadType()));
    }

    public Receipt query(String caller, long org, ReceiptQuery query) {
        var rows = jdbc.queryForList("SELECT * FROM design_capability_receipt WHERE caller_app_id=? AND org_id=? AND operation=? AND request_key=?",
                caller, org, query.operation().name(), query.requestKey());
        return rows.isEmpty() ? notFound(query) : receipt(rows.get(0));
    }

    private Map<String, Object> lockOrCreate(Request<?> request) {
        String fingerprint = json.fingerprint(request);
        var bindings = jdbc.queryForList("SELECT tenant_id,service_account_id FROM design_capability_organization "
                + "WHERE caller_app_id=? AND org_id=? AND active=true FOR SHARE", request.callerAppId(), request.orgId());
        if (bindings.size() != 1) throw CapabilityException.forbidden();
        var binding = bindings.get(0);
        jdbc.update("INSERT INTO design_capability_receipt(receipt_id,caller_app_id,org_id,tenant_id,service_account_id,operation,request_key,fingerprint,actor_json,payload_json,state) "
                        + "VALUES(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,'PROCESSING') ON CONFLICT(caller_app_id,org_id,operation,request_key) DO NOTHING",
                UUID.randomUUID().toString(), request.callerAppId(), request.orgId(), binding.get("tenant_id"), binding.get("service_account_id"), request.operation().name(),
                request.requestKey(), fingerprint, json.write(request.actor()), json.write(request.payload()));
        var row = jdbc.queryForMap("SELECT * FROM design_capability_receipt WHERE caller_app_id=? AND org_id=? AND operation=? AND request_key=? FOR UPDATE",
                request.callerAppId(), request.orgId(), request.operation().name(), request.requestKey());
        if (!binding.get("tenant_id").equals(row.get("tenant_id"))
                || !binding.get("service_account_id").equals(row.get("service_account_id"))) throw CapabilityException.forbidden();
        if (!fingerprint.equals(row.get("fingerprint"))) throw CapabilityException.conflict();
        return row;
    }

    private Reply<Object> finish(Map<String, Object> row, Object result, boolean replayed) {
        Long jobId = result instanceof DesignServicePort.JobSubmitted job ? job.jobId()
                : result instanceof DesignServicePort.RevisionSubmitted revision ? revision.newJobId()
                : result instanceof DesignServicePort.AiJobView job ? job.jobId() : null;
        jdbc.update("UPDATE design_capability_receipt SET state='SUCCEEDED',result_json=?::jsonb,job_id=?, "
                        + "lease_token=NULL,lease_until=NULL,updated_at=now() WHERE receipt_id=?",
                json.write(result), jobId, row.get("receipt_id"));
        row.put("state", "SUCCEEDED");
        row.put("result_json", json.write(result));
        row.put("job_id", jobId);
        return reply(row, replayed);
    }

    private Reply<Object> reply(Map<String, Object> row, boolean replayed) {
        Receipt receipt = receipt(row);
        return new Reply<>(receipt.status() == ReceiptStatus.FAILED ? receipt.errorCode() : 0,
                receipt.errorMessage() == null ? "" : receipt.errorMessage(), receipt.result(), receipt, replayed);
    }

    private Receipt receipt(Map<String, Object> row) {
        Operation operation = Operation.valueOf(row.get("operation").toString());
        Object result = row.get("result_json") == null ? null : json.result(json.read(row.get("result_json").toString(), Object.class), operation);
        return new Receipt(row.get("receipt_id").toString(), operation, row.get("request_key").toString(),
                row.get("fingerprint").toString(), ReceiptStatus.valueOf(row.get("state").toString()), result,
                row.get("job_id") == null ? null : ((Number) row.get("job_id")).longValue(),
                row.get("error_code") == null ? null : ((Number) row.get("error_code")).intValue(),
                row.get("error_message") == null ? null : row.get("error_message").toString());
    }

    private Receipt notFound(ReceiptQuery query) {
        return new Receipt(null, query.operation(), query.requestKey(), null, ReceiptStatus.NOT_FOUND, null, null, null, null);
    }
}
