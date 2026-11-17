package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Import({ContentPersonaService.class,ContentPersonaDraftService.class})
class ContentPersonaDraftPgIntegrationTest extends FranchisePgIntegrationTest {
    @Resource ContentPersonaDraftService drafts;
    @Resource ContentPersonaService personas;
    @Resource JdbcTemplate jdbc;
    PostSnapshot post(long user,long company,String role){return new PostSnapshot(user,user,company,"FRANCHISE",role,null,false,Set.of());}
    PostSnapshot me(){return post(77,33,"sales");}
    @BeforeEach void clean(){jdbc.execute("TRUNCATE fr_content_persona_draft,fr_content_persona");}
    @Test void incompleteRawFieldsRoundTripWithoutPublishingAPerson(){
        assertThat(drafts.get(me(),"new").exists()).isFalse();
        var form=new LinkedHashMap<String,Object>(Map.of("name","","who"," 乡墅顾问 ","sell","设计、施工", "audienceNeeds","预算\n采光","sampleScript","", "enabled",true));
        var saved=drafts.save(me(),"new",0,form);
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(drafts.get(me(),"new").form()).isEqualTo(form);
        assertThat(personas.list(33)).isEmpty();
        assertThatThrownBy(()->drafts.publish(me(),"new",1)).isInstanceOf(RuntimeException.class);
        assertThat(drafts.get(me(),"new").form()).isEqualTo(form);
    }
    @Test void draftOwnershipAndCompanyBoundariesApplyToReadsWritesAndPublication(){
        drafts.save(me(),"new",0,Map.of("name","本人"));
        for(var other:List.of(post(88,33,"sales"),post(77,34,"sales"),post(88,33,"boss"))){
            assertThat(drafts.get(other,"new").exists()).isFalse();
            assertThatThrownBy(()->drafts.publish(other,"new",1)).isInstanceOf(RuntimeException.class);
        }
        var colleague=personas.create(post(88,33,"sales"),Map.of("name","同事"));
        String key=colleague.getId().toString();
        assertThatThrownBy(()->drafts.get(me(),key)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->drafts.save(me(),key,0,Map.of("name","越权"))).isInstanceOf(RuntimeException.class);
        assertThat(drafts.save(post(88,33,"sales"),key,0,Map.of("name","同事本人"),0L,false)).isNotNull();
        assertThat(drafts.get(post(99,33,"boss"),key).exists()).isFalse();
        for(String role:List.of("finance","engineering","guest"))
            assertThatThrownBy(()->drafts.save(post(77,33,role),"new",0,Map.of())).isInstanceOf(RuntimeException.class);
    }
    @Test void validationRejectsOversizedUnknownAndNonStringFieldsWithoutMutatingDraft(){
        drafts.save(me(),"new",0,Map.of("name","张工"));
        for(var bad:List.of(Map.of("name","x".repeat(65)),Map.of("sampleScript","x".repeat(2001)),
                Map.of("companyId",34),Map.of("audience",List.of("建房")),Map.of("enabled","false")))
            assertThatThrownBy(()->drafts.save(me(),"new",1,new LinkedHashMap<>(bad))).isInstanceOf(RuntimeException.class);
        for(String key:List.of("0","-1","new/other","9223372036854775808"))
            assertThatThrownBy(()->drafts.get(me(),key)).isInstanceOf(RuntimeException.class);
        assertThat(drafts.get(me(),"new").revision()).isEqualTo(1);
        assertThat(drafts.get(me(),"new").form()).containsEntry("name","张工");
    }
    @Test void publishIsAtomicIdempotentAndTombstoneRejectsOldAutosaves(){
        var form=Map.<String,Object>of("name","张工","audienceNeeds","预算与采光","enabled",true);
        drafts.save(me(),"new",0,form);
        var person=drafts.publish(me(),"new",1);
        assertThat(drafts.publish(me(),"new",1).getId()).isEqualTo(person.getId());
        assertThat(personas.list(33)).hasSize(1);
        var tombstone=drafts.get(me(),"new");
        assertThat(tombstone.exists()).isFalse();assertThat(tombstone.revision()).isEqualTo(2);
        assertThatThrownBy(()->drafts.save(me(),"new",1,form)).hasMessageContaining("另一处更新");
        assertThatThrownBy(()->drafts.save(me(),"new",0,form)).hasMessageContaining("另一处更新");
        drafts.save(me(),"new",2,Map.of("name","下一个IP"));
        assertThatThrownBy(()->drafts.publish(me(),"new",1)).hasMessageContaining("另一处更新");
    }
    @Test void confirmingAnEditUpdatesTheSamePersonAndAllowsClearingSupplement(){
        var person=personas.create(me(),Map.of("name","张工","who","乡墅顾问","sampleScript","旧口播"));
        String key=person.getId().toString();
        drafts.save(me(),key,0,Map.of("name","张工","who","","sampleScript",""),0L,false);
        assertThat(personas.list(33).get(0).getSampleScript()).isEqualTo("旧口播");
        var saved=drafts.publish(me(),key,1);
        assertThat(saved.getId()).isEqualTo(person.getId());
        assertThat(personas.list(33)).hasSize(1);
        assertThat(personas.list(33).get(0).getWho()).isNull();
        assertThat(personas.list(33).get(0).getSampleScript()).isNull();
    }
    @Test void simultaneousWritersCannotSilentlyOverwriteEachOther() throws Exception {
        drafts.save(me(),"new",0,Map.of("name","初稿"));
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var results=new ArrayList<Future<Boolean>>();
            for(String name:List.of("手机A","手机B"))results.add(pool.submit(()->{
                start.await();try{drafts.save(me(),"new",1,Map.of("name",name));return true;}
                catch(cn.iocoder.yudao.framework.common.exception.ServiceException conflict){return false;}
            }));
            start.countDown();int accepted=0;for(var result:results)if(result.get(10,TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isEqualTo(1);assertThat(drafts.get(me(),"new").revision()).isEqualTo(2);
        }finally{pool.shutdownNow();}
    }
    @Test void twoEditorsCannotPublishOverAnotherOfficialVersionAndDraftSurvives() {
        var person=personas.create(me(),Map.of("name","original"));String key=person.getId().toString();
        var boss=post(99,33,"boss");
        drafts.save(me(),key,0,Map.of("name","first"),0L,false);
        drafts.save(boss,key,0,Map.of("name","second"),0L,false);
        assertThat(drafts.publish(me(),key,1).getVersion()).isEqualTo(1);
        assertThatThrownBy(()->drafts.publish(boss,key,1)).hasMessageContaining("正式人物已更新");
        assertThat(drafts.get(boss,key).form()).containsEntry("name","second");
        assertThat(personas.requireWritable(boss,person.getId()).getName()).isEqualTo("first");
        drafts.save(boss,key,1,Map.of("name","second edited"),0L,false);
        assertThatThrownBy(()->drafts.publish(boss,key,2)).hasMessageContaining("正式人物已更新");
        drafts.save(boss,key,2,Map.of("name","confirmed"),1L,true);
        assertThat(drafts.publish(boss,key,3).getName()).isEqualTo("confirmed");
    }
    @Test void baselineComesFromReadAndLegacyDraftCannotBeBlindlyPublished() {
        var person=personas.create(me(),Map.of("name","original"));String key=person.getId().toString();
        var read=drafts.get(me(),key);assertThat(read.personaVersion()).isZero();
        personas.update(me(),person.getId(),Map.of("name","latest"));
        drafts.save(me(),key,0,Map.of("name","old edit"),read.personaVersion(),false);
        assertThatThrownBy(()->drafts.publish(me(),key,1)).hasMessageContaining("正式人物已更新");
        jdbc.update("UPDATE fr_content_persona_draft SET base_persona_version=NULL WHERE draft_key=?",key);
        assertThatThrownBy(()->drafts.publish(me(),key,1)).hasMessageContaining("正式人物已更新");
        assertThatThrownBy(()->drafts.save(me(),key,1,Map.of("name","stale confirmation"),0L,true)).hasMessageContaining("正式人物已更新");
        assertThat(drafts.get(me(),key).form()).containsEntry("name","old edit");
    }
    @Test void simultaneousPublicationsFromDifferentUsersHaveOneWinner() throws Exception {
        var person=personas.create(me(),Map.of("name","original"));String key=person.getId().toString();
        var boss=post(99,33,"boss");drafts.save(me(),key,0,Map.of("name","one"),0L,false);drafts.save(boss,key,0,Map.of("name","two"),0L,false);
        var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var results=new ArrayList<Future<Boolean>>();
            for(var actor:List.of(me(),boss))results.add(pool.submit(()->{gate.await();try{drafts.publish(actor,key,1);return true;}catch(cn.iocoder.yudao.framework.common.exception.ServiceException e){return false;}}));
            gate.countDown();int won=0;for(var result:results)if(result.get(10,TimeUnit.SECONDS))won++;
            assertThat(won).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_persona_draft WHERE form IS NOT NULL",Integer.class)).isEqualTo(1);
            assertThat(personas.requireWritable(me(),person.getId()).getVersion()).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }

}
