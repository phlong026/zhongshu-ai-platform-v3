package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.module.design.asset.*;
import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import cn.iocoder.yudao.module.design.capability.*;
import cn.iocoder.yudao.module.design.capability.security.ServicePrincipal;
import cn.iocoder.yudao.module.design.rights.RightsGrantService;
import cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall;
import cn.iocoder.yudao.module.platform.test.TestPostgres;
import com.fasterxml.jackson.databind.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.http.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real provider import, image scanner, immutable objects, grants and PG; only A's HTTP transport and moderation are fixtures. */
@Testcontainers
class PublishedCaseReferenceImporterIntegrationTest {
    @Container static final PostgreSQLContainer<?> PG=TestPostgres.database("postgres:17-alpine");
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private JdbcTemplate jdbc;private PublishedCaseReferenceImporter importer;private byte[] image;private JsonNode manifest;
    private long owner,other;private int code;private String objectHost="objects.example.test";
    private SimpleDriverDataSource source;private DataSourceTransactionManager manager;
    private CaseCatalogService catalog;private AssetService assets;
    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        var ds=new SimpleDriverDataSource(new org.postgresql.Driver(),PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        var flyway=Flyway.configure().dataSource(ds).cleanDisabled(false).locations("classpath:db/migration/platform","classpath:db/migration/design","classpath:db/migration/design-capability").load();flyway.clean();flyway.migrate();jdbc=new JdbcTemplate(ds);
        owner=jdbc.queryForObject("INSERT INTO design_capability_organization(caller_app_id,org_id,tenant_id,active) VALUES('source-test',10,1,true) RETURNING service_account_id",Long.class);
        other=jdbc.queryForObject("INSERT INTO design_capability_organization(caller_app_id,org_id,tenant_id,active) VALUES('source-test',11,1,true) RETURNING service_account_id",Long.class);
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"png",bytes);image=bytes.toByteArray();
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        var images=new ArrayList<Map<String,Object>>();int id=100;
        for(String role:List.of("COVER","FLOOR_PLAN","FLOOR_PLAN","ELEVATION")){var item=new LinkedHashMap<String,Object>();item.put("assetId",String.valueOf(++id));item.put("role",role);item.put("floorNo",role.equals("FLOOR_PLAN")?id-101:null);item.put("sha256",hash);item.put("mimeType","image/png");item.put("sizeBytes",image.length);item.put("generationAllowed",true);item.put("downloadUrl","https://objects.example.test/"+id);images.add(item);}
        var detail=new LinkedHashMap<String,Object>();detail.put("title","源案例");detail.put("description","");detail.put("styleCode","MODERN");detail.put("floorCount",2);detail.put("buildingArea",240);detail.put("faceWidth",12);detail.put("depth",10);detail.put("rooms",List.of());detail.put("tags",List.of());detail.put("dimensionBasis","SITE");
        manifest=json.valueToTree(Map.of("caseId","51","caseVersionId","61","line","FLAT","validUntil",Instant.now().plusSeconds(120).toString(),"case",detail,"images",images));
        source=ds;manager=new DataSourceTransactionManager(ds);var storage=mock(ObjectStoragePort.class);var objects=new ConcurrentHashMap<String,byte[]>();
        when(storage.presignDownloadUrl(anyString(),anyLong())).thenAnswer(inv->"https://objects.example.test/"+inv.getArgument(0));
        doAnswer(inv->{objects.put(inv.getArgument(0),inv.getArgument(1));return null;}).when(storage).putObject(anyString(),any());
        when(storage.getObject(anyString())).thenAnswer(inv->new ByteArrayInputStream(objects.get(inv.getArgument(0))));
        when(storage.existsWithSize(anyString(),anyLong())).thenAnswer(inv->objects.containsKey(inv.getArgument(0))&&objects.get(inv.getArgument(0)).length==(long)inv.getArgument(1));
        var moderation=mock(ContentModerationPort.class);when(moderation.supportsUpload(anyString())).thenReturn(true);when(moderation.review(anyString(),any())).thenReturn(new ContentModerationPort.Decision(true,"fixture","request","pass","fixture"));
        var rights=new RightsGrantService(ds);assets=new AssetService(ds,manager,storage,new AssetContentScanner(),moderation,new cn.iocoder.yudao.module.infra.zhongshu.delivery.JdbcDeliveryPort(ds),rights,new CapabilityServiceAccountGuard(ds));
        catalog=new CaseCatalogService(ds,manager);
        importer=new PublishedCaseReferenceImporter(ds,manager,json,assets,catalog,rights,"https://catalog.example.test","objects.example.test");
        var http=mock(HttpClient.class);ReflectionTestUtils.setField(importer,"http",http);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(inv->{
            HttpRequest request=inv.getArgument(0);assertThat(request.headers().firstValue("Authorization")).isEmpty();byte[] body;
            if(request.uri().getHost().equals("catalog.example.test")){assertThat(request.headers().firstValue("tenant-id")).contains("1");body=json.writeValueAsBytes(Map.of("code",code,"msg","source authorization unavailable","data",manifest));}
            else {assertThat(request.uri().getHost()).isEqualTo(objectHost);body=image;}
            HttpResponse<InputStream> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn(new ByteArrayInputStream(body));when(response.headers()).thenReturn(HttpHeaders.of(Map.of(),(a,b)->true));return response;
        });
    }
    @AfterEach void close(){if(importer!=null)importer.close();SecurityContextHolder.clearContext();}
    private DesignFlowCall prepare(long account){SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(new ServicePrincipal("source-test",account==owner?10:11,1,account),null,"SERVICE"));return importer.prepare(account,new DesignFlowCall("POST","/design-projects",Map.of(),Map.of("sourceType","CASE_REFERENCE","refCaseId","51","refCaseVersionId","61","line","FLAT"),"reference"));}
    @Test void verifiedImagesAndCaseIdsAreMaterializedPerCompanyAndRetriesReuseAcceptedAssets(){
        var first=prepare(owner);assertThat(first.text("refCaseId")).isNotEqualTo("51");assertThat(first.text("refCaseVersionId")).isNotEqualTo("61");
        long count=jdbc.queryForObject("SELECT count(*) FROM asset WHERE owner_user_id=? AND upload_status='ACCEPTED'",Long.class,owner);assertThat(count).isEqualTo(4);
        assertThat(prepare(owner).text("refCaseId")).isEqualTo(first.text("refCaseId"));assertThat(jdbc.queryForObject("SELECT count(*) FROM asset WHERE owner_user_id=?",Long.class,owner)).isEqualTo(count);
        var second=prepare(other);assertThat(second.text("refCaseId")).isNotEqualTo(first.text("refCaseId"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_rights_grant WHERE grantor_user_id=? AND scope='GENERATION_REFERENCE' AND expires_at<=now()+interval '120 seconds'",Long.class,other)).isEqualTo(4);
    }
    @Test void revokedSourceAuthorizationIsRecheckedEvenWhileTheLocalLeaseStillExists(){prepare(owner);code=403;assertThatThrownBy(()->prepare(owner)).isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,e->assertThat(e.getCode()).isEqualTo(403));}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"gif","bmp","tiff","jpg"})
    void everyOriginalCaseImageFormatGoesThroughTheActualScanner(String format) throws Exception {
        var bytes=new ByteArrayOutputStream();assertThat(ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),format,bytes)).isTrue();image=bytes.toByteArray();
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        for(JsonNode node:manifest.path("images")){var item=(com.fasterxml.jackson.databind.node.ObjectNode)node;item.put("sha256",hash);item.put("sizeBytes",image.length);item.put("mimeType",format.equals("jpg")?"image/jpeg":"image/"+format);}
        var mirrored=prepare(owner);assertThat(mirrored.text("refCaseId")).isNotEqualTo("51");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset WHERE owner_user_id=? AND upload_status='ACCEPTED'",Long.class,owner)).isEqualTo(4);
        var controller=new cn.iocoder.yudao.module.design.controller.app.AppGenerationReferenceController(source,manager,catalog,mock(cn.iocoder.yudao.module.design.catalog.CaseGenerationCandidateService.class),assets);
        var exported=json.valueToTree(controller.manifest(Long.parseLong(mirrored.text("refCaseId")),null,"ELEVATION").getData());
        assertThat(exported.path("images").get(0).path("mimeType").asText()).isEqualTo(format.equals("jpg")?"image/jpeg":"image/png");
    }
    @Test void anImageIdentityChangeWithoutANewSourceVersionIsRejected() {
        prepare(owner);((com.fasterxml.jackson.databind.node.ObjectNode)manifest.path("images").get(0)).put("assetId","999");
        assertThatThrownBy(()->prepare(owner)).isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,e->assertThat(e.getCode()).isEqualTo(1099000001));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_capability_case_mirror",Long.class)).isEqualTo(1);
    }
    @Test void undoRefusesToDiscardImportedImageIdentitiesAndCaseMappings() throws Exception {
        prepare(owner);String undo;try(var stream=getClass().getResourceAsStream("/db/migration-undo/design-capability/V20261116.922__undo_original_design_case_references.sql")){undo=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        final String sql=undo;assertThatThrownBy(()->jdbc.execute(sql)).hasMessageContaining("DESIGN_REFERENCE_ROLLBACK_REQUIRES_EMPTY_TABLES");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_capability_case_mirror",Long.class)).isEqualTo(1);
    }
    @Test void undoWorksBeforeAnyReferenceHasBeenImported() throws Exception {
        try(var stream=getClass().getResourceAsStream("/db/migration-undo/design-capability/V20261116.922__undo_original_design_case_references.sql")){jdbc.execute(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        assertThat(jdbc.queryForObject("SELECT to_regclass('design_capability_case_mirror')",String.class)).isNull();
    }
    @Test @SuppressWarnings("unchecked") void sourceManifestUsesTheAcceptedObjectHashAndNeverOutlivesTheActualGrant(){
        var mirrored=prepare(owner);long caseId=Long.parseLong(mirrored.text("refCaseId"));
        long cover=jdbc.queryForObject("SELECT ca.asset_id FROM design_case_asset ca JOIN design_case c ON c.current_version_id=ca.case_version_id WHERE c.id=? AND ca.asset_role='COVER' AND ca.deleted=false",Long.class,caseId);
        String stored=jdbc.queryForObject("SELECT stored_sha256 FROM asset WHERE id=?",String.class,cover);
        jdbc.update("UPDATE asset SET sha256=repeat('0',64) WHERE id=?",cover);
        jdbc.update("UPDATE asset_rights_grant SET expires_at=now()+interval '60 seconds' WHERE asset_id=? AND scope='PUBLIC_DISPLAY'",cover);
        var controller=new cn.iocoder.yudao.module.design.controller.app.AppGenerationReferenceController(source,manager,catalog,mock(cn.iocoder.yudao.module.design.catalog.CaseGenerationCandidateService.class),assets);
        var exported=controller.manifest(caseId,null,"ELEVATION").getData();
        var images=(List<Map<String,Object>>)exported.get("images");assertThat(images.stream().filter(i->i.get("role").equals("COVER")).findFirst().orElseThrow().get("sha256")).isEqualTo(stored);
        assertThat(Instant.parse(exported.get("validUntil").toString())).isBefore(Instant.now().plusSeconds(61));
    }
    @Test void wrongVersionOrMissingRequiredGenerationRightFailsBeforeImport(){
        ((com.fasterxml.jackson.databind.node.ObjectNode)manifest).put("caseVersionId","62");assertThatThrownBy(()->prepare(owner)).isInstanceOf(RuntimeException.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM asset",Long.class)).isZero();
        ((com.fasterxml.jackson.databind.node.ObjectNode)manifest).put("caseVersionId","61");((com.fasterxml.jackson.databind.node.ObjectNode)manifest.path("images").get(1)).put("generationAllowed",false);assertThatThrownBy(()->prepare(owner)).isInstanceOf(RuntimeException.class);
    }
    @Test void unapprovedObjectHostsAndTamperedBytesNeverBecomeGenerationAssets(){
        ((com.fasterxml.jackson.databind.node.ObjectNode)manifest.path("images").get(0)).put("downloadUrl","https://169.254.169.254/secret");assertThatThrownBy(()->prepare(owner)).isInstanceOf(RuntimeException.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM asset",Long.class)).isZero();
        ((com.fasterxml.jackson.databind.node.ObjectNode)manifest.path("images").get(0)).put("downloadUrl","https://objects.example.test/101");image=new byte[]{1,2,3};assertThatThrownBy(()->prepare(owner)).isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,e->assertThat(e.getCode()).isEqualTo(1071000003));
    }
}
