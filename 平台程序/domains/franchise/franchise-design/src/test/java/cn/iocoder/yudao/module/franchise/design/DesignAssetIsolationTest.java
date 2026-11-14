package cn.iocoder.yudao.module.franchise.design;

import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.CustomerSchemePort;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.design.remote.RemoteDesignGateway;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DesignAssetIsolationTest {
    private static final PostSnapshot POST = new PostSnapshot(11L, 22L, 33L, "store", "hq-design-admin", null, false, Set.of());
    private static final AssetAccessPort.Actor ACTOR = new AssetAccessPort.Actor(33L, 777L, 22L, 11L);

    @Test
    void remoteDesignUsesBLocalAssetsForTheWholeReferenceUploadChain() {
        DesignServicePort legacy = mock(DesignServicePort.class);
        StoreServiceAccountPort accounts = mock(StoreServiceAccountPort.class);
        when(accounts.requireServiceAccount(33L)).thenReturn(777L);
        var service = new DesignAccessService(legacy, mock(CustomerSchemePort.class), accounts,
                mock(FranchiseCommands.class), mock(CurrentPost.class), mock(PlatformTransactionManager.class), mock(DataSource.class));
        RemoteDesignGateway remote = mock(RemoteDesignGateway.class);
        AssetAccessPort assets = mock(AssetAccessPort.class);
        service.setRemoteGateway(remote);
        service.setLocalAssets(assets);
        when(assets.createUploadTicket(ACTOR, "DESIGN_REFERENCE", "image/png", 8L, "a".repeat(64)))
                .thenReturn(new AssetAccessPort.UploadTicketView(123L, "/admin-api/franchise/assets/123/content?token=opaque"));
        when(assets.completeUpload(ACTOR, 123L)).thenReturn(new AssetAccessPort.AssetCompleted(123L, "ACCEPTED", null));
        when(assets.createDownloadUrl(ACTOR, 123L)).thenReturn(new AssetAccessPort.DownloadUrlView("/admin-api/franchise/assets/123/content?token=read", 999L));

        var ticket = service.createUploadTicket(POST,
                new DesignServicePort.UploadTicketCreate("DESIGN_REFERENCE", "image/png", 8L, "a".repeat(64)), "client-key");
        var completed = service.completeUpload(POST, 123L, "client-key");
        var download = service.createDownloadUrl(POST, 123L);

        assertThat(ticket.uploadUrl()).startsWith("/admin-api/franchise/assets/");
        assertThat(completed.uploadStatus()).isEqualTo("ACCEPTED");
        assertThat(download.downloadUrl()).startsWith("/admin-api/franchise/assets/");
        verify(assets).createUploadTicket(ACTOR, "DESIGN_REFERENCE", "image/png", 8L, "a".repeat(64));
        verify(assets).completeUpload(ACTOR, 123L);
        verify(assets).createDownloadUrl(ACTOR, 123L);
        verifyNoInteractions(remote, legacy);
    }

    @Test
    void bReferenceIdsFailBeforeAnyPaidRemoteElevationCall() {
        DesignServicePort legacy = mock(DesignServicePort.class);
        var service = new DesignAccessService(legacy, mock(CustomerSchemePort.class), mock(StoreServiceAccountPort.class),
                mock(FranchiseCommands.class), mock(CurrentPost.class), mock(PlatformTransactionManager.class), mock(DataSource.class));
        RemoteDesignGateway remote = mock(RemoteDesignGateway.class);
        service.setRemoteGateway(remote);

        assertThatThrownBy(() -> service.requireReferenceInputSupported(java.util.Map.of("referenceAssetIds", java.util.List.of("123"))))
                .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(1_112_100_003);
                    assertThat(error.getMessage()).contains("暂不支持参考图参与生成");
                });
        verifyNoInteractions(remote, legacy);
    }
}
