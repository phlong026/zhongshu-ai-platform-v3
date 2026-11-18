package cn.iocoder.yudao.module.franchise.content.controller.admin;

import cn.iocoder.yudao.module.franchise.content.content.ContentTaskService;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.Test;
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
        return MockMvcBuilders.standaloneSetup(controller).build();
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
}
