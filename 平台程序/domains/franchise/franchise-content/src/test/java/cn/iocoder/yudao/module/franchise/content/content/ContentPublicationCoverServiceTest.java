package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContentPublicationCoverServiceTest {
    private final CurrentPost posts = mock(CurrentPost.class);
    private final StoreServiceAccountPort accounts = mock(StoreServiceAccountPort.class);
    private final AssetAccessPort assets = mock(AssetAccessPort.class);
    private final ContentPublicationCoverService covers = new ContentPublicationCoverService(posts, accounts, assets);

    @Test void absentCoverNeedsNoAssetRequestAndMalformedIdsFailBeforeReading() {
        assertThat(covers.validate(100, 7, null)).isNull();
        for (Object invalid : new Object[]{"", "https://example.test/image.png", "1.2", -1, "9223372036854775808"})
            assertThatThrownBy(() -> covers.validate(100, 7, invalid)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(posts, accounts, assets);
    }

    @Test void acceptedOwnedImageKeepsStableIdAndRejectsWrongContentOrRevokedAccess() {
        when(posts.require()).thenReturn(new PostSnapshot(9L, 7L, 100L, "store", "sales", null, false, Set.of()));
        when(accounts.requireServiceAccount(100L)).thenReturn(50L);
        when(assets.readContentMedia(new AssetAccessPort.Actor(100L, 50L, 7L, 9L), 88L))
                .thenReturn(new AssetAccessPort.ContentMedia(new byte[]{1}, "image/png", "sha", 0, 100, 100));
        assertThat(covers.validate(100L, 7L, "88")).isEqualTo(88L);
        assertThatThrownBy(() -> covers.validate(101L, 7L, "88")).isInstanceOf(ServiceException.class);
        when(assets.readContentMedia(any(), eq(88L)))
                .thenReturn(new AssetAccessPort.ContentMedia(new byte[]{1}, "video/mp4", "sha", 1, 100, 100));
        assertThatThrownBy(() -> covers.validate(100L, 7L, "88")).hasMessageContaining("封面图片");
        when(assets.readContentMedia(any(), eq(88L))).thenThrow(new ServiceException(403, "asset unavailable"));
        assertThatThrownBy(() -> covers.validate(100L, 7L, "88")).hasMessage("asset unavailable");
    }
}
