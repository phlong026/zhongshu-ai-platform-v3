package cn.iocoder.yudao.module.franchise.asset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B_END 白名单快照锁（评审基线强制清单 #13）：本通道白名单须与 design AssetTypePolicy.B_END 保持一致。
 * 跨域经 franchise-api Port，禁直依赖 design 模块——design 侧调整 B_END 时本测试即红，
 * 提示按 14 文档 §五.2 登记后同步本通道白名单。
 */
class AssetTypeBEndSnapshotTest {

    @Test
    void bEndTypesMatchDesignAssetTypePolicySnapshot() {
        // DESIGN_REFERENCE 在独立 B 中归 B 自有资产；不再走 A 的 C_END 素材入口。
        assertThat(AssetAccessService.B_END_TYPES).containsExactlyInAnyOrder(
                "FIN_VOUCHER", "ENG_DRAWING", "ENG_SITE_PHOTO", "CONTRACT_ATTACHMENT", "CONTENT_ASSET", "DESIGN_REFERENCE");
    }

    @Test
    void designReferenceDoesNotGrantBossOrCrossDomainRolesAssetAccess() {
        assertThat(AssetAccessService.channelAllowed("DESIGN_REFERENCE", "sales")).isTrue();
        assertThat(AssetAccessService.channelAllowed("DESIGN_REFERENCE", "sales-lead")).isTrue();
        assertThat(AssetAccessService.channelAllowed("DESIGN_REFERENCE", "hq-design-admin")).isTrue();
        assertThat(AssetAccessService.channelAllowed("DESIGN_REFERENCE", "boss")).isFalse();
        assertThat(AssetAccessService.channelAllowed("DESIGN_REFERENCE", "finance")).isFalse();
        assertThat(AssetAccessService.channelAllowed("DESIGN_REFERENCE", "engineer")).isFalse();
    }

}
