package cn.iocoder.yudao.module.franchise.design.remote;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.iocoder.yudao.module.franchise.api.*;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;

import org.junit.jupiter.api.Test;

import java.util.*;

class RemoteReferenceReaderTest {
    final PostSnapshot post = new PostSnapshot(11, 22, 33, "store", "sales", null, false, Set.of());
    final AssetAccessPort.Actor actor = new AssetAccessPort.Actor(33, 777, 22, 11);
    final AssetAccessPort assets = mock(AssetAccessPort.class);
    final RemoteDesignGateway remote = mock(RemoteDesignGateway.class);
    final ReferenceMirrorStore store = mock(ReferenceMirrorStore.class);
    final ReferenceByteUploader upload = mock(ReferenceByteUploader.class);
    final RemoteReferenceReader reader = new RemoteReferenceReader("B", remote, store, upload);
    final byte[] bytes = {1, 2, 3};
    final String sha = "a".repeat(64);
    final ReferenceMirrorStore.Scope scope =
            new ReferenceMirrorStore.Scope("B", 1, 33, 22, 11, 123, sha);
    final ReferenceMirrorStore.Lease fresh =
            new ReferenceMirrorStore.Lease(scope, "lease", "attempt", false, null);

    DesignServicePort.DesignProjectCreate command(Object refs) {
        return new DesignServicePort.DesignProjectCreate(
                "SELF_UPLOAD",
                null,
                null,
                Map.of("referenceAssetIds", refs, "width", 10),
                "ELEVATION",
                "project-key");
    }

    void setup() {
        when(assets.readDesignReference(actor, 123))
                .thenReturn(new AssetAccessPort.ReferenceContent(bytes, "image/png", sha));
        when(remote.tenant(post)).thenReturn(1L);
        when(store.claim(scope)).thenReturn(fresh);
        when(remote.call(eq(post), eq(Operation.CREATE_UPLOAD_TICKET), anyString(), any()))
                .thenReturn(
                        new DesignServicePort.UploadTicketView(
                                901, "https://objects.example/ref?signature=opaque"));
        when(remote.call(eq(post), eq(Operation.COMPLETE_UPLOAD), anyString(), any()))
                .thenReturn(new DesignServicePort.AssetCompleted(901, "ACCEPTED", null));
    }

    @Test
    void copiesBytesThenMapsToRemoteIdWithoutChangingInputOrCharging() {
        setup();
        var original = command(List.of("123"));
        var translated = reader.translate(post, original, assets, actor);
        assertThat(translated.requirementInputs().get("referenceAssetIds"))
                .isEqualTo(List.of("901"));
        assertThat(original.requirementInputs().get("referenceAssetIds")).isEqualTo(List.of("123"));
        assertThat(translated.idempotencyKey()).isEqualTo("project-key");
        verify(upload).put("https://objects.example/ref?signature=opaque", bytes, "image/png");
        verify(remote)
                .call(
                        post,
                        Operation.CREATE_UPLOAD_TICKET,
                        "reference:attempt:ticket",
                        new DesignServicePort.UploadTicketCreate(
                                "DESIGN_REFERENCE", "image/png", 3, sha));
        verify(remote)
                .call(
                        post,
                        Operation.COMPLETE_UPLOAD,
                        "reference:attempt:complete",
                        new Asset(901));
        verify(store).accept(fresh, 901);
        verify(store).release(fresh);
        verify(remote).tenant(post);
        verifyNoMoreInteractions(remote);
    }

    @Test
    void validatesAllImagesBeforeTheFirstRemoteCall() {
        setup();
        when(assets.readDesignReference(actor, 124))
                .thenThrow(new IllegalArgumentException("forbidden"));
        assertThatThrownBy(
                        () -> reader.translate(post, command(List.of("123", "124")), assets, actor))
                .hasMessage("forbidden");
        verifyNoInteractions(remote, store);
    }

    @Test
    void acceptedMappingSurvivesReaderRestartButStillRequiresSourceAuthorization() {
        setup();
        when(store.claim(scope))
                .thenReturn(new ReferenceMirrorStore.Lease(scope, null, null, false, 901L));
        var result = reader.translate(post, command(List.of("123")), assets, actor);
        assertThat(result.requirementInputs().get("referenceAssetIds")).isEqualTo(List.of("901"));
        verify(assets).readDesignReference(actor, 123);
        verify(upload, never()).put(anyString(), any(), anyString());
        verify(remote, never()).call(any(), any(), any(), any());
    }

