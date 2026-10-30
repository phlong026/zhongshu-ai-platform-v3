package cn.iocoder.yudao.module.platform.authz;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessTest {

    private static final PostSnapshot LEAD = new PostSnapshot(1L, 2L, 100L, "store", "sales-lead", 50L, true, Set.of(50L, 51L));

    @Test
    void commonObjectRules() {
        assertThat(Access.sameOrg(LEAD, 100L)).isTrue();
        assertThat(Access.sameOrg(LEAD, 200L)).isFalse();
        assertThat(Access.isSelf(LEAD, 2L)).isTrue();
        assertThat(Access.isSelf(LEAD, 3L)).isFalse();
        assertThat(Access.managesTeam(LEAD, 51L)).isTrue();
        assertThat(Access.managesTeam(LEAD, 60L)).isFalse();
        assertThat(Access.managesTeam(LEAD, null)).isFalse();
    }

    @Test
    void requireThrowsResourceForbidden() {
        Access.require(true);
        assertThatThrownBy(() -> Access.require(false)).isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN.getCode());
    }

}
