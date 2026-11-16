package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.api.*;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentPersonaDO;
import cn.iocoder.yudao.module.franchise.content.provider.*;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentClonePgIntegrationTest extends FranchisePgIntegrationTest {
    @Resource DataSource dataSource;
    @Resource PlatformTransactionManager transactionManager;
    JdbcTemplate jdbc;
    ContentPersonaService personas;
    OrgPointPort points;
    ContentProviderProperties properties;
    final PostSnapshot post=new PostSnapshot(11L,22L,33L,"store","sales-lead",null,false,Set.of());
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(dataSource);jdbc.execute("TRUNCATE fr_content_digital_clone,fr_content_hifly_upload");
        personas=mock(ContentPersonaService.class);points=mock(OrgPointPort.class);
        when(personas.list(33)).thenReturn(List.of(new FrContentPersonaDO().setId(21L).setEnabled(true),new FrContentPersonaDO().setId(23L).setEnabled(false)));
        when(points.reserveConfirmed(any(),eq(5L))).thenReturn(555L);
        properties=new ContentProviderProperties();properties.getHifly().setApiKey("test-key");
        for(long id:new long[]{101,102})jdbc.update("INSERT INTO fr_content_hifly_upload(id,company_id,persona_id,author_user_id,author_post_id,kind,provider_file_id,mime_type,size_bytes,declared_sha256,status,expires_at) VALUES(?,33,21,22,11,?,?,?,100,?,'UPLOADED',now()+interval '1 hour')",id,id==101?"AVATAR":"VOICE","file-"+id,id==101?"video/mp4":"audio/mpeg","a".repeat(64));
    }
    ContentCloneService service(ContentProviderTransport transport) {
        return new ContentCloneService(jdbc,personas,new ContentCloneUploadService(jdbc,personas,properties,transport,null),points,properties,transport,null,transactionManager);
    }
    Map<String,Object> create(ContentCloneService service,String kind,String key) {
        return service.create(post,21,kind,"顾问",kind.equals("AVATAR")?101:102,ContentCloneService.CONSENT_VERSION,key,5);
    }
    @Test void acceptedCloneReplaysSameReceiptAndOnlyDispatchesOnce() {
        var transport=new ContentProviderTransport.Scripted(ContentProviderTransport.json(200,"{\"code\":0,\"data\":{\"task_id\":\"task-a\"}}",0));
        var service=service(transport);var first=create(service,"AVATAR","same");var replay=create(service,"AVATAR","same");
        assertThat(first.get("status")).isEqualTo("RUNNING");assertThat(replay.get("id")).isEqualTo(first.get("id"));assertThat(transport.calls).hasSize(1);
        verify(points,times(1)).reserveConfirmed(any(),eq(5L));
        assertThatThrownBy(()->service.create(post,21,"AVATAR","不同名称",101,ContentCloneService.CONSENT_VERSION,"same",5)).hasMessageContaining("参数已变化");
    }
    @Test void explicitRejectionReleasesAndRetryNeverCreatesAgain() {
        var transport=new ContentProviderTransport.Scripted(ContentProviderTransport.json(200,"{\"code\":11,\"data\":{}}",0));
        var service=service(transport);var row=create(service,"AVATAR","rejected");
        assertThat(row.get("status")).isEqualTo("FAILED");create(service,"AVATAR","rejected");
        assertThat(transport.calls).hasSize(1);verify(points,times(1)).release(555);verify(points,never()).settle(anyLong(),anyLong());
    }
    @Test void unknownSubmissionKeepsReserveAndCannotBeRedispatchedByRetry() {
        var transport=new ContentProviderTransport.Scripted(true);var service=service(transport);var row=create(service,"AVATAR","unknown");
        assertThat(row.get("status")).isEqualTo("UNKNOWN");create(service,"AVATAR","unknown");service.refresh(post,Long.parseLong((String)row.get("id")));
        assertThat(transport.calls).hasSize(1);verify(points,never()).release(anyLong());verify(points,never()).settle(anyLong(),anyLong());
    }
    @Test void completedAvatarAndVoiceResolveUnderSameCompanyAndPersonaWithoutExposingProviderIDs() {
        var transport=new ContentProviderTransport.Scripted(
            ContentProviderTransport.json(200,"{\"code\":0,\"data\":{\"task_id\":\"a\"}}",0),
            ContentProviderTransport.json(200,"{\"code\":0,\"data\":{\"status\":3,\"avatar\":\"provider-avatar\"}}",0),
            ContentProviderTransport.json(200,"{\"code\":0,\"data\":{\"task_id\":\"v\"}}",0),
            ContentProviderTransport.json(200,"{\"code\":0,\"data\":{\"status\":3,\"voice\":\"provider-voice\",\"demo_url\":\"https://example.test/demo.mp3\"}}",0));
        var service=service(transport);long avatar=Long.parseLong((String)create(service,"AVATAR","avatar").get("id"));
        var ready=service.refresh(post,avatar);assertThat(ready.get("ready")).isEqualTo(true);
        long voice=Long.parseLong((String)create(service,"VOICE","voice").get("id"));service.refresh(post,voice);
        assertThat(service.select(post,avatar,voice).avatar()).isEqualTo("provider-avatar");
        assertThat(service.select(post,avatar,voice).voice()).isEqualTo("provider-voice");
        assertThat(service.list(post,21).toString()).doesNotContain("provider-avatar","provider-voice");
        var other=new PostSnapshot(12L,22L,34L,"store","sales",null,false,Set.of());
        assertThatThrownBy(()->service.select(other,avatar,voice)).hasMessageContaining("无权使用");
    }
    @Test void wrongMediaAndMissingConsentFailBeforeReserveOrExternalRequest() {
        var transport=new ContentProviderTransport.Scripted();var service=service(transport);
        assertThatThrownBy(()->service.create(post,21,"AVATAR","顾问",102,ContentCloneService.CONSENT_VERSION,"wrong",5)).hasMessageContaining("MP4");
        assertThatThrownBy(()->service.create(post,21,"VOICE","顾问",102,"","missing",5)).hasMessageContaining("授权");
        assertThatThrownBy(()->service.create(post,23,"AVATAR","顾问",101,ContentCloneService.CONSENT_VERSION,"disabled",5)).hasMessageContaining("停用");
        verify(points,never()).reserveConfirmed(any(),anyLong());assertThat(transport.calls).isEmpty();
    }
    @Test void officialRootResponsesSettleBothResourcesAndRejectMixedPersonas() {
        when(personas.list(33)).thenReturn(List.of(new FrContentPersonaDO().setId(21L).setEnabled(true),new FrContentPersonaDO().setId(22L).setEnabled(true)));
        var transport=new ContentProviderTransport.Scripted(
            ContentProviderTransport.json(200,"{\"code\":0,\"task_id\":\"official-avatar\"}",0),
            ContentProviderTransport.json(200,"{\"code\":0,\"status\":3,\"avatar\":\"avatar-resource\"}",0),
            ContentProviderTransport.json(200,"{\"code\":0,\"task_id\":\"official-voice\"}",0),
            ContentProviderTransport.json(200,"{\"code\":0,\"status\":3,\"voice\":\"voice-resource\",\"demo_url\":\"https://example.test/demo.mp3\"}",0));
        var service=service(transport);long a=Long.parseLong((String)create(service,"AVATAR","official-a").get("id"));
        assertThat(service.refresh(post,a).get("ready")).isEqualTo(true);
        long v=Long.parseLong((String)create(service,"VOICE","official-v").get("id"));
        assertThat(service.refresh(post,v).get("ready")).isEqualTo(true);
        assertThat(service.select(post,a,v).voice()).isEqualTo("voice-resource");
        verify(points,times(2)).settle(555,1);
        jdbc.update("UPDATE fr_content_digital_clone SET persona_id=22 WHERE id=?",v);
        assertThatThrownBy(()->service.select(post,a,v)).hasMessageContaining("同一人物");
    }
    @Test void uncompletedDirectUploadNeverChargesOrDispatches() {
        var transport=new ContentProviderTransport.Scripted();var service=service(transport);
        jdbc.update("UPDATE fr_content_hifly_upload SET status='ISSUED' WHERE id=101");
        assertThatThrownBy(()->create(service,"AVATAR","not-uploaded")).hasMessageContaining("尚未上传完成");
        verifyNoInteractions(points);assertThat(transport.calls).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_digital_clone",Long.class)).isZero();
    }
    @Test void sizeLimitsRejectEmptyAndOversizedSamplesBeforeCharge() {
        var transport=new ContentProviderTransport.Scripted();var service=service(transport);
        for(long size:new long[]{20L*1024*1024+1}){
            jdbc.update("UPDATE fr_content_hifly_upload SET size_bytes=? WHERE id=102",size);
            assertThatThrownBy(()->create(service,"VOICE","size-"+size)).hasMessageContaining("20MB");
        }
        jdbc.update("UPDATE fr_content_hifly_upload SET size_bytes=? WHERE id=101",50L*1024*1024+1);
        assertThatThrownBy(()->create(service,"AVATAR","large-video")).hasMessageContaining("50MB");
        verifyNoInteractions(points);assertThat(transport.calls).isEmpty();
    }
    @Test void rejectedReserveRollsBackCloneIntentAndNeverDispatches() {
        when(points.reserveConfirmed(any(),eq(5L))).thenThrow(new IllegalArgumentException("insufficient points"));
        var transport=new ContentProviderTransport.Scripted();var service=service(transport);
        assertThatThrownBy(()->create(service,"AVATAR","no-points")).hasMessageContaining("insufficient");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_digital_clone",Long.class)).isZero();assertThat(transport.calls).isEmpty();
    }
    String undoSql() throws Exception {
        try(var input=getClass().getResourceAsStream("/db/migration-undo/franchise/V20261115.917__undo_fr_content_digital_clones.sql")) {
            return new String(Objects.requireNonNull(input).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    @Test void undoDropsOnlyAnEmptyUnusedTable() throws Exception {
        String sql=undoSql();
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status->{
            jdbc.execute(sql);
            assertThat(jdbc.queryForObject("SELECT to_regclass('public.fr_content_digital_clone')::text",String.class)).isNull();
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_digital_clone",Long.class)).isZero();
    }
    @Test void undoCannotEraseEvenFailedAndReleasedCloneFacts() throws Exception {
        var transport=new ContentProviderTransport.Scripted(ContentProviderTransport.json(200,"{\"code\":11,\"data\":{}}",0));
        create(service(transport),"AVATAR","undo-failed");
        String sql=undoSql();
        assertThatThrownBy(()->jdbc.execute(sql)).rootCause().hasMessageContaining("数字人制作或积分事实已存在，禁止回滚917");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_digital_clone",Long.class)).isEqualTo(1);
    }
}
