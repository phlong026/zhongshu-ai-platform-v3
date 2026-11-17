package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.content.dal.FrContentScriptMapper;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ASR 缓存命中即时终态不进 sweep——必须当场落脚本行（评审十三 P2-3）。 */
class ContentScriptServiceTest {

    @Test
    void asrCacheHitWritesScriptRow() {
        ContentTaskService tasks = mock(ContentTaskService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        FrContentTaskMapper taskMapper = mock(FrContentTaskMapper.class);
        ContentScriptService service = new ContentScriptService(tasks, mock(FrContentScriptMapper.class),
                taskMapper, mock(ContentPersonaService.class), jdbc, null);

        FrContentTaskDO cached = new FrContentTaskDO().setId(1L).setCompanyId(100L).setAuthorUserId(7L)
                .setKind("asr").setStatus("SUCCEEDED")
                .setResultSnapshot("{\"text\":\"缓存转写\",\"durationSec\":30}");
        when(taskMapper.selectSucceededBySha(100L, "asr", "sha-1")).thenReturn(cached);
        FrContentTaskDO hit = new FrContentTaskDO().setId(2L).setCompanyId(100L).setAuthorUserId(7L)
                .setKind("asr").setStatus("SUCCEEDED").setEngine("cache")
                .setResultSnapshot("{\"text\":\"缓存转写\",\"durationSec\":30}");
        when(tasks.authorizedAsrSha(100L,7L,5L)).thenReturn("sha-1");
        when(tasks.createAsr(100L, 7L, 5L, null, "sha-1", 30.0, cached)).thenReturn(hit);

        service.extract(100L, 7L, 5L, null, 30.0, "client-hash-is-ignored");

        verify(jdbc, atLeastOnce()).update(startsWith("INSERT INTO fr_content_script"), any(Object[].class));
        verify(jdbc, atLeastOnce()).update(startsWith("UPDATE fr_content_script SET duration_seconds"),
                any(Object[].class));
    }

}
