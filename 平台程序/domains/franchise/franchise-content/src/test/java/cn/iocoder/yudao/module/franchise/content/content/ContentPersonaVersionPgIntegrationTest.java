package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.INPUT_INVALID;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.PERSONA_VERSION_CONFLICT;
import static cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;

@Import(ContentPersonaService.class)
class ContentPersonaVersionPgIntegrationTest extends FranchisePgIntegrationTest {
    @Resource ContentPersonaService personas;
    @Resource JdbcTemplate jdbc;

    private PostSnapshot editor(long user) {
        return new PostSnapshot(user,user,33L,"FRANCHISE","boss",null,false,Set.of());
    }

    private Map<String,Object> stored(long id) {
        return jdbc.queryForMap("SELECT * FROM fr_content_persona WHERE id=?",id);
    }

    @Test void staleAndMissingVersionsCannotOverwriteAColleagueAndRefreshAllowsSave() {
        var a=editor(77); var b=editor(88);
        var person=personas.create(a,Map.of("name","original","who","original identity"));
        var readA=personas.requireWritable(a,person.getId());
        var readB=personas.requireWritable(b,person.getId());
        var savedA=personas.update(a,person.getId(),Map.of("version",readA.getVersion(),"name","A saved","who","A identity"));
        assertThat(savedA.getVersion()).isEqualTo(1);
        var afterA=stored(person.getId());

        assertError(()->personas.update(b,person.getId(),Map.of("version",readB.getVersion(),"name","stale B","who","old identity")),PERSONA_VERSION_CONFLICT);
        assertThat(stored(person.getId())).isEqualTo(afterA);
        assertError(()->personas.update(b,person.getId(),Map.of("name","unversioned B","enabled",false)),PERSONA_VERSION_CONFLICT);
        assertThat(stored(person.getId())).isEqualTo(afterA);
        // The internal overload must enforce the same contract, not silently fill in a version.
        assertError(()->personas.update(33L,person.getId(),Map.of("name","unversioned internal")),PERSONA_VERSION_CONFLICT);
        assertThat(stored(person.getId())).isEqualTo(afterA);

        var refreshedB=personas.requireWritable(b,person.getId());
        var savedB=personas.update(b,person.getId(),Map.of("version",refreshedB.getVersion(),"name","B refreshed"));
        assertThat(savedB.getVersion()).isEqualTo(2);
        assertThat(personas.requireWritable(a,person.getId()).getName()).isEqualTo("B refreshed");
        assertThat(personas.requireWritable(a,person.getId()).getWho()).isEqualTo("A identity");
    }

    @Test void nullOrMalformedVersionNeverMutatesAnExistingPersona() {
        var post=editor(77);var person=personas.create(post,Map.of("name","unchanged"));
        var before=stored(person.getId());
        var explicitNull=new HashMap<String,Object>();explicitNull.put("name","bad");explicitNull.put("version",null);
        assertError(()->personas.update(post,person.getId(),explicitNull),PERSONA_VERSION_CONFLICT);
        assertThat(stored(person.getId())).isEqualTo(before);
        for(Object invalid:List.of("0",false,-1,0.5,new BigDecimal("0.0000000000000000001"),
                new BigInteger("18446744073709551616"),9_007_199_254_740_991L,Double.NaN,Double.POSITIVE_INFINITY)) {
            assertError(()->personas.update(post,person.getId(),Map.of("version",invalid,"name","bad")),INPUT_INVALID);
            assertThat(stored(person.getId())).isEqualTo(before);
        }
        assertThat(personas.update(post,person.getId(),Map.of("version",0,"name","valid integer")).getVersion()).isEqualTo(1);
    }

    @Test void concurrentDirectEditorsWithTheSameVersionHaveExactlyOneWinner() throws Exception {
        var person=personas.create(editor(77),Map.of("name","original"));
        var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var results=new ArrayList<Future<Boolean>>();
            for(var actor:List.of(editor(77),editor(88)))results.add(pool.submit(()->{
                gate.await();
                try {personas.update(actor,person.getId(),Map.of("version",0L,"name","editor "+actor.userId()));return true;}
                catch(cn.iocoder.yudao.framework.common.exception.ServiceException e){
                    assertThat(e.getCode()).isEqualTo(PERSONA_VERSION_CONFLICT.getCode());return false;
                }
            }));
            gate.countDown();int winners=0;for(var result:results)if(result.get(10,TimeUnit.SECONDS))winners++;
            assertThat(winners).isEqualTo(1);
            assertThat(personas.requireWritable(editor(77),person.getId()).getVersion()).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
}
