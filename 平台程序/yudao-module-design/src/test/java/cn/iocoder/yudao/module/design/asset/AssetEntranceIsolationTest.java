package cn.iocoder.yudao.module.design.asset;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 纯单测（14 文档 D-基1-4a / AC5/AC11）：入口 audience 双向隔离——直接测包级纯函数
 * {@code AssetService.resolvePolicyForEntrance}（未知类型/B_END 进 C 门/C_END 进 B 门/合法放行四分支），
 * 不经 null 依赖（Codex 补审 P2：原写法以任意非 ServiceException 证明放行，NPE 也能通过）。
 */
class AssetEntranceIsolationTest {

    private static final String SHA = "a".repeat(64);

    private static int codeOf(Runnable call) {
        try {
            call.run();
        } catch (ServiceException e) {
            return e.getCode();
        }
        return 0;
    }

    @Test
    void cEndEntranceRejectsBEndAndUnknownTypes() {
        for (String type : new String[]{"FIN_VOUCHER", "ENG_DRAWING", "ENG_SITE_PHOTO", "CONTRACT_ATTACHMENT"}) {
            assertThat(codeOf(() -> AssetService.resolvePolicyForEntrance(type, AssetTypePolicy.Audience.C_END)))
                    .as("C 端入口须拒 " + type).isEqualTo(1_071_000_001);
        }
        assertThat(codeOf(() -> AssetService.resolvePolicyForEntrance("NOT_A_TYPE", AssetTypePolicy.Audience.C_END)))
                .isEqualTo(1_071_000_001);
        assertThat(codeOf(() -> AssetService.resolvePolicyForEntrance(null, AssetTypePolicy.Audience.C_END)))
                .as("null 类型统一 1_071（Codex 补审 P2：原 valueOf(null) NPE→500）").isEqualTo(1_071_000_001);
    }

    @Test
    void bEndEntranceRejectsCEndAndUnknownTypes() {
        for (String type : new String[]{"USER_SKETCH", "DESIGN_REFERENCE", "USER_AVATAR", "CASE_IMAGE",
                "CASE_PDF", "AI_OUTPUT"}) {
            assertThat(codeOf(() -> AssetService.resolvePolicyForEntrance(type, AssetTypePolicy.Audience.B_END)))
                    .as("B 端入口须拒 " + type).isEqualTo(1_071_000_001);
        }
        assertThat(codeOf(() -> AssetService.resolvePolicyForEntrance(null, AssetTypePolicy.Audience.B_END)))
                .isEqualTo(1_071_000_001);
    }

    @Test
    void matchingEntranceReturnsPolicy() {
        assertThat(AssetService.resolvePolicyForEntrance("FIN_VOUCHER", AssetTypePolicy.Audience.B_END))
                .isEqualTo(AssetTypePolicy.FIN_VOUCHER);
        assertThat(AssetService.resolvePolicyForEntrance("USER_SKETCH", AssetTypePolicy.Audience.C_END))
                .isEqualTo(AssetTypePolicy.USER_SKETCH);
        assertThatCode(() -> AssetService.resolvePolicyForEntrance("ENG_SITE_PHOTO", AssetTypePolicy.Audience.B_END))
                .doesNotThrowAnyException();
    }

}