    @Test
    void lostUploadResponseReconcilesOriginalTicketWithoutAnotherPut() {
        setup();
        doThrow(new IllegalStateException("response lost"))
                .when(upload)
                .put(anyString(), any(), anyString());
        assertThatThrownBy(() -> reader.translate(post, command(List.of("123")), assets, actor))
                .hasMessage("response lost");
        var recovery = new ReferenceMirrorStore.Lease(scope, "next-lease", "attempt", true, null);
        when(store.claim(scope)).thenReturn(recovery);
        reader.translate(post, command(List.of("123")), assets, actor);
        verify(upload, times(1)).put(anyString(), any(), anyString());
        verify(store).accept(recovery, 901);
        verify(remote, times(2))
                .call(
                        eq(post),
                        eq(Operation.CREATE_UPLOAD_TICKET),
                        eq("reference:attempt:ticket"),
                        any());
    }

    @Test
    void rejectedPreviousCopyGetsANewAttemptBeforeAnyProjectOrPaidJob() {
        setup();
        var recovery = new ReferenceMirrorStore.Lease(scope, "lease", "old", true, null);
        var next = new ReferenceMirrorStore.Lease(scope, "lease", "new", false, null);
        when(store.claim(scope)).thenReturn(recovery);
        when(store.restart(recovery)).thenReturn(next);
        when(remote.call(
                        eq(post),
                        eq(Operation.COMPLETE_UPLOAD),
                        eq("reference:old:complete"),
                        any()))
                .thenReturn(new DesignServicePort.AssetCompleted(901, "REJECTED", "missing bytes"));
        reader.translate(post, command(List.of("123")), assets, actor);
        verify(remote)
                .call(
                        eq(post),
                        eq(Operation.CREATE_UPLOAD_TICKET),
                        eq("reference:new:ticket"),
                        any());
        verify(store).accept(next, 901);
        verify(upload, times(1)).put(anyString(), any(), anyString());
    }

    @Test
    void pendingOrMismatchedCompletionNeverPublishesAMapping() {
        setup();
        when(remote.call(eq(post), eq(Operation.COMPLETE_UPLOAD), anyString(), any()))
                .thenReturn(new DesignServicePort.AssetCompleted(902, "ACCEPTED", null));
        assertThatThrownBy(() -> reader.translate(post, command(List.of("123")), assets, actor))
                .hasMessageContaining("不匹配");
        verify(store, never()).accept(any(), anyLong());
    }

    @Test
    void pendingRecoveryDoesNotReuploadOrStartANewAttempt() {
        setup();
        when(store.claim(scope))
                .thenReturn(new ReferenceMirrorStore.Lease(scope, "lease", "attempt", true, null));
        when(remote.call(eq(post), eq(Operation.COMPLETE_UPLOAD), anyString(), any()))
                .thenReturn(new DesignServicePort.AssetCompleted(901, "PENDING", null));
        assertThatThrownBy(() -> reader.translate(post, command(List.of("123")), assets, actor))
                .hasMessageContaining("校验中");
        verify(upload, never()).put(anyString(), any(), anyString());
        verify(store, never()).restart(any());
        verify(store, never()).accept(any(), anyLong());
    }

    @Test
    void malformedDuplicateExcessiveAndNonpositiveIdsAreRejectedBeforeReads() {
        for (Object raw :
                List.of(
                        "123",
                        List.of("123", "123"),
                        List.of("0"),
                        List.of("01"),
                        List.of("9223372036854775808"),
                        List.of("1", "2", "3", "4")))
            assertThatThrownBy(() -> reader.translate(post, command(raw), assets, actor))
                    .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(assets, remote, store, upload);
    }

    @Test
    void noReferencesLeaveTheExistingProjectContractUntouched() {
        var command = command(List.of());
        assertThat(reader.translate(post, command, assets, actor)).isSameAs(command);
        verifyNoInteractions(assets, remote, store, upload);
    }

    @Test
    void sameOrganizationConcurrentReadIsRejectedBeforeLoadingMoreBytes() throws Exception {
        setup();
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(assets.readDesignReference(actor, 123))
                .thenAnswer(
                        invocation -> {
                            started.countDown();
                            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                throw new IllegalStateException("test timeout");
                            return new AssetAccessPort.ReferenceContent(bytes, "image/png", sha);
                        });
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first =
                    executor.submit(
                            () -> reader.translate(post, command(List.of("123")), assets, actor));
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> reader.translate(post, command(List.of("123")), assets, actor))
                    .hasMessageContaining("繁忙");
            release.countDown();
            assertThat(
                            first.get(5, java.util.concurrent.TimeUnit.SECONDS)
                                    .requirementInputs()
                                    .get("referenceAssetIds"))
                    .isEqualTo(List.of("901"));
            verify(assets, times(1)).readDesignReference(actor, 123);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
