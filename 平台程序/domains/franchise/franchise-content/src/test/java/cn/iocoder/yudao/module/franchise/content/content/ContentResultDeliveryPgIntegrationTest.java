package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Import({ContentTaskResultService.class,ContentTaskStore.class,ContentScriptService.class})
class ContentResultDeliveryPgIntegrationTest extends FranchisePgIntegrationTest {
    @Resource ContentTaskResultService results;
    @Resource ContentTaskStore store;
    @Resource JdbcTemplate jdbc;
    @MockitoBean ContentTaskService tasks;
    @MockitoBean ContentPersonaService personas;
    @MockitoBean FranchiseCommands commands;
    long success(String kind,String json) {
        long id=ContentTaskStore.newId();store.insert(id,33,77,kind,"{}",null);
        store.succeedSync(id,33,"fake",null,null,null,json);return id;
    }
    @Test void mediaOnlyLinksCompleteWithoutCreatingEmptyScripts() {
        for(String caption:java.util.List.of("",",\"text\":null",",\"text\":\"\"",",\"text\":\"   \"")) {
            for(String media:java.util.List.of("videoUrl","audioUrl")) {
                String snapshot="{\""+media+"\":\"https://media.example/source\""+caption+"}";
                long id=success("link_parse",snapshot);
                String savedSnapshot=store.byId(33,id).getResultSnapshot();
                results.deliver(33,id);results.deliver(33,id);
                assertThat(store.byId(33,id).getResultState()).isEqualTo("READY");
                assertThat(store.byId(33,id).getResultSnapshot()).isEqualTo(savedSnapshot);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_script WHERE task_id=?",Integer.class,id)).isZero();
                assertThat(jdbc.queryForObject("SELECT result_attempts FROM fr_content_task WHERE id=?",Integer.class,id)).isZero();
            }
        }
    }
    @Test void linkCaptionIsStillSavedOnceButIsNotTheTranscript() {
        long id=success("link_parse","{\"videoUrl\":\"https://media.example/video.mp4\",\"text\":\"optional caption\",\"platform\":\"douyin\"}");
        results.deliver(33,id);results.deliver(33,id);
        assertThat(store.byId(33,id).getResultState()).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT raw_text FROM fr_content_script WHERE task_id=?",String.class,id)).isEqualTo("optional caption");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_script WHERE task_id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void textProducingTasksStillRejectEmptyResults() {
        for(String kind:java.util.List.of("copy","asr")) {
            long id=success(kind,"{\"text\":\"   \"}");
            assertThatThrownBy(()->results.deliver(33,id)).isInstanceOf(IllegalStateException.class);
            assertThat(store.byId(33,id).getResultState()).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_script WHERE task_id=?",Integer.class,id)).isZero();
        }
    }
    @Test void restartAfterProviderSuccessRecoversOnceEvenWithConcurrentDelivery() throws Exception {
        long id=success("asr","{\"text\":\"recovered text\",\"durationSec\":5}");
        assertThat(results.pending()).anyMatch(row->((Number)row.get("id")).longValue()==id);
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{gate.await();results.deliver(33,id);return true;});
            var b=pool.submit(()->{gate.await();results.deliver(33,id);return true;});
            gate.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        results.deliver(33,id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_script WHERE task_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(store.byId(33,id).getResultState()).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT duration_seconds FROM fr_content_script WHERE task_id=?",Integer.class,id)).isEqualTo(5);
    }
    @Test void failureBetweenScriptAndReadyRollsBackAndLaterRecovers() {
        long id=success("link_parse","{\"text\":\"recover after failure\"}");
        jdbc.execute("CREATE FUNCTION reject_result_ready() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.result_state='READY' THEN RAISE EXCEPTION 'simulated database write failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_result_ready BEFORE UPDATE ON fr_content_task FOR EACH ROW EXECUTE FUNCTION reject_result_ready()");
        try {
            assertThatThrownBy(()->results.deliver(33,id)).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_script WHERE task_id=?",Integer.class,id)).isZero();
            assertThat(store.byId(33,id).getResultState()).isEqualTo("PENDING");
        }finally{jdbc.execute("DROP TRIGGER reject_result_ready ON fr_content_task");jdbc.execute("DROP FUNCTION reject_result_ready()");}
        results.failed(33,id);
        jdbc.update("UPDATE fr_content_task SET result_retry_after=now() WHERE id=?",id);
        results.deliver(33,id);assertThat(store.byId(33,id).getResultState()).isEqualTo("READY");
    }
    @Test void missingCopyRowAndInvalidResultsBecomeVisibleSaveFailure() {
        long id=success("copy","{\"text\":\"optimized\",\"engine\":\"rules\"}");
        for(int i=0;i<5;i++) {
            assertThatThrownBy(()->results.deliver(33,id)).isInstanceOf(RuntimeException.class);
            results.failed(33,id);jdbc.update("UPDATE fr_content_task SET result_retry_after=now() WHERE id=?",id);
        }
        assertThat(store.byId(33,id).getResultState()).isEqualTo("FAILED");
        assertThat(store.byId(33,id).getResultError()).contains("保存失败");
        assertThat(results.pending()).noneMatch(row->((Number)row.get("id")).longValue()==id);
        long video=success("ai_video","{}");results.deliver(33,video);
        assertThat(jdbc.queryForObject("SELECT result_attempts FROM fr_content_task WHERE id=?",Integer.class,video)).isZero();
    }
    @Test void exhaustedSaveRetryRecoversOriginalAsrWithoutAnotherProviderCallOrCharge() {
        var provider=org.mockito.Mockito.mock(cn.iocoder.yudao.module.franchise.content.provider.AsrPort.class);
        org.mockito.Mockito.when(provider.transcribe(org.mockito.ArgumentMatchers.any())).thenReturn(
            new cn.iocoder.yudao.module.franchise.content.provider.AsrPort.Transcript("已转写的原始文案",5.0));
        var points=org.mockito.Mockito.mock(cn.iocoder.yudao.module.franchise.api.OrgPointPort.class);
        jdbc.execute("CREATE TABLE IF NOT EXISTS org_point_ledger (id BIGINT PRIMARY KEY,operation_id BIGINT,biz_key VARCHAR(160),type VARCHAR(16))");
        org.mockito.Mockito.when(points.settleByBizKey(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call->{
            jdbc.update("INSERT INTO org_point_ledger(id,biz_key,type) VALUES(?,?,'CONSUME')",ContentTaskStore.newId(),call.getArgument(0));
            return new cn.iocoder.yudao.module.franchise.api.OrgPointPort.Settlement(777,1,0,false);
        });
        var service=new ContentTaskService(store,null,null,provider,null,null,null,null,commands,
            new cn.iocoder.yudao.module.franchise.content.provider.ContentProviderProperties(),points);
        var task=service.createAsr(33,77,null,"https://media.example/audio",null,5.0,null);
        service.submitOne(task);
        long id=task.getId();assertThat(store.byId(33,id).getStatus()).isEqualTo("SUCCEEDED");
        String snapshot=store.byId(33,id).getResultSnapshot();
        jdbc.execute("CREATE FUNCTION reject_asr_save() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'simulated script save outage'; END $$");
        jdbc.execute("CREATE TRIGGER reject_asr_save BEFORE INSERT ON fr_content_script FOR EACH ROW EXECUTE FUNCTION reject_asr_save()");
        try {
            for(int i=0;i<5;i++) {
                assertThatThrownBy(()->results.deliver(33,id)).isInstanceOf(RuntimeException.class);
                results.failed(33,id);jdbc.update("UPDATE fr_content_task SET result_retry_after=now() WHERE id=?",id);
            }
        } finally {jdbc.execute("DROP TRIGGER reject_asr_save ON fr_content_script");jdbc.execute("DROP FUNCTION reject_asr_save()");}
        assertThat(store.byId(33,id).getResultState()).isEqualTo("FAILED");
        assertThatThrownBy(()->results.retrySave(34,77,id)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(()->results.retrySave(33,78,id)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(results.retrySave(33,77,id).getResultState()).isEqualTo("PENDING");
        results.retrySave(33,77,id);results.deliver(33,id);results.retrySave(33,77,id);results.deliver(33,id);
        assertThat(store.byId(33,id).getResultState()).isEqualTo("READY");
        assertThat(store.byId(33,id).getResultSnapshot()).isEqualTo(snapshot);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_script WHERE task_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_ledger WHERE biz_key=?",Integer.class,task.getPoolBizKey())).isEqualTo(1);
        org.mockito.Mockito.verify(provider,org.mockito.Mockito.times(1)).transcribe(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(points,org.mockito.Mockito.times(1)).reserve(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(points,org.mockito.Mockito.times(1)).settleByBizKey(task.getPoolBizKey(),1);
        org.mockito.Mockito.verifyNoMoreInteractions(provider,points);
    }
    @Test void retrySaveCannotRestartProviderFailuresOrOtherKinds() {
        long failed=success("asr","{}");jdbc.update("UPDATE fr_content_task SET status='FAILED' WHERE id=?",failed);
        assertThatThrownBy(()->results.retrySave(33,77,failed)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        long video=success("ai_video","{}");assertThatThrownBy(()->results.retrySave(33,77,video)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }
}
