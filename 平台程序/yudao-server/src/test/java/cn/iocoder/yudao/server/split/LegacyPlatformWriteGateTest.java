package cn.iocoder.yudao.server.split;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyPlatformWriteGateTest {
    private static final String SCOPE = "a".repeat(64);
    private static final Set<String> WRITERS = Set.of("http", "event", "worker");

    static final class MemoryStore implements StateStore {
        volatile Checkpoint saved;
        volatile boolean fail;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        @Override public Checkpoint load() { return saved; }
        @Override public void save(Checkpoint checkpoint) throws IOException {
            if (fail) throw new IOException("synthetic storage failure");
            if (entered != null) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
            }
            saved = checkpoint;
        }
    }

    private LegacyPlatformWriteGate gate(MemoryStore store) {
        return new LegacyPlatformWriteGate("one", SCOPE, Set.of("one", "two"), WRITERS, store);
    }
    private void open(LegacyPlatformWriteGate gate) {
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());
    }
    private void drain(LegacyPlatformWriteGate gate) {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
    }
    private FreezeEvidence evidence(LegacyPlatformWriteGate gate) {
        String boot = gate.snapshot().bootId();
        return new FreezeEvidence(Map.of("one", boot, "two", "other-boot"),
                Map.of("one", new Ack("one", boot, 2, SCOPE, State.DRAINING, 0, false),
                        "two", new Ack("two", "other-boot", 2, SCOPE, State.DRAINING, 0, false)),
                WRITERS, true, true, true, Map.of("payment-inbox", "synthetic-reviewed-zero"));
    }

    @Test void bIsClosedBeforeExplicitControlWhileARuns() {
        var gate = gate(new MemoryStore());
        assertDoesNotThrow(() -> gate.enter(Owner.A, "a-design", "login").close());
        assertThrows(IllegalStateException.class, () -> gate.enter(Owner.B_LEGACY, "http", "create"));
        assertThrows(IllegalStateException.class, () -> gate.enter(Owner.UNKNOWN, "http", "create"));
    }
    @Test void openAdmissionIsDurableAndCloseIsIdempotent() {
        var store = new MemoryStore();
        var gate = gate(store);
        open(gate);
        var permit = gate.enter(Owner.B_LEGACY, "http", "create");
        assertEquals(1, store.saved.pending().size());
        permit.close();
        permit.close();
        assertTrue(store.saved.pending().isEmpty());
    }
    @Test void drainingRejectsNewWorkButTracksPreRegisteredAfterCommitChild() {
        var gate = gate(new MemoryStore());
        open(gate);
        var request = gate.enter(Owner.B_LEGACY, "http", "disable");
        var afterCommit = request.fork("event", "after-commit");
        drain(gate);
        request.close();
        assertEquals(1, gate.snapshot().checkpoint().pending().size());
        assertThrows(IllegalStateException.class, () -> gate.enter(Owner.B_LEGACY, "worker", "new-retry"));
        assertThrows(IllegalStateException.class, () -> gate.apply(new Control(3, State.FROZEN, SCOPE), evidence(gate)));
        afterCommit.close();
        gate.apply(new Control(3, State.FROZEN, SCOPE), evidence(gate));
        assertEquals(State.FROZEN, gate.snapshot().checkpoint().state());
    }
    @Test void unknownExternalOutcomeSurvivesCloseAndRestart() {
        var store = new MemoryStore();
        var gate = gate(store);
        open(gate);
        var call = gate.enter(Owner.B_LEGACY, "worker", "provider-call");
        call.markUnknown();
        call.close();
        var restarted = gate(store);
        assertEquals(1, restarted.snapshot().checkpoint().pending().size());
        assertTrue(restarted.snapshot().checkpoint().pending().values().iterator().next().unknown());
        drain(restarted);
        assertThrows(IllegalStateException.class, () -> restarted.apply(new Control(3, State.FROZEN, SCOPE), evidence(restarted)));
    }
    @Test void failedUnknownPersistenceCannotBeErasedByClose() {
        var store = new MemoryStore();
        var gate = gate(store);
        open(gate);
        var permit = gate.enter(Owner.B_LEGACY, "worker", "external-submit");
        store.fail = true;
        assertThrows(IllegalStateException.class, permit::markUnknown);
        store.fail = false;
        permit.close();
        assertEquals(1, gate.snapshot().checkpoint().pending().size());
        assertTrue(gate.snapshot().checkpoint().pending().values().iterator().next().unknown());
        assertEquals(1, gate(store).snapshot().checkpoint().pending().size());
    }
    @Test void staleBootGenerationScopeOrBusyInstanceCannotFreeze() {
        var gate = gate(new MemoryStore());
        open(gate);
        drain(gate);
        var full = evidence(gate);
        for (Ack invalid : java.util.List.of(
                new Ack("two", "old-boot", 2, SCOPE, State.DRAINING, 0, false),
                new Ack("two", "other-boot", 1, SCOPE, State.DRAINING, 0, false),
                new Ack("two", "other-boot", 2, "b".repeat(64), State.DRAINING, 0, false),
                new Ack("two", "other-boot", 2, SCOPE, State.OPEN, 0, false),
                new Ack("two", "other-boot", 2, SCOPE, State.DRAINING, 1, false),
                new Ack("two", "other-boot", 2, SCOPE, State.DRAINING, 0, true))) {
            var acks = new java.util.HashMap<>(full.acknowledgements());
            acks.put("two", invalid);
            var rejected = new FreezeEvidence(full.expectedBoots(), acks, WRITERS,
                    true, true, true, full.watermarks());
            assertThrows(IllegalStateException.class,
                    () -> gate.apply(new Control(3, State.FROZEN, SCOPE), rejected));
        }
        var missingInstance = new FreezeEvidence(full.expectedBoots(), Map.of("one", full.acknowledgements().get("one")),
                WRITERS, true, true, true, full.watermarks());
        assertThrows(IllegalStateException.class,
                () -> gate.apply(new Control(3, State.FROZEN, SCOPE), missingInstance));
    }
    @Test void missingDurableWorkReceiverConsumerOrWatermarkCannotFreeze() {
        var gate = gate(new MemoryStore());
        open(gate);
        drain(gate);
        var full = evidence(gate);
        for (int missing = 0; missing < 5; missing++) {
            Map<String, String> watermarks = missing == 3 ? Map.of()
                    : missing == 4 ? Map.of("payment-inbox", " ") : full.watermarks();
            var rejected = new FreezeEvidence(full.expectedBoots(), full.acknowledgements(), WRITERS,
                    missing != 0, missing != 1, missing != 2, watermarks);
            assertThrows(IllegalStateException.class,
                    () -> gate.apply(new Control(3, State.FROZEN, SCOPE), rejected));
        }
    }
    @Test void restartPreservesUnfinishedOrdinaryWorkAndDoesNotInventCompletion() {
        var store = new MemoryStore();
        var first = gate(store);
        open(first);
        first.enter(Owner.B_LEGACY, "http", "uncommitted");
        var second = gate(store);
        assertNotEquals(first.snapshot().bootId(), second.snapshot().bootId());
        assertEquals(1, second.snapshot().checkpoint().pending().size());
    }
    @Test void restartRequiresFreshControlValidationBeforeBAdmission() {
        var store = new MemoryStore();
        var first = gate(store);
        open(first);
        var second = gate(store);
        assertThrows(IllegalStateException.class, () -> second.enter(Owner.B_LEGACY, "http", "new-request"));
        assertDoesNotThrow(() -> second.enter(Owner.A, "a-design", "login").close());
        open(second);
        second.enter(Owner.B_LEGACY, "http", "new-request").close();
    }
    @Test void missingControlPersistsFaultAndOldOpenCannotReenable() {
        var store = new MemoryStore();
        var gate = gate(store);
        open(gate);
        gate.controlUnavailable();
        assertTrue(store.saved.controlFault());
        assertThrows(IllegalStateException.class, () -> gate.enter(Owner.B_LEGACY, "http", "create"));
        assertThrows(IllegalStateException.class, () -> gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing()));
        assertThrows(IllegalStateException.class, () -> gate.apply(new Control(3, State.OPEN, SCOPE), FreezeEvidence.missing()));
        assertDoesNotThrow(() -> gate.enter(Owner.A, "a-payment", "reconcile").close());
    }
    @Test void frozenCannotReopenAndScopeCannotChange() {
        var gate = gate(new MemoryStore());
        open(gate);
        drain(gate);
        gate.apply(new Control(3, State.FROZEN, SCOPE), evidence(gate));
        assertThrows(IllegalStateException.class, () -> gate.apply(new Control(4, State.OPEN, SCOPE), FreezeEvidence.missing()));
        assertThrows(IllegalStateException.class, () -> gate.apply(new Control(4, State.DRAINING, "b".repeat(64)), FreezeEvidence.missing()));
    }
    @Test void absentCoverageOrInstanceAckRefusesFrozen() {
        var gate = gate(new MemoryStore());
        open(gate);
        drain(gate);
        assertThrows(IllegalStateException.class, () -> gate.apply(new Control(3, State.FROZEN, SCOPE), FreezeEvidence.missing()));
        var full = evidence(gate);
        var missing = new FreezeEvidence(full.expectedBoots(), full.acknowledgements(), Set.of("http"),
                true, true, true, full.watermarks());
        assertThrows(IllegalStateException.class, () -> gate.apply(new Control(3, State.FROZEN, SCOPE), missing));
    }
    @Test void oldGenerationSameGenerationDifferentStateAndUnknownWriterFail() {
        var gate = gate(new MemoryStore());
        open(gate);
        assertThrows(IllegalStateException.class, () -> gate.apply(new Control(0, State.OPEN, SCOPE), FreezeEvidence.missing()));
        assertThrows(IllegalStateException.class, () -> gate.apply(new Control(1, State.DRAINING, SCOPE), FreezeEvidence.missing()));
        assertThrows(IllegalStateException.class, () -> gate.enter(Owner.B_LEGACY, "not-registered", "create"));
    }
    @Test void failedDurabilityNeverAdmitsBAndCannotBlockA() {
        var store = new MemoryStore();
        var gate = gate(store);
        open(gate);
        store.fail = true;
        assertThrows(IllegalStateException.class, () -> gate.enter(Owner.B_LEGACY, "http", "create"));
        assertTrue(gate.snapshot().checkpoint().controlFault());
        assertDoesNotThrow(() -> gate.enter(Owner.A, "a-design", "budget").close());
    }
    @Test void aAdmissionDoesNotWaitForBFilePersistence() throws Exception {
        var store = new MemoryStore();
        var gate = gate(store);
        open(gate);
        store.entered = new CountDownLatch(1);
        store.release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocked = pool.submit(() -> gate.enter(Owner.B_LEGACY, "http", "create"));
            assertTrue(store.entered.await(2, TimeUnit.SECONDS));
            pool.submit(() -> gate.enter(Owner.A, "a-design", "login").close()).get(1, TimeUnit.SECONDS);
            store.release.countDown();
            blocked.get(2, TimeUnit.SECONDS).close();
        } finally {
            store.release.countDown();
            pool.shutdownNow();
        }
    }
    @Test void concurrentAdmissionAndDrainCannotLoseARegisteredWriter() throws Exception {
        var store = new MemoryStore();
        var gate = gate(store);
        open(gate);
        store.entered = new CountDownLatch(1);
        store.release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var admission = pool.submit(() -> gate.enter(Owner.B_LEGACY, "http", "create"));
            assertTrue(store.entered.await(2, TimeUnit.SECONDS));
            var draining = pool.submit(() -> drain(gate));
            store.release.countDown();
            var permit = admission.get(2, TimeUnit.SECONDS);
            draining.get(2, TimeUnit.SECONDS);
            assertEquals(State.DRAINING, gate.snapshot().checkpoint().state());
            assertEquals(1, gate.snapshot().checkpoint().pending().size());
            assertThrows(IllegalStateException.class, () -> gate.apply(new Control(3, State.FROZEN, SCOPE), evidence(gate)));
            permit.close();
        } finally {
            store.release.countDown();
            pool.shutdownNow();
        }
    }
}
