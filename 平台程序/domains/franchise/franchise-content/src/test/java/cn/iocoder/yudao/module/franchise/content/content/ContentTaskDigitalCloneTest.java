package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import cn.iocoder.yudao.module.franchise.content.provider.ContentProviderProperties;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentTaskDigitalCloneTest {
    final PostSnapshot post = new PostSnapshot(9, 11, 33, "FRANCHISE", "sales", null, false, Set.of());
    FranchiseCommands commands;
    ContentCloneService clones;
    ContentTaskService service;

    @BeforeEach void setup() {
        commands = mock(FranchiseCommands.class);
        clones = mock(ContentCloneService.class);
        when(commands.replayed(any(), anyString(), any())).thenReturn(Optional.empty());
        service = spy(new ContentTaskService(mock(ContentTaskStore.class), null, null, null, null,
                null, null, null, commands, new ContentProviderProperties(), null));
        ReflectionTestUtils.setField(service, "clones", clones);
    }

    @Test void rawNamesCannotBypassCompanyAndPersonaCloneSelection() {
        assertThatThrownBy(() -> service.createFromPost(post,
                Map.of("kind", "digital", "actor", "vendor-avatar", "voice", "vendor-voice", "text", "稿件"), "raw"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        verifyNoInteractions(clones);
        verify(commands, never()).record(any(), anyString(), anyLong(), anyInt(), anyString(), any(), anyString());
    }

    @Test void taskUsesOnlyProviderIdsResolvedFromLocalClones() {
        when(clones.select(post, 101, 102)).thenReturn(new ContentCloneService.DigitalSelection(21, "resolved-avatar", "resolved-voice"));
        var task = new FrContentTaskDO().setId(88L);
        doReturn(task).when(service).createDigital(33, 11, "resolved-voice", "稿件", "resolved-avatar", "口播", null);
        var body = Map.<String, Object>of("kind", "digital", "avatarCloneId", "101", "voiceCloneId", "102",
                "actor", "untrusted-avatar", "voice", "untrusted-voice", "text", "稿件", "title", "口播");
        assertThat(service.createFromPost(post, body, "selected")).isSameAs(task);
        verify(clones).select(post, 101, 102);
        verify(service).createDigital(33, 11, "resolved-voice", "稿件", "resolved-avatar", "口播", null);
    }
}
