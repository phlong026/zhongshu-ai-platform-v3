package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.content.controller.admin.ContentAssetController;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentAssetIsolationTest {
    @Test
    void contentEndpointsCannotCompleteOrDownloadFinanceAttachments() {
        var assets = mock(AssetAccessPort.class);
        var post = new PostSnapshot(11L, 22L, 33L, "store", "sales", null, false, Set.of());
        var actor = new AssetAccessPort.Actor(33, 777, 22, 11);
        var controller = new ContentAssetController();
        ReflectionTestUtils.setField(controller, "assets", assets);
        ReflectionTestUtils.setField(controller, "currentPost", (CurrentPost) () -> Optional.of(post));
        ReflectionTestUtils.setField(controller, "serviceAccounts", (StoreServiceAccountPort) org -> 777);
        when(assets.assetTypeOf(actor, 5)).thenReturn("FIN_VOUCHER");
        assertThatThrownBy(() -> controller.complete(5L)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> controller.downloadTicket(5L)).isInstanceOf(RuntimeException.class);
        verify(assets, never()).completeUpload(actor, 5);
        verify(assets, never()).createDownloadUrl(actor, 5);
    }
}
