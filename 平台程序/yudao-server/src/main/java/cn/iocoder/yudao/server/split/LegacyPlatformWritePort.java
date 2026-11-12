package cn.iocoder.yudao.server.split;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** G1 local admission contract. Callers must resolve ownership on the server. */
public interface LegacyPlatformWritePort {
    enum Owner { A, B_LEGACY, UNKNOWN }
    enum State { CLOSED, OPEN, DRAINING, FROZEN }

    record Control(long generation, State state, String scopeHash) { }
    record Work(String writer, String operation, boolean unknown) { }
    record Checkpoint(long generation, State state, String scopeHash,
                      boolean controlFault, Map<String, Work> pending) {
        public Checkpoint { pending = Map.copyOf(pending); }
    }
    record Snapshot(String instanceId, String bootId, Checkpoint checkpoint) { }
    record Ack(String instanceId, String bootId, long generation, String scopeHash,
               State state, int pending, boolean controlFault) { }
    record FreezeEvidence(Map<String, String> expectedBoots, Map<String, Ack> acknowledgements,
                          Set<String> wiredWriters, boolean durableWorkReviewed,
                          boolean callbackReceiverActive, boolean uniqueConsumers,
                          Map<String, String> watermarks) {
        public FreezeEvidence {
            expectedBoots = Map.copyOf(expectedBoots);
            acknowledgements = Map.copyOf(acknowledgements);
            wiredWriters = Set.copyOf(wiredWriters);
            watermarks = Map.copyOf(watermarks);
        }
        public static FreezeEvidence missing() {
            return new FreezeEvidence(Map.of(), Map.of(), Set.of(), false, false, false, Map.of());
        }
    }

    interface Permit extends AutoCloseable {
        /** Reserve before publishing after-commit work; never reconstruct a permit from request input. */
        Permit fork(String writer, String operation);
        /** A timeout without a terminal receipt must remain pending across close and restart. */
        void markUnknown();
        @Override void close();
    }

    interface StateStore extends AutoCloseable {
        Checkpoint load() throws IOException;
        void save(Checkpoint checkpoint) throws IOException;
        @Override default void close() throws IOException { }
    }

    Permit enter(Owner owner, String writer, String operation);
    void apply(Control control, FreezeEvidence evidence);
    void controlUnavailable();
    Snapshot snapshot();
}
