package cn.iocoder.yudao.module.platform.headquarters;

import cn.iocoder.yudao.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.logger.dto.OperateLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.HeadquartersDocumentAccessPort.Request;
import cn.iocoder.yudao.module.franchise.api.HeadquartersDocumentAccessPort.Resource;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.asset.AssetContentScanner;
import cn.iocoder.yudao.module.franchise.asset.LocalAssetService;
import cn.iocoder.yudao.module.franchise.asset.ObjectStoragePort;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowAssetPolicy;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowReadService;
import cn.iocoder.yudao.module.franchise.finance.FinWorkbenchService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.headquarters.controller.admin.HeadquartersExternalEntrustController;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual PostgreSQL ownership/reference joins, ordinary asset scanning and guarded HQ byte downloads. */
@TestPropertySource(properties={"platform.test.extra-flyway-locations=,classpath:db/migration/franchise,classpath:db/migration/platform-wallet,classpath:db/migration/franchise-asset",
        "platform.legacy-design-bridge.enabled=false"})
@Import({HeadquartersReadGuard.class,HeadquartersReadService.class,HeadquartersDocumentAccess.class,
        HeadquartersExternalEntrustController.class,CustomerWorkflowReadService.class,CustomerWorkflowAssetPolicy.class,
        LocalAssetService.class,AssetContentScanner.class,HeadquartersExternalEntrustIntegrationTest.Config.class})
