package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.api.*;
import cn.iocoder.yudao.module.franchise.content.ContentPermissions;
import cn.iocoder.yudao.module.franchise.content.dal.*;
import cn.iocoder.yudao.module.franchise.content.provider.*;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 PG/MyBatis/JDK HTTP；供应商仅为本机官网协议桩，绝不访问飞影或收费。 */
@Import(ContentTaskStore.class)
class ContentDigitalFlowPgIntegrationTest extends FranchisePgIntegrationTest {
    @Resource DataSource dataSource;
    @Resource PlatformTransactionManager transactionManager;
    @Resource FrContentPersonaMapper personaMapper;
    @Resource FrContentDraftMapper draftMapper;
    @Resource ContentTaskStore store;
    final ObjectMapper json=new ObjectMapper();

    @Test void workTitleDefaultsToTheTaskThemeAndHistoricalBlankTitlesOnlyReadTheirOwnTasks() {
        var jdbc = new JdbcTemplate(dataSource);
        long companyId = 73L;
        long authorId = 22L;
        var tasks = mock(ContentTaskService.class);
        when(tasks.requireOwned(eq(companyId), anyLong()))
                .thenAnswer(invocation -> store.byId(companyId, invocation.getArgument(1)));
        var drafts = new ContentDraftService(draftMapper, null, null, tasks, jdbc);
        store.insert(901L, companyId, authorId, "digital", "{\"text\":\"围合庭院保留自然通风\"}", null);
        store.insert(902L, companyId, authorId, "ai_video", "{\"description\":\"傍晚的暖色窗光\"}", null);
        store.insert(903L, companyId, authorId, "copy", "{\"sourceText\":\"地基如何设计\"}", null);
        store.insert(904L, companyId, 79L, "digital", "{\"text\":\"同组织其他账号的稿件\"}", null);
        store.insert(905L, companyId + 1, authorId, "digital", "{\"text\":\"另一组织的稿件\"}", null);
        jdbc.update("UPDATE fr_content_task SET status='SUCCEEDED' WHERE id IN (901,902)");

        var automatic = drafts.saveWork(companyId, authorId, 901L, null);
        assertThat(automatic.getTitle()).isEqualTo("数字人口播｜围合庭院保留自然通风");
        var explicit = drafts.saveWork(companyId, authorId, 902L, "我自定的作品名称");
        assertThat(drafts.saveWork(companyId, authorId, 902L, "重试标题").getTitle()).isEqualTo("我自定的作品名称");
        assertThat(explicit.getTitle()).isEqualTo("我自定的作品名称");

        jdbc.update("""
                INSERT INTO fr_content_draft(id,company_id,author_user_id,task_id,kind,title)
                VALUES (1903,73,22,903,'remix',' '), (1904,73,22,904,'digital',''), (1905,73,22,905,'digital','')
                """);
        var works = drafts.listWorks(companyId, authorId);
        assertThat(works).extracting(FrContentDraftDO::getTitle).containsExactlyInAnyOrder(
                "数字人口播｜围合庭院保留自然通风", "我自定的作品名称", "文案改写｜地基如何设计",
                "数字人口播｜未命名创作", "数字人口播｜未命名创作");
        assertThat(jdbc.queryForObject("SELECT title FROM fr_content_draft WHERE id=1903", String.class)).isEqualTo(" ");
    }

