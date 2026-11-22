package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@Import(ContentDraftService.class)
class ContentVideoDraftPgIntegrationTest extends FranchisePgIntegrationTest {
    @Resource ContentDraftService drafts;
    @MockitoBean ContentTaskService tasks;
    Map<String,Object> body(String key,long revision,String text) {
        return Map.of("draftKey",key,"revision",revision,"mode","frames","description",text,"parameters",Map.of("duration",5));
    }
    @Test void taskAndOrganizationDraftsAreIndependentAndStaleWritesRejected() {
        when(tasks.requireOwned(33,101)).thenReturn(new FrContentTaskDO().setCompanyId(33L).setAuthorUserId(77L).setKind("ai_video"));
        when(tasks.requireOwned(33,102)).thenReturn(new FrContentTaskDO().setCompanyId(33L).setAuthorUserId(77L).setKind("ai_video"));
        assertThatThrownBy(()->drafts.saveVideoDraft(33,77,Map.of("revision",0.5,"description","invalid"))).isInstanceOf(RuntimeException.class);
        drafts.saveVideoDraft(33,77,body("new",0,"new"));
        drafts.saveVideoDraft(33,77,body("task:101",0,"A"));drafts.saveVideoDraft(33,77,body("task:102",0,"B"));
        drafts.saveVideoDraft(34,77,body("new",0,"other org"));
        assertThatThrownBy(()->drafts.saveVideoDraft(33,77,body("new",0,"stale"))).hasMessageContaining("视频草稿已在另一处更新");
        assertThat(drafts.videoDraft(33,77).getDescription()).isEqualTo("new");
        assertThat(drafts.videoDraft(34,77).getDescription()).isEqualTo("other org");
        assertThat(drafts.videoDraft(33,77,"task:101").getDescription()).isEqualTo("A");
        assertThat(drafts.videoDraft(33,77,"task:102").getParameters()).contains("5");
        when(tasks.requireOwned(33,103)).thenReturn(new FrContentTaskDO().setCompanyId(33L).setAuthorUserId(88L).setKind("ai_video"));
        assertThatThrownBy(()->drafts.saveVideoDraft(33,77,body("task:103",0,"forbidden"))).isInstanceOf(RuntimeException.class);
    }

    @Test void editableInputUsesTheSameAuthorAndTaskKindBoundaryWithoutMutatingTheTask() {
        String snapshot="{\"description\":\"original\",\"resolution\":\"768P\",\"duration\":8,\"references\":[]}";
        var task=new FrContentTaskDO().setId(850101L).setCompanyId(850033L).setAuthorUserId(850077L).setKind("ai_video").setInputSnapshot(snapshot);
        when(tasks.requireOwned(850033,850101)).thenReturn(task);
        when(tasks.requireOwned(850034,850101)).thenThrow(new IllegalArgumentException("other company"));
        assertThat(drafts.videoTaskInput(850033,850077,850101)).containsEntry("description","original").containsEntry("mode","frames");
        drafts.saveVideoDraft(850033,850077,body("task:850101",0,"edited copy"));
        assertThat(task.getInputSnapshot()).isEqualTo(snapshot);
        assertThat(drafts.videoTaskInput(850033,850077,850101)).containsEntry("description","original");
        assertThatThrownBy(()->drafts.videoTaskInput(850033,850088,850101)).hasMessageContaining("任务不存在");
        assertThatThrownBy(()->drafts.videoTaskInput(850034,850077,850101)).hasMessageContaining("other company");
        task.setKind("digital");
        assertThatThrownBy(()->drafts.videoTaskInput(850033,850077,850101)).hasMessageContaining("任务不存在");
        assertThatThrownBy(()->drafts.videoTaskInput(850033,850077,0)).isInstanceOf(RuntimeException.class);
    }

    @Test void unsupportedHistoricalMediaCannotSilentlyBecomeAnEmptyPaidGeneration() {
        var task=new FrContentTaskDO().setId(101L).setAuthorUserId(77L).setKind("ai_video");
        when(tasks.requireOwned(33,101)).thenReturn(task);
        for(String refs:java.util.List.of(
                "[{\"role\":\"first_frame\",\"url\":\"https://old.example\"}]",
                "[{\"role\":\"unknown\",\"assetId\":91}]",
                "[{\"role\":\"first_frame\",\"assetId\":91},{\"role\":\"reference_image\",\"assetId\":92}]",
                "[{\"role\":\"first_frame\",\"assetId\":91},{\"role\":\"first_frame\",\"assetId\":92}]")) {
            task.setInputSnapshot("{\"description\":\"old\",\"resolution\":\"768P\",\"duration\":8,\"references\":"+refs+"}");
            assertThatThrownBy(()->drafts.videoTaskInput(33,77,101)).isInstanceOf(RuntimeException.class);
        }
    }
}