@Sql("/fixtures/operations-system-users.sql")
class HeadquartersExternalEntrustIntegrationTest extends PlatformPgIntegrationTest {
    @TestConfiguration static class Config {
        @Bean CurrentPost posts() { return mock(CurrentPost.class); }
        @Bean OperateLogCommonApi audit() { return mock(OperateLogCommonApi.class); }
        @Bean SystemDirectory directory() { var value=mock(SystemDirectory.class);when(value.usersByIds(anyList())).thenReturn(List.of());return value; }
        @Bean FinWorkbenchService finance(JdbcTemplate jdbc) { return new FinWorkbenchService(jdbc,null,null,null); }
        @Bean MemoryStorage storage() { return new MemoryStorage(); }
        @Bean StoreServiceAccountPort accounts(JdbcTemplate jdbc) {
            return new StoreServiceAccountPort() {
                public long requireServiceAccount(long company) { return company+1_000_000L; }
                public long requireExistingServiceAccountForTask(long tenant,long company) {
                    if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM system_dept WHERE id=? AND tenant_id=? AND status=0 AND deleted=0)",Boolean.class,company,tenant)))
                        throw new ServiceAccountInactiveException(company);
                    return company+1_000_000L;
                }
            };
        }
    }
    static class MemoryStorage implements ObjectStoragePort {
        final Map<String,byte[]> values=new ConcurrentHashMap<>();
        Runnable beforeRead;
        public String presignUploadUrl(String key,long ttl) { return "local://"+key; }
        public boolean existsWithSize(String key,long size) { return values.containsKey(key)&&values.get(key).length==size; }
        public InputStream getObject(String key) { if(beforeRead!=null)beforeRead.run();return new ByteArrayInputStream(values.get(key)); }
        public void putObject(String key,Path file) { try {values.put(key,Files.readAllBytes(file));}catch(Exception failure){throw new IllegalStateException(failure);} }
        public String presignDownloadUrl(String key,long ttl) { throw new AssertionError("HQ must not issue a transferable external URL"); }
    }
    private static final AtomicLong IDS=new AtomicLong(44_000_000);
    private static final byte[] PDF="%PDF-1.4\n1 0 obj << /Type /Page >> endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    @jakarta.annotation.Resource JdbcTemplate jdbc;
    @jakarta.annotation.Resource CurrentPost posts;
    @jakarta.annotation.Resource OperateLogCommonApi audit;
    @jakarta.annotation.Resource HeadquartersReadService reads;
    @jakarta.annotation.Resource HeadquartersDocumentAccess documents;
    @jakarta.annotation.Resource HeadquartersExternalEntrustController controller;
    @jakarta.annotation.Resource LocalAssetService assets;
    @jakarta.annotation.Resource MemoryStorage storage;
    private long tenant,foreignTenant,headquarters,companyA,companyB,foreignCompany,user,customerA,customerB,foreignCustomer;
    private PostSnapshot admin,salesA,salesB;

    @BeforeEach void setup() {
        jdbc.update("INSERT INTO pf_org_type(code,domain,name) VALUES('headquarters','franchise','总部'),('store','franchise','门店') ON CONFLICT DO NOTHING");
        for(String role:List.of("hq-admin","finance","finance-lead","hq-design-admin","boss","sales"))
            jdbc.update("INSERT INTO pf_post_template(role_code,domain,org_types,lead) VALUES(?,'franchise','headquarters,store',false) ON CONFLICT DO NOTHING",role);
        tenant=id();foreignTenant=id();headquarters=organization(tenant,"headquarters");
        companyA=organization(tenant,"store");companyB=organization(tenant,"store");foreignCompany=organization(foreignTenant,"store");
        user=user(tenant);admin=post(user,headquarters,"headquarters","hq-admin");
        salesA=post(user(tenant),companyA,"store","sales");salesB=post(user(tenant),companyB,"store","sales");
        customerA=customer(companyA,salesA.userId());customerB=customer(companyB,salesB.userId());foreignCustomer=customer(foreignCompany,user(foreignTenant));
        reset(audit);storage.beforeRead=null;actor(admin,tenant);
    }
    @AfterEach void clear() {storage.beforeRead=null;TenantContextHolder.clear();SecurityContextHolder.clearContext();}

    @Test void headquartersRolesPageBothCompaniesAndKeepOriginalRequestPaymentAndResultHistory() {
        long a=commission(companyA,customerA),b=commission(companyB,customerB);
        var measured = Map.of("customerId",Long.toString(customerA),"eventId","9007199254740099251","revision",9,
                "fields",Map.of("orientation","南偏东 15°","siteElevationNote","北侧 T 形道路高 0.45 米"));
        request(a,tenant,companyA,customerA,1,id());request(a,tenant,companyA,customerA,2,id(),measured);
        request(b,tenant,companyB,customerB,1,id());result(a,tenant,companyA,customerA,1,1,id());
        for(String role:List.of("hq-admin","finance","finance-lead")) {
            actor(role.equals("hq-admin")?admin:post(user,headquarters,"headquarters",role),tenant);
            var page=reads.externalVersions(customerA,"requests",1,1);
            assertThat(page.total()).isEqualTo(2);assertThat(page.pageNo()).isEqualTo(1);assertThat(page.pageSize()).isEqualTo(1);assertThat(page.readOnly()).isTrue();
            assertThat(page.list()).hasSize(1);assertThat(page.list().get(0).get("version")).isEqualTo(2);
            assertThat(((Map<?,?>)page.list().get(0).get("data")).get("text")).isEqualTo("秘密需求V2");
            assertThat(((Map<?,?>)page.list().get(0).get("data")).get("measurementRef")).isEqualTo(measured);
            assertThat(((Map<?,?>)page.list().get(0).get("paymentPlan")).get("source")).isEqualTo("DRAFT");
            assertThat(page.list().get(0).get("actorName")).isEqualTo("人员"+user);
            var old = reads.externalVersions(customerA,"requests",2,1).list().get(0);
            assertThat(old.get("version")).isEqualTo(1);
            assertThat(((Map<?,?>)old.get("data")).get("measurementRef")).isNull();
            assertThat(reads.externalVersions(customerB,"requests",1,20).total()).isEqualTo(1);
            var result=reads.externalVersions(customerA,"results",1,20).list().get(0);
            assertThat(result).doesNotContainKey("paymentPlan");assertThat((List<?>)result.get("attachments")).hasSize(3);
            assertThat(result.get("createdAt")).isNotNull();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_contract WHERE company_id IN (?,?)",Long.class,companyA,companyB)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_receipt WHERE company_id IN (?,?)",Long.class,companyA,companyB)).isZero();
    }

    @Test void getWithoutVersionsIsReadOnlyAndRejectsInvalidKindOrUnboundedPagination() {
        assertThat(reads.externalVersions(customerA,"requests",1,20).list()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_commission WHERE customer_id=?",Long.class,customerA)).isZero();
        code(400,()->reads.externalVersions(customerA,"invalid",1,20));
        code(400,()->reads.externalVersions(customerA,"requests",0,20));
        code(400,()->reads.externalVersions(customerA,"results",1,101));
    }

    @Test void roleTenantAndCurrentCompanyChecksApplyToBothMetadataAndAttachments() {
        long commission=commission(companyA,customerA),file=id();request(commission,tenant,companyA,customerA,1,file);
        for(var denied:List.of(salesA,post(user,companyA,"store","finance"),post(user,companyA,"store","boss"),post(user,headquarters,"headquarters","hq-design-admin"))) {
            actor(denied,tenant);code(403,()->reads.externalVersions(customerA,"requests",1,20));
            code(403,()->documents.requireReadable(ref(customerA,"requests",1,file)));
        }
        actor(admin,tenant);code(404,()->reads.externalVersions(foreignCustomer,"requests",1,20));
        code(404,()->documents.requireReadable(ref(foreignCustomer,"requests",1,file)));
        actor(admin,foreignTenant);code(403,()->reads.externalVersions(customerA,"requests",1,20));
        actor(admin,tenant);jdbc.update("UPDATE system_dept SET status=1 WHERE id=?",companyA);
        code(404,()->reads.externalVersions(customerA,"requests",1,20));code(404,()->documents.requireReadable(ref(customerA,"requests",1,file)));
    }

    @Test void historicalReferencesRequireExactKindVersionCustomerTenantAndCommissionOwner() {
        long commission=commission(companyA,customerA),one=id(),two=id(),resultFile=id();
        request(commission,tenant,companyA,customerA,1,one);request(commission,tenant,companyA,customerA,2,two);
        result(commission,tenant,companyA,customerA,1,1,resultFile);
        assertThat(documents.requireReadable(ref(customerA,"requests",1,one)).allowedTypes()).containsExactly("BUSINESS_DOCUMENT");
        assertThat(documents.requireReadable(ref(customerA,"results",1,resultFile)).ownerOrgId()).isEqualTo(companyA);
        code(404,()->documents.requireReadable(ref(customerA,"requests",2,one)));
        code(404,()->documents.requireReadable(ref(customerA,"results",1,one)));
        code(404,()->documents.requireReadable(ref(customerB,"requests",1,one)));
        code(404,()->documents.requireReadable(new Request(Resource.CUSTOMER,customerA,"EXTERNAL_REQUEST:01",one)));
        code(404,()->documents.requireReadable(new Request(Resource.CUSTOMER,customerA,"EXTERNAL_RESULT:9999999999",one)));
        // Legacy bad rows can share a commission but must not change either tenant or customer.
        request(commission,foreignTenant,companyA,customerA,3,id());
        request(commission,tenant,companyA,customerB,4,id());
        assertThat(reads.externalVersions(customerA,"requests",1,20).total()).isEqualTo(2);
        assertThat(reads.externalVersions(customerB,"requests",1,20).total()).isZero();
        jdbc.update("UPDATE fr_commission SET customer_id=? WHERE id=?",customerB,commission);
        assertThat(reads.externalVersions(customerA,"requests",1,20).total()).isZero();
        code(404,()->documents.requireReadable(ref(customerA,"requests",1,one)));
    }

    @Test void historicalScannedBytesUseRealGrantAuditAndNoStoreHeadersWithoutNewStoreIdentityOrDownloadTicket() throws Exception {
        long one=upload(salesA,"BUSINESS_DOCUMENT"),two=upload(salesA,"BUSINESS_DOCUMENT"),commission=commission(companyA,customerA);
        request(commission,tenant,companyA,customerA,1,one);request(commission,tenant,companyA,customerA,2,two);
        result(commission,tenant,companyA,customerA,1,1,one);
        jdbc.update("UPDATE fr_commission SET external_request_version=2,external_result_version=1 WHERE id=?",commission);
        actor(admin,tenant);
        var response=controller.content(customerA,"requests",1,one);
        assertThat(response.getBody()).isEqualTo(PDF);assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");assertThat(response.getHeaders().getLocation()).isNull();
        assertThat(controller.content(customerA,"results",1,one).getBody()).isEqualTo(PDF);
        assertThat(posts.require()).isEqualTo(admin);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_asset_download_ticket WHERE tenant_id=?",Long.class,tenant)).isZero();
        var capture=ArgumentCaptor.forClass(OperateLogCreateReqDTO.class);verify(audit,times(2)).createOperateLog(capture.capture());
        assertThat(capture.getAllValues()).allSatisfy(event->{
            assertThat(event.getUserId()).isEqualTo(user);assertThat(event.getBizId()).isEqualTo(customerA);
            assertThat(event.getExtra()).contains(Long.toString(companyA),Long.toString(one)).doesNotContain("秘密需求","人员");
        });
        assertThatThrownBy(()->assets.createDownloadUrl(assetActor(admin),one)).isInstanceOf(ServiceException.class);
    }

    @Test void aReferencedAssetMustStillBelongToTheCompanyAndBeAcceptedBusinessDocument() throws Exception {
        long wrongCompany=upload(salesB,"BUSINESS_DOCUMENT"),wrongType=upload(salesA,"CONTRACT_ATTACHMENT");
        actor(salesA,tenant);var pending=assets.createUploadTicket(assetActor(salesA),"BUSINESS_DOCUMENT","application/pdf",PDF.length,sha(PDF));
        long commission=commission(companyA,customerA);request(commission,tenant,companyA,customerA,1,wrongCompany);
        request(commission,tenant,companyA,customerA,2,wrongType);request(commission,tenant,companyA,customerA,3,pending.assetId());
        actor(admin,tenant);
        for(var item:List.of(new long[]{1,wrongCompany},new long[]{2,wrongType},new long[]{3,pending.assetId()}))
            code(1099000002,()->controller.content(customerA,"requests",(int)item[0],item[1]));
        verifyNoInteractions(audit);
    }

    @Test void revocationAndCompanyTenantChangesDuringStorageReadCannotReleaseBytes() throws Exception {
        long file=upload(salesA,"BUSINESS_DOCUMENT"),commission=commission(companyA,customerA);request(commission,tenant,companyA,customerA,1,file);actor(admin,tenant);
        storage.beforeRead=()->jdbc.update("UPDATE pf_staff_post SET status='REVOKED',revoked_at=now() WHERE id=?",admin.postId());
        code(403,()->controller.content(customerA,"requests",1,file));verifyNoInteractions(audit);
        jdbc.update("UPDATE pf_staff_post SET status='ACTIVE',revoked_at=NULL WHERE id=?",admin.postId());
        storage.beforeRead=()->jdbc.update("UPDATE system_dept SET tenant_id=? WHERE id=?",foreignTenant,companyA);
        code(404,()->controller.content(customerA,"requests",1,file));verifyNoInteractions(audit);
    }

    @Test void failedAuditOrRevocationDuringAuditCannotReleasePreviouslyLoadedBytes() throws Exception {
        long file=upload(salesA,"BUSINESS_DOCUMENT"),commission=commission(companyA,customerA);request(commission,tenant,companyA,customerA,1,file);actor(admin,tenant);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).createOperateLog(any());
        assertThatThrownBy(()->controller.content(customerA,"requests",1,file)).hasMessageContaining("audit unavailable");
        reset(audit);doAnswer(call->{jdbc.update("UPDATE pf_staff_post SET status='REVOKED',revoked_at=now() WHERE id=?",admin.postId());return null;}).when(audit).createOperateLog(any());
        code(403,()->controller.content(customerA,"requests",1,file));
    }

    @Test void headquartersProjectNumericReferencesNeedTheAcceptedExternalPacketAndRejectUnrelatedAssets() throws Exception {
        long file=upload(salesA,"BUSINESS_DOCUMENT"),unrelated=upload(salesA,"BUSINESS_DOCUMENT");
        long commission=commission(companyA,customerA),project=id(),packet=id(),document=id(),wrongDocument=id();
        request(commission,tenant,companyA,customerA,1,file);result(commission,tenant,companyA,customerA,1,1,file);
        jdbc.update("INSERT INTO fr_eng_project(id,company_id,customer_id,customer_name,source_type,source_commission_id,manager_user_id,name,author_user_id) VALUES(?,?,?,'客户','HANDOVER',?,?,'外部移交工程',?)",
                project,companyA,customerA,commission,user,user);
        jdbc.update("INSERT INTO fr_commission_handover(id,company_id,commission_id,version,dispatch_seq,source_mode,external_result_version,result_file,drawing_file,review_file,confirmation_file,confirmation_asset_id,drawing_version,confirmation_date,note,manager_user_id,status,submitted_by_user_id) VALUES(?,?,?,1,0,'EXTERNAL',1,?,?,?,?,?,'外部V1',CURRENT_DATE,'真实移交确认',?,'PENDING',?)",
                packet,companyA,commission,Long.toString(file),Long.toString(file),Long.toString(file),Long.toString(file),file,user,salesA.userId());
        jdbc.update("UPDATE fr_commission SET handover_version=1,status='HANDOVER_SUBMITTED' WHERE id=?",commission);
        jdbc.update("INSERT INTO fr_eng_document(id,company_id,project_id,name,file_ref,kind) VALUES(?,?,?,'外部图纸',?,'DOCUMENT'),(?,?,?,'无关资料',?,'DOCUMENT')",
                document,companyA,project,Long.toString(file),wrongDocument,companyA,project,Long.toString(unrelated));
        actor(admin,tenant);var exact=new Request(Resource.PROJECT,project,"DOCUMENT",document);
        code(404,()->documents.requireReadable(exact));
        jdbc.update("UPDATE fr_commission_handover SET status='ACCEPTED',decided_at=now(),decided_by_user_id=? WHERE id=?",user,packet);
        jdbc.update("UPDATE fr_commission SET status='ACCEPTED' WHERE id=?",commission);
        assertThat(documents.requireReadable(exact).allowedTypes()).containsExactly("BUSINESS_DOCUMENT");
        assertThat(assets.readHeadquartersDocument(exact).bytes()).isEqualTo(PDF);
        code(404,()->assets.readHeadquartersDocument(new Request(Resource.PROJECT,project,"DOCUMENT",wrongDocument)));
        code(404,()->assets.readHeadquartersDocument(new Request(Resource.PROJECT,project,"DOCUMENT",unrelated)));
        actor(salesA,tenant);code(403,()->assets.readHeadquartersDocument(exact));
    }

    private long id(){return IDS.incrementAndGet();}
    private long user(long ownerTenant){long id=id();jdbc.update("INSERT INTO system_users(id,tenant_id,nickname,username,password,mobile,status,deleted) VALUES(?,?,?,?,'unused',?,0,0)",id,ownerTenant,"人员"+id,"external-hq-"+id,"1"+String.format("%010d",id));return id;}
    private long organization(long ownerTenant,String type){long id=id();jdbc.update("INSERT INTO system_dept(id,name,tenant_id,status,deleted) VALUES(?,?,?,0,0)",id,"公司"+id,ownerTenant);jdbc.update("INSERT INTO pf_org_unit(dept_id,kind,org_id,org_type) VALUES(?,'ORG',?,?)",id,id,type);return id;}
    private PostSnapshot post(long actor,long company,String type,String role){long id=id();jdbc.update("INSERT INTO pf_staff_post(id,user_id,org_id,role_code) VALUES(?,?,?,?)",id,actor,company,role);return new PostSnapshot(id,actor,company,type,role,null,false,Set.of());}
    private void actor(PostSnapshot post,long ownerTenant){when(posts.require()).thenReturn(post);TenantContextHolder.setTenantId(ownerTenant);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser().setId(post.userId()).setUserType(2).setTenantId(ownerTenant),null,List.of()));}
    private long customer(long company,long owner){long id=id();jdbc.update("INSERT INTO fr_customer(id,company_id,owner_user_id,name,phone,source,status) VALUES(?,?,?,'客户',?,'test','REGISTERED')",id,company,owner,"1"+String.format("%010d",id));return id;}
    private long commission(long company,long customer){long id=id();jdbc.update("INSERT INTO fr_commission(id,company_id,design_org_id,customer_id,customer_name,status,submission_version,workflow_mode) VALUES(?,?,?,?,'客户','EXTERNAL_PENDING',1,'EXTERNAL')",id,company,company,customer);return id;}
    private void request(long commission,long ownerTenant,long company,long customer,int version,long asset){
        request(commission,ownerTenant,company,customer,version,asset,null);
    }
    private void request(long commission,long ownerTenant,long company,long customer,int version,long asset,Map<String,?> measurement){
        var attachment=List.of(Map.of("assetId",Long.toString(asset),"title","需求依据"));
        var data=new java.util.LinkedHashMap<String,Object>(Map.of("text","秘密需求V"+version,"attachments",attachment));
        if (measurement != null) data.put("measurementRef",measurement);
        var plan=Map.of("source","DRAFT","revision",version,"totalCents",10000,"customerId",Long.toString(customer),"companyId",Long.toString(company));
        jdbc.update("INSERT INTO fr_commission_external_version(commission_id,version,tenant_id,company_id,customer_id,action,data,payment_plan,actor_user_id,actor_post_id) VALUES(?,?,?,?,?,'REGISTER',CAST(? AS jsonb),CAST(? AS jsonb),?,?)",commission,version,ownerTenant,company,customer,JsonUtils.toJsonString(data),JsonUtils.toJsonString(plan),user,admin.postId());
    }
    private void result(long commission,long ownerTenant,long company,long customer,int version,int request,long asset){
        var attachment=List.of(Map.of("assetId",Long.toString(asset),"title","方案"),Map.of("assetId",Long.toString(asset),"title","施工图"),Map.of("assetId",Long.toString(asset),"title","审核依据"));
        var data=Map.of("requestVersion",request,"drawingVersion","V"+version,"note","实际外部成果");
        jdbc.update("INSERT INTO fr_commission_external_result(commission_id,version,request_version,tenant_id,company_id,customer_id,data,attachments,actor_user_id,actor_post_id) VALUES(?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?)",commission,version,request,ownerTenant,company,customer,JsonUtils.toJsonString(data),JsonUtils.toJsonString(attachment),user,admin.postId());
    }
    private long upload(PostSnapshot post,String type)throws Exception{actor(post,tenant);var ticket=assets.createUploadTicket(assetActor(post),type,"application/pdf",PDF.length,sha(PDF));assets.receiveUpload(assetActor(post),ticket.assetId(),ticket.uploadUrl().split("token=")[1],new ByteArrayInputStream(PDF));assertThat(assets.completeUpload(assetActor(post),ticket.assetId()).uploadStatus()).isEqualTo("ACCEPTED");return ticket.assetId();}
    private static AssetAccessPort.Actor assetActor(PostSnapshot post){return new AssetAccessPort.Actor(post.orgId(),post.orgId()+1_000_000L,post.userId(),post.postId());}
    private static Request ref(long customer,String kind,int version,long asset){return new Request(Resource.CUSTOMER,customer,HeadquartersExternalEntrustKind.fromPath(kind).section(version),asset);}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void code(int code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class,failure->assertThat(failure.getCode()).isEqualTo(code));}
}
