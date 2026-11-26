package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.SALES;

@Import({ContentPublicationService.class, ContentPublisherAccountService.class, ContentPublicationCoverService.class,
        ContentDraftService.class, FranchiseCommands.class, CommandLogImpl.class,
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
    private ContentDraftService drafts;
    @Resource
    private ContentPublisherAccountService publisherAccounts;
    @Resource
    private JdbcTemplate jdbc;
    @MockitoBean
    private AssetAccessPort assets;
    @MockitoBean
    private StoreServiceAccountPort serviceAccounts;

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
        when(serviceAccounts.requireServiceAccount(anyLong())).thenReturn(50L);
    }

    @Test
    void privateWorkDetailUsesOwnSavedScriptAndStatusChangesStayWithTheAuthor() {
        jdbc.update("UPDATE fr_content_draft SET script=? WHERE id=?", "第一段\n第二段", workId);
        var mine = drafts.workOfAuthor(store.orgId(), owner.userId(), workId);
        assertThat(mine.getTitle()).isEqualTo("本人作品");
        assertThat(mine.getScript()).isEqualTo("第一段\n第二段");
        for (var other : java.util.List.of(colleague, outsider)) {
            assertThatThrownBy(() -> drafts.workOfAuthor(other.orgId(), other.userId(), workId))
                    .isInstanceOf(ServiceException.class);
            assertThatThrownBy(() -> drafts.updateWorkStatus(other.orgId(), other.userId(), workId, "saved"))
                    .isInstanceOf(ServiceException.class);
        }
        assertThat(drafts.workOfAuthor(store.orgId(), owner.userId(), workId).getStatus()).isEqualTo("draft");
        assertThat(drafts.updateWorkStatus(store.orgId(), owner.userId(), workId, "saved").getStatus())
                .isEqualTo("saved");
    }

    @Test
    void trashAndRestoreKeepTheOriginalWorkAndPublishedReceipt() throws Exception {
        var publication = service.createFromPost(owner, manualBody("作品回收验收"), "publish-before-trash-" + workId);
        var controller = new cn.iocoder.yudao.module.franchise.content.controller.admin.ContentDraftController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "currentPost", currentPost);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "drafts", drafts);
        var permissions = mock(cn.iocoder.yudao.module.platform.authz.PostPermissionEvaluator.class);
        when(permissions.has(cn.iocoder.yudao.module.franchise.content.ContentPermissions.WRITE)).thenReturn(true);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "postPermissions", permissions);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        String list = "/franchise/content/drafts";
        String action = list + "/" + workId + "/trash";
        for (int retry = 0; retry < 2; retry++) {
            mvc.perform(put(action).contentType("application/json").content("{\"trashed\":true,\"revision\":0}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1));
        }
        mvc.perform(get(list)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(list + "/trash")).andExpect(jsonPath("$.data[0].workId").value(String.valueOf(workId)))
                .andExpect(jsonPath("$.data[0].canManage").value(true));
        for (var other : java.util.List.of(colleague, outsider)) {
            assertThat(drafts.listWorks(other.orgId(), other.userId(), true)).isEmpty();
            assertThatThrownBy(() -> drafts.setTrashed(other.orgId(), other.userId(), workId, false, 1))
                    .isInstanceOf(ServiceException.class);
        }
        assertThat(service.requireOwned(store.orgId(), owner.userId(), publication.getId()).getWorkId()).isEqualTo(workId);
        assertThatThrownBy(() -> drafts.workOfAuthor(store.orgId(), owner.userId(), workId))
                .isInstanceOf(ServiceException.class);
        mvc.perform(put(action).contentType("application/json").content("{\"trashed\":false,\"revision\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(2));
        mvc.perform(get(list + "/trash")).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(list)).andExpect(jsonPath("$.data[0].workId").value(String.valueOf(workId)))
                .andExpect(jsonPath("$.data[0].title").value("本人作品"));
        assertThatThrownBy(() -> drafts.setTrashed(store.orgId(), owner.userId(), workId, true, 0))
                .isInstanceOf(ServiceException.class).hasMessageContaining("状态已更新");
        assertThat(service.createFromPost(owner, manualBody("作品回收验收"), "publish-before-trash-" + workId).getId())
                .isEqualTo(publication.getId());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void publishedPlaybackAndCustomerSourcesSurviveMovingThePersonalWorkToTrash() throws Exception {
        long taskId = IdWorker.getId(), customerId = IdWorker.getId(), leadId = IdWorker.getId();
        jdbc.update("UPDATE fr_content_draft SET task_id=? WHERE id=?", taskId, workId);
        var publication = service.createFromPost(owner, manualBody("回放验收"), "work-playback-" + workId);
        service.verify(store.orgId(), owner.userId(), publication.getId());
        service.link(store.orgId(), owner.userId(), publication.getId(), "https://channels.example/post/" + publication.getId());
        // The content module reads these projections from CRM in the combined server.
        jdbc.execute("CREATE TEMP TABLE fr_customer(id bigint,company_id bigint,owner_user_id bigint,team_id bigint) ON COMMIT DROP");
        jdbc.execute("CREATE TEMP TABLE fr_lead(id bigint,company_id bigint,customer_id bigint,source text,need text,status text,create_time timestamptz) ON COMMIT DROP");
        jdbc.update("INSERT INTO fr_customer VALUES(?,?,?,NULL)", customerId, store.orgId(), owner.userId());
        jdbc.update("INSERT INTO fr_lead VALUES(?,?,?,?,'庭院咨询','CONVERTED',now())",
                leadId, store.orgId(), customerId, ContentGrowthService.SOURCE_PREFIX + publication.getId());
        var taskService = mock(ContentTaskService.class);
        when(taskService.requireOwned(store.orgId(), taskId)).thenReturn(
                new cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO().setId(taskId)
                        .setAuthorUserId(owner.userId()).setStatus("SUCCEEDED").setResultUrl("https://media.example/original.mp4"));
        var controller = new cn.iocoder.yudao.module.franchise.content.controller.admin.ContentPublicationController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "publications", service);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "currentPost", currentPost);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "drafts", drafts);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "tasks", taskService);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "growth", new ContentGrowthService(jdbc));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        drafts.setTrashed(store.orgId(), owner.userId(), workId, true, 0);
        mvc.perform(get("/franchise/content/publications/{id}/playback", publication.getId()))
                .andExpect(jsonPath("$.data.workId").value(String.valueOf(workId)))
                .andExpect(jsonPath("$.data.resultUrl").value("https://media.example/original.mp4"));
        mvc.perform(get("/franchise/content/growth/customers/{id}/sources", customerId))
                .andExpect(jsonPath("$.data[0].leadId").value(String.valueOf(leadId)));
        assertThatThrownBy(() -> service.create(store.orgId(), owner.userId(), manualBody("新账号")))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void workTrashMigrationPreservesExistingRowsAndRequiresRestoreBeforeUndo() throws Exception {
        var forward = new org.springframework.core.io.ClassPathResource(
                "db/migration/franchise/V20261126.1002__personal_work_trash.sql");
        var undo = new org.springframework.core.io.ClassPathResource(
                "db/migration-undo/franchise/V20261126.1002__undo_personal_work_trash.sql");
        try (var connection = jdbc.getDataSource().getConnection(); var sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                sql.execute("SET LOCAL search_path TO pg_temp");
                sql.execute("CREATE TEMP TABLE fr_content_draft(id BIGINT PRIMARY KEY, title TEXT)");
                sql.execute("INSERT INTO fr_content_draft VALUES(42, '已有作品')");
                sql.execute(forward.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
                try (var result = sql.executeQuery("SELECT library_revision,trashed_at FROM fr_content_draft")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isZero();
                    assertThat(result.getObject(2)).isNull();
                }
                sql.execute("UPDATE fr_content_draft SET trashed_at=now(),library_revision=1");
                var save = connection.setSavepoint();
                String revert = undo.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                assertThatThrownBy(() -> sql.execute(revert)).isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("Restore works");
                connection.rollback(save);
                sql.execute("UPDATE fr_content_draft SET trashed_at=NULL, library_revision=2");
                sql.execute(revert);
                try (var work = sql.executeQuery("SELECT id,title FROM fr_content_draft")) {
                    assertThat(work.next()).isTrue();
                    assertThat(work.getLong(1)).isEqualTo(42);
                    assertThat(work.getString(2)).isEqualTo("已有作品");
                }
            } finally {
                connection.rollback();
            }
        }
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
    void registeredIdentityValidatesScopePlatformAndStatusAndKeepsHistoricalNames() {
        var account = publisherAccounts.create(owner, Map.of("platform", "channels", "displayName", "原视频号名称",
                "identifier", "store-channel"));
        Map<String, Object> body = manualBody("客户端不可信名称");
        body.put("publisherAccountId", Long.toString(account.id()));
        var publication = service.createFromPost(owner, body, "registered-first-" + workId);
        assertThat(publication.getPublisherAccountId()).isEqualTo(account.id());
        assertThat(publication.getAccountId()).isEqualTo("原视频号名称");
        publisherAccounts.update(owner, account.id(), Map.of("displayName", "改名后"));
        assertThatThrownBy(() -> service.createFromPost(owner, body, "renamed-repeat-" + workId))
                .isInstanceOfSatisfying(ContentPublicationConflict.class, error ->
                        assertThat(error.details()).containsEntry("existingPublicationId", publication.getId().toString())
                                .containsEntry("sameInput", true));
        assertThat(service.requireOwned(store.orgId(), owner.userId(), publication.getId()).getAccountId())
                .isEqualTo("原视频号名称");

        Map<String, Object> mismatch = new LinkedHashMap<>(body);
        mismatch.put("platform", "douyin");
        assertThatThrownBy(() -> service.createFromPost(owner, mismatch, "platform-mismatch-" + workId))
                .isInstanceOf(ServiceException.class).hasMessageContaining("发布账号");
        assertThatThrownBy(() -> service.createFromPost(colleague, body, "colleague-account-" + workId))
                .isInstanceOf(ServiceException.class).hasMessageContaining("发布账号");
        assertThatThrownBy(() -> service.createFromPost(outsider, body, "outside-account-" + workId))
                .isInstanceOf(ServiceException.class).hasMessageContaining("发布账号");
        publisherAccounts.update(owner, account.id(), Map.of("enabled", false));
        assertThatThrownBy(() -> service.createFromPost(owner, body, "disabled-account-" + workId))
                .isInstanceOf(ServiceException.class).hasMessageContaining("已停用");
        assertThat(publicationCount()).isEqualTo(1);
    }

    @Test
    void verifiedCoverIsPersistedReadBackAndIncludedInDuplicateComparison() {
        long firstCover = IdWorker.getId();
        long changedCover = IdWorker.getId();
        var actor = new AssetAccessPort.Actor(store.orgId(), 50L, owner.userId(), owner.postId());
        var image = new AssetAccessPort.ContentMedia(new byte[]{1}, "image/png", "verified-digest", 0, 100, 100);
        when(assets.readContentMedia(actor, firstCover)).thenReturn(image);
        when(assets.readContentMedia(actor, changedCover)).thenReturn(image);
        Map<String, Object> body = manualBody("封面视频号");
        body.put("coverAssetId", Long.toString(firstCover));
        var created = service.createFromPost(owner, body, "cover-first-" + workId);
        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM fr_content_publication WHERE id=?",
                Long.class, created.getId())).isEqualTo(firstCover);
        assertThat(service.list(store.orgId(), owner.userId(), null)).anySatisfy(row ->
                assertThat(row.getCoverAssetId()).isEqualTo(firstCover));
        assertThat(service.createFromPost(owner, body, "cover-first-" + workId).getCoverAssetId()).isEqualTo(firstCover);
        assertThatThrownBy(() -> service.createFromPost(owner, body, "cover-repeat-" + workId))
                .isInstanceOfSatisfying(ContentPublicationConflict.class, error ->
                        assertThat(error.details()).containsEntry("sameInput", true));
        Map<String, Object> changed = new LinkedHashMap<>(body);
        changed.put("coverAssetId", Long.toString(changedCover));
        assertThatThrownBy(() -> service.createFromPost(owner, changed, "cover-changed-" + workId))
                .isInstanceOfSatisfying(ContentPublicationConflict.class, error ->
                        assertThat(error.details()).containsEntry("sameInput", false));
        assertThat(service.requireOwned(store.orgId(), owner.userId(), created.getId()).getCoverAssetId())
                .isEqualTo(firstCover);
        assertThat(publicationCount()).isEqualTo(1);
    }

    @Test
    void unavailableOrNonImageCoverCannotCreatePublicationOrCommand() {
        long coverId = IdWorker.getId();
        Map<String, Object> body = manualBody("不可用封面账号");
        body.put("coverAssetId", Long.toString(coverId));
        long commandsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM pf_command_event", Long.class);
        when(assets.readContentMedia(any(), eq(coverId))).thenThrow(new ServiceException(403, "素材未完成或无权访问"));
        assertThatThrownBy(() -> service.createFromPost(owner, body, "cover-forbidden-" + workId))
                .isInstanceOf(ServiceException.class).hasMessageContaining("无权访问");
        doReturn(new AssetAccessPort.ContentMedia(new byte[]{1}, "video/mp4", "hash", 1, 100, 100))
                .when(assets).readContentMedia(any(), eq(coverId));
        assertThatThrownBy(() -> service.createFromPost(owner, body, "cover-video-" + workId))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(1114000015));
        assertThat(publicationCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_command_event", Long.class)).isEqualTo(commandsBefore);
    }

    @Test
    void undoRefusesIncompatibleRowsAtomicallyAndSupportsCompatibleRows() throws Exception {
        String schema = "publication_undo_" + workId;
        String undo;
        try (var input = new org.springframework.core.io.ClassPathResource(
                "db/migration-undo/franchise/V20261124.1022__undo_publication_platform_identity.sql").getInputStream()) {
            undo = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        try (var connection = jdbc.getDataSource().getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                statement.execute("SET search_path TO " + schema);
                statement.execute("CREATE TABLE fr_content_publication (work_id BIGINT, platform TEXT, account_id TEXT, status TEXT)");
                statement.execute("CREATE UNIQUE INDEX uk_fr_content_publication_active ON fr_content_publication "
                        + "(work_id, platform, account_id) WHERE status <> 'failed'");
                statement.execute("INSERT INTO fr_content_publication VALUES (1,'douyin','same','publishing'),(1,'channels','same','published')");
                assertThatThrownBy(() -> statement.execute(undo)).isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("cross-platform records exist");
                try (var indexes = statement.executeQuery("SELECT indexdef FROM pg_indexes WHERE schemaname='" + schema + "'")) {
                    assertThat(indexes.next()).isTrue();
                    assertThat(indexes.getString(1)).contains("work_id, platform, account_id");
                }
                statement.execute("DELETE FROM fr_content_publication WHERE platform='channels'");
                statement.execute(undo);
                try (var indexes = statement.executeQuery("SELECT indexdef FROM pg_indexes WHERE schemaname='" + schema + "'")) {
                    assertThat(indexes.next()).isTrue();
                    assertThat(indexes.getString(1)).contains("work_id, account_id").doesNotContain("platform");
                }
            } finally {
                statement.execute("SET search_path TO public");
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void sameNameOnDifferentPlatformsCanPublishAndDuplicateRecordIsRecoverable() {
        Map<String, Object> channels = manualBody("同名账号");
        var first = service.createFromPost(owner, channels, "channels-first-" + workId);
        Map<String, Object> douyin = new LinkedHashMap<>(channels);
        douyin.put("platform", "douyin");
        var second = service.createFromPost(owner, douyin, "douyin-first-" + workId);
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(publicationCount()).isEqualTo(2);

        assertThatThrownBy(() -> service.createFromPost(owner, channels, "reopened-page-" + workId))
                .isInstanceOfSatisfying(ContentPublicationConflict.class, error ->
                        assertThat(error.details()).containsEntry("existingPublicationId", first.getId().toString())
                                .containsEntry("sameInput", true).containsEntry("status", "publishing"));
        Map<String, Object> changed = new LinkedHashMap<>(channels);
        changed.put("publishText", "重新编辑的文案");
        assertThatThrownBy(() -> service.createFromPost(owner, changed, "changed-page-" + workId))
                .isInstanceOfSatisfying(ContentPublicationConflict.class, error ->
                        assertThat(error.details()).containsEntry("sameInput", false));
        assertThat(publicationCount()).isEqualTo(2);
        assertThat(service.requireOwned(store.orgId(), owner.userId(), first.getId()).getPublishText())
                .isEqualTo("人工发布文案");

        service.verify(store.orgId(), owner.userId(), first.getId());
        service.link(store.orgId(), owner.userId(), first.getId(), "https://channels.example/post/" + first.getId());
        assertThatThrownBy(() -> service.createFromPost(owner, channels, "published-page-" + workId))
                .isInstanceOfSatisfying(ContentPublicationConflict.class, error ->
                        assertThat(error.details()).containsEntry("status", "published"));
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
