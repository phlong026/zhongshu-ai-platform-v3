package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.finance.dal.FinFileDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinVoucherMapper;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinAssetReferenceTest {
    private static final PostSnapshot POST = new PostSnapshot(11L, 22L, 33L, "store", "finance", null, false, Set.of());
    private static final AssetAccessPort.Actor ACTOR = new AssetAccessPort.Actor(33, 777, 22, 11);
    private final AssetAccessPort assets = mock(AssetAccessPort.class);
    private final FinVoucherMapper files = mock(FinVoucherMapper.class);

    @AfterEach void clear() { TenantContextHolder.clear(); }

    @Test
    void assetTypeOrStatusFailureCannotFallBackToHistoricalSameId() {
        when(assets.validateReference(ACTOR, 5, Set.of("FIN_VOUCHER"))).thenReturn(false);
        when(assets.legacyReferenceAllowed(ACTOR, 5)).thenReturn(false);
        assertThatThrownBy(() -> check(5)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(files);
    }

    @Test
    void acceptedVoucherPassesWithoutReadingHistoricalFiles() {
        when(assets.validateReference(ACTOR, 5, Set.of("FIN_VOUCHER"))).thenReturn(true);
        assertThatCode(() -> check(5)).doesNotThrowAnyException();
        verifyNoInteractions(files);
    }

    @Test
    void historicalFilesMustMatchOrgTenantAndVoucherMime() {
        TenantContextHolder.setTenantId(1L);
        when(assets.legacyReferenceAllowed(ACTOR, 5)).thenReturn(true);
        var file = new FinFileDO().setId(5L).setCompanyId(33L).setTenantId(1L).setContentType("application/pdf");
        when(files.selectById(5L)).thenReturn(file);
        assertThatCode(() -> check(5)).doesNotThrowAnyException();
        file.setContentType("video/mp4");
        assertThatThrownBy(() -> check(5)).isInstanceOf(RuntimeException.class);
        file.setContentType("application/pdf").setCompanyId(34L);
        assertThatThrownBy(() -> check(5)).isInstanceOf(RuntimeException.class);
        file.setCompanyId(33L).setTenantId(2L);
        assertThatThrownBy(() -> check(5)).isInstanceOf(RuntimeException.class);
    }

    private void check(long id) {
        FinReceiptService.requireVoucherAsset(POST, id, assets, files, org -> 777);
    }
}
