package cn.iocoder.yudao.server.split;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;

/** Opt-in A-local control. No business controllers, tokens, payments or workers are wired by G1. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.legacy-b-write-control-directory")
public class LegacyPlatformWriteConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(LegacyPlatformWriteConfiguration.class);
    public static final Set<String> REQUIRED_WRITERS = Set.of(
            "platform-http", "franchise-http", "legacy-session", "identity-provision",
            "system-mutations", "system-events", "commerce-wallet", "commerce-payment",
            "design-driver", "ai-cancel-runtime", "content-jobs", "outbox-export-privacy", "remote-recovery");

    @Bean(destroyMethod = "close")
    LegacyPlatformWriteGate legacyPlatformWriteGate(
            @Value("${platform.legacy-b-write-control-directory}") String directory,
            @Value("${platform.legacy-b-write-instance-id:legacy-a}") String instance,
            @Value("${platform.legacy-b-write-scope-sha256:UNRESOLVED}") String scope) {
        StateStore store;
        try {
            store = new FileStore(Path.of(directory));
        } catch (IOException | RuntimeException failure) {
            LOG.error("[legacy-b-gate] store unavailable; no B admission, failureType={}", failure.getClass().getSimpleName());
            store = new UnavailableStore();
        }
        // No topology/coverage evidence is supplied by G1. FROZEN must remain impossible here.
        return new LegacyPlatformWriteGate(instance, scope, Set.of(), REQUIRED_WRITERS, store);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ControlReloader legacyPlatformWriteControlReloader(LegacyPlatformWriteGate gate,
            @Value("${platform.legacy-b-write-control-directory}") String directory) {
        ControlReloader reloader = new ControlReloader(gate, Path.of(directory));
        reloader.reload();
        return reloader;
    }

    static final class UnavailableStore implements StateStore {
        @Override public Checkpoint load() throws IOException { throw new IOException("STORE_UNAVAILABLE"); }
        @Override public void save(Checkpoint value) throws IOException { throw new IOException("STORE_UNAVAILABLE"); }
    }

    public static final class ControlReloader implements AutoCloseable {
        private final LegacyPlatformWriteGate gate;
        private final Path directory;
        // Keep B file I/O off the application's shared A worker scheduler.
        private final ScheduledExecutorService controlLoop = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "legacy-b-control");
            thread.setDaemon(true);
            return thread;
        });
        private boolean started;
        private boolean errorReported;
        public ControlReloader(LegacyPlatformWriteGate gate, Path directory) {
            this.gate = gate;
            this.directory = directory;
        }
        public synchronized void start() {
            if (started) return;
            controlLoop.scheduleWithFixedDelay(this::reload, 1, 1, TimeUnit.SECONDS);
            started = true;
        }
        @Override public void close() { controlLoop.shutdownNow(); }
        public void reload() {
            try {
                Map<String, String> fields = FileStore.readFields(directory.resolve("control.properties"));
                if (!fields.keySet().equals(Set.of("format", "generation", "state", "scope_sha256"))
                        || !"legacy-b-control-v1".equals(fields.get("format"))) throw new IOException("CONTROL_FORMAT");
                gate.apply(new Control(Long.parseLong(fields.get("generation")),
                        State.valueOf(fields.get("state")), fields.get("scope_sha256")), FreezeEvidence.missing());
                errorReported = false;
            } catch (IOException | RuntimeException failure) {
                gate.controlUnavailable();
                if (!errorReported) {
                    LOG.warn("[legacy-b-gate] control rejected; B closed, instance={} failureType={}",
                            gate.snapshot().instanceId(), failure.getClass().getSimpleName());
                    errorReported = true;
                }
            }
        }
    }

    /** A private local directory and one writer per checkpoint; never initialise on a missing load. */
    public static final class FileStore implements StateStore {
        private static final int MAX_BYTES = 2 * 1024 * 1024;
        private static final Set<String> BASE_KEYS = Set.of("format", "generation", "state", "scope_sha256", "control_fault");
        private final Path directory;
        private final Path checkpointFile;
        private final FileChannel lockChannel;
        private final FileLock lock;
        private boolean savedOrLoaded;
        private boolean closed;

        public FileStore(Path directory) throws IOException {
            this.directory = directory.toAbsolutePath().normalize();
            checkPrivate(this.directory, true);
            this.checkpointFile = this.directory.resolve("checkpoint.properties");
            Path lockFile = this.directory.resolve("writer.lock");
            FileChannel channel = FileChannel.open(lockFile,
                    Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                checkPrivate(lockFile, false);
                FileLock acquired = channel.tryLock();
                if (acquired == null) throw new IOException("STORE_ALREADY_LOCKED");
                lockChannel = channel;
                lock = acquired;
            } catch (IOException | RuntimeException failure) {
                channel.close();
                throw failure;
            }
        }

        @Override public synchronized Checkpoint load() throws IOException {
            requireOpen();
            Map<String, String> fields = readFields(checkpointFile);
            if (!fields.keySet().containsAll(BASE_KEYS) || !"legacy-b-checkpoint-v1".equals(fields.get("format"))) {
                throw new IOException("CHECKPOINT_FORMAT");
            }
            Map<String, Work> pending = new HashMap<>();
            for (var entry : fields.entrySet()) {
                if (BASE_KEYS.contains(entry.getKey())) continue;
                if (!entry.getKey().startsWith("pending.")) throw new IOException("CHECKPOINT_UNKNOWN_FIELD");
                String token = entry.getKey().substring(8);
                if (!java.util.UUID.fromString(token).toString().equals(token)) throw new IOException("CHECKPOINT_TOKEN");
                String[] parts = entry.getValue().split(",", -1);
                if (parts.length != 3 || !(parts[2].equals("ACTIVE") || parts[2].equals("UNKNOWN"))) {
                    throw new IOException("CHECKPOINT_WORK");
                }
                pending.put(token, new Work(LegacyPlatformWriteGate.label(parts[0]),
                        LegacyPlatformWriteGate.label(parts[1]), parts[2].equals("UNKNOWN")));
            }
            String fault = fields.get("control_fault");
            if (!(fault.equals("true") || fault.equals("false"))) throw new IOException("CHECKPOINT_FAULT");
            Checkpoint value = new Checkpoint(Long.parseLong(fields.get("generation")),
                    State.valueOf(fields.get("state")), fields.get("scope_sha256"), Boolean.parseBoolean(fault), pending);
            savedOrLoaded = true;
            return value;
        }

        @Override public synchronized void save(Checkpoint value) throws IOException {
            requireOpen();
            boolean exists = Files.exists(checkpointFile, LinkOption.NOFOLLOW_LINKS);
            if (savedOrLoaded && !exists) throw new IOException("CHECKPOINT_DISAPPEARED");
            if (!exists && (value.generation() != 0 || value.state() != State.CLOSED
                    || value.controlFault() || !value.pending().isEmpty())) throw new IOException("EXPLICIT_INITIAL_CHECKPOINT_REQUIRED");
            if (exists) checkPrivate(checkpointFile, false);
            Map<String, String> fields = new TreeMap<>();
            fields.put("format", "legacy-b-checkpoint-v1");
            fields.put("generation", Long.toString(value.generation()));
            fields.put("state", value.state().name());
            fields.put("scope_sha256", value.scopeHash());
            fields.put("control_fault", Boolean.toString(value.controlFault()));
            value.pending().forEach((token, work) -> fields.put("pending." + token,
                    work.writer() + "," + work.operation() + "," + (work.unknown() ? "UNKNOWN" : "ACTIVE")));
            writeAtomic(checkpointFile, fields);
            savedOrLoaded = true;
        }

        static Map<String, String> readFields(Path path) throws IOException {
            checkPrivate(path.getParent(), true);
            checkPrivate(path, false);
            byte[] bytes;
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("STATE_SIZE");
            Map<String, String> fields = new HashMap<>();
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
                int separator = line.indexOf('=');
                if (separator <= 0 || separator == line.length() - 1 || line.indexOf('=', separator + 1) >= 0) {
                    throw new IOException("STATE_LINE");
                }
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                if (!key.matches("[A-Za-z0-9_.-]+") || !value.matches("[A-Za-z0-9_.:,-]+")
                        || fields.putIfAbsent(key, value) != null) throw new IOException("STATE_DUPLICATE_OR_INVALID_FIELD");
            }
            return fields;
        }

        static void writeAtomic(Path destination, Map<String, String> fields) throws IOException {
            Path directory = destination.getParent();
            checkPrivate(directory, true);
            StringBuilder encoded = new StringBuilder();
            for (var entry : new TreeMap<>(fields).entrySet()) {
                if (!entry.getKey().matches("[A-Za-z0-9_.-]+") || !entry.getValue().matches("[A-Za-z0-9_.:,-]+")) {
                    throw new IOException("STATE_ENCODING");
                }
                encoded.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            }
            byte[] bytes = encoded.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) throw new IOException("STATE_SIZE");
            Path temporary = Files.createTempFile(directory, ".gate-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) output.write(buffer);
                    output.force(true);
                }
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                try (var parent = FileChannel.open(directory, StandardOpenOption.READ)) {
                    parent.force(true);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        private static void checkPrivate(Path path, boolean directory) throws IOException {
            if (path == null || Files.isSymbolicLink(path)
                    || (directory ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    : !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) throw new IOException("STATE_PATH");
            var owner = path.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            if (!Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(owner)) throw new IOException("STATE_OWNER");
            var allowed = PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------");
            if (!allowed.containsAll(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("STATE_PERMISSIONS");
            }
        }
        private void requireOpen() throws IOException {
            if (closed || !lock.isValid()) throw new IOException("STORE_CLOSED");
        }
        @Override public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            try { lock.release(); } finally { lockChannel.close(); }
        }
    }
}
