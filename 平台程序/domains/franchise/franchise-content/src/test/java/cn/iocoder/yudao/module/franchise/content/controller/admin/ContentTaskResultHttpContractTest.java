package cn.iocoder.yudao.module.franchise.content.controller.admin;

import cn.iocoder.yudao.module.franchise.content.content.ContentTaskService;
import cn.iocoder.yudao.module.franchise.content.content.ContentDraftService;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ContentTaskResultHttpContractTest {
    private MockMvc endpoint(FrContentTaskDO task) {
        var current=mock(CurrentPost.class);
        when(current.require()).thenReturn(new PostSnapshot(77L,77L,33L,"FRANCHISE","boss",null,false,Set.of()));
        var tasks=mock(ContentTaskService.class);
        when(tasks.requireOwned(33L,123L)).thenReturn(task);
        var controller=new ContentTaskController();
        ReflectionTestUtils.setField(controller,"currentPost",current);
        ReflectionTestUtils.setField(controller,"tasks",tasks);
        ReflectionTestUtils.setField(controller,"drafts",new ContentDraftService(null,null,null,tasks,null));
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test void pendingGenerationAndUnarchivedWorkCannotBeRemovedFromTaskCenter() {
        var current=mock(CurrentPost.class);
        when(current.require()).thenReturn(new PostSnapshot(77L,77L,33L,"store","boss",null,false,Set.of()));
        var tasks=mock(ContentTaskService.class);
        var jdbc=mock(JdbcTemplate.class);
        var controller=new ContentTaskController();
        ReflectionTestUtils.setField(controller,"currentPost",current);
        ReflectionTestUtils.setField(controller,"tasks",tasks);
        ReflectionTestUtils.setField(controller,"jdbcTemplate",jdbc);
        var task=new FrContentTaskDO().setId(123L).setAuthorUserId(77L).setKind("copy");
        when(tasks.requireOwned(33L,123L)).thenReturn(task);
        for(String status:List.of("QUEUED","RUNNING","SUBMISSION_UNCERTAIN")) {
            task.setStatus(status);
            assertThatThrownBy(()->controller.dismiss(123L)).hasMessageContaining("仍在处理");
        }
        for(String result:List.of("PENDING","FAILED")) {
            task.setStatus("SUCCEEDED").setResultState(result);
            assertThatThrownBy(()->controller.dismiss(123L)).hasMessageContaining("待补保存");
        }
        verifyNoInteractions(jdbc);
        task.setResultState("READY");
        org.assertj.core.api.Assertions.assertThat(controller.dismiss(123L).getData()).isTrue();
        verify(jdbc).update(startsWith("INSERT INTO fr_content_task_hidden"),eq(33L),eq(77L),eq(123L),eq(77L));
    }

    @Test void mediaParsingSuccessDoesNotWaitForOptionalCaptionPersistence() throws Exception {
        for(String state:List.of("PENDING","FAILED","READY")) {
            var task=new FrContentTaskDO().setId(123L).setKind("link_parse").setStatus("SUCCEEDED")
                    .setResultState(state).setResultError("保存失败")
                    .setResultSnapshot("{\"text\":\"\",\"videoUrl\":\"https://media.example/video.mp4\",\"audioUrl\":\"https://media.example/audio.mp3\"}");
            endpoint(task).perform(get("/franchise/content/tasks/123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                    .andExpect(jsonPath("$.data.resultState").value(state))
                    .andExpect(jsonPath("$.data.statusText").value(""))
                    .andExpect(jsonPath("$.data.failedReason").value(""))
                    .andExpect(jsonPath("$.data.result.videoUrl").value("https://media.example/video.mp4"))
                    .andExpect(jsonPath("$.data.result.audioUrl").value("https://media.example/audio.mp3"));
        }
    }

    @Test void copyAndTranscriptionStillWaitForDurableScript() throws Exception {
        for(String kind:List.of("copy","asr"))for(String state:List.of("PENDING","FAILED","READY")) {
            var task=new FrContentTaskDO().setId(123L).setKind(kind).setStatus("SUCCEEDED").setResultState(state);
            endpoint(task).perform(get("/franchise/content/tasks/123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.generationStatus").value("SUCCEEDED"))
                    .andExpect(jsonPath("$.data.saveRetryable").value("FAILED".equals(state)))
                    .andExpect(jsonPath("$.data.canDismiss").value("READY".equals(state)))
                    .andExpect(jsonPath("$.data.status").value(switch(state){case "PENDING"->"ARCHIVING";case "FAILED"->"FAILED";default->"SUCCEEDED";}));
        }
    }

    @Test void actualParseFailureIsStillReported() throws Exception {
        var task=new FrContentTaskDO().setId(123L).setKind("link_parse").setStatus("FAILED")
                .setResultState("PENDING").setFailedReason("链接中没有可用视频");
        endpoint(task).perform(get("/franchise/content/tasks/123"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failedReason").value("链接中没有可用视频"));
    }

    @Test void editableInputReturnsOnlyTheOriginalVideoFormAndStableAssetIds() throws Exception {
        var task=new FrContentTaskDO().setId(123L).setAuthorUserId(77L).setKind("ai_video")
                .setInputSnapshot("""
                    {"description":"原始画面", "resolution":"2K", "duration":9, "ratio":"adaptive",
                     "references":[{"role":"first_frame","assetId":9007199254740993,"url":"https://private.example/signed"},
                                   {"role":"last_frame","assetId":"9007199254740995"}],
                     "_authorizedAssets":{"tenantId":11,"postId":77,"secret":"proof"},"providerToken":"secret"}
                    """);
        endpoint(task).perform(get("/franchise/content/tasks/123/editable-input"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data",org.hamcrest.Matchers.aMapWithSize(8)))
                .andExpect(jsonPath("$.data.taskId").value("123"))
                .andExpect(jsonPath("$.data.kind").value("ai_video"))
                .andExpect(jsonPath("$.data.mode").value("frames"))
                .andExpect(jsonPath("$.data.description").value("原始画面"))
                .andExpect(jsonPath("$.data.parameters.duration").value(9))
                .andExpect(jsonPath("$.data.parameters.resolution").value("2K"))
                .andExpect(jsonPath("$.data.parameters.ratio").value("adaptive"))
                .andExpect(jsonPath("$.data.firstFrameAssetId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.lastFrameAssetId").value("9007199254740995"))
                .andExpect(jsonPath("$.data.references").isEmpty())
                .andExpect(jsonPath("$.data._authorizedAssets").doesNotExist())
                .andExpect(jsonPath("$.data.providerToken").doesNotExist());
    }

    @Test void referenceInputOmitsPrivateUrlsAndPreservesEachSupportedRole() throws Exception {
        var task=new FrContentTaskDO().setId(123L).setAuthorUserId(77L).setKind("ai_video")
                .setInputSnapshot("""
                    {"description":"参考原片", "resolution":"768P", "duration":5, "ratio":"16:9",
                     "references":[{"role":"reference_image","assetId":"91","name":"图","url":"https://private.example","_authorizedAssets":{"secret":true}},
                                   {"role":"reference_video","assetId":"92","name":"视频"},
                                   {"role":"reference_audio","assetId":"93","name":"音频"}]}
                    """);
        endpoint(task).perform(get("/franchise/content/tasks/123/editable-input"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value("reference"))
                .andExpect(jsonPath("$.data.firstFrameAssetId").value(""))
                .andExpect(jsonPath("$.data.references.length()").value(3))
                .andExpect(jsonPath("$.data.references[0]",org.hamcrest.Matchers.aMapWithSize(3)))
                .andExpect(jsonPath("$.data.references[0].assetId").value("91"))
                .andExpect(jsonPath("$.data.references[0].name").value("图"))
                .andExpect(jsonPath("$.data.references[1].role").value("reference_video"))
                .andExpect(jsonPath("$.data.references[2].role").value("reference_audio"));
    }
}
