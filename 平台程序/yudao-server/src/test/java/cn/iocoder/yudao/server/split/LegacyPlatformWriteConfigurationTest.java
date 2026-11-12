package cn.iocoder.yudao.server.split;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;
import static cn.iocoder.yudao.server.split.LegacyPlatformWriteConfiguration.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyPlatformWriteConfigurationTest {
    private static final String SCOPE = "c".repeat(64);
    @TempDir Path temporary;

    private Path directory() throws IOException {
        return Files.createDirectory(temporary.resolve("gate"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }
    private FileStore initialized(Path directory) throws IOException {
        FileStore store = new FileStore(directory);
        store.save(new Checkpoint(0, State.CLOSED, SCOPE, false, Map.of()));
        return store;
    }
    private LegacyPlatformWriteGate gate(FileStore store) {
        return new LegacyPlatformWriteGate("one", SCOPE, Set.of(), REQUIRED_WRITERS, store);
    }
    private void control(Path directory, long generation, String state) throws IOException {
        FileStore.writeAtomic(directory.resolve("control.properties"), Map.of(
                "format", "legacy-b-control-v1", "generation", Long.toString(generation),
                "state", state, "scope_sha256", SCOPE));
    }

    @Test void configuredControlLoopDoesNotRequireOrReplaceSharedSpringScheduler() throws Exception {
        Path directory = directory();
        try (var store = initialized(directory)) {
            assertEquals(State.CLOSED, store.load().state());
        }
        control(directory, 1, "OPEN");
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "synthetic-gate", Map.of("platform.legacy-b-write-control-directory", directory.toString(),
                    "platform.legacy-b-write-instance-id", "one", "platform.legacy-b-write-scope-sha256", SCOPE)));
            context.register(LegacyPlatformWriteConfiguration.class);
            context.refresh();
            var gate = context.getBean(LegacyPlatformWriteGate.class);
            assertEquals(State.OPEN, gate.snapshot().checkpoint().state());
            assertTrue(context.getBeansOfType(org.springframework.scheduling.TaskScheduler.class).isEmpty());
            assertTrue(context.getBeansOfType(java.util.concurrent.ScheduledExecutorService.class).isEmpty());
            control(directory, 2, "DRAINING");
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> {
                while (gate.snapshot().checkpoint().state() != State.DRAINING) Thread.sleep(20);
            });
            assertDoesNotThrow(() -> gate.enter(Owner.A, "design", "login").close());
        }
        try (var reopened = new FileStore(directory)) {
            assertEquals(State.DRAINING, reopened.load().state());
        }
    }

    @Test void realFilesHotDrainAndPersistBeforeReturningAPermit() throws Exception {
        Path directory = directory();
        try (var gate = gate(initialized(directory))) {
            var reload = new ControlReloader(gate, directory);
            control(directory, 1, "OPEN");
            reload.reload();
            var permit = gate.enter(Owner.B_LEGACY, "platform-http", "create");
            assertTrue(Files.readString(directory.resolve("checkpoint.properties")).contains("pending."));
            control(directory, 2, "DRAINING");
            reload.reload();
            assertEquals(State.DRAINING, gate.snapshot().checkpoint().state());
            assertThrows(IllegalStateException.class, () -> gate.enter(Owner.B_LEGACY, "platform-http", "new"));
            assertDoesNotThrow(() -> gate.enter(Owner.A, "design", "budget").close());
            permit.close();
            assertFalse(Files.readString(directory.resolve("checkpoint.properties")).contains("pending."));
        }
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
    @Test void checkpointMissingOnRestartNeverReinitializesAsOpen() throws Exception {
        Path directory = directory();
        try (var gate = gate(initialized(directory))) {
            control(directory, 1, "OPEN");
            new ControlReloader(gate, directory).reload();
        }
        Files.delete(directory.resolve("checkpoint.properties"));
        try (var restarted = gate(new FileStore(directory))) {
            new ControlReloader(restarted, directory).reload();
            assertTrue(restarted.snapshot().checkpoint().controlFault());
            assertThrows(IllegalStateException.class, () -> restarted.enter(Owner.B_LEGACY, "platform-http", "new"));
            assertDoesNotThrow(() -> restarted.enter(Owner.A, "design", "login").close());
        }
        assertFalse(Files.exists(directory.resolve("checkpoint.properties")));
    }
    @Test void deletingAnEstablishedCheckpointMakesSubsequentAdmissionFail() throws Exception {
        Path directory = directory();
        try (var gate = gate(initialized(directory))) {
            control(directory, 1, "OPEN");
            new ControlReloader(gate, directory).reload();
            Files.delete(directory.resolve("checkpoint.properties"));
            assertThrows(IllegalStateException.class, () -> gate.enter(Owner.B_LEGACY, "platform-http", "new"));
            assertTrue(gate.snapshot().checkpoint().controlFault());
            assertFalse(Files.exists(directory.resolve("checkpoint.properties")));
        }
    }
    @Test void unknownExternalWorkIsRestoredFromDisk() throws Exception {
        Path directory = directory();
        try (var gate = gate(initialized(directory))) {
            control(directory, 1, "OPEN");
            new ControlReloader(gate, directory).reload();
            var permit = gate.enter(Owner.B_LEGACY, "content-jobs", "external-submit");
            permit.markUnknown();
            permit.close();
        }
        try (var restarted = gate(new FileStore(directory))) {
            assertEquals(1, restarted.snapshot().checkpoint().pending().size());
            assertTrue(restarted.snapshot().checkpoint().pending().values().iterator().next().unknown());
        }
    }
    @Test void corruptCheckpointDoesNotPreventExplicitAAdmission() throws Exception {
        Path directory = directory();
        try (var store = initialized(directory)) {
            assertEquals(0, store.load().generation());
        }
        Files.writeString(directory.resolve("checkpoint.properties"), "not-valid-checkpoint\n");
        try (var gate = gate(new FileStore(directory))) {
            assertTrue(gate.snapshot().checkpoint().controlFault());
            assertDoesNotThrow(() -> gate.enter(Owner.A, "design", "payment").close());
            assertThrows(IllegalStateException.class, () -> gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing()));
        }
    }
    @Test void missingControlFaultSurvivesRestartAndValidOldControl() throws Exception {
        Path directory = directory();
        try (var gate = gate(initialized(directory))) {
            new ControlReloader(gate, directory).reload();
            assertTrue(gate.snapshot().checkpoint().controlFault());
        }
        control(directory, 1, "OPEN");
        try (var restarted = gate(new FileStore(directory))) {
            new ControlReloader(restarted, directory).reload();
            assertThrows(IllegalStateException.class, () -> restarted.enter(Owner.B_LEGACY, "platform-http", "create"));
        }
    }
    @Test void duplicateKeysAndWorldReadableControlAreRejected() throws Exception {
        Path directory = directory();
        try (var gate = gate(initialized(directory))) {
            control(directory, 1, "OPEN");
            Path path = directory.resolve("control.properties");
            Files.writeString(path, Files.readString(path) + "generation=2\n");
            assertThrows(IOException.class, () -> FileStore.readFields(path));
            control(directory, 1, "OPEN");
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
            new ControlReloader(gate, directory).reload();
            assertTrue(gate.snapshot().checkpoint().controlFault());
        }
    }
    @Test void symlinkControlAndConcurrentStoreOwnerAreRejected() throws Exception {
        Path directory = directory();
        try (var store = initialized(directory)) {
            assertEquals(State.CLOSED, store.load().state());
            assertThrows(OverlappingFileLockException.class, () -> new FileStore(directory));
            Path target = temporary.resolve("synthetic-target");
            Files.writeString(target, "synthetic-only\n");
            Files.createSymbolicLink(directory.resolve("control.properties"), target);
            assertThrows(IOException.class, () -> FileStore.readFields(directory.resolve("control.properties")));
        }
    }
    @Test void runtimeReloaderHasNoCoverageOrTopologyProofSoFrozenIsRefused() throws Exception {
        Path directory = directory();
        try (var gate = gate(initialized(directory))) {
            var reload = new ControlReloader(gate, directory);
            control(directory, 1, "OPEN");
            reload.reload();
            control(directory, 2, "DRAINING");
            reload.reload();
            control(directory, 3, "FROZEN");
            reload.reload();
            assertEquals(State.DRAINING, gate.snapshot().checkpoint().state());
            assertTrue(gate.snapshot().checkpoint().controlFault());
            assertDoesNotThrow(() -> gate.enter(Owner.A, "design", "approval").close());
        }
    }
}
