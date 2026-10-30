package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.datapermission.config.YudaoDataPermissionAutoConfiguration;
import cn.iocoder.yudao.framework.datapermission.core.db.DataPermissionRuleHandler;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.authz.OrgScopeRule;
import cn.iocoder.yudao.module.platform.identity.dal.WechatBindingDO;
import cn.iocoder.yudao.module.platform.identity.dal.WechatBindingEventDO;
import cn.iocoder.yudao.module.platform.identity.dal.WechatBindingMapper;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 绑定审计：人员管理作废绑定、审计落库，以及老板按组织查看。
 */
@Import({YudaoDataPermissionAutoConfiguration.class, OrgScopeRule.class, PlatformIdentityConfiguration.class,
        StaffWechatBindingServiceImpl.class, BindingAuditIntegrationTest.Config.class})
class BindingAuditIntegrationTest extends PlatformPgIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @TestConfiguration
    static class Config {
        @Bean
        StubCurrentPost stubCurrentPost() {
            return new StubCurrentPost();
        }

        @Bean
        @Lazy(false)
        Object eagerDataPermission(DataPermissionRuleHandler handler) {
            return handler;
        }
    }

    static class StubCurrentPost implements CurrentPost {
        PostSnapshot post;

        @Override
        public Optional<PostSnapshot> find() {
            return Optional.ofNullable(post);
        }
    }

    @Resource
    private StaffWechatBindingService bindingService;
    @Resource
    private WechatBindingMapper bindingMapper;
    @Resource
    private StubCurrentPost currentPost;
    @MockitoBean
    private StaffPostService staffPostService;
    @MockitoBean
    private TokenIssuer tokenIssuer;

    private long orgA;
    private long orgB;
    private long userId;

    @BeforeEach
    void setUp() {
        orgA = IDS.incrementAndGet();
        orgB = IDS.incrementAndGet();
        userId = IDS.incrementAndGet();
        when(staffPostService.listOrgIdsEverHeld(userId)).thenReturn(List.of(orgA, orgB));
    }

    @Test
    void staffManagementRevokesEveryBindingAuditsItAndRevokesTokens() {
        bindingService.bind("wx-a", "o-" + userId, userId, "13900000001");
        bindingService.bind("wx-b", "o-" + userId, userId, "13900000001");

        bindingService.revokeAllForUser(userId, WechatBindingDO.REASON_MOBILE_CHANGED, 77L);

        assertThat(bindingMapper.selectActiveByUserInAllApps(userId)).isEmpty();
        currentPost.post = post(orgA);
        List<WechatBindingEventDO> events = bindingService.pageEvents(page(), userId).getList();
        assertThat(events).extracting(WechatBindingEventDO::getEventType).containsExactlyInAnyOrder("BOUND", "BOUND", "REVOKED", "REVOKED");
        assertThat(events).filteredOn(e -> "REVOKED".equals(e.getEventType()))
                .allSatisfy(e -> {
                    assertThat(e.getReason()).isEqualTo(WechatBindingDO.REASON_MOBILE_CHANGED);
                    assertThat(e.getOperatorUserId()).isEqualTo(77L);
                });
        verify(tokenIssuer).revokeAll(userId);
    }

    @Test
    void tokensAreRevokedEvenWithoutAnyWechatBinding() {
        bindingService.revokeAllForUser(userId, WechatBindingDO.REASON_USER_DISABLED, 77L);

        verify(tokenIssuer).revokeAll(userId);
    }

    @Test
    void onlyMobileChangeOrDisableMayBeUsedByStaffManagement() {
        assertThatThrownBy(() -> bindingService.revokeAllForUser(userId, WechatBindingDO.REASON_REBOUND, 77L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(tokenIssuer, never()).revokeAll(anyLong());
    }

    @Test
    void eachBossSeesOnlyRecordsOfTheirOwnOrg() {
        bindingService.bind("wx-a", "o-" + userId, userId, "13900000001");
        long outsider = IDS.incrementAndGet();
        long orgC = IDS.incrementAndGet();
        when(staffPostService.listOrgIdsEverHeld(outsider)).thenReturn(List.of(orgC));
        bindingService.bind("wx-a", "o-" + outsider, outsider, "13900000002");

        currentPost.post = post(orgA);
        assertThat(bindingService.pageEvents(page(), null).getList())
                .extracting(WechatBindingEventDO::getUserId).containsOnly(userId);
        currentPost.post = post(orgC);
        assertThat(bindingService.pageEvents(page(), null).getList())
                .extracting(WechatBindingEventDO::getUserId).containsOnly(outsider);
        currentPost.post = null;
        assertThat(bindingService.pageEvents(page(), null).getList()).isEmpty();
    }

    @Test
    void staffWithoutAnyPostIsAuditedUnderThePlatformOrgInvisibleToBosses() {
        long loner = IDS.incrementAndGet();
        when(staffPostService.listOrgIdsEverHeld(loner)).thenReturn(List.of());
        bindingService.bind("wx-a", "o-" + loner, loner, "13900000003");

        currentPost.post = post(0L);
        assertThat(bindingService.pageEvents(page(), loner).getList()).hasSize(1);
        currentPost.post = post(orgA);
        assertThat(bindingService.pageEvents(page(), loner).getList()).isEmpty();
    }

    private static PageParam page() {
        PageParam page = new PageParam();
        page.setPageNo(1);
        page.setPageSize(50);
        return page;
    }

    private static PostSnapshot post(long orgId) {
        return new PostSnapshot(IDS.incrementAndGet(), IDS.incrementAndGet(), orgId, "store", "boss", null, false, Set.of());
    }

}
