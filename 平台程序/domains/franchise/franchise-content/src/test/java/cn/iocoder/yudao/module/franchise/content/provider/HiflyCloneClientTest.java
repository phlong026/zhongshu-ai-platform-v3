package cn.iocoder.yudao.module.franchise.content.provider;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
class HiflyCloneClientTest {
    @Test void directUploadUsesOfficialReceiptWithoutCodeAndExactMp3ContentType() {
        var transport=new ContentProviderTransport.Scripted(ContentProviderTransport.json(200,"{\"upload_url\":\"https://upload.hifly.cc/test.mp3?Signature=fixture\",\"content_type\":\"audio/mp3\",\"file_id\":\"file-1\"}",0));
        var grant=client(transport).uploadGrant("mp3",ProviderCallAudit.NONE);
        assertThat(grant.fileId()).isEqualTo("file-1");assertThat(grant.contentType()).isEqualTo("audio/mp3");
        assertThat(transport.calls.get(0).url()).endsWith("/tool/create_upload_url");
        assertThat(new String(transport.calls.get(0).body(),StandardCharsets.UTF_8)).contains("file_extension","mp3");
    }
    @Test void directUploadRejectsMalformedOrForeignDestinationsAndCreatesByFileId() {
        for(String url:java.util.List.of("https://platform.example/object","http://upload.hifly.cc/a","https://upload.hifly.cc@evil.example/a")) {
            var t=new ContentProviderTransport.Scripted(ContentProviderTransport.json(200,"{\"upload_url\":\""+url+"\",\"content_type\":\"video/mp4\",\"file_id\":\"file-1\"}",0));
            assertThatThrownBy(()->client(t).uploadGrant("mp4",ProviderCallAudit.NONE)).isInstanceOf(ContentProviderException.Unavailable.class);
        }
        for(String kind:java.util.List.of("AVATAR","VOICE")) {
            var t=new ContentProviderTransport.Scripted(ContentProviderTransport.json(200,"{\"code\":0,\"task_id\":\"task-1\"}",0));
            assertThat(client(t).createByFile(kind,"本人素材","file-1",ProviderCallAudit.NONE)).isEqualTo("task-1");
            assertThat(new String(t.calls.get(0).body(),StandardCharsets.UTF_8)).contains("file_id").doesNotContain("video_url","audio_url");
        }
    }
    @Test void officialRootLevelReceiptsWorkForBothCloneKinds() {
        for(String kind:java.util.List.of("AVATAR","VOICE")) {
            String field="AVATAR".equals(kind)?"avatar":"voice";
            var transport=new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200,"{\"code\":0,\"task_id\":\"official-task\"}",0),
                ContentProviderTransport.json(200,"{\"code\":0,\"status\":3,\""+field+"\":\"resource-1\",\"demo_url\":\"https://example.test/demo.mp3\"}",0));
            var client=client(transport);
            assertThat(client.create(kind,"测试资源","https://example.test/source",ProviderCallAudit.NONE)).isEqualTo("official-task");
            var done=client.poll(kind,"official-task",ProviderCallAudit.NONE);
            assertThat(done.status()).isEqualTo("READY");assertThat(done.resourceId()).isEqualTo("resource-1");
            assertThat(done.demoUrl()).isEqualTo("https://example.test/demo.mp3");
        }
    }
    @Test void rootLevelProgressFailureAndUnknownStayDistinct() {
        for(int status:java.util.List.of(1,2,4,99)) {
            var transport=new ContentProviderTransport.Scripted(ContentProviderTransport.json(200,"{\"code\":0,\"status\":"+status+"}",0));
            assertThat(client(transport).poll("VOICE","task",ProviderCallAudit.NONE).status())
                .isEqualTo(status==4?"FAILED":status==99?"UNKNOWN":"RUNNING");
        }
    }
    private HiflyCloneClient client(ContentProviderTransport transport) {var config=new ContentProviderProperties.Hifly();config.setApiKey("test-key");return new HiflyCloneClient(config,transport,null);}
    @Test void cloneUsesDocumentedVideoAndVoiceContracts() {
        var transport=new ContentProviderTransport.Scripted(ContentProviderTransport.json(200,"{\"code\":0,\"data\":{\"task_id\":\"a\"}}",0),ContentProviderTransport.json(200,"{\"code\":0,\"data\":{\"task_id\":\"v\"}}",0));
        var client=client(transport);assertThat(client.create("AVATAR","本人分身","https://example.test/a.mp4",ProviderCallAudit.NONE)).isEqualTo("a");
        client.create("VOICE","本人声音","https://example.test/v.mp3",ProviderCallAudit.NONE);
        assertThat(transport.calls.get(0).url()).endsWith("/avatar/create_by_video");assertThat(new String(transport.calls.get(0).body(),StandardCharsets.UTF_8)).contains("video_url","aigc_flag").doesNotContain("image_url");
        assertThat(transport.calls.get(1).url()).endsWith("/voice/create");assertThat(new String(transport.calls.get(1).body(),StandardCharsets.UTF_8)).contains("audio_url","voice_type");
    }
    @Test void malformedSuccessIsUncertainAndNeverAssumedRejected() {
        var transport=new ContentProviderTransport.Scripted(ContentProviderTransport.json(200,"{\"data\":{}}",0));
        assertThatThrownBy(()->client(transport).create("AVATAR","分身","https://example.test/a.mp4",ProviderCallAudit.NONE)).isInstanceOf(ContentProviderException.Uncertain.class);
    }
    @Test void successfulTaskWithoutResourceIDIsStillUnknown() {
        var transport=new ContentProviderTransport.Scripted(ContentProviderTransport.json(200,"{\"code\":0,\"data\":{\"status\":3}}",0));
        assertThat(client(transport).poll("VOICE","a",ProviderCallAudit.NONE).status()).isEqualTo("UNKNOWN");
    }
}
