package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.content.dal.*;
import cn.iocoder.yudao.module.franchise.content.provider.*;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentPersonaOwnershipTest {
    PostSnapshot post(String role, long user, long company) {
        return new PostSnapshot(11L,user,company,"store",role,null,false,Set.of());
    }
    FrContentPersonaDO person() {
        return new FrContentPersonaDO().setId(21L).setCompanyId(33L).setAuthorUserId(22L).setEnabled(true);
    }
    @Test void salesCanCreateOwnProfileButCannotForgeItsAuthorOrCompany() {
        var mapper=mock(FrContentPersonaMapper.class);var service=new ContentPersonaService(mapper);
        service.create(post("sales",22,33),Map.of("name","本人IP","authorUserId",99,"companyId",34));
        var row=ArgumentCaptor.forClass(FrContentPersonaDO.class);verify(mapper).insert(row.capture());
        assertThat(row.getValue().getAuthorUserId()).isEqualTo(22);
        assertThat(row.getValue().getCompanyId()).isEqualTo(33);
        assertThatThrownBy(()->service.create(post("finance",22,33),Map.of("name","越权"))).hasMessageContaining("权限");
        verify(mapper,times(1)).insert(any(FrContentPersonaDO.class));
    }
    @Test void onlyAuthorOrCompanyManagersCanChangeAnExistingProfile() {
        var mapper=mock(FrContentPersonaMapper.class);var service=new ContentPersonaService(mapper);
        when(mapper.selectOne(any())).thenReturn(person());
        assertThat(service.requireWritable(post("sales",22,33),21).getId()).isEqualTo(21);
        for(String role:List.of("boss","owner","sales-lead"))
            assertThat(service.requireWritable(post(role,99,33),21).getId()).isEqualTo(21);
        for(var denied:List.of(post("sales",99,33),post("sales",22,34),post("boss",22,34),post("finance",22,33))) {
            assertThatThrownBy(()->service.update(denied,21,Map.of("name","越权"))).hasMessageContaining("权限");
            assertThatThrownBy(()->service.delete(denied,21)).hasMessageContaining("权限");
        }
        verify(mapper,never()).updateById(any(FrContentPersonaDO.class));
        verify(mapper,never()).deleteById(anyLong());
    }
    @Test void unauthorizedUploadsAreRejectedBeforeRequestingFlyingApiCredentials() {
        var mapper=mock(FrContentPersonaMapper.class);when(mapper.selectOne(any())).thenReturn(person());
        var transport=new ContentProviderTransport.Scripted();var jdbc=mock(JdbcTemplate.class);
        var config=new ContentProviderProperties();config.getHifly().setApiKey("test-only");
        var uploads=new ContentCloneUploadService(jdbc,new ContentPersonaService(mapper),config,transport,null);
        for(var denied:List.of(post("sales",99,33),post("sales",22,34)))
            assertThatThrownBy(()->uploads.issue(denied,21,"VOICE","audio/mpeg",100,"a".repeat(64))).hasMessageContaining("权限");
        assertThat(transport.calls).isEmpty();verifyNoInteractions(jdbc);
    }
}
