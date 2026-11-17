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
}
