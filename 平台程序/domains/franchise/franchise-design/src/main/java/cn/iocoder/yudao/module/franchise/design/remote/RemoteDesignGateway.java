package cn.iocoder.yudao.module.franchise.design.remote;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.capability.CapabilityJson;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import cn.iocoder.yudao.module.franchise.design.remote.transport.DesignCapabilityTransport;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Slf4j
public class RemoteDesignGateway {
    public static final int OUTCOME_UNKNOWN = 1_112_100_002;
    private final String callerAppId;
    private final DesignCapabilityTransport transport;
    private final RemoteDesignCommandStore commands;
    private final ObjectMapper mapper;
    private final CapabilityJson json;
    private final RemoteDesignAdmission admission;

    public RemoteDesignGateway(String callerAppId, DesignCapabilityTransport transport,
                               RemoteDesignCommandStore commands, ObjectMapper mapper) {
        this(callerAppId, transport, commands, mapper, new RemoteDesignAdmission(16, 4));
    }

    public RemoteDesignGateway(String callerAppId, DesignCapabilityTransport transport,
                               RemoteDesignCommandStore commands, ObjectMapper mapper, RemoteDesignAdmission admission) {
        cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.requireText(callerAppId, 80, "callerAppId");
        this.callerAppId = callerAppId;
        this.transport = transport;
        this.commands = commands;
        this.mapper = mapper.copy();
        this.json = new CapabilityJson(mapper);
        this.admission = java.util.Objects.requireNonNull(admission);
    }

    public <T> T call(PostSnapshot post, Operation operation, String requestKey, Object payload) {
        try (var permit = admission.enter(post.orgId())) {
            return callAdmitted(post, operation, requestKey, payload);
        } catch (RemoteDesignAdmission.BusyException busy) {
            // Nothing was persisted or sent: retry with the same business key is safe.
            throw new ServiceException(429, "设计服务繁忙，请稍后使用原业务键重试");
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T callAdmitted(PostSnapshot post, Operation operation, String requestKey, Object payload) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Remote design HTTP must not run inside a database transaction");
        }
        boolean write = operation.requiresReceipt(payload);
        if (write && (requestKey == null || requestKey.isBlank())) {
            throw new ServiceException(400, "远程写操作必须提供稳定的 Idempotency-Key");
        }
        var request = new Request<>(callerAppId, post.orgId(),
                new DesignServicePort.Actor(post.userId(), post.postId(), post.roleCode()), operation,
                write ? requestKey : UUID.randomUUID().toString(), UUID.randomUUID().toString(), payload);
        if (!write) {
            long tenant = commands.tenant(post);
            var reply = exchange(request);
            requireSuccess(reply);
            Object result = json.result(reply.result(), operation);
            if (operation == Operation.GET_AI_JOB) settle(post, tenant, ((Job) payload).jobId());
            return (T) result;
        }
        var command = commands.prepare(post, request);
        if ("SUCCEEDED".equals(command.state()) && command.audited()) return (T) replay(command.result());
        if ("REJECTED".equals(command.state())) throw commands.rejection(command);
        return (T) submit(commands.claim(command));
    }

    private Object submit(RemoteDesignCommandStore.Lease lease) {
        if (!lease.acquired()) throw unknown();
        var request = lease.command().request();
        try {
            if (lease.recovery()) {
                Receipt found = receipt(request);
                if (found.status() == ReceiptStatus.SUCCEEDED) return replay(commands.complete(lease, found));
                if (found.status() == ReceiptStatus.FAILED) {
                    commands.reject(lease, found.errorCode(), found.errorMessage());
                    throw new ServiceException(found.errorCode(), found.errorMessage());
                }
                if (found.status() != ReceiptStatus.NOT_FOUND) {
                    commands.pending(lease);
                    throw unknown();
                }
                // Only an authoritative NOT_FOUND permits re-sending the same persisted key and payload.
            }
            Reply<?> reply = exchange(request);
            if (reply.code() == OUTCOME_UNKNOWN || reply.receipt() != null
                    && (reply.receipt().status() == ReceiptStatus.PROCESSING || reply.receipt().status() == ReceiptStatus.UNKNOWN)) {
                commands.pending(lease);
                throw unknown();
            }
            if (reply.code() != 0) {
                commands.reject(lease, reply.code(), reply.message());
                throw new ServiceException(reply.code(), reply.message());
            }
            if (reply.receipt() == null) throw new IllegalStateException("Remote mutation omitted its durable receipt");
            return commands.complete(lease, reply.receipt());
        } catch (ServiceException failure) {
            if (failure.getCode() == OUTCOME_UNKNOWN) {
                log.warn("[remote-design][pending command={} action={}]", lease.command().commandId(), request.operation());
                throw failure;
            }
            if ("REJECTED".equals(commands.get(lease.command()).state())) throw failure;
            // Receipt validation or local audit/settlement failed after dispatch: do not release money.
            commands.unknown(lease);
            log.warn("[remote-design][local completion unresolved command={}]", lease.command().commandId(), failure);
            throw unknown();
        } catch (RuntimeException failure) {
            commands.unknown(lease);
            log.warn("[remote-design][uncertain command={} org={} action={}]", lease.command().commandId(), request.orgId(), request.operation(), failure);
            throw unknown();
        }
    }

