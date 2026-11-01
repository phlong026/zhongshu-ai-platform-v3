package cn.iocoder.yudao.module.franchise.org;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B2 板块 A / P-8 示范单测②：命令键公共规范化——键必填/长度上限、锁键与收据键同源确定性。
 */
class FranchiseCommandKeysTest {

    @Test
    void 空键拒绝() {
        assertThatThrownBy(() -> FranchiseCommandKeys.normalize(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FranchiseCommandKeys.normalize(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 超长键拒绝() {
        assertThatThrownBy(() -> FranchiseCommandKeys.normalize("k".repeat(201)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(FranchiseCommandKeys.normalize("k".repeat(200))).hasSize(200);
    }

    @Test
    void 锁键确定性且同源() {
        var scope = new FranchiseCommandKeys.PostKeyScope("franchise", 42L);
        String key = FranchiseCommandKeys.normalize("save-cust-001");
        long lock1 = FranchiseCommandKeys.lockKey(scope, key);
        long lock2 = FranchiseCommandKeys.lockKey(scope, key);
        assertThat(lock1).isEqualTo(lock2).isPositive();
        // 不同用户同键 → 不同锁键
        long other = FranchiseCommandKeys.lockKey(new FranchiseCommandKeys.PostKeyScope("franchise", 43L), key);
        assertThat(other).isNotEqualTo(lock1);
    }
}
