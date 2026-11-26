package cn.iocoder.yudao.module.platform.training;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.operations.OperationsGuard;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static cn.iocoder.yudao.module.platform.training.TrainingModel.*;

@TestPropertySource(properties={"platform.test.extra-flyway-locations=,classpath:db/migration/franchise,classpath:db/migration/platform-wallet,classpath:db/migration/franchise-asset","zhongshu.org-point.headquarters-tenant-id=1"})
@Import({TrainingIntegrationTest.Config.class,OperationsGuard.class,TrainingFiles.class,TrainingCatalog.class,TrainingLearning.class})
@Sql("/fixtures/operations-system-users.sql")
class TrainingIntegrationTest extends PlatformPgIntegrationTest {
    private static final AtomicLong IDS=new AtomicLong(9731000);
    private static final Path STORE=Path.of(System.getProperty("java.io.tmpdir"),"zs-training-"+UUID.randomUUID());
    @DynamicPropertySource static void paths(DynamicPropertyRegistry r){r.add("training.storage-root",STORE::toString);}
    @TestConfiguration static class Config {
        @Bean ObjectMapper json(){return new ObjectMapper();}
        @Bean CurrentPost currentPost(){return Mockito.mock(CurrentPost.class);}
    }
    @Resource JdbcTemplate jdbc;@Resource TrainingFiles files;@Resource TrainingCatalog catalog;@Resource TrainingLearning learning;@Resource CurrentPost posts;
    long admin,student,org,other,post;PostSnapshot current;
    @BeforeEach void setup(){
        jdbc.execute("ALTER TABLE system_role ADD COLUMN IF NOT EXISTS deleted int2 DEFAULT 0");
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_user_role(id bigserial PRIMARY KEY,user_id bigint,role_id bigint,tenant_id bigint,deleted int2 DEFAULT 0)");
        jdbc.update("INSERT INTO pf_org_type(code,domain,name) VALUES('store','test','培训测试门店') ON CONFLICT DO NOTHING");
        admin=IDS.incrementAndGet();student=IDS.incrementAndGet();org=IDS.incrementAndGet();other=IDS.incrementAndGet();post=IDS.incrementAndGet();
        jdbc.update("INSERT INTO system_role(id,tenant_id,name,code,status,deleted) VALUES(9731000,1,'培训测试管理员','super_admin',0,0) ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO pf_post_template(role_code,domain,org_types,lead) VALUES('sales','test','store',false),('finance','test','store',false) ON CONFLICT DO NOTHING");
        for(long id:List.of(admin,student))jdbc.update("INSERT INTO system_users(id,tenant_id,nickname,username,status,deleted) VALUES(?,1,?,?,0,0)",id,"培训测试"+id,"training"+id);
        jdbc.update("INSERT INTO system_user_role(user_id,role_id,tenant_id) VALUES(?,9731000,1)",admin);
        for(long id:List.of(org,other)){jdbc.update("INSERT INTO system_dept(id,name,tenant_id,status,deleted) VALUES(?,?,1,0,0)",id,"培训隔离门店"+id);jdbc.update("INSERT INTO pf_org_unit(dept_id,kind,org_id,org_type) VALUES(?,'ORG',?,'store')",id,id);}
        jdbc.update("INSERT INTO pf_staff_post(id,user_id,org_id,role_code) VALUES(?,?,?,'sales')",post,student,org);
        current=new PostSnapshot(post,student,org,"store","sales",null,false,Set.of());Mockito.when(posts.require()).thenAnswer(call->current);Mockito.when(posts.find()).thenAnswer(call->Optional.of(current));admin();
    }
    @AfterEach void clear(){TenantContextHolder.clear();SecurityContextHolder.clearContext();}
    private void actor(long id,long tenant){TenantContextHolder.setTenantId(tenant);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser().setId(id).setUserType(2).setTenantId(tenant),null,List.of()));}
    private void admin(){actor(admin,1);}private void student(){actor(student,1);}
    private byte[] png()throws Exception{var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(4,4,BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}
    private String upload(byte[] bytes,String name,boolean process)throws Exception{var u=files.begin(new Upload(name,"AI短视频获客",null,name,bytes.length,TrainingFiles.digest(bytes),id()));String uid=u.get("id").toString();for(int n=0;n<((Number)u.get("chunk_count")).intValue();n++){int start=n*TrainingFiles.CHUNK;files.part(uid,n,new ByteArrayInputStream(Arrays.copyOfRange(bytes,start,Math.min(bytes.length,start+TrainingFiles.CHUNK))));}files.finish(uid);String v=u.get("version_id").toString();if(process){jdbc.update("UPDATE pf_training_material_version SET state='PROCESSING' WHERE id=?",v);files.process(v,1);}return v;}
    private Course content(String main,List<Attachment> attachments){return new Course("课程"+id(),"AI短视频获客","培训负责人","实操培训","学会本节操作","",false,List.of(String.valueOf(org)),List.of("sales","boss"),List.of(),"","",List.of(new Lesson("lesson-1","第一节","第一章",main,attachments,90)),List.of());}
    private Map<String,Object> draft(Course c){return catalog.save(null,new Save(null,c));}
    private String publish(Course c){var d=draft(c);String id=d.get("id").toString();d=catalog.act(id,"submit",new Action(((Number)d.get("revision")).longValue(),""));catalog.act(id,"publish",new Action(((Number)d.get("revision")).longValue(),"测试发布"));return id;}
    private String token(Map<String,Object> t){return t.get("path").toString().substring("/training/media/".length());}
    @Test void chunkRetryResumeAndWholeHashAreVerified()throws Exception{
        byte[] bytes=new byte[TrainingFiles.CHUNK+7];Arrays.fill(bytes,(byte)1);var input=new Upload("断点续传","AI短视频获客",null,"large.pdf",bytes.length,TrainingFiles.digest(bytes),id());var u=files.begin(input);String id=u.get("id").toString();
        files.part(id,0,new ByteArrayInputStream(Arrays.copyOfRange(bytes,0,TrainingFiles.CHUNK)));files.part(id,0,new ByteArrayInputStream(Arrays.copyOfRange(bytes,0,TrainingFiles.CHUNK)));assertThat(files.upload(id).get("parts")).isEqualTo(List.of(0));assertThat(files.begin(input).get("id")).isEqualTo(id);
        assertThatThrownBy(()->files.finish(id)).isInstanceOf(ServiceException.class);files.part(id,1,new ByteArrayInputStream(Arrays.copyOfRange(bytes,TrainingFiles.CHUNK,bytes.length)));files.finish(id);assertThat(files.finish(id).get("state")).isEqualTo("COMPLETE");
        assertThat(Files.exists(files.path("uploads/"+id+"/0.part"))).isFalse();
        // Recreate a part left by an interrupted cleanup. The scheduled pass must remove it.
        var stalePart=files.path("uploads/"+id+"/0.part");
        Files.createDirectories(stalePart.getParent());
        Files.write(stalePart,new byte[]{1});
        files.cleanupCompletedParts();
        assertThat(Files.exists(stalePart)).isFalse();
        assertThat(Files.readAllBytes(files.path(files.version(u.get("version_id").toString(),1).get("original_key").toString()))).isEqualTo(bytes);
    }
    @Test void uploadRejectsCrossUserWrongHashSizeAndContent()throws Exception{
        var u=files.begin(new Upload("错误校验","AI短视频获客",null,"sample.pdf",4,"0".repeat(64),id()));String uid=u.get("id").toString();assertThatThrownBy(()->files.part(uid,0,new ByteArrayInputStream(new byte[5]))).isInstanceOf(ServiceException.class);files.part(uid,0,new ByteArrayInputStream(new byte[4]));assertThatThrownBy(()->files.finish(uid)).isInstanceOf(ServiceException.class);student();assertThatThrownBy(()->files.upload(uid)).isInstanceOf(ServiceException.class);
    }
    @Test void processingRejectsForgedImagesAndPermitsRetry()throws Exception{String version=upload("not png".getBytes(),"bad.png",false);jdbc.update("UPDATE pf_training_material_version SET state='PROCESSING' WHERE id=?",version);assertThatThrownBy(()->files.process(version,1)).isInstanceOf(ServiceException.class);jdbc.update("UPDATE pf_training_material_version SET state='FAILED' WHERE id=?",version);files.retry(version);assertThat(files.version(version,1).get("state")).isEqualTo("UPLOADED");}
    @Test void publicationRequiresReadyMaterialsAndExplicitScope()throws Exception{String v=upload(png(),"pending.png",false);var d=draft(content(v,List.of()));String id=d.get("id").toString();assertThat(catalog.issues(id)).isNotEmpty();assertThatThrownBy(()->catalog.act(id,"submit",new Action(0,""))).isInstanceOf(ServiceException.class);assertThatThrownBy(()->catalog.act(id,"publish",new Action(0,""))).isInstanceOf(ServiceException.class);}
    @Test void draftEditingCannotChangePublishedReleaseAndStaleWritesFail()throws Exception{String v=upload(png(),"lesson.png",true);Course c=content(v,List.of());String id=publish(c);var d=catalog.detail(id);Course changed=new Course("新标题",c.category(),c.lecturer(),c.summary(),c.objective(),"",c.allOrganizations(),c.organizationIds(),c.roles(),c.userIds(),"","",c.lessons(),c.attachments());catalog.save(id,new Save(((Number)d.get("revision")).longValue(),changed));assertThatThrownBy(()->catalog.save(id,new Save(((Number)d.get("revision")).longValue(),changed))).isInstanceOf(ServiceException.class);student();assertThat(catalog.learnerDetail(id).get("title")).isEqualTo(c.title());}
    @Test void listDetailsAndDirectMaterialTicketsShareOrganizationAndRoleRules()throws Exception{String v=upload(png(),"scope.png",true);String id=publish(content(v,List.of(new Attachment(v,false))));student();assertThat(catalog.learnerCourses("")).extracting(c->c.get("id")).contains(id);assertThatThrownBy(()->learning.learnerTicket(id,v,"download")).isInstanceOf(ServiceException.class);current=new PostSnapshot(post,student,org,"store","finance",null,false,Set.of());assertThat(catalog.learnerCourses("")).extracting(c->c.get("id")).doesNotContain(id);assertThatThrownBy(()->catalog.learnerDetail(id)).isInstanceOf(ServiceException.class);current=new PostSnapshot(post,student,other,"store","sales",null,false,Set.of());assertThatThrownBy(()->learning.learnerTicket(id,v,"preview")).isInstanceOf(ServiceException.class);}
    @Test void materialVersionUploadNeverChangesExistingReferences()throws Exception{String v=upload(png(),"v1.png",true);String id=publish(content(v,List.of()));var original=files.version(v,1);byte[] bytes=png();var next=files.begin(new Upload("v1.png","AI短视频获客",original.get("material_id").toString(),"v2.png",bytes.length,TrainingFiles.digest(bytes),id()));assertThat(files.version(next.get("version_id").toString(),1).get("version_no")).isEqualTo(2);student();var c=catalog.learnerDetail(id);assertThat(((List<Map<String,Object>>)c.get("lessons"))).hasSize(1);assertThat(learning.begin(id,"lesson-1").get("document")).isEqualTo(true);}
    @Test void signedMediaChecksExpiryAndCurrentPostAndWithdrawal()throws Exception{String v=upload(png(),"ticket.png",true);String id=publish(content(v,List.of()));student();String expired=token(learning.learnerTicket(id,v,"preview"));assertThat(learning.media(expired).mime()).isEqualTo("image/png");jdbc.update("UPDATE pf_training_ticket SET expires_at=now()-interval '1 second'");assertThatThrownBy(()->learning.media(expired)).isInstanceOf(ServiceException.class);String active=token(learning.learnerTicket(id,v,"preview"));admin();var c=catalog.detail(id);catalog.act(id,"withdraw",new Action(((Number)c.get("revision")).longValue(),"测试下架"));assertThatThrownBy(()->learning.media(active)).isInstanceOf(ServiceException.class);}
    @Test void revokedPostInvalidatesIssuedMediaWithoutWaitingForExpiry()throws Exception{String v=upload(png(),"revoked.png",true);String id=publish(content(v,List.of()));student();String t=token(learning.learnerTicket(id,v,"preview"));jdbc.update("UPDATE pf_staff_post SET status='REVOKED',revoked_at=now() WHERE id=?",post);assertThatThrownBy(()->learning.media(t)).isInstanceOf(ServiceException.class);}
    @Test void adminTicketDoesNotSurviveAdminRevocation()throws Exception{String v=upload(png(),"admin.png",true);String t=token(learning.adminTicket(v,"preview"));jdbc.update("UPDATE system_user_role SET deleted=1 WHERE user_id=?",admin);assertThatThrownBy(()->learning.media(t)).isInstanceOf(ServiceException.class);}
    @Test void documentReadingIsSeparateFromDownloadAndRejectsEarlyConfirmation()throws Exception{String v=upload(png(),"read.png",true);String id=publish(content(v,List.of()));student();var session=learning.begin(id,"lesson-1");String play=session.get("playId").toString();assertThatThrownBy(()->learning.heartbeat(new Beat(play,0),true)).isInstanceOf(ServiceException.class);jdbc.update("UPDATE pf_training_play SET created_at=now()-interval '31 seconds' WHERE id=?",play);assertThat(learning.heartbeat(new Beat(play,0),true).get("completed")).isEqualTo(true);assertThat(catalog.learnerDetail(id).get("completed")).isEqualTo(1L);}
    @Test void videoSeekAndDuplicateHeartbeatsDoNotCompleteCourse()throws Exception{String v=upload(png(),"video-test.png",true);jdbc.update("UPDATE pf_training_material_version SET duration=100,media_key=original_key WHERE id=?",v);String id=publish(content(v,List.of()));student();String play=learning.begin(id,"lesson-1").get("playId").toString();jdbc.update("UPDATE pf_training_play SET last_ping=now()-interval '5 seconds' WHERE id=?",play);assertThat(learning.heartbeat(new Beat(play,99),false).get("coveredSeconds")).isEqualTo(0.0);assertThat(learning.heartbeat(new Beat(play,99),false).get("completed")).isEqualTo(false);assertThatThrownBy(()->learning.heartbeat(new Beat(play,0),true)).isInstanceOf(ServiceException.class);}
    @Test void actualWatchingMergesCoverageWithoutDoubleCredit()throws Exception{String v=upload(png(),"coverage.png",true);jdbc.update("UPDATE pf_training_material_version SET duration=10,media_key=original_key WHERE id=?",v);String id=publish(content(v,List.of()));student();String play=learning.begin(id,"lesson-1").get("playId").toString();jdbc.update("UPDATE pf_training_play SET last_ping=now()-interval '5 seconds' WHERE id=?",play);learning.heartbeat(new Beat(play,5),false);jdbc.update("UPDATE pf_training_play SET last_ping=now()-interval '5 seconds' WHERE id=?",play);var result=learning.heartbeat(new Beat(play,10),false);assertThat(result.get("coveredSeconds")).isEqualTo(10.0);assertThat(result.get("completed")).isEqualTo(true);assertThat(TrainingLearning.merge(List.of(new double[]{0,5}),new double[]{3,8})).singleElement().satisfies(x->assertThat(x).containsExactly(0,8));}
    @Test void changingLessonVersionDoesNotReuseOldCompletion()throws Exception{String v=upload(png(),"old.png",true),v2=upload(png(),"new.png",true);Course c=content(v,List.of());String id=publish(c);student();String play=learning.begin(id,"lesson-1").get("playId").toString();jdbc.update("UPDATE pf_training_play SET created_at=now()-interval '31 seconds' WHERE id=?",play);learning.heartbeat(new Beat(play,0),true);admin();var d=catalog.detail(id);Course newer=new Course(c.title(),c.category(),c.lecturer(),c.summary(),c.objective(),"",false,c.organizationIds(),c.roles(),List.of(),"","",List.of(new Lesson("lesson-1","第一节","第一章",v2,List.of(),90)),List.of());d=catalog.save(id,new Save(((Number)d.get("revision")).longValue(),newer));d=catalog.act(id,"submit",new Action(((Number)d.get("revision")).longValue(),""));catalog.act(id,"publish",new Action(((Number)d.get("revision")).longValue(),""));student();assertThat(catalog.learnerDetail(id).get("completed")).isEqualTo(0L);}
    @Test void crossTenantAdminAndUnknownRoleFailClosed()throws Exception{String v=upload(png(),"tenant.png",true);String id=publish(content(v,List.of()));actor(admin,2);assertThatThrownBy(()->catalog.detail(id)).isInstanceOf(ServiceException.class);student();current=new PostSnapshot(post,student,org,"store","unknown",null,false,Set.of());assertThat(catalog.learnerCourses("")).isEmpty();assertThatThrownBy(()->learning.begin(id,"lesson-1")).isInstanceOf(ServiceException.class);}
    @Test void cloudWriteMustSucceedBeforeMaterialsBecomeReady()throws Exception{
        var cloud=Mockito.mock(TrainingCloudStorage.class);ReflectionTestUtils.setField(files,"cloud",cloud);
        try{String v=upload(png(),"cloud.png",false);jdbc.update("UPDATE pf_training_material_version SET state='PROCESSING' WHERE id=?",v);Mockito.doThrow(new ServiceException(503,"课程云存储写入失败")).when(cloud).store(Mockito.anyString(),Mockito.any());assertThatThrownBy(()->files.process(v,1)).isInstanceOf(ServiceException.class);assertThat(files.version(v,1).get("state")).isEqualTo("PROCESSING");Mockito.reset(cloud);files.process(v,1);assertThat(files.version(v,1).get("state")).isEqualTo("READY");Mockito.verify(cloud).store(Mockito.startsWith("versions/"),Mockito.any());}
        finally{ReflectionTestUtils.setField(files,"cloud",null);}
    }
    @Test void editingMaterialPreservesFilesAndRejectsStaleRevision()throws Exception{
        String v=upload(png(),"edit.png",true);var before=files.version(v,1);String m=before.get("material_id").toString();byte[] bytes=Files.readAllBytes(files.path(before.get("original_key").toString()));
        files.editMaterial(m,new MaterialEdit(0L,"  编辑后的资料  ","经营管理"));var after=files.version(v,1);
        assertThat(after.get("name")).isEqualTo("编辑后的资料");assertThat(after.get("category")).isEqualTo("经营管理");assertThat(after.get("material_revision")).isEqualTo(1L);assertThat(Files.readAllBytes(files.path(after.get("original_key").toString()))).isEqualTo(bytes);
        assertThatThrownBy(()->files.editMaterial(m,new MaterialEdit(0L,"覆盖","经营管理"))).isInstanceOf(ServiceException.class);assertThatThrownBy(()->files.deleteMaterial(m,new MaterialDelete(0L))).isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_training_audit WHERE object_id=? AND action='MATERIAL_EDIT'",Long.class,m)).isEqualTo(1L);
    }
    @Test void newVersionInvalidatesOpenEditorAndDeleteConfirmation()throws Exception{
        byte[] bytes=png();String v=upload(bytes,"inventory.png",true),m=files.version(v,1).get("material_id").toString();
        files.begin(new Upload("资料新版本","经营管理",m,"inventory-v2.png",bytes.length,TrainingFiles.digest(bytes),id()));
        assertThat(files.version(v,1).get("material_revision")).isEqualTo(1L);
        assertThatThrownBy(()->files.editMaterial(m,new MaterialEdit(0L,"过期编辑","经营管理"))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->files.deleteMaterial(m,new MaterialDelete(0L))).isInstanceOf(ServiceException.class);
        files.deleteMaterial(m,new MaterialDelete(1L));assertThatThrownBy(()->files.version(v,1)).isInstanceOf(ServiceException.class);
    }
    @Test void deletingUnusedMaterialRevokesTicketsAndPreventsReuse()throws Exception{
        String v=upload(png(),"delete.png",true);var before=files.version(v,1);String m=before.get("material_id").toString();String token=learning.adminTicket(v,"preview").get("path").toString().replace("/training/media/","");
        files.deleteMaterial(m,new MaterialDelete(0L));assertThatThrownBy(()->files.version(v,1)).isInstanceOf(ServiceException.class);assertThatThrownBy(()->learning.media(token)).isInstanceOf(ServiceException.class);
        assertThat(((List<Map<String,Object>>)files.materials("delete.png","",1).get("list"))).noneMatch(r->v.equals(r.get("version_id")));
        assertThatThrownBy(()->files.begin(new Upload("新版本","经营管理",m,"new.png",png().length,TrainingFiles.digest(png()),id()))).isInstanceOf(ServiceException.class);
        assertThat(Files.isRegularFile(files.path(before.get("original_key").toString()))).isTrue();assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_training_audit WHERE object_id=? AND action='MATERIAL_DELETE'",Long.class,m)).isEqualTo(1L);
    }
    @Test void removalProtectsDraftAndCurrentReleaseAcrossAllVersions()throws Exception{
        String old=upload(png(),"published.png",true),replacement=upload(png(),"replacement.png",true);String m=files.version(old,1).get("material_id").toString();String course=publish(content(old,List.of()));
        var saved=catalog.save(course,new Save(((Number)catalog.detail(course).get("revision")).longValue(),content(replacement,List.of())));
        assertThat(files.materialReferences(m,1)).anyMatch(r->"PUBLISHED".equals(r.get("reference_type")));assertThatThrownBy(()->files.deleteMaterial(m,new MaterialDelete(0L))).isInstanceOf(ServiceException.class);
        var submitted=catalog.act(course,"submit",new Action(((Number)saved.get("revision")).longValue(),""));catalog.act(course,"publish",new Action(((Number)submitted.get("revision")).longValue(),""));
        assertThat(files.materialReferences(m,1)).isEmpty();files.deleteMaterial(m,new MaterialDelete(0L));student();assertThat(catalog.learnerDetail(course)).isNotEmpty();
        admin();String draftVersion=upload(png(),"draft-ref.png",true);String draftMaterial=files.version(draftVersion,1).get("material_id").toString();draft(content(draftVersion,List.of()));assertThatThrownBy(()->files.deleteMaterial(draftMaterial,new MaterialDelete(0L))).isInstanceOf(ServiceException.class);
    }
    @Test void deletingIncompleteUploadStopsResumeAndProcessing()throws Exception{
        byte[] bytes=png();var u=files.begin(new Upload("取消上传","经营管理",null,"cancel.png",bytes.length,TrainingFiles.digest(bytes),id()));String m=u.get("material_id").toString(),uid=u.get("id").toString();files.deleteMaterial(m,new MaterialDelete(0L));
        assertThatThrownBy(()->files.upload(uid)).isInstanceOf(ServiceException.class);assertThatThrownBy(()->files.part(uid,0,new ByteArrayInputStream(bytes))).isInstanceOf(ServiceException.class);assertThatThrownBy(()->files.finish(uid)).isInstanceOf(ServiceException.class);
        String v=upload(bytes,"pending-delete.png",false);files.deleteMaterial(files.version(v,1).get("material_id").toString(),new MaterialDelete(0L));assertThatThrownBy(()->files.process(v,1)).isInstanceOf(ServiceException.class);
    }
    @Test void materialMutationsEnforceValidationTenantRoleAndProcessingGuards()throws Exception{
        String v=upload(png(),"guards.png",true),m=files.version(v,1).get("material_id").toString();assertThatThrownBy(()->files.editMaterial(m,new MaterialEdit(0L," ","经营管理"))).isInstanceOf(ServiceException.class);assertThatThrownBy(()->files.editMaterial(m,new MaterialEdit(0L,"名称",null))).isInstanceOf(ServiceException.class);
        jdbc.update("UPDATE pf_training_material_version SET state='PROCESSING' WHERE id=?",v);assertThatThrownBy(()->files.deleteMaterial(m,new MaterialDelete(0L))).isInstanceOf(ServiceException.class);
        student();assertThatThrownBy(()->files.editMaterial(m,new MaterialEdit(0L,"越权","经营管理"))).isInstanceOf(ServiceException.class);assertThatThrownBy(()->files.deleteMaterial(m,new MaterialDelete(0L))).isInstanceOf(ServiceException.class);
        actor(admin,2);assertThatThrownBy(()->files.deleteMaterial(m,new MaterialDelete(0L))).isInstanceOf(ServiceException.class);admin();assertThat(files.version(v,1).get("material_revision")).isEqualTo(0L);
    }
    @Test void creatingCourseAndRemovingItsMaterialAreSerialized()throws Exception{
        String v=upload(png(),"concurrent.png",true),m=files.version(v,1).get("material_id").toString();var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var deleting=new java.util.concurrent.CountDownLatch(1);
        try{
            var creation=pool.submit(()->{admin();try{new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()))).executeWithoutResult(status->{files.lockCatalog(1);held.countDown();try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Test coordination timed out");}catch(InterruptedException e){throw new RuntimeException(e);}draft(content(v,List.of()));});}finally{clear();}});
            assertThat(held.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();var removal=pool.submit(()->{admin();deleting.countDown();try{files.deleteMaterial(m,new MaterialDelete(0L));}finally{clear();}});
            assertThat(deleting.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();Thread.sleep(150);assertThat(removal.isDone()).isFalse();release.countDown();creation.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThatThrownBy(()->removal.get(10,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(ServiceException.class);
        }finally{release.countDown();pool.shutdownNow();}admin();assertThat(files.version(v,1)).isNotEmpty();
    }


    @Test void categoryRenamePreservesPublishedFilesAndInvalidatesStaleMaterialEdits()throws Exception{
        var c=files.createMaterialCategory(new CategoryEdit(null,"分类"+id()));String key=c.get("id").toString(),name=c.get("name").toString();String v=upload(png(),"category-rename.png",true);var original=files.version(v,1);String m=original.get("material_id").toString();
        files.editMaterial(m,new MaterialEdit(0L,"分类文件",name));String course=publish(content(v,List.of()));var renamed=files.renameMaterialCategory(key,new CategoryEdit(0L,name+"新"));
        assertThat(renamed.get("materialCount")).isEqualTo(1);assertThat(files.version(v,1).get("category")).isEqualTo(name+"新");assertThat(files.version(v,1).get("material_revision")).isEqualTo(2L);
        assertThatThrownBy(()->files.editMaterial(m,new MaterialEdit(1L,"旧窗口",name+"新"))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->files.renameMaterialCategory(key,new CategoryEdit(0L,"过期"))).isInstanceOf(ServiceException.class);
        student();assertThat(learning.media(token(learning.learnerTicket(course,v,"preview"))).mime()).isEqualTo("image/png");assertThat(Files.readAllBytes(files.path(original.get("original_key").toString()))).isEqualTo(png());
    }
    @Test void deletingCategoryMovesMaterialsAndAllVersionsWithoutBreakingPublishedCourse()throws Exception{
        var c=files.createMaterialCategory(new CategoryEdit(null,"待删分类"+id()));String key=c.get("id").toString(),name=c.get("name").toString();String v=upload(png(),"category-remove.png",true);String m=files.version(v,1).get("material_id").toString();files.editMaterial(m,new MaterialEdit(0L,"保留文件",name));
        files.begin(new Upload("保留文件",name,m,"next.png",png().length,TrainingFiles.digest(png()),id()));String course=publish(content(v,List.of()));
        assertThatThrownBy(()->files.deleteMaterialCategory(key,new CategoryDelete(0L,0L))).isInstanceOf(ServiceException.class);
        var deleted=files.deleteMaterialCategory(key,new CategoryDelete(0L,1L));assertThat(deleted.get("movedMaterials")).isEqualTo(1L);assertThat(files.version(v,1).get("category")).isEqualTo("未分类");
        assertThat(files.materialCategories()).noneMatch(row->key.equals(row.get("id")));assertThat(files.materialCategories()).noneMatch(row->name.equals(row.get("name")));
        assertThatThrownBy(()->files.begin(new Upload("旧分类",name,null,"a.csv",1,"0".repeat(64),id()))).isInstanceOf(ServiceException.class);
        student();assertThat(learning.media(token(learning.learnerTicket(course,v,"preview"))).mime()).isEqualTo("image/png");
    }
    @Test void categoryValidationReservedNamesAndAuthorization(){
        String name="Case"+id();var c=files.createMaterialCategory(new CategoryEdit(null,name));String key=c.get("id").toString();
        assertThatThrownBy(()->files.createMaterialCategory(new CategoryEdit(null,name.toLowerCase(Locale.ROOT)))).isInstanceOf(ServiceException.class);
        for(String bad:Arrays.asList("","  ","全部资料","a\nb","x".repeat(41)))assertThatThrownBy(()->files.createMaterialCategory(new CategoryEdit(null,bad))).isInstanceOf(ServiceException.class);
        var reserved=files.materialCategories().stream().filter(row->Boolean.TRUE.equals(row.get("system_default"))).findFirst().orElseThrow();String fallback=reserved.get("id").toString();
        assertThatThrownBy(()->files.renameMaterialCategory(fallback,new CategoryEdit(0L,"改名"))).isInstanceOf(ServiceException.class);assertThatThrownBy(()->files.deleteMaterialCategory(fallback,new CategoryDelete(0L,0L))).isInstanceOf(ServiceException.class);
        student();assertThatThrownBy(()->files.materialCategories()).isInstanceOf(ServiceException.class);assertThatThrownBy(()->files.renameMaterialCategory(key,new CategoryEdit(0L,"员工改名"))).isInstanceOf(ServiceException.class);
        actor(admin,2);assertThatThrownBy(()->files.deleteMaterialCategory(key,new CategoryDelete(0L,0L))).isInstanceOf(ServiceException.class);
    }
    @Test void categoryDeleteRechecksNewUploadsAndAuditsMoves(){
        String name="并发分类"+id();var c=files.createMaterialCategory(new CategoryEdit(null,name));String key=c.get("id").toString();files.begin(new Upload("刚上传",name,null,"a.csv",1,"0".repeat(64),id()));
        assertThatThrownBy(()->files.deleteMaterialCategory(key,new CategoryDelete(0L,0L))).isInstanceOf(ServiceException.class);
        files.deleteMaterialCategory(key,new CategoryDelete(0L,1L));assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_training_audit WHERE object_id=? AND action IN ('CATEGORY_CREATE','CATEGORY_DELETE')",Long.class,key)).isEqualTo(2L);
        assertThat(files.createMaterialCategory(new CategoryEdit(null,name)).get("id")).isNotEqualTo(key);
    }
}