    @Test void historicalTypeDefaultsShowOwnedTaskThemesWithoutChangingStoredNames() {
        var jdbc = new JdbcTemplate(dataSource);
        var drafts = new ContentDraftService(draftMapper, null, null, null, jdbc);
        store.insert(911L, 74L, 22L, "digital", "{\"text\":\"人字屋顶的采光\"}", null);
        store.insert(912L, 74L, 22L, "ai_video", "{\"description\":\"山间小屋的日落\"}", null);
        store.insert(913L, 74L, 22L, "copy", "{\"sourceText\":\"地下室如何通风\"}", null);
        store.insert(914L, 74L, 79L, "digital", "{\"text\":\"同组织其他作者的正文\"}", null);
        store.insert(915L, 75L, 22L, "digital", "{\"text\":\"其他组织的正文\"}", null);
        store.insert(916L, 74L, 22L, "digital", "{}", null);
        store.insert(917L, 74L, 22L, "ai_video", "{\"description\":\"用于检查类型匹配\"}", null);
        store.insert(918L, 74L, 22L, "copy", "{\"sourceText\":\"用于检查精确匹配\"}", null);
        jdbc.update("""
                INSERT INTO fr_content_draft(id,company_id,author_user_id,task_id,kind,title) VALUES
                (1911,74,22,911,'digital','数字人口播作品'), (1912,74,22,912,'ai_video','AI 视频作品'),
                (1913,74,22,913,'remix','口播文案作品'), (1914,74,22,914,'digital','数字人口播作品'),
                (1915,74,22,915,'digital','数字人口播作品'), (1916,74,22,916,'digital','数字人口播作品'),
                (1917,74,22,917,'ai_video','数字人口播作品'), (1918,74,22,918,'remix','口播文案作品合集')
                """);

        assertThat(drafts.listWorks(74L, 22L)).extracting(FrContentDraftDO::getId, FrContentDraftDO::getTitle)
                .containsExactlyInAnyOrder(tuple(1911L, "数字人口播｜人字屋顶的采光"), tuple(1912L, "AI视频｜山间小屋的日落"),
                        tuple(1913L, "文案改写｜地下室如何通风"), tuple(1914L, "数字人口播作品"),
                        tuple(1915L, "数字人口播作品"), tuple(1916L, "数字人口播作品"),
                        tuple(1917L, "数字人口播作品"), tuple(1918L, "口播文案作品合集"));
        assertThat(jdbc.queryForList("SELECT title FROM fr_content_draft WHERE id BETWEEN 1911 AND 1913 ORDER BY id", String.class))
                .containsExactly("数字人口播作品", "AI 视频作品", "口播文案作品");
    }

