package cn.iocoder.yudao.module.franchise.design.remote;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Operation;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Empty;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Note;
import cn.iocoder.yudao.module.franchise.design.remote.transport.DesignCapabilityTransport;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteDesignAdmissionTest {
    @Test
    void hundredSimultaneousCallersAreBoundedWithoutWaiting() throws Exception {
        var gate = new RemoteDesignAdmission(16, 4);
        var pool = Executors.newFixedThreadPool(100);
        var start = new CountDownLatch(1);
        var attempted = new CountDownLatch(100);
        var release = new CountDownLatch(1);
        var accepted = new AtomicInteger();
        var rejected = new AtomicInteger();
        var jobs = new ArrayList<Future<?>>();
        try {
            for (int index = 0; index < 100; index++) {
                long org = index % 20 + 1;
                jobs.add(pool.submit(() -> {
                    start.await();
                    RemoteDesignAdmission.Permit permit = null;
                    try {
                        permit = gate.enter(org);
                        accepted.incrementAndGet();
                    } catch (RemoteDesignAdmission.BusyException expected) {
                        rejected.incrementAndGet();
                    } finally {
                        attempted.countDown();
                    }
                    if (permit != null) {
                        try { release.await(); } finally { permit.close(); }
                    }
                    return null;
                }));
            }
            start.countDown();
            assertTrue(attempted.await(10, TimeUnit.SECONDS), "busy calls must not wait for held permits");
            assertEquals(16, accepted.get());
            assertEquals(84, rejected.get());
            release.countDown();
            for (var job : jobs) job.get(10, TimeUnit.SECONDS);
            for (int org = 1; org <= 1000; org++) {
                try (var permit = gate.enter(org)) { /* released organizations must not leak capacity */ }
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void organizationLimitDoesNotConsumeOtherOrganizationsCapacity() {
        var gate = new RemoteDesignAdmission(3, 1);
        var first = gate.enter(1);
        assertThrows(RemoteDesignAdmission.BusyException.class, () -> gate.enter(1));
        try (var other = gate.enter(2)) { assertNotNull(other); }
        first.close();
        first.close();
        try (var retry = gate.enter(1)) { assertNotNull(retry); }
    }

    @Test
    void overloadRejectsBeforeCommandPersistenceOrRemoteDispatch() {
        var gate = new RemoteDesignAdmission(1, 1);
        var commands = mock(RemoteDesignCommandStore.class);
        var transport = mock(DesignCapabilityTransport.class);
        var gateway = new RemoteDesignGateway("review", transport, commands, new ObjectMapper(), gate);
        var post = new PostSnapshot(1, 2, 3, "store", "sales", null, false, Set.of());
        try (var occupied = gate.enter(3)) {
            var failure = assertThrows(ServiceException.class,
                    () -> gateway.call(post, Operation.NOTE_POLISH, "stable-key", null));
            assertEquals(429, failure.getCode());
            verifyNoInteractions(commands, transport);
        }
    }

    @Test
    void storageAndTransportFailuresReleaseThePermit() {
        var gate = new RemoteDesignAdmission(1, 1);
        var commands = mock(RemoteDesignCommandStore.class);
        var transport = mock(DesignCapabilityTransport.class);
        var gateway = new RemoteDesignGateway("review", transport, commands, new ObjectMapper(), gate);
        var post = new PostSnapshot(1, 2, 3, "store", "sales", null, false, Set.of());
        when(commands.prepare(eq(post), any())).thenThrow(new IllegalStateException("database unavailable"));
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThrows(IllegalStateException.class,
                    () -> gateway.call(post, Operation.NOTE_POLISH, "stable-key", new Note("text", "sales")));
        }
        verify(commands, times(2)).prepare(eq(post), any());
        when(transport.exchange(eq(Operation.LIST_CASE_STYLES), any())).thenThrow(new IllegalStateException("timeout"));
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThrows(IllegalStateException.class,
                    () -> gateway.call(post, Operation.LIST_CASE_STYLES, null, new Empty()));
        }
        verify(transport, times(2)).exchange(eq(Operation.LIST_CASE_STYLES), any());
        try (var permit = gate.enter(post.orgId())) { assertNotNull(permit); }
    }

    @Test
    void busyRecoveryLeavesPersistedCommandUntouched() {
        var gate = new RemoteDesignAdmission(1, 1);
        var commands = mock(RemoteDesignCommandStore.class);
        var transport = mock(DesignCapabilityTransport.class);
        var gateway = new RemoteDesignGateway("review", transport, commands, new ObjectMapper(), gate);
        var post = new PostSnapshot(1, 2, 3, "store", "sales", null, false, Set.of());
        var command = new RemoteDesignCommandStore.Command(1, 1, "key", null, post, "fingerprint", null,
                "UNKNOWN", null, null, false);
        when(commands.recoverable(25)).thenReturn(java.util.List.of(command));
        try (var occupied = gate.enter(post.orgId())) { gateway.recover(); }
        verify(commands).recoverable(25);
        verifyNoMoreInteractions(commands);
        verifyNoInteractions(transport);
    }

    @Test
    void invalidLimitsFailConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new RemoteDesignAdmission(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RemoteDesignAdmission(2, 3));
    }
}
