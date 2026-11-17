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
}
