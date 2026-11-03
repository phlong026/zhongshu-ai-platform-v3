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
        // 快照取自 yudao-module-design AssetTypePolicy.B_END（2026-11-03 实读：五类 B_END 免审）
        assertThat(AssetAccessService.B_END_TYPES).containsExactlyInAnyOrder(
                "FIN_VOUCHER", "ENG_DRAWING", "ENG_SITE_PHOTO", "CONTRACT_ATTACHMENT", "CONTENT_ASSET");
    }

}
