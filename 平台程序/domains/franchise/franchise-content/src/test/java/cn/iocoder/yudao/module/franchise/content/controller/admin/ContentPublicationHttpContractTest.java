package cn.iocoder.yudao.module.franchise.content.controller.admin;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.franchise.content.content.ContentDraftService;
import cn.iocoder.yudao.module.franchise.content.content.ContentGrowthService;
import cn.iocoder.yudao.module.franchise.content.content.ContentPublicationService;
import cn.iocoder.yudao.module.franchise.content.content.ContentPublicationConflict;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentDraftDO;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentPublicationDO;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentPublicationMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.Set;
import java.time.OffsetDateTime;

import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.INPUT_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ContentPublicationHttpContractTest {

    @Test
    void duplicateResponseKeepsSafeRecoveryDetailsAndSpecificMessage() throws Exception {
        PostSnapshot post = new PostSnapshot(9L, 7L, 100L, "store", "content", null, false, Set.of());
        CurrentPost currentPost = mock(CurrentPost.class);
        when(currentPost.require()).thenReturn(post);
        ContentPublicationService service = mock(ContentPublicationService.class);
        when(service.createFromPost(org.mockito.ArgumentMatchers.eq(post), org.mockito.ArgumentMatchers.any(), anyString()))
                .thenThrow(new ContentPublicationConflict(new FrContentPublicationDO().setId(91L)
                        .setStatus("published"), false));
        ContentPublicationController controller = new ContentPublicationController();
        ReflectionTestUtils.setField(controller, "currentPost", currentPost);
        ReflectionTestUtils.setField(controller, "publications", service);
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/franchise/content/publications").header("Idempotency-Key", "duplicate-http")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"workId\":88}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1114000013))
                .andExpect(jsonPath("$.data.existingPublicationId").value("91"))
                .andExpect(jsonPath("$.data.status").value("published"))
                .andExpect(jsonPath("$.data.sameInput").value(false))
                .andExpect(jsonPath("$.msg").value("该作品在此平台账号已有发布记录，请继续原记录或查看发布结果"));
    }

    @Test
    void manualCreateResponseContainsStoredReceiptMetadata() throws Exception {
        PostSnapshot post = new PostSnapshot(9L, 7L, 100L, "store", "content", null, false, Set.of());
        CurrentPost currentPost = mock(CurrentPost.class);
        when(currentPost.require()).thenReturn(post);
        ContentDraftService drafts = mock(ContentDraftService.class);
        when(drafts.workOf(100L, 88L)).thenReturn(new FrContentDraftDO().setId(88L).setCompanyId(100L)
                .setAuthorUserId(7L));
        FranchiseCommands commands = mock(FranchiseCommands.class);
        when(commands.replayed(org.mockito.ArgumentMatchers.eq(post), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(1);
        ContentPublicationService service = new ContentPublicationService(mock(FrContentPublicationMapper.class),
                drafts, commands, jdbc, mock(cn.iocoder.yudao.module.franchise.content.content.ContentPublisherAccountService.class));
        ContentPublicationController controller = new ContentPublicationController();
        ReflectionTestUtils.setField(controller, "currentPost", currentPost);
        ReflectionTestUtils.setField(controller, "publications", service);
        ReflectionTestUtils.setField(controller, "growth", mock(ContentGrowthService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/franchise/content/publications")
                        .header("Idempotency-Key", "publication-http-manual")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workId\":88,\"accountId\":\" 门店视频号 \","
                                + "\"platform\":\"channels\",\"publishText\":\"人工发布文案\","
                                + "\"tags\":\"乡墅,案例\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountId").value("门店视频号"))
                .andExpect(jsonPath("$.data.publishText").value("人工发布文案"))
                .andExpect(jsonPath("$.data.tags").value("乡墅,案例"))
                .andExpect(jsonPath("$.data.authorUserId").exists())
                .andExpect(jsonPath("$.data.createTime").isNotEmpty());
    }

    @Test
    void publicationViewExposesStoredManualRegistrationMetadata() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-11-14T09:30:00+08:00");
        var view = ContentPublicationController.view(new FrContentPublicationDO()
                .setId(1L).setWorkId(2L).setAuthorUserId(9007199254740993L)
                .setTitle("人工登记").setAccountId("门店视频号").setPlatform("channels")
                .setPublishText("已人工发布的文案").setTags("乡墅,案例").setStatus("publishing")
                .setCreateTime(createdAt));

        assertThat(view).containsEntry("publishText", "已人工发布的文案")
                .containsEntry("tags", "乡墅,案例")
                .containsEntry("accountSource", "MANUAL_LEGACY")
                .containsEntry("publisherAccountId", null)
                .containsEntry("authorUserId", 9007199254740993L)
                .containsEntry("createTime", createdAt.toString());
    }

    @Test
    void scheduledPublicationIsRejectedAtHttpBoundary() throws Exception {
        PostSnapshot post = new PostSnapshot(9L, 7L, 100L, "store", "content", null, false, Set.of());
        CurrentPost currentPost = mock(CurrentPost.class);
        when(currentPost.require()).thenReturn(post);
        ContentDraftService drafts = mock(ContentDraftService.class);
        when(drafts.workOf(100L, 88L)).thenReturn(new FrContentDraftDO().setId(88L).setCompanyId(100L));
        FranchiseCommands commands = mock(FranchiseCommands.class);
        when(commands.replayed(org.mockito.ArgumentMatchers.eq(post), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(1);
        ContentPublicationService service = new ContentPublicationService(mock(FrContentPublicationMapper.class),
                drafts, commands, jdbc, mock(cn.iocoder.yudao.module.franchise.content.content.ContentPublisherAccountService.class));
        ContentPublicationController controller = new ContentPublicationController();
        ReflectionTestUtils.setField(controller, "currentPost", currentPost);
        ReflectionTestUtils.setField(controller, "publications", service);
        ReflectionTestUtils.setField(controller, "growth", mock(ContentGrowthService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler("test", mock(ApiErrorLogCommonApi.class)))
                .build();

        mvc.perform(post("/franchise/content/publications")
                        .header("Idempotency-Key", "publication-http-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workId\":88,\"accountId\":\"门店视频号\",\"platform\":\"channels\","
                                + "\"scheduledAt\":\"2026-12-01T10:00:00+08:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(INPUT_INVALID.getCode()));
    }
}
