package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.api.*;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.content.ContentPermissions;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentPersonaMapper;
import cn.iocoder.yudao.module.franchise.content.provider.*;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
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
    @Resource FrContentPersonaMapper personaMapper;
    @Resource PlatformTransactionManager transactionManager;
    JdbcTemplate jdbc;
    ContentPersonaService personas;
    OrgPointPort points;
    SystemDirectory directory;
    ContentProviderProperties properties;
    final PostSnapshot post=new PostSnapshot(11L,22L,33L,"store","sales-lead",null,false,Set.of());
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(dataSource);jdbc.execute("TRUNCATE fr_content_clone_default,fr_content_digital_clone,fr_content_hifly_upload,fr_content_persona");
        personas=new ContentPersonaService(personaMapper);
        points=mock(OrgPointPort.class);
        directory=mock(SystemDirectory.class);
        when(directory.roleHasPermission("sales-lead", ContentPermissions.PERSONA)).thenReturn(true);
        when(directory.roleHasPermission("sales", ContentPermissions.PERSONA_SELF)).thenReturn(true);
        jdbc.update("""
                INSERT INTO fr_content_persona(id,company_id,author_user_id,name,enabled)
                VALUES(21,33,22,'本人形象',true),(22,33,78,'同事形象',true),(23,33,22,'停用形象',false)
                """);
        when(points.reserveConfirmed(any(),eq(5L))).thenReturn(555L);
        properties=new ContentProviderProperties();properties.getHifly().setApiKey("test-key");
        for(long id:new long[]{101,102})jdbc.update("INSERT INTO fr_content_hifly_upload(id,company_id,persona_id,author_user_id,author_post_id,kind,provider_file_id,mime_type,size_bytes,declared_sha256,status,expires_at) VALUES(?,33,21,22,11,?,?,?,100,?,'UPLOADED',now()+interval '1 hour')",id,id==101?"AVATAR":"VOICE","file-"+id,id==101?"video/mp4":"audio/mpeg","a".repeat(64));
    }
    ContentCloneService service(ContentProviderTransport transport) {
        return new ContentCloneService(jdbc,personas,new ContentCloneUploadService(jdbc,personas,properties,transport,null),points,properties,transport,null,transactionManager,directory);
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
        jdbc.update("UPDATE fr_content_hifly_upload SET size_bytes=? WHERE id=101",500L*1024*1024+1);
        assertThatThrownBy(()->create(service,"AVATAR","large-video")).hasMessageContaining("500MB");
        verifyNoInteractions(points);assertThat(transport.calls).isEmpty();
    }
    @Test void rejectedReserveRollsBackCloneIntentAndNeverDispatches() {
        when(points.reserveConfirmed(any(),eq(5L))).thenThrow(new IllegalArgumentException("insufficient points"));
        var transport=new ContentProviderTransport.Scripted();var service=service(transport);
        assertThatThrownBy(()->create(service,"AVATAR","no-points")).hasMessageContaining("insufficient");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_digital_clone",Long.class)).isZero();assertThat(transport.calls).isEmpty();
    }
    private void readyClone(long id,long persona,String kind,String billing) {
        jdbc.update("INSERT INTO fr_content_digital_clone(id,company_id,persona_id,author_user_id,author_post_id,kind,title,source_asset_id,source_sha256,consent_version,idempotency_key,request_hash,status,provider_resource_id,operation_id,billing_state) VALUES(?,33,?,22,11,?,'同名资源',101,?,?,?,?,'READY',?,555,?)",
                id,persona,kind,"a".repeat(64),ContentCloneService.CONSENT_VERSION,"seed-"+id,"hash-"+id,"provider-"+id,billing);
    }
    @Test void personalSalesCanSetEachOwnResourceAsDefault() {
        var sales = new PostSnapshot(13L, 22L, 33L, "store", "sales", null, false, Set.of());
        readyClone(201, 21, "AVATAR", "SETTLED");
        readyClone(301, 21, "VOICE", "SETTLED");
        var transport = new ContentProviderTransport.Scripted();
        var service = service(transport);
        assertThat(service.setDefault(sales, 201)).containsEntry("isDefault", true).containsEntry("canSetDefault", false);
        assertThat(service.setDefault(sales, 301)).containsEntry("isDefault", true);
        verifyNoInteractions(points);
        assertThat(transport.calls).isEmpty();
    }

    @Test void writeActionsAndFlagsRespectBothOwnersAndTheManagersActualPermission() {
        readyClone(201, 21, "AVATAR", "SETTLED");
        readyClone(202, 21, "VOICE", "SETTLED");
        readyClone(203, 22, "AVATAR", "SETTLED");
        readyClone(204, 23, "VOICE", "SETTLED");
        jdbc.update("UPDATE fr_content_digital_clone SET author_user_id=78 WHERE id=202");
        var sales = new PostSnapshot(13L, 22L, 33L, "store", "sales", null, false, Set.of());
        var engineer = new PostSnapshot(14L, 22L, 33L, "store", "engineer", null, false, Set.of());
        when(directory.roleHasPermission("engineer", ContentPermissions.PERSONA_SELF)).thenReturn(true);
        var transport = new ContentProviderTransport.Scripted();
        var service = service(transport);
        var owned = service.list(sales, 21).stream().filter(row -> row.get("id").equals("201")).findFirst().orElseThrow();
        assertThat(owned).containsEntry("canRename", true).containsEntry("canDelete", true).containsEntry("canSetDefault", true);
        assertThat(service.rename(sales, 201, "  主讲分身  ")).containsEntry("title", "主讲分身");
        assertThatThrownBy(() -> service.rename(sales, 201, "🏠".repeat(11))).hasMessageContaining("20字");
        assertThatThrownBy(() -> service.rename(sales, 201, " ")).hasMessageContaining("名称");
        assertThat(service.list(sales, 23).get(0)).containsEntry("canSetDefault", false).containsEntry("canDelete", true);
        assertThat(service.list(sales, 22).get(0)).containsEntry("canRename", false);
        assertThatThrownBy(() -> service.rename(sales, 203, "跨作者人物")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.delete(sales, 202)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.setDefault(sales, 202)).isInstanceOf(ServiceException.class);
        assertThat(service.list(engineer, 21)).allSatisfy(row -> assertThat(row).containsEntry("canRename", false));
        assertThatThrownBy(() -> service.rename(engineer, 201, "异常岗位")).isInstanceOf(ServiceException.class);
        var foreign = new PostSnapshot(15L, 22L, 34L, "store", "sales-lead", null, false, Set.of());
        assertThatThrownBy(() -> service.delete(foreign, 201)).hasMessageContaining("无权使用");
        assertThat(service.rename(post, 202, "负责人维护同公司资源")).containsEntry("canDelete", true);
        when(directory.roleHasPermission("sales-lead", ContentPermissions.PERSONA)).thenReturn(false);
        when(directory.roleHasPermission("sales-lead", ContentPermissions.PERSONA_SELF)).thenReturn(true);
        assertThat(service.list(post, 21).stream().filter(row -> row.get("id").equals("202")).findFirst().orElseThrow())
                .containsEntry("canRename", false).containsEntry("canDelete", false).containsEntry("canSetDefault", false);
        assertThatThrownBy(() -> service.rename(post, 202, "不能跨作者")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.create(post, 22, "AVATAR", "不能跨人物", 101,
                ContentCloneService.CONSENT_VERSION, "self-only-manager", 5)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(points);
        assertThat(transport.calls).isEmpty();
    }

    @Test void archivedCloneLeavesListsAndSelectionButOriginalCreationRemainsIdempotent() {
        var transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200, "{\"code\":0,\"task_id\":\"archived-avatar\"}", 0),
                ContentProviderTransport.json(200, "{\"code\":0,\"status\":3,\"avatar\":\"history-avatar\"}", 0));
        var service = service(transport);
        long id = Long.parseLong((String) create(service, "AVATAR", "archive-once").get("id"));
        readyClone(301, 21, "VOICE", "SETTLED");
        assertThat(service.refresh(post, id)).containsEntry("canDelete", true).containsEntry("ready", true);
        service.setDefault(post, id);
        service.rename(post, id, "新展示名");
        service.delete(post, id);
        service.delete(post, id);
        assertThat(service.list(post, 21).stream().map(row -> row.get("id"))).containsExactly("301");
        assertThatThrownBy(() -> service.refresh(post, id)).hasMessageContaining("已删除");
        assertThatThrownBy(() -> service.select(post, id, 301)).hasMessageContaining("已删除");
        assertThatThrownBy(() -> service.setDefault(post, id)).hasMessageContaining("已删除");
        assertThatThrownBy(() -> service.rename(post, id, "不再修改")).hasMessageContaining("已删除");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_clone_default WHERE clone_id=?", Long.class, id)).isZero();
        assertThat(jdbc.queryForMap("SELECT provider_resource_id,billing_state,archived_at FROM fr_content_digital_clone WHERE id=?", id))
                .containsEntry("provider_resource_id", "history-avatar").containsEntry("billing_state", "SETTLED").hasEntrySatisfying("archived_at", value -> assertThat(value).isNotNull());
        var replay = create(service, "AVATAR", "archive-once");
        assertThat(replay).containsEntry("id", String.valueOf(id)).containsEntry("title", "新展示名")
                .containsEntry("status", "READY").containsEntry("archived", true).containsEntry("ready", false)
                .containsEntry("demoUrl", "").containsEntry("canRename", false).containsEntry("canDelete", false).containsEntry("canSetDefault", false);
        assertThat(service.replayLegacy(post, 21, "AVATAR", "顾问", 101, ContentCloneService.CONSENT_VERSION, "archive-once", 5))
                .isEqualTo(replay);
        assertThat(transport.calls).hasSize(2);
        verify(points, times(1)).reserveConfirmed(any(), eq(5L));
        verify(points, times(1)).settle(555L, 1);
    }

    @Test void onlyTerminalAndReconciledResourcesCanBeDeleted() {
        readyClone(201, 21, "AVATAR", "PENDING");
        readyClone(202, 21, "AVATAR", "PENDING");
        readyClone(203, 21, "VOICE", "RELEASED");
        readyClone(204, 21, "VOICE", "PENDING");
        jdbc.update("UPDATE fr_content_digital_clone SET status='RUNNING' WHERE id=202");
        jdbc.update("UPDATE fr_content_digital_clone SET status='FAILED' WHERE id IN (203,204)");
        var transport = new ContentProviderTransport.Scripted();
        var service = service(transport);
        for (long id : new long[]{201L, 202L, 204L}) {
            assertThatThrownBy(() -> service.delete(post, id)).hasMessageContaining("积分已结清");
        }
        service.delete(post, 203);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_digital_clone WHERE archived_at IS NOT NULL", Long.class)).isEqualTo(1L);
        verifyNoInteractions(points);
        assertThat(transport.calls).isEmpty();
    }

    @Test void concurrentDeleteAndDefaultNeverLeaveAnArchivedDefault() throws Exception {
        readyClone(201, 21, "AVATAR", "SETTLED");
        var service = service(new ContentProviderTransport.Scripted());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var setting = executor.submit(() -> {
                start.await();
                try {
                    assertThat(service.setDefault(post, 201)).containsEntry("isDefault", true);
                } catch (ServiceException error) {
                    assertThat(error.getMessage()).contains("已删除");
                }
                return null;
            });
            var deleting = executor.submit(() -> { start.await(); service.delete(post, 201); return null; });
            start.countDown();
            setting.get(10, java.util.concurrent.TimeUnit.SECONDS);
            deleting.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(service.list(post, 21)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_clone_default", Long.class)).isZero();
    }

    @Test void multipleResourcesCanCoexistAndEachKindHasOneReplaceableDefaultWithoutCharge() {
        readyClone(201,21,"AVATAR","SETTLED");readyClone(202,21,"AVATAR","SETTLED");readyClone(301,21,"VOICE","SETTLED");
        var transport=new ContentProviderTransport.Scripted();var service=service(transport);
        assertThat(service.setDefault(post,201).get("isDefault")).isEqualTo(true);
        service.setDefault(post,301);service.setDefault(post,202);service.setDefault(post,202);
        var rows=service.list(post,21);
        assertThat(rows).hasSize(3);
        assertThat(rows.stream().filter(row->Boolean.TRUE.equals(row.get("isDefault"))).map(row->row.get("id"))).containsExactlyInAnyOrder("202","301");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_clone_default WHERE company_id=33 AND persona_id=21",Long.class)).isEqualTo(2);
        verifyNoInteractions(points);assertThat(transport.calls).isEmpty();
    }
    @Test void separateCreationCommandsCreateMultipleResourcesForTheSamePersonaAndKind() {
        var transport=new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200,"{\"code\":0,\"task_id\":\"first-avatar\"}",0),
                ContentProviderTransport.json(200,"{\"code\":0,\"task_id\":\"second-avatar\"}",0));
        var service=service(transport);
        var first=create(service,"AVATAR","multiple-first");var second=create(service,"AVATAR","multiple-second");
        assertThat(first.get("id")).isNotEqualTo(second.get("id"));
        assertThat(service.list(post,21)).hasSize(2);assertThat(transport.calls).hasSize(2);
        verify(points,times(2)).reserveConfirmed(any(),eq(5L));
    }
    @Test void defaultsRejectUnsettledDisabledAndForeignResources() {
        readyClone(201,21,"AVATAR","PENDING");readyClone(202,23,"AVATAR","SETTLED");
        var service=service(new ContentProviderTransport.Scripted());
        assertThatThrownBy(()->service.setDefault(post,201)).hasMessageContaining("结算");
        assertThatThrownBy(()->service.setDefault(post,202)).hasMessageContaining("停用");
        var foreign=new PostSnapshot(12L,22L,34L,"store","sales-lead",null,false,Set.of());
        assertThatThrownBy(()->service.setDefault(foreign,201)).hasMessageContaining("无权使用");
        var sales=new PostSnapshot(13L,22L,33L,"store","sales",null,false,Set.of());
        assertThatThrownBy(()->service.setDefault(sales,201)).hasMessageContaining("结算");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_clone_default",Long.class)).isZero();
    }
    @Test void concurrentDefaultChangesKeepExactlyOneDefaultForThePersonaAndKind() throws Exception {
        readyClone(201,21,"AVATAR","SETTLED");readyClone(202,21,"AVATAR","SETTLED");
        var service=service(new ContentProviderTransport.Scripted());
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var first=executor.submit(()->{start.await();return service.setDefault(post,201);});
            var second=executor.submit(()->{start.await();return service.setDefault(post,202);});
            start.countDown();first.get(10,java.util.concurrent.TimeUnit.SECONDS);second.get(10,java.util.concurrent.TimeUnit.SECONDS);
        } finally {executor.shutdownNow();}
        assertThat(service.list(post,21).stream().filter(row->Boolean.TRUE.equals(row.get("isDefault")))).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_digital_clone",Long.class)).isEqualTo(2);
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
