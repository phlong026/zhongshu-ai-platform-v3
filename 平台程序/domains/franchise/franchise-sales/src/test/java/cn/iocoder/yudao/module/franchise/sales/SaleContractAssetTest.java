package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SaleContractAssetTest {
    @Test
    void contractAttachmentMustBeAcceptedInSameOrgAndCorrectType() {
        var assets = mock(AssetAccessPort.class);
        var service = new SaleContractService(null, null, null, null, null, assets, org -> 777,
                null, null, mock(PlatformTransactionManager.class), mock(DataSource.class));
        var post = new PostSnapshot(11L, 22L, 33L, "store", "sales", null, false, Set.of());
        var actor = new AssetAccessPort.Actor(33, 777, 22, 11);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "validateAsset", post, 5L))
                .isInstanceOf(RuntimeException.class);
        when(assets.validateReference(actor, 5L, Set.of("CONTRACT_ATTACHMENT"))).thenReturn(true);
        assertThatCode(() -> ReflectionTestUtils.invokeMethod(service, "validateAsset", post, 5L))
                .doesNotThrowAnyException();
        verify(assets, times(2)).validateReference(actor, 5L, Set.of("CONTRACT_ATTACHMENT"));
        verifyNoMoreInteractions(assets);
    }
}
