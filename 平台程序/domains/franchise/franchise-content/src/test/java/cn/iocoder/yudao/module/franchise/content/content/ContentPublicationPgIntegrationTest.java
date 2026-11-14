package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchiseFixture;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.SALES;

@Import({ContentPublicationService.class, ContentDraftService.class, FranchiseCommands.class, CommandLogImpl.class,
        ContentPublicationPgIntegrationTest.Config.class})
class ContentPublicationPgIntegrationTest extends FranchisePgIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        ContentTaskService contentTaskService() {
            return mock(ContentTaskService.class);
        }
    }

    @Resource
    private ContentPublicationService service;
    @Resource
    private JdbcTemplate jdbc;

    private FranchiseFixture.Store store;
    private PostSnapshot owner;
    private PostSnapshot colleague;
    private PostSnapshot outsider;
    private long workId;

    @BeforeEach
    void setUpPublication() {
        store = fixture.store();
        owner = fixture.post(store.orgId(), SALES, store.salesTeamA());
        colleague = fixture.post(store.orgId(), SALES, store.salesTeamB());
        FranchiseFixture.Store other = fixture.store();
        outsider = fixture.post(other.orgId(), SALES, other.salesTeamA());
        workId = IdWorker.getId();
        insertWork(workId, store.orgId(), owner.userId(), "本人作品");
        currentPost.use(owner);
    }

    @Test
    void manualCreateStoresPublishingWithoutScheduleAndRejectsScheduleWithoutWriting() {
        Map<String, Object> manual = manualBody("  门店视频号  ");
        String idempotencyKey = "manual-publication-" + workId;
        var created = service.createFromPost(owner, manual, idempotencyKey);
        var replayed = service.createFromPost(owner, manual, idempotencyKey);
        assertThat(replayed.getId()).isEqualTo(created.getId());
        assertThat(created.getAccountId()).isEqualTo("门店视频号");
        assertThat(created.getAuthorUserId()).isEqualTo(owner.userId());
        assertThat(created.getPublishText()).isEqualTo("人工发布文案");
        assertThat(created.getTags()).isEqualTo("乡墅,案例");
        assertThat(created.getCreateTime()).isNotNull();
        assertThat(jdbc.queryForMap("SELECT account_id, status, scheduled_at FROM fr_content_publication WHERE id = ?",
                created.getId())).containsEntry("account_id", "门店视频号").containsEntry("status", "publishing")
                .containsEntry("scheduled_at", null);

        Map<String, Object> changedPayload = manualBody("另一个人工账号");
        assertThatThrownBy(() -> service.createFromPost(owner, changedPayload, idempotencyKey))
                .isInstanceOf(ServiceException.class);

        assertThat(service.verify(store.orgId(), owner.userId(), created.getId()).getStatus()).isEqualTo("verify");
        String publishedUrl = "https://channels.example/post/" + created.getId();
        service.link(store.orgId(), owner.userId(), created.getId(), publishedUrl);
        assertThat(service.link(store.orgId(), owner.userId(), created.getId(), publishedUrl).getStatus())
                .isEqualTo("published");
        assertThat(service.list(store.orgId(), null, null)).anySatisfy(record -> {
            assertThat(record.getId()).isEqualTo(created.getId());
            assertThat(record.getAccountId()).isEqualTo("门店视频号");
            assertThat(record.getPublishedUrl()).isEqualTo(publishedUrl);
        });
        assertThat(service.requireOwned(store.orgId(), owner.userId(), created.getId()).getPublishedUrl())
                .isEqualTo(publishedUrl);

        long before = publicationCount();
        long commandsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM pf_command_event", Long.class);
        Map<String, Object> scheduled = manualBody("抖音人工账号");
        scheduled.put("scheduledAt", OffsetDateTime.now().plusDays(1).toString());
        assertThatThrownBy(() -> service.createFromPost(owner, scheduled, "scheduled-rejected-" + workId))
                .isInstanceOf(ServiceException.class);
        assertThat(publicationCount()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_command_event", Long.class)).isEqualTo(commandsBefore);

        Map<String, Object> invalidPlatform = manualBody("未知平台账号");
        invalidPlatform.put("platform", "wechat_channels");
        assertThatThrownBy(() -> service.createFromPost(owner, invalidPlatform, "platform-rejected-" + workId))
                .isInstanceOf(ServiceException.class);
        assertThat(publicationCount()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_command_event", Long.class)).isEqualTo(commandsBefore);
    }

    @Test
    void cannotRegisterColleagueOrOtherOrganizationsWork() {
        long colleagueWork = IdWorker.getId();
        insertWork(colleagueWork, store.orgId(), colleague.userId(), "同组织同事作品");
        long outsiderWork = IdWorker.getId();
        insertWork(outsiderWork, outsider.orgId(), outsider.userId(), "其他组织作品");
        long publicationsBefore = publicationCount();
        long commandsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM pf_command_event", Long.class);

        assertThatThrownBy(() -> service.createFromPost(owner, manualBody(colleagueWork, "同事账号"),
                "colleague-work-" + colleagueWork)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.createFromPost(owner, manualBody(outsiderWork, "外部账号"),
                "other-org-work-" + outsiderWork)).isInstanceOf(ServiceException.class);

        assertThat(publicationCount()).isEqualTo(publicationsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_command_event", Long.class)).isEqualTo(commandsBefore);
    }

    @Test
    void historicalScheduledRecordCanBeManuallyVerifiedAndLinkedIdempotently() {
        long publicationId = insertHistoricalScheduled(owner, "历史视频号");
        OffsetDateTime originalScheduledAt = jdbc.queryForObject(
                "SELECT scheduled_at FROM fr_content_publication WHERE id = ?", OffsetDateTime.class, publicationId);

        assertThat(service.verify(store.orgId(), owner.userId(), publicationId).getStatus()).isEqualTo("verify");
        assertThat(service.verify(store.orgId(), owner.userId(), publicationId).getStatus()).isEqualTo("verify");
        assertThat(jdbc.queryForObject("SELECT scheduled_at IS NULL FROM fr_content_publication WHERE id = ?",
                Boolean.class, publicationId)).isTrue();
        String url = "https://channels.example/post/" + publicationId;
        assertThat(service.link(store.orgId(), owner.userId(), publicationId, url).getStatus()).isEqualTo("published");
        assertThat(service.link(store.orgId(), owner.userId(), publicationId, url).getPublishedUrl()).isEqualTo(url);
        Map<?, ?> versions = JsonUtils.parseObject(jdbc.queryForObject(
                "SELECT versions::text FROM fr_content_publication WHERE id = ?", String.class, publicationId), Map.class);
        assertThat(OffsetDateTime.parse(String.valueOf(versions.get("originalScheduledAt"))).toInstant())
                .isEqualTo(originalScheduledAt.toInstant());
        assertThat(jdbc.queryForObject("SELECT signature FROM fr_content_publication WHERE id = ?", String.class,
                publicationId)).isEqualTo("legacy-frozen-signature");
    }

    @Test
    void onlyOwnerInSameOrganizationCanVerifyHistoricalSchedule() {
        long publicationId = insertHistoricalScheduled(owner, "历史抖音号");

        currentPost.use(colleague);
        assertThatThrownBy(() -> service.verify(store.orgId(), colleague.userId(), publicationId))
                .isInstanceOf(ServiceException.class);
        currentPost.use(outsider);
        assertThatThrownBy(() -> service.verify(outsider.orgId(), outsider.userId(), publicationId))
                .isInstanceOf(ServiceException.class);

        currentPost.use(owner);
        assertThat(jdbc.queryForObject("SELECT status FROM fr_content_publication WHERE id = ?", String.class,
                publicationId)).isEqualTo("scheduled");
    }

    private long insertHistoricalScheduled(PostSnapshot post, String account) {
        long id = IdWorker.getId();
        jdbc.update("INSERT INTO fr_content_publication (id, company_id, author_user_id, work_id, title, account_id, "
                        + "platform, scheduled_at, status, versions, signature) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'scheduled', CAST(? AS jsonb), ?)",
                id, post.orgId(), post.userId(), IdWorker.getId(), "历史定时发布", account, "channels",
                OffsetDateTime.now().plusDays(1), "{\"version\":1,\"status\":\"scheduled\"}",
                "legacy-frozen-signature");
        return id;
    }

    private long publicationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM fr_content_publication WHERE company_id = ?", Long.class,
                store.orgId());
    }

    private Map<String, Object> manualBody(String accountId) {
        return manualBody(workId, accountId);
    }

    private Map<String, Object> manualBody(long requestedWorkId, String accountId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workId", requestedWorkId);
        body.put("accountId", accountId);
        body.put("platform", "channels");
        body.put("title", "手动发布登记");
        body.put("publishText", "人工发布文案");
        body.put("tags", "乡墅,案例");
        return body;
    }

    private void insertWork(long id, long companyId, long authorUserId, String title) {
        jdbc.update("INSERT INTO fr_content_draft (id, company_id, author_user_id, kind, title, status) "
                        + "VALUES (?, ?, ?, 'remix', ?, 'draft')",
                id, companyId, authorUserId, title);
    }
}
