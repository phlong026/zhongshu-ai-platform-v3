package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentDraftDO;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentPublicationDO;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentPublicationMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContentPublicationServiceTest {

    private static final PostSnapshot POST = new PostSnapshot(9L, 7L, 100L, "store", "content", null,
            false, Set.of());

    private final FrContentPublicationMapper publications = mock(FrContentPublicationMapper.class);
    private final ContentDraftService drafts = mock(ContentDraftService.class);
    private final FranchiseCommands commands = mock(FranchiseCommands.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ContentPublisherAccountService publisherAccounts = mock(ContentPublisherAccountService.class);
    private final ContentPublicationCoverService covers = mock(ContentPublicationCoverService.class);
    private final ContentPublicationService service = new ContentPublicationService(publications, drafts, commands, jdbc, publisherAccounts, covers);

    @BeforeEach
    void absentCoverRetainsTheValidationServicesNullContract() {
        when(covers.validate(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(null);
    }

    @Test
    void createFromPostRejectsScheduleBeforeIdempotencyLookup() {
        Map<String, Object> body = manualBody("门店视频号");
        body.put("scheduledAt", "2026-12-01T10:00:00+08:00");

        assertThatThrownBy(() -> service.createFromPost(POST, body, "publication-1"))
                .isInstanceOf(ServiceException.class);

        verifyNoInteractions(commands, drafts, jdbc, publications);
    }

    @Test
    void createRejectsScheduleBeforeWorkLookup() {
        Map<String, Object> body = manualBody("门店视频号");
        body.put("scheduledAt", "2026-12-01T10:00:00+08:00");

        assertThatThrownBy(() -> service.create(100L, 7L, body)).isInstanceOf(ServiceException.class);

        verifyNoInteractions(drafts, jdbc, publications, commands);
    }

    @Test
    void accountNameIsTrimmedButNeverSilentlyTruncated() {
        when(drafts.workOf(100L, 88L)).thenReturn(new FrContentDraftDO().setId(88L).setCompanyId(100L)
                .setAuthorUserId(7L));
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(1);

        var created = service.create(100L, 7L, manualBody("  门店视频号  "));

        assertThat(created.getAccountId()).isEqualTo("门店视频号");
        assertThat(created.getStatus()).isEqualTo("publishing");
        assertThat(created.getScheduledAt()).isNull();

        clearInvocations(drafts, jdbc);
        Map<String, Object> tooLong = manualBody("账".repeat(65));
        assertThatThrownBy(() -> service.create(100L, 7L, tooLong)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(drafts, jdbc);
    }

    @Test
    void createRejectsUnknownPlatformBeforeReadingWorkOrWriting() {
        Map<String, Object> body = manualBody("门店视频号");
        body.put("platform", "wechat_channels");

        assertThatThrownBy(() -> service.create(100L, 7L, body)).isInstanceOf(ServiceException.class);

        verifyNoInteractions(drafts, jdbc, publications, commands);
    }

    @Test
    void blankScheduleRemainsCompatibleWithManualRegistration() {
        when(commands.replayed(POST, "publication-2", manualBody("门店视频号"))).thenReturn(Optional.empty());
        when(drafts.workOf(100L, 88L)).thenReturn(new FrContentDraftDO().setId(88L).setCompanyId(100L)
                .setAuthorUserId(7L));
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(1);
        Map<String, Object> body = manualBody("门店视频号");
        body.put("scheduledAt", "  ");

        var created = service.createFromPost(POST, body, "publication-2");

        assertThat(created.getStatus()).isEqualTo("publishing");
        assertThat(created.getScheduledAt()).isNull();
        verify(commands).record(POST, "fr_content_publication", created.getId(), 1,
                "content-publication-create", body, "publication-2");
    }

    private static Map<String, Object> manualBody(String accountId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("workId", 88L);
        body.put("accountId", accountId);
        body.put("platform", "channels");
        body.put("title", "手动发布登记");
        return body;
    }

    @Test
    void registeredAccountUsesAuthorizedStableIdentityAndIgnoresSubmittedName() {
        when(drafts.workOf(100L, 88L)).thenReturn(new FrContentDraftDO().setId(88L).setCompanyId(100L)
                .setAuthorUserId(7L));
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(1);
        when(publisherAccounts.requireUsable(100L, 7L, 99L, "channels"))
                .thenReturn(new ContentPublisherAccountService.Account(99L, 100L, 7L, "channels",
                        "真实登记名称", "actual-id", true, null, null));
        Map<String, Object> body = manualBody("伪造的他人名称");
        body.put("publisherAccountId", "99");
        var created = service.create(100L, 7L, body);
        assertThat(created.getPublisherAccountId()).isEqualTo(99L);
        assertThat(created.getAccountId()).isEqualTo("真实登记名称");
        verify(publisherAccounts).requireUsable(100L, 7L, 99L, "channels");
    }

    @Test
    void duplicateReturnsRecoverableRecordAndComparesContentWithoutOverwriting() {
        when(drafts.workOf(100L, 88L)).thenReturn(new FrContentDraftDO().setId(88L).setCompanyId(100L)
                .setAuthorUserId(7L));
        FrContentPublicationDO existing = new FrContentPublicationDO().setId(91L).setCompanyId(100L)
                .setAuthorUserId(7L).setWorkId(88L).setPlatform("channels").setAccountId("门店视频号")
                .setTitle("手动发布登记").setStatus("published");
        when(publications.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(existing);
        assertThatThrownBy(() -> service.create(100L, 7L, manualBody("门店视频号")))
                .isInstanceOfSatisfying(ContentPublicationConflict.class, error ->
                        assertThat(error.details()).containsEntry("existingPublicationId", "91")
                                .containsEntry("status", "published").containsEntry("sameInput", true));
        Map<String, Object> changed = manualBody("门店视频号");
        changed.put("tags", "修改的话题");
        assertThatThrownBy(() -> service.create(100L, 7L, changed))
                .isInstanceOfSatisfying(ContentPublicationConflict.class, error ->
                        assertThat(error.details()).containsEntry("sameInput", false));
        verify(publications, org.mockito.Mockito.never()).updateById(
                org.mockito.ArgumentMatchers.<FrContentPublicationDO>any());
    }

    @Test
    void rejectedCoverDoesNotInsertPublicationOrRecordCommand() {
        when(drafts.workOf(100L, 88L)).thenReturn(new FrContentDraftDO().setId(88L).setCompanyId(100L)
                .setAuthorUserId(7L));
        Map<String, Object> body = manualBody("门店视频号");
        body.put("coverAssetId", "99");
        when(covers.validate(100L, 7L, "99")).thenThrow(new ServiceException(1114000015, "封面不可用"));
        assertThatThrownBy(() -> service.create(100L, 7L, body)).hasMessage("封面不可用");
        verifyNoInteractions(jdbc, publications, commands);
    }
}
