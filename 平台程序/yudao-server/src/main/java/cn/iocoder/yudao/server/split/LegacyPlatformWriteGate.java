package cn.iocoder.yudao.server.split;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Local B admission and durable pending work. It does not discover owners or wire consumers. */
public final class LegacyPlatformWriteGate implements LegacyPlatformWritePort, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(LegacyPlatformWriteGate.class);
    private static final int MAX_PENDING = 10000;
    private static final Permit A_PERMIT = new Permit() {
        @Override public Permit fork(String writer, String operation) { return this; }
        @Override public void markUnknown() { /* A work is owned by the existing A lifecycle. */ }
        @Override public void close() { /* No B admission was acquired. */ }
    };
    private final Object bLock = new Object();
    private final String instanceId;
    private final String bootId = UUID.randomUUID().toString();
    private final String scopeHash;
    private final Set<String> instances;
    private final Set<String> writers;
    private final StateStore store;
    private volatile Checkpoint checkpoint;
    private boolean closed;
    private boolean controlValidated;

    public LegacyPlatformWriteGate(String instanceId, String scopeHash, Set<String> instances,
                                   Set<String> writers, StateStore store) {
        this.instanceId = label(instanceId);
        this.scopeHash = Objects.requireNonNull(scopeHash);
        this.instances = Set.copyOf(instances);
        this.writers = Set.copyOf(writers);
        this.store = Objects.requireNonNull(store);
        checkpoint = new Checkpoint(0, State.CLOSED, scopeHash, false, Map.of());
        try {
            Checkpoint restored = store.load();
            if (restored != null) {
                require(scopeHash.equals(restored.scopeHash()), "RESTORED_SCOPE_MISMATCH");
                require(restored.generation() >= 0 && restored.pending().size() <= MAX_PENDING, "INVALID_CHECKPOINT");
                checkpoint = restored;
            }
        } catch (IOException | RuntimeException failure) {
            checkpoint = fault(checkpoint);
            LOG.error("[legacy-b-gate] checkpoint load failed; B closed, instance={} failureType={}",
                    instanceId, failure.getClass().getSimpleName());
        }
    }

    @Override public Permit enter(Owner owner, String writer, String operation) {
        // A never takes bLock, touches the store, or waits for B persistence.
        if (owner == Owner.A) return A_PERMIT;
        require(owner == Owner.B_LEGACY, "UNKNOWN_OWNER");
        synchronized (bLock) {
            require(!closed && controlValidated && !checkpoint.controlFault()
                    && checkpoint.state() == State.OPEN, "B_ADMISSION_CLOSED");
            return register(writer, operation);
        }
    }

    private Permit register(String writer, String operation) {
        require(writers.contains(writer), "UNREGISTERED_WRITER");
        label(writer);
        label(operation);
        require(checkpoint.pending().size() < MAX_PENDING, "PENDING_LIMIT");
        String token = UUID.randomUUID().toString();
        Map<String, Work> pending = new HashMap<>(checkpoint.pending());
        pending.put(token, new Work(writer, operation, false));
        persist(copy(pending));
        return new BPermit(token);
    }

    @Override public void apply(Control control, FreezeEvidence evidence) {
        Objects.requireNonNull(control);
        synchronized (bLock) {
            require(!closed, "GATE_CLOSED");
            require(scopeHash.matches("[0-9a-f]{64}") && scopeHash.equals(control.scopeHash()), "SCOPE_MISMATCH");
            require(control.generation() >= checkpoint.generation(), "STALE_GENERATION");
            if (control.generation() == checkpoint.generation()) {
                require(control.state() == checkpoint.state() && !checkpoint.controlFault(), "CONTROL_CONFLICT");
                controlValidated = true;
                return;
            }
            require(control.state() != null && control.state() != State.CLOSED, "INVALID_TARGET_STATE");
            if (control.state() == State.OPEN) {
                // No live thaw or fault reset. Only initial explicit installation may open admission.
                require(checkpoint.generation() == 0 && checkpoint.state() == State.CLOSED
                        && !checkpoint.controlFault() && checkpoint.pending().isEmpty(), "REOPEN_FORBIDDEN");
            } else if (control.state() == State.DRAINING) {
                require(checkpoint.state() != State.FROZEN, "FROZEN_IS_MONOTONIC");
            } else {
                verifyFreeze(Objects.requireNonNull(evidence));
            }
            persist(new Checkpoint(control.generation(), control.state(), scopeHash,
                    checkpoint.controlFault(), checkpoint.pending()));
            controlValidated = true;
            LOG.warn("[legacy-b-gate] state={} generation={} pending={} instance={}",
                    checkpoint.state(), checkpoint.generation(), checkpoint.pending().size(), instanceId);
        }
    }

    private void verifyFreeze(FreezeEvidence evidence) {
        require(!checkpoint.controlFault() && checkpoint.state() == State.DRAINING
                && checkpoint.pending().isEmpty(), "LOCAL_DRAIN_INCOMPLETE");
        require(!writers.isEmpty() && evidence.wiredWriters().equals(writers), "WRITER_COVERAGE_INCOMPLETE");
        require(!instances.isEmpty() && instances.contains(instanceId)
                && evidence.expectedBoots().keySet().equals(instances)
                && evidence.acknowledgements().keySet().equals(instances), "INSTANCE_ACK_INCOMPLETE");
        require(bootId.equals(evidence.expectedBoots().get(instanceId)), "LOCAL_BOOT_CHANGED");
        for (String id : instances) {
            Ack ack = evidence.acknowledgements().get(id);
            require(id.equals(ack.instanceId()) && ack.bootId() != null && !ack.bootId().isBlank()
                    && ack.bootId().equals(evidence.expectedBoots().get(id))
                    && ack.generation() == checkpoint.generation() && scopeHash.equals(ack.scopeHash())
                    && ack.state() == State.DRAINING && ack.pending() == 0 && !ack.controlFault(), "INSTANCE_ACK_STALE");
        }
        require(evidence.durableWorkReviewed() && evidence.callbackReceiverActive() && evidence.uniqueConsumers()
                && evidence.watermarks().containsKey("payment-inbox")
                && evidence.watermarks().values().stream().allMatch(value -> !value.isBlank()), "DURABLE_EVIDENCE_MISSING");
    }

    @Override public void controlUnavailable() {
        synchronized (bLock) {
            if (checkpoint.controlFault()) return;
            // Publish the fault even if disk failure prevents recording it. B admission stays closed.
            Checkpoint blocked = fault(checkpoint);
            checkpoint = blocked;
            try {
                store.save(blocked);
            } catch (IOException | RuntimeException failure) {
                LOG.error("[legacy-b-gate] failed to persist control fault; instance={} failureType={}",
                        instanceId, failure.getClass().getSimpleName());
            }
        }
    }

    @Override public Snapshot snapshot() { return new Snapshot(instanceId, bootId, checkpoint); }

    private Checkpoint copy(Map<String, Work> pending) {
        return new Checkpoint(checkpoint.generation(), checkpoint.state(), scopeHash, checkpoint.controlFault(), pending);
    }
    private static Checkpoint fault(Checkpoint value) {
        return new Checkpoint(value.generation(), value.state(), value.scopeHash(), true, value.pending());
    }
    private void persist(Checkpoint next) {
        try {
            store.save(next);
            checkpoint = next;
        } catch (IOException | RuntimeException failure) {
            checkpoint = fault(checkpoint);
            LOG.error("[legacy-b-gate] durable write failed; B closed, instance={} generation={} failureType={}",
                    instanceId, checkpoint.generation(), failure.getClass().getSimpleName());
            throw new IllegalStateException("DURABILITY_FAILED", failure);
        }
    }
    private static void require(boolean condition, String code) {
        if (!condition) throw new IllegalStateException(code);
    }
    static String label(String value) {
        require(value != null && value.length() <= 128 && value.matches("[A-Za-z0-9_.:-]+"), "INVALID_LABEL");
        return value;
    }

    private final class BPermit implements Permit {
        private final String token;
        private boolean released;
        private BPermit(String token) { this.token = token; }
        @Override public Permit fork(String writer, String operation) {
            synchronized (bLock) {
                Work parent = checkpoint.pending().get(token);
                require(!closed && !released && !checkpoint.controlFault() && parent != null && !parent.unknown()
                        && (checkpoint.state() == State.OPEN || checkpoint.state() == State.DRAINING), "PARENT_PERMIT_INVALID");
                return register(writer, operation);
            }
        }
        @Override public void markUnknown() {
            synchronized (bLock) {
                require(!closed && !released, "PERMIT_CLOSED");
                Work work = checkpoint.pending().get(token);
                require(work != null, "PERMIT_MISSING");
                Map<String, Work> pending = new HashMap<>(checkpoint.pending());
                pending.put(token, new Work(work.writer(), work.operation(), true));
                // If saving UNKNOWN fails, close must still retain the unresolved work.
                checkpoint = copy(pending);
                persist(checkpoint);
            }
        }
        @Override public void close() {
            synchronized (bLock) {
                if (released) return;
                require(!closed, "GATE_CLOSED");
                Work work = checkpoint.pending().get(token);
                if (work != null && !work.unknown()) {
                    Map<String, Work> pending = new HashMap<>(checkpoint.pending());
                    pending.remove(token);
                    persist(copy(pending));
                }
                released = true;
            }
        }
    }

    @Override public void close() {
        synchronized (bLock) {
            if (closed) return;
            closed = true;
            try {
                store.close();
            } catch (IOException failure) {
                LOG.error("[legacy-b-gate] store close failed; instance={}", instanceId, failure);
            }
        }
    }
}
