package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.content.dal.FrContentPersonaDO;
import cn.iocoder.yudao.module.franchise.content.provider.*;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentCloneUploadPgIntegrationTest extends FranchisePgIntegrationTest {
    @Resource DataSource dataSource;
    @Resource PlatformTransactionManager transactionManager;
    JdbcTemplate jdbc;
    ContentPersonaService personas;
    final PostSnapshot post=new PostSnapshot(11L,22L,33L,"store","sales-lead",null,false,Set.of());
    final String sha="a".repeat(64);
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(dataSource);jdbc.execute("TRUNCATE fr_content_digital_clone,fr_content_hifly_upload");
        personas=mock(ContentPersonaService.class);
        when(personas.list(33)).thenReturn(List.of(new FrContentPersonaDO().setId(21L).setEnabled(true),new FrContentPersonaDO().setId(23L).setEnabled(false)));
        when(personas.requireWritable(eq(post),eq(21L))).thenReturn(new FrContentPersonaDO().setId(21L).setEnabled(true));
        when(personas.requireWritable(eq(post),eq(23L))).thenReturn(new FrContentPersonaDO().setId(23L).setEnabled(false));
    }
    ContentCloneUploadService service(ContentProviderTransport transport) {
        var config=new ContentProviderProperties();config.getHifly().setApiKey("server-only-key");
        return new ContentCloneUploadService(jdbc,personas,config,transport,null);
    }
    ContentProviderTransport.Scripted grants() {
        return new ContentProviderTransport.Scripted(ContentProviderTransport.json(200,"{\"upload_url\":\"https://upload.hifly.cc/sample.mp3?Signature=fixture\",\"content_type\":\"audio/mp3\",\"file_id\":\"provider-file\"}",0));
    }
    long issue(ContentCloneUploadService service) {return Long.parseLong((String)service.issue(post,21,"VOICE","audio/mpeg",100,sha).get("uploadId"));}
    @Test void grantExposesOnlyScopedUploadReceiptAndKeepsNoMediaBytes() {
        var transport=grants();var service=service(transport);
        var ticket=service.issue(post,21,"VOICE","audio/mpeg",100,sha);
        assertThat(ticket.keySet()).containsExactlyInAnyOrder("uploadId","uploadUrl","contentType","expiresAt");
        assertThat(ticket.toString()).doesNotContain("server-only-key","provider-file");assertThat(ticket.get("contentType")).isEqualTo("audio/mp3");
        assertThat(transport.calls).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT status FROM fr_content_hifly_upload",String.class)).isEqualTo("ISSUED");
    }
    @Test void completingIsIdempotentButCannotTrustOtherPeopleOrKindsOrUnfinishedUploads() {
        var service=service(grants());long id=issue(service);
        assertThatThrownBy(()->service.requireUploaded(post,21,id,"VOICE")).hasMessageContaining("尚未上传完成");
        assertThat(service.complete(post,id)).isEqualTo(service.complete(post,id));
        assertThat(service.requireUploaded(post,21,id,"VOICE").fileId()).isEqualTo("provider-file");
        assertThatThrownBy(()->service.requireUploaded(post,22,id,"VOICE")).hasMessageContaining("当前人物");
        assertThatThrownBy(()->service.requireUploaded(post,21,id,"AVATAR")).hasMessageContaining("MP4");
        for(var other:List.of(new PostSnapshot(12L,99L,33L,"store","boss",null,false,Set.of()),new PostSnapshot(12L,22L,34L,"store","boss",null,false,Set.of())))
            assertThatThrownBy(()->service.complete(other,id)).hasMessageContaining("无权使用");
    }
    @Test void invalidMediaAndInactivePersonNeverRequestVendorCredentials() {
        var transport=new ContentProviderTransport.Scripted();var service=service(transport);
        for(long size:new long[]{0,20L*1024*1024+1})assertThatThrownBy(()->service.issue(post,21,"VOICE","audio/mpeg",size,sha)).hasMessageContaining("20MB");
        assertThatThrownBy(()->service.issue(post,21,"AVATAR","audio/mpeg",100,sha)).hasMessageContaining("MP4");
        assertThatThrownBy(()->service.issue(post,23,"VOICE","audio/mpeg",100,sha)).hasMessageContaining("停用");
        assertThatThrownBy(()->service.issue(post,21,"VOICE","audio/mpeg",100,"bad-hash")).hasMessageContaining("校验");
        assertThat(transport.calls).isEmpty();
    }
    @Test void expiredUnfinishedGrantCannotBeCompleted() {
        var service=service(grants());long id=issue(service);
        jdbc.update("UPDATE fr_content_hifly_upload SET expires_at=now()-interval '1 second'");
        assertThatThrownBy(()->service.complete(post,id)).hasMessageContaining("过期");
        assertThat(jdbc.queryForObject("SELECT status FROM fr_content_hifly_upload",String.class)).isEqualTo("ISSUED");
    }
    String undo() throws Exception {
        try(var input=getClass().getResourceAsStream("/db/migration-undo/franchise/V20261116.924__undo_fr_content_hifly_direct_upload.sql")){return new String(Objects.requireNonNull(input).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
    }
    @Test void emptySchemaCanRollBackInsideTransaction() throws Exception {
        String sql=undo();new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            jdbc.execute(sql);assertThat(jdbc.queryForObject("SELECT to_regclass('public.fr_content_hifly_upload')::text",String.class)).isNull();status.setRollbackOnly();
        });
    }
    @Test void rollbackCannotEraseEvenUncompletedVendorUploadFacts() throws Exception {
        issue(service(grants()));String sql=undo();
        assertThatThrownBy(()->jdbc.execute(sql)).rootCause().hasMessageContaining("禁止回滚924");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_hifly_upload",Long.class)).isEqualTo(1);
    }
}
