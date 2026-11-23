package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentPersonaDO;
import cn.iocoder.yudao.module.franchise.content.provider.ContentProviderProperties;
import cn.iocoder.yudao.module.franchise.content.provider.ContentProviderTransport;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** DH-01 and VO-01: defaults belong to a user; shared personas do not share preferences. */
class ContentClonePersonalDefaultPgIntegrationTest extends FranchisePgIntegrationTest {
    @Resource DataSource dataSource;
    @Resource PlatformTransactionManager transactionManager;
    JdbcTemplate jdbc;
    ContentPersonaService personas;
    OrgPointPort points;
    ContentProviderTransport.Scripted transport;
    ContentCloneService service;

    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE fr_content_clone_personal_default,fr_content_clone_default,fr_content_digital_clone,fr_content_hifly_upload");
        personas=mock(ContentPersonaService.class);
        when(personas.list(33)).thenReturn(List.of(new FrContentPersonaDO().setId(21L).setEnabled(true),
                new FrContentPersonaDO().setId(23L).setEnabled(false)));
        points=mock(OrgPointPort.class);
        transport=new ContentProviderTransport.Scripted();
        service=new ContentCloneService(jdbc,personas,null,points,new ContentProviderProperties(),transport,null,transactionManager);
    }

    private PostSnapshot post(String role,long user,long company) {
        return new PostSnapshot(11,user,company,"store",role,null,false,Set.of());
    }
    private void clone(long id,long author,long persona,String kind) {
        jdbc.update("INSERT INTO fr_content_digital_clone(id,company_id,persona_id,author_user_id,author_post_id,kind,title,source_asset_id,source_sha256,consent_version,idempotency_key,request_hash,status,provider_resource_id,operation_id,billing_state) VALUES(?,33,?,?,11,?,'本人的资源',101,?,'digital-clone-v1',?,?,'READY',?,555,'SETTLED')",
                id,persona,author,kind,"a".repeat(64),"seed-"+id,"hash-"+id,"provider-"+id);
    }
    private List<String> defaults(PostSnapshot post) {
        return service.list(post,21).stream().filter(r->Boolean.TRUE.equals(r.get("isDefault")))
                .map(r->(String)r.get("id")).toList();
    }
    private void ownDefaultIsIndependent(String kind) {
        var first=post("sales",22,33);var second=post("sales",23,33);
        clone(201,22,21,kind);clone(202,22,21,kind);clone(203,23,21,kind);
        assertThat(service.setDefault(first,201)).containsEntry("isDefault",true).containsEntry("canManage",true);
        service.setDefault(second,203);
        service.setDefault(first,202);
        assertThat(defaults(first)).containsExactly("202");
        assertThat(defaults(second)).containsExactly("203");
        assertThat(service.refresh(first,202)).containsEntry("isDefault",true).containsEntry("canManage",true);
        assertThat(service.refresh(second,202)).containsEntry("isDefault",false).containsEntry("canManage",false);
        // A new service instance reads the persisted selection, including same-user post changes.
        var reopened=new ContentCloneService(jdbc,personas,null,points,new ContentProviderProperties(),transport,null,transactionManager);
        assertThat(reopened.list(post("sales-lead",22,33),21).stream()
                .filter(r->Boolean.TRUE.equals(r.get("isDefault"))).map(r->r.get("id"))).containsExactly("202");
        verifyNoInteractions(points);assertThat(transport.calls).isEmpty();
    }
    @Test void dh01SalesCanChooseOwnAvatarWithoutChangingCoworkerDefault() {ownDefaultIsIndependent("AVATAR");}
    @Test void vo01SalesCanChooseOwnVoiceWithoutChangingCoworkerDefault() {ownDefaultIsIndependent("VOICE");}

    @Test void changingAvatarPreservesVoiceAndOldCompanyDefaultIsNotAPersonalChoice() {
        clone(201,22,21,"AVATAR");clone(202,22,21,"AVATAR");clone(301,22,21,"VOICE");
        jdbc.update("INSERT INTO fr_content_clone_default(company_id,persona_id,kind,clone_id,updated_by) VALUES(33,21,'AVATAR',201,99)");
        var current=post("sales",22,33);
        assertThat(defaults(current)).isEmpty();
        service.setDefault(current,201);service.setDefault(current,301);service.setDefault(current,202);
        assertThat(defaults(current)).containsExactlyInAnyOrder("202","301");
        assertThat(jdbc.queryForObject("SELECT clone_id FROM fr_content_clone_default WHERE company_id=33 AND persona_id=21 AND kind='AVATAR'",Long.class)).isEqualTo(201);
    }

    @Test void managersCannotManageCoworkerResourcesAndOtherRolesOrOrganizationsCannotManageOwn() {
        clone(201,22,21,"AVATAR");clone(301,22,21,"VOICE");
        for(String role:List.of("sales","sales-lead","boss","owner")) {
            for(long id:List.of(201L,301L)) {
                assertThatThrownBy(()->service.setDefault(post(role,23,33),id)).hasMessageContaining("本人创建");
                assertThatThrownBy(()->service.setDefault(post(role,22,34),id)).hasMessageContaining("无权管理");
            }
            assertThat(service.list(post(role,23,33),21)).allSatisfy(row->assertThat(row).containsEntry("canManage",false));
        }
        assertThatThrownBy(()->service.setDefault(post("finance",22,33),201)).hasMessageContaining("当前岗位");
        assertThat(service.list(post("finance",22,33),21)).allSatisfy(row->assertThat(row).containsEntry("canManage",false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_clone_personal_default",Long.class)).isZero();
        verifyNoInteractions(points);assertThat(transport.calls).isEmpty();
    }

    @Test void unavailableResourcesCannotBecomeDefaultsForEitherKind() {
        for(String kind:List.of("AVATAR","VOICE")) {
            clone(201,22,21,kind);
            for(String status:List.of("SUBMITTING","RUNNING","UNKNOWN","FAILED")) {
                jdbc.update("UPDATE fr_content_digital_clone SET status=? WHERE id=201",status);
                assertThatThrownBy(()->service.setDefault(post("sales",22,33),201)).hasMessageContaining("已就绪");
            }
            jdbc.update("UPDATE fr_content_digital_clone SET status='READY',billing_state='PENDING' WHERE id=201");
            assertThatThrownBy(()->service.setDefault(post("sales",22,33),201)).hasMessageContaining("结算");
            jdbc.update("UPDATE fr_content_digital_clone SET billing_state='SETTLED',provider_resource_id=' ' WHERE id=201");
            assertThatThrownBy(()->service.setDefault(post("sales",22,33),201)).hasMessageContaining("已就绪");
            jdbc.update("UPDATE fr_content_digital_clone SET provider_resource_id='ready',persona_id=23 WHERE id=201");
            assertThatThrownBy(()->service.setDefault(post("sales",22,33),201)).hasMessageContaining("停用");
            jdbc.update("DELETE FROM fr_content_digital_clone WHERE id=201");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_clone_personal_default",Long.class)).isZero();
        verifyNoInteractions(points);assertThat(transport.calls).isEmpty();
    }

    @Test void migrationOnlyCarriesUsableChoicesMadeByTheirOwnAuthorAndPreservesLegacyFacts() throws Exception {
        String migration;
        try(var input=getClass().getResourceAsStream("/db/migration/franchise/V20261123.1023__content_clone_personal_defaults.sql")) {
            migration=new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8);
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            jdbc.execute("DROP TABLE fr_content_clone_personal_default");
            jdbc.execute("TRUNCATE fr_content_persona");
            for(long persona=21;persona<=27;persona++) {
                jdbc.update("INSERT INTO fr_content_persona(id,company_id,name,author_user_id,enabled,deleted) VALUES(?,33,'测试人物',22,?,?)",persona,persona!=26,persona==27);
                clone(200+persona,22,persona,"AVATAR");
                jdbc.update("INSERT INTO fr_content_clone_default(company_id,persona_id,kind,clone_id,updated_by) VALUES(33,?,'AVATAR',?,?)",persona,200+persona,persona==22?99:22);
            }
            clone(321,22,21,"VOICE");
            jdbc.update("INSERT INTO fr_content_clone_default(company_id,persona_id,kind,clone_id,updated_by) VALUES(33,21,'VOICE',321,22)");
            jdbc.update("UPDATE fr_content_digital_clone SET provider_resource_id='' WHERE id=223");
            jdbc.update("UPDATE fr_content_digital_clone SET billing_state='PENDING' WHERE id=224");
            jdbc.update("UPDATE fr_content_digital_clone SET company_id=34 WHERE id=225");
            jdbc.execute(migration);
            assertThat(jdbc.queryForList("SELECT clone_id FROM fr_content_clone_personal_default ORDER BY clone_id",Long.class)).containsExactly(221L,321L);
            assertThat(jdbc.queryForList("SELECT DISTINCT user_id FROM fr_content_clone_personal_default",Long.class)).containsExactly(22L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_clone_default",Long.class)).isEqualTo(8);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_digital_clone",Long.class)).isEqualTo(8);
            status.setRollbackOnly();
        });
    }
}