    private Receipt receipt(Request<?> original) {
        var query = new Request<>(original.callerAppId(), original.orgId(), original.actor(), Operation.QUERY_RECEIPT,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), new ReceiptQuery(original.operation(), original.requestKey()));
        var reply = exchange(query);
        requireSuccess(reply);
        return mapper.convertValue(reply.result(), Receipt.class);
    }

    private Reply<?> exchange(Request<?> request) {
        // Every physical attempt has a fresh nonce; actor, payload and business key come from persistence.
        var wire = new Request<>(request.callerAppId(), request.orgId(), request.actor(), request.operation(),
                request.requestKey(), UUID.randomUUID().toString(), request.payload());
        var response = transport.exchange(request.operation(), json.write(wire).getBytes(StandardCharsets.UTF_8));
        if (response.statusCode() >= 500 || response.statusCode() >= 300 && response.statusCode() < 400) {
            throw new IllegalStateException("Design capability response is uncertain: HTTP " + response.statusCode());
        }
        try {
            Reply<?> reply = mapper.readValue(response.responseBody(), Reply.class);
            if (reply.code() == 0 && response.statusCode() != 200) throw new IllegalStateException("Inconsistent capability HTTP status");
            return reply;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Invalid or incomplete design capability response", failure);
        }
    }

    private void settle(PostSnapshot post, long tenant, long jobId) {
        commands.settle(callerAppId, tenant, post.orgId(), settlement(post, jobId));
    }

    private Settlement settlement(PostSnapshot post, long jobId) {
        var query = new Request<>(callerAppId, post.orgId(), new DesignServicePort.Actor(post.userId(), post.postId(), post.roleCode()),
                Operation.GET_SETTLEMENT, UUID.randomUUID().toString(), UUID.randomUUID().toString(), new Job(jobId));
        var reply = exchange(query);
        requireSuccess(reply);
        Settlement facts = mapper.convertValue(reply.result(), Settlement.class);
        if (facts == null || facts.jobId() != jobId) throw new IllegalStateException("Settlement response does not match the requested job");
        return facts;
    }

    private void pollSettlement(RemoteDesignCommandStore.Command command) {
        var lease = commands.claimSettlement(command);
        if (!lease.acquired()) return;
        try {
            commands.settle(lease, settlement(lease.command().post(), lease.command().jobId()));
        } finally {
            commands.finishSettlementPoll(lease);
        }
    }

    @Scheduled(fixedDelayString = "${zhongshu.design.remote.recovery-delay-ms:10000}")
    public void recover() {
        for (var command : commands.recoverable(25)) {
            try (var permit = admission.enter(command.post().orgId())) {
                if ("SUCCEEDED".equals(command.state())) pollSettlement(command);
                else submit(commands.claim(command));
            } catch (RemoteDesignAdmission.BusyException busy) {
                // Leave the durable command unchanged for the next recovery pass.
                log.debug("[remote-design][recovery deferred command={}]", command.commandId());
            } catch (RuntimeException failure) {
                log.warn("[remote-design][recovery command={} org={} state={}]", command.commandId(), command.post().orgId(), command.state(), failure);
            }
        }
    }

    private static void requireSuccess(Reply<?> reply) {
        if (reply.code() != 0) throw new ServiceException(reply.code(), reply.message());
    }
    private static ServiceException unknown() { return new ServiceException(OUTCOME_UNKNOWN, "请求结果待确认，请使用原业务键重试查询"); }

    private Object replay(Object result) {
        if (result instanceof DesignServicePort.ProjectCreated value) return new DesignServicePort.ProjectCreated(value.projectId(), true);
        if (result instanceof DesignServicePort.JobSubmitted value) return new DesignServicePort.JobSubmitted(value.jobId(), true);
        if (result instanceof DesignServicePort.RevisionSubmitted value) return new DesignServicePort.RevisionSubmitted(value.requestId(), value.newJobId(), true);
        if (result instanceof DesignServicePort.SelectionSaved value) return new DesignServicePort.SelectionSaved(value.resultVersionId(), value.version(), value.selectionSaved(), true);
        if (result instanceof DesignServicePort.EstimateCreated value) return new DesignServicePort.EstimateCreated(value.estimateId(), value.totalLowCents(), value.totalHighCents(), value.ruleVersionId(), true);
        return result;
    }
}