    @Test void ipToTwoClonesToDigitalVideoAndOneSavedWorkOverActualHttp() throws Exception {
        JdbcTemplate jdbc=new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE fr_content_persona, fr_content_digital_clone, fr_content_task, fr_content_draft,fr_content_hifly_upload");
        var personas=new ContentPersonaService(personaMapper);
        var person=personas.create(33,22,Map.of("name","张工","who","乡墅设计师","audienceNeeds","预算和采光","expressionStyle","专业、通俗","forbiddenClaims","保证零增项"));
        var post=new PostSnapshot(11L,22L,33L,"store","sales-lead",null,false,Set.of());
        AssetAccessPort assets=mock(AssetAccessPort.class);OrgPointPort points=mock(OrgPointPort.class);
        when(points.reserveConfirmed(any(),eq(5L))).thenReturn(555L);
        when(points.settleByBizKey(anyString(),anyLong())).thenReturn(new OrgPointPort.Settlement(777,5,0,false));
        List<String> endpoints=Collections.synchronizedList(new ArrayList<>());
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String endpoint=exchange.getRequestURI().getPath();endpoints.add(endpoint);
            String response;
            if(endpoint.endsWith("avatar/create_by_video")||endpoint.endsWith("voice/create")) {
                var p=json.readTree(exchange.getRequestBody().readAllBytes());
                if(!p.path("file_id").asText().startsWith("file-")||p.has("video_url")||p.has("audio_url")){exchange.sendResponseHeaders(400,-1);exchange.close();return;}
            }
            if(endpoint.endsWith("avatar/create_by_video"))response="{\"code\":0,\"task_id\":\"avatar-task\"}";
            else if(endpoint.endsWith("avatar/task"))response="{\"code\":0,\"status\":3,\"avatar\":\"resolved-avatar\"}";
            else if(endpoint.endsWith("voice/create"))response="{\"code\":0,\"task_id\":\"voice-task\"}";
            else if(endpoint.endsWith("voice/task"))response="{\"code\":0,\"status\":3,\"voice\":\"resolved-voice\",\"demo_url\":\"https://fixture.example/demo.mp3\"}";
            else if(endpoint.endsWith("video/create_by_tts")){
                var payload=json.readTree(exchange.getRequestBody().readAllBytes());
                if(!payload.path("avatar").asText().equals("resolved-avatar")||!payload.path("voice").asText().equals("resolved-voice")||payload.path("aigc_flag").asInt()!=1){exchange.sendResponseHeaders(400,-1);exchange.close();return;}
                response="{\"code\":0,\"task_id\":\"video-task\"}";
            }else if(endpoint.endsWith("video/task"))response="{\"code\":0,\"status\":3,\"video_Url\":\"https://fixture.example/completed.mp4\",\"duration\":30}";
            else {exchange.sendResponseHeaders(404,-1);exchange.close();return;}
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var config=new ContentProviderProperties();config.getHifly().setApiKey("isolated-http-test-key");config.getHifly().setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
            var transport=new ContentProviderTransport.Jdk();
            for(long id:new long[]{101,102})jdbc.update("INSERT INTO fr_content_hifly_upload(id,company_id,persona_id,author_user_id,author_post_id,kind,provider_file_id,mime_type,size_bytes,declared_sha256,status,expires_at) VALUES(?,33,?,22,11,?,?,?,100,?,'UPLOADED',now()+interval '1 hour')",id,person.getId(),id==101?"AVATAR":"VOICE","file-"+id,id==101?"video/mp4":"audio/mpeg","a".repeat(64));
            var uploads=new ContentCloneUploadService(jdbc,personas,config,transport,null);
            var directory = mock(SystemDirectory.class);
            when(directory.roleHasPermission(post.roleCode(), ContentPermissions.PERSONA)).thenReturn(true);
            var clones=new ContentCloneService(jdbc,personas,uploads,points,config,transport,null,transactionManager,directory);
            long avatar=Long.parseLong((String)clones.create(post,person.getId(),"AVATAR","张工分身",101,ContentCloneService.CONSENT_VERSION,"avatar-once",5).get("id"));
            assertThat(clones.refresh(post,avatar).get("ready")).isEqualTo(true);
            long voice=Long.parseLong((String)clones.create(post,person.getId(),"VOICE","张工声音",102,ContentCloneService.CONSENT_VERSION,"voice-once",5).get("id"));
            assertThat(clones.refresh(post,voice).get("demoUrl")).isEqualTo("https://fixture.example/demo.mp3");
            FranchiseCommands commands=mock(FranchiseCommands.class);when(commands.replayed(any(),anyString(),any())).thenReturn(Optional.empty());
            var tasks=new ContentTaskService(store,null,null,null,null,new HiflyOralClient(config.getHifly(),transport,null),null,assets,commands,config,points);
            ReflectionTestUtils.setField(tasks,"clones",clones);
            var task=tasks.createFromPost(post,Map.of("kind","digital","avatarCloneId",String.valueOf(avatar),"voiceCloneId",String.valueOf(voice),"title","张工口播","text","先把预算和采光想清楚。"),"digital-once");
            tasks.submitOne(task);var running=tasks.requireOwned(33,task.getId());assertThat(running.getStatus()).isEqualTo("RUNNING");
            tasks.pollOne(running);var completed=tasks.requireOwned(33,task.getId());
            assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");assertThat(completed.getResultUrl()).isEqualTo("https://fixture.example/completed.mp4");
            var drafts=new ContentDraftService(draftMapper,null,null,tasks,jdbc);
            assertThatThrownBy(() -> drafts.saveWork(33, 23, task.getId(), "同事保存"))
                    .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
            var work=drafts.saveWork(33,22,task.getId(),"张工口播");assertThat(drafts.saveWork(33,22,task.getId(),"重复保存").getId()).isEqualTo(work.getId());
            assertThat(work.getConfiguration()).contains("completed.mp4");
            drafts.setTrashed(33, 22, work.getId(), true, 0);
            assertThatThrownBy(() -> drafts.saveWork(33, 22, task.getId(), "迟到的重试"))
                    .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class)
                    .hasMessageContaining("回收站");
            drafts.setTrashed(33, 22, work.getId(), false, 1);
            assertThat(drafts.saveWork(33, 22, task.getId(), "再次保存").getId()).isEqualTo(work.getId());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_draft WHERE task_id=?",Long.class,task.getId())).isEqualTo(1);
            verifyNoInteractions(assets);assertThat(endpoints).hasSize(6);verify(points,times(2)).settle(555,1);
            verify(points).settleByBizKey(anyString(),eq(task.getPoolReservedUnits())); // 交付量超过预扣时不追加扣费。
            assertThat(clones.list(post,person.getId()).toString()).doesNotContain("resolved-avatar","resolved-voice");
        } finally {server.stop(0);}
    }
}
