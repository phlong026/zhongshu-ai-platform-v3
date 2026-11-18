package cn.iocoder.yudao.module.franchise.content.content;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentPersonaDO;
import cn.iocoder.yudao.module.franchise.content.provider.*;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.Test;import org.springframework.jdbc.core.JdbcTemplate;import java.util.*;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;
class ContentUploadFormatsTest {
 final PostSnapshot post=new PostSnapshot(1,2,3,"store","sales",null,false,Set.of());
 @Test void officialFormatsAndExactSizeBoundariesAreAcceptedWithoutUploadingBytes(){
  for(var entry:Map.of("video/mp4","mp4","video/mov","mov","audio/mpeg","mp3","audio/m4a","m4a","audio/wav","wav").entrySet()){
   boolean video=entry.getKey().startsWith("video/");var personas=mock(ContentPersonaService.class);when(personas.requireWritable(post,4)).thenReturn(new FrContentPersonaDO().setEnabled(true));
   var t=new ContentProviderTransport.Scripted(ContentProviderTransport.json(200,"{\"upload_url\":\"https://upload.hifly.cc/test\",\"content_type\":\""+entry.getKey()+"\",\"file_id\":\"file\"}",0));
   var config=new ContentProviderProperties();config.getHifly().setApiKey("test");var jdbc=mock(JdbcTemplate.class);var service=new ContentCloneUploadService(jdbc,personas,config,t,null);long size=20L*1024*1024;
   service.issue(post,4,video?"AVATAR":"VOICE",entry.getKey(),size,"a".repeat(64));assertThat(t.calls).hasSize(1);assertThat(new String(t.calls.get(0).body(),java.nio.charset.StandardCharsets.UTF_8)).contains(entry.getValue());
   assertThatThrownBy(()->service.issue(post,4,video?"AVATAR":"VOICE",entry.getKey(),size+1,"a".repeat(64))).hasMessageContaining("20MB");assertThat(t.calls).hasSize(1);
  }
 }
}
