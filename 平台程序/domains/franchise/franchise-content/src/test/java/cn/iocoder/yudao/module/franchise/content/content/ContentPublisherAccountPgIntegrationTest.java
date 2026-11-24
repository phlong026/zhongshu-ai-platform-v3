package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.SALES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(ContentPublisherAccountService.class)
class ContentPublisherAccountPgIntegrationTest extends FranchisePgIntegrationTest {
    @Resource private ContentPublisherAccountService accounts;
    private PostSnapshot owner;
    private PostSnapshot colleague;
    private PostSnapshot outsider;

    @BeforeEach void setUpAccounts() {
        var company = fixture.store();
        owner = fixture.post(company.orgId(), SALES, company.salesTeamA());
        colleague = fixture.post(company.orgId(), SALES, company.salesTeamB());
        var other = fixture.store();
        outsider = fixture.post(other.orgId(), SALES, other.salesTeamA());
        currentPost.use(owner);
    }

    @Test void ownDirectorySupportsDistinctPlatformsAndDoesNotTrustCallerOwnership() {
        var first = accounts.create(owner, Map.of("platform", "douyin", "displayName", "门店账号",
                "identifier", "my-store", "ownerUserId", colleague.userId(), "companyId", outsider.orgId()));
        assertThat(first.ownerUserId()).isEqualTo(owner.userId());
        assertThat(first.companyId()).isEqualTo(owner.orgId());
        var second = accounts.create(owner, Map.of("platform", "channels", "displayName", "同名视频号",
                "identifier", "my-store"));
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(accounts.list(owner, null)).hasSize(2);
        assertThat(accounts.list(owner, "channels")).extracting(ContentPublisherAccountService.Account::id)
                .containsExactly(second.id());
        assertThat(accounts.list(colleague, null)).isEmpty();
        assertThat(accounts.list(outsider, null)).isEmpty();
        assertThatThrownBy(() -> accounts.update(colleague, first.id(), Map.of("displayName", "越权")))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> accounts.update(outsider, first.id(), Map.of("enabled", false)))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> accounts.requireUsable(owner.orgId(), colleague.userId(), first.id(), "douyin"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> accounts.requireUsable(outsider.orgId(), outsider.userId(), first.id(), "douyin"))
                .isInstanceOf(ServiceException.class);
    }

    @Test void repeatedRegistrationCannotDuplicateOrReactivateAndIdentityCannotBeReassigned() {
        var body = Map.<String, Object>of("platform", "channels", "displayName", "本人视频号", "identifier", "channel-01");
        var account = accounts.create(owner, body);
        assertThat(accounts.create(owner, body).id()).isEqualTo(account.id());
        assertThatThrownBy(() -> accounts.create(colleague, body)).isInstanceOf(ServiceException.class);
        accounts.update(owner, account.id(), Map.of("displayName", "改名后", "enabled", false));
        assertThat(accounts.create(owner, body).enabled()).isFalse();
        assertThat(accounts.list(owner, null)).hasSize(1);
        assertThatThrownBy(() -> accounts.requireUsable(owner.orgId(), owner.userId(), account.id(), "channels"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> accounts.update(owner, account.id(), Map.of("identifier", "another-channel")))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> accounts.update(owner, account.id(), Map.of("platform", "douyin")))
                .isInstanceOf(ServiceException.class);
        accounts.update(owner, account.id(), Map.of("enabled", true));
        assertThat(accounts.requireUsable(owner.orgId(), owner.userId(), account.id(), "channels").displayName())
                .isEqualTo("改名后");
        assertThatThrownBy(() -> accounts.requireUsable(owner.orgId(), owner.userId(), account.id(), "douyin"))
                .isInstanceOf(ServiceException.class);
    }

    @Test void accountFieldsAreValidatedBeforePersistence() {
        for (Map<String, Object> body : java.util.List.<Map<String, Object>>of(
                Map.of("platform", "channels", "displayName", "本人视频号", "identifier", ""),
                Map.of("platform", "unknown", "displayName", "本人视频号", "identifier", "my-account"),
                Map.of("platform", "channels", "displayName", "账".repeat(65), "identifier", "my-account"),
                Map.of("platform", "channels", "displayName", "本人视频号", "identifier", "line\nbreak"))) {
            assertThatThrownBy(() -> accounts.create(owner, body)).isInstanceOf(ServiceException.class);
        }
        assertThat(accounts.list(owner, null)).isEmpty();
    }
}
