package cn.iocoder.yudao.module.design;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯单测（14 文档 AC11）：validateReference 三分支——ACCEPTED→true / REJECTED→false / 不存在或他组织属主→false。
 * 直接测两 Port 共享的包级校验实现（FranchiseDesignAdapter.assetReferenceValid），无需 DB。
 */
class FranchiseDesignAdapterReferenceTest {

    private static List<Map<String, Object>> row(long owner, String status) {
        return List.of(Map.of("owner_user_id", owner, "upload_status", status));
    }

    @Test
    void acceptedOwnedAssetIsValid() {
        assertThat(FranchiseDesignAdapter.assetReferenceValid(row(7L, "ACCEPTED"), 7L)).isTrue();
    }

    @Test
    void rejectedAssetIsInvalidEvenIfOwned() {
        assertThat(FranchiseDesignAdapter.assetReferenceValid(row(7L, "REJECTED"), 7L)).isFalse();
    }

    @Test
    void missingOrForeignOrPendingAssetIsInvalid() {
        assertThat(FranchiseDesignAdapter.assetReferenceValid(List.of(), 7L)).isFalse(); // 不存在
        assertThat(FranchiseDesignAdapter.assetReferenceValid(row(9L, "ACCEPTED"), 7L)).isFalse(); // 他组织属主
        assertThat(FranchiseDesignAdapter.assetReferenceValid(row(7L, "PENDING"), 7L)).isFalse(); // 未 ACCEPTED
    }

}
