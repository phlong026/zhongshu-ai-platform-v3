package cn.iocoder.yudao.module.franchise.asset;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.crm.CrmOrgScope;
import cn.iocoder.yudao.module.franchise.crm.CustomerSchemeAdapter;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.LeadService;
import cn.iocoder.yudao.module.franchise.crm.controller.admin.CustomerController;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowAssetPolicy;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowReadService;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowService;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowSupplementService;
import cn.iocoder.yudao.module.franchise.crm.controller.admin.CustomerWorkflowSupplementController;
import cn.iocoder.yudao.module.franchise.crm.controller.admin.CustomerWorkflowController;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.sales.SaleQuoteService;
import cn.iocoder.yudao.module.franchise.sales.SaleRequirementService;
import cn.iocoder.yudao.module.franchise.sales.SalesAccess;
import cn.iocoder.yudao.module.franchise.sales.controller.admin.CustomerWorkflowQuotesController;
import cn.iocoder.yudao.module.franchise.test.FakeSystemDirectory;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.controller.admin.StaffProfileController;
import cn.iocoder.yudao.module.platform.org.profile.StaffProfileService;
import cn.iocoder.yudao.module.platform.org.profile.StaffProfileStore;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

/** Real PG, upload scanning, accepted bytes and permission rechecks. Storage alone is an in-memory fixture. */
@Import({LeadService.class, CustomerService.class, CrmOrgScope.class, FranchiseCommands.class, CommandLogImpl.class,
        SaleQuoteService.class, SaleRequirementService.class, SalesAccess.class, CustomerSchemeAdapter.class,
        StaffProfileService.class, StaffProfileController.class,
        CustomerWorkflowService.class, CustomerWorkflowReadService.class, CustomerWorkflowAssetPolicy.class,CustomerWorkflowSupplementService.class,
        cn.iocoder.yudao.module.franchise.sales.SaleWorkflowFactsAdapter.class,cn.iocoder.yudao.module.franchise.engineering.EngWorkflowFactsAdapter.class,
        AssetAccessService.class, CustomerWorkflowAssetPgIntegrationTest.Config.class})
@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/franchise,classpath:db/migration/franchise-asset")
class CustomerWorkflowAssetPgIntegrationTest extends FranchisePgIntegrationTest {
    private static final AtomicLong PHONES = new AtomicLong(17_000_000_000L + System.nanoTime() % 800_000_000L);
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj << /Type /Page >> endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    @Resource JdbcTemplate jdbc;
    @Resource LocalAssetService assets;
    @Resource AssetAccessService channel;
    @Resource CustomerWorkflowService workflow;
    @Resource CustomerWorkflowSupplementService supplements;
    @Resource LeadService leads;
    @Resource CustomerService customers;
    @Resource SaleQuoteService quotes;
    @Resource StaffProfileController profiles;
    @MockitoBean StaffProfileStore profileStore;
    @Resource MemoryStorage storage;
    private PostSnapshot sales, colleague, lead, boss, other;
    private long tenant;

    @TestConfiguration
    static class Config {
        @Bean cn.iocoder.yudao.module.franchise.api.AssetEvidenceQueryPort workflowEvidence(JdbcTemplate jdbc) {
            return new AssetEvidenceQueryAdapter(jdbc);
        }
        @Bean StoreServiceAccountPort accounts() { return org -> org + 1_000_000L; }
        @Bean MemoryStorage storage() { return new MemoryStorage(); }
        @Bean LocalAssetService assets(DataSource data, PlatformTransactionManager transactions, MemoryStorage storage,
                                       CurrentPost posts, StoreServiceAccountPort accounts, CustomerWorkflowAssetPolicy policy) {
            LocalAssetService assets = new LocalAssetService(data, transactions, storage, new AssetContentScanner(), posts, accounts);
            assets.setVisibilityPolicies(List.of(policy)); assets.setWorkflowScopes(policy); return assets;
        }
    }
    static class MemoryStorage implements ObjectStoragePort {
        final Map<String, byte[]> values = new ConcurrentHashMap<>();
        volatile Runnable beforeRead;
        public String presignUploadUrl(String key, long ttl) { return "local://" + key; }
        public boolean existsWithSize(String key, long size) { return values.containsKey(key) && values.get(key).length == size; }
        public java.io.InputStream getObject(String key) {
            Runnable callback = beforeRead; beforeRead = null; if (callback != null) callback.run();
            return new ByteArrayInputStream(java.util.Objects.requireNonNull(values.get(key)));
        }
        public void putObject(String key, java.nio.file.Path file) {
            try { values.put(key, Files.readAllBytes(file)); } catch (java.io.IOException error) { throw new RuntimeException(error); }
        }
        public String presignDownloadUrl(String key, long ttl) { return "local://" + key; }
    }

    @BeforeEach void organization() {
        var store = fixture.store(); tenant = store.orgId();
        sales = fixture.post(store.orgId(), SALES, store.salesTeamA());
        colleague = fixture.post(store.orgId(), SALES, store.salesTeamA());
        lead = fixture.post(store.orgId(), SALES_LEAD, store.salesTeamA());
        boss = fixture.post(store.orgId(), BOSS, null);
        var otherStore = fixture.store(); other = fixture.post(otherStore.orgId(), SALES, otherStore.salesTeamA());
        for (long company : List.of(store.orgId(), otherStore.orgId()))
            jdbc.update("INSERT INTO system_dept(id,name,status,deleted,tenant_id) VALUES(?,'资料测试门店',0,0,?) ON CONFLICT(id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id", company, tenant);
        TenantContextHolder.setTenantId(tenant); currentPost.use(sales); storage.beforeRead = null;
    }
    @AfterEach void clearTenant() { storage.beforeRead = null; TenantContextHolder.clear(); }

    @Test void optionalMaterialsHaveTheirOwnHttpCollectionForEveryStep() throws Exception {
        long id=customer();
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new CustomerWorkflowController(workflow,currentPost),new CustomerWorkflowSupplementController(supplements,currentPost)).build();
        for (var step:CustomerWorkflowModel.Step.values()) {
            var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/franchise/customers/"+id+"/workflow/steps/"+step.name()+"/supplements").param("pageNo","1").param("pageSize","20")).andReturn().getResponse();
            assertThat(response.getStatus()).as(step.name()).isEqualTo(200);
            assertThat(cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseTree(response.getContentAsString()).at("/data/total").asLong(-1)).isZero();
        }
        assertThat(workflow.get(sales,id).revision()).isZero();
    }

    @Test void supplementRowsKeepTheOriginalCustomerStepFilesAndNeverAdvanceTheWorkflow() throws Exception {
        long id=customer(),otherId=customer(),pdf=upload(PDF,"application/pdf"),image=upload(png(),"image/png");
        var docs=List.of(new CustomerWorkflowModel.Attachment(Long.toString(pdf),"户型.pdf"),
                new CustomerWorkflowModel.Attachment(Long.toString(image),"现场.png"));
        var input=new CustomerWorkflowSupplementService.Input("  仅供参考  ",docs);
        var original=supplements.append(sales,id,"INVITATION",input,"new-supplement");
        assertThat(original.note()).isEqualTo("仅供参考");assertThat(original.actorUserId()).isEqualTo(Long.toString(sales.userId()));
        assertThat(java.time.Instant.parse(original.createdAt())).isBefore(java.time.Instant.now().plusSeconds(1));
        assertThat(supplements.append(sales,id,"INVITATION",input,"new-supplement")).isEqualTo(original);
        denied(()->supplements.append(sales,otherId,"INVITATION",input,"new-supplement"));
        denied(()->supplements.append(sales,id,"START",input,"new-supplement"));
        denied(()->supplements.append(sales,id,"INVITATION",new CustomerWorkflowSupplementService.Input("不同",docs),"new-supplement"));
        denied(()->supplements.append(sales,otherId,"CONTRACT",input,"new-customer-key"));
        denied(()->workflow.save(sales,otherId,"QUOTATION",quote(0,pdf),"old-endpoint-other-customer"));
        denied(()->supplements.append(sales,id,"START",new CustomerWorkflowSupplementService.Input(" ",List.of()),"empty"));
        assertThat(supplements.list(sales,id,"START",1,20).total()).isZero();
        assertThat(supplements.list(sales,id,"INVITATION",1,20).list()).containsExactly(original);
        var before=workflow.get(sales,id);assertThat(before.revision()).isZero();assertThat(before.completedCount()).isZero();
        var command=new CustomerWorkflowModel.SaveCommand(0,LocalDate.now(ZoneId.of("Asia/Shanghai")),"原邀约",
                Map.of("contactMethod","电话","invitationResult","CONFIRMED","appointmentAt","2026-11-26T09:00"),List.of());
        workflow.save(sales,id,"INVITATION",command,"business-save");
        var done=workflow.complete(sales,id,"INVITATION",1,"business-complete");
        var after=supplements.append(sales,id,"INVITATION",new CustomerWorkflowSupplementService.Input("办理后补记",List.of()),"after");
        assertThat(supplements.list(sales,id,"INVITATION",1,1).list()).containsExactly(after);
        assertThat(supplements.list(sales,id,"INVITATION",2,1).list()).containsExactly(original);
        assertThat(workflow.get(sales,id).revision()).isEqualTo(done.revision());
        assertThat(workflow.get(sales,id).completedCount()).isEqualTo(done.completedCount());
        assertThat(as(boss,()->supplements.list(boss,id,"INVITATION",1,20)).canWrite()).isFalse();
        denied(()->as(boss,()->supplements.append(boss,id,"START",input,"readonly")));
        denied(()->as(other,()->supplements.list(other,id,"INVITATION",1,20)));
        denied(()->as(colleague,()->supplements.list(colleague,id,"INVITATION",1,20)));
        var ticket=as(sales,()->workflow.download(sales,id,"INVITATION",pdf));
        assertThat(as(sales,()->assets.readDownload(actor(sales),pdf,token(ticket.downloadUrl()))).bytes()).isEqualTo(PDF);
        denied(()->as(sales,()->workflow.download(sales,id,"CONTRACT",pdf)));
        jdbc.update("UPDATE fr_customer SET owner_user_id=? WHERE id=?",colleague.userId(),id);
        denied(()->as(sales,()->assets.readDownload(actor(sales),pdf,token(ticket.downloadUrl()))));
        denied(()->as(sales,()->supplements.append(sales,id,"INVITATION",input,"new-supplement")));
        assertThat(as(colleague,()->supplements.list(colleague,id,"INVITATION",1,20)).list()).contains(original);
    }

    @Test void simultaneousRequestsCreateOneEntryAndReplayingFromAnotherPostIsRejected() throws Exception {
        long id = customer();
        var input = new CustomerWorkflowSupplementService.Input("同一条现场说明", List.of());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<CustomerWorkflowSupplementService.Entry> request = () -> {
            TenantContextHolder.setTenantId(tenant);
            try {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return supplements.append(sales, id, "MEASUREMENT", input, "same-retry");
            } finally {
                TenantContextHolder.clear();
            }
        };
        try {
            var first = workers.submit(request);
            var second = workers.submit(request);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(first.get(15, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
        var otherAppointment = fixture.postFor(sales.userId(), sales.orgId(), SALES_LEAD, sales.teamId());
        var history = as(otherAppointment, () -> supplements.list(otherAppointment, id, "MEASUREMENT", 1, 20));
        assertThat(history.canWrite()).isTrue();
        assertThat(history.total()).isEqualTo(1);
        assertThatThrownBy(() -> supplements.append(otherAppointment, id, "MEASUREMENT", input, "same-retry"))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(1099000000));
        assertThat(supplements.list(otherAppointment, id, "MEASUREMENT", 1, 20)).isEqualTo(history);
    }

    @Test void realMiniCanAppendToOngoingAndCompletedStepsAndReadOnlyTheOriginalFiles() throws Exception {
        String client=System.getProperty("franchise.customer-supplement.client");
        org.junit.jupiter.api.Assumptions.assumeTrue(client!=null&&!client.isBlank(),"requires the paired mini checkout");
        var directory = FakeSystemDirectory.INSTANCE;
        long user = directory.createUser("黄工","13799221119",sales.teamId());
        var employee = directory.usersByIds(List.of(user)).get(0);
        when(profileStore.findByUserId(user)).thenReturn(
                new StaffProfileStore.UserProfile(user, employee.name(), employee.mobile(), null));
        sales=fixture.postFor(user,sales.orgId(),SALES,sales.teamId());
        currentPost.use(sales);
        long id=customer(),another=customer();
        byte[] photo=png();
        long pdf=upload(PDF,"application/pdf"),image=upload(photo,"image/png");
        long contract=accepted(sales,"CONTRACT_ATTACHMENT","application/pdf",PDF);
        var engineer=fixture.post(sales.orgId(),"engineer",null);
        long sitePhoto=accepted(engineer,"ENG_SITE_PHOTO","image/png",photo);
        long project=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),node=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        LocalDate occurred=LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        jdbc.update("INSERT INTO fr_sale_contract(id,company_id,customer_id,total_cents,first_cents,signed_date,asset_id,status,author_user_id) VALUES(?,?,?,200000,50000,?,?,'SIGNED',?)",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),sales.orgId(),id,occurred,contract,sales.userId());
        jdbc.update("INSERT INTO fr_eng_project(id,company_id,customer_id,source_type,manager_user_id,name,actual_start_at,status,author_user_id) VALUES(?,?,?,'MANUAL',?,'配对施工',?,'IN_PROGRESS',?)",
                project,sales.orgId(),id,engineer.userId(),occurred,engineer.userId());
        jdbc.update("INSERT INTO fr_eng_plan_node(id,company_id,project_id,node_index,name,status,milestone,completed_at) VALUES(?,?,?,1,'开工','DONE','START',?)",
                node,sales.orgId(),project,occurred);
        jdbc.update("INSERT INTO fr_eng_site_record(id,company_id,project_id,node_id,occurred_at,note,completion,result,review_mode,file_asset_ids,author_user_id) VALUES(?,?,?,?,?,'实际开工',true,'PASS','SINGLE_PERSON_RECORD',CAST(? AS jsonb),?)",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),sales.orgId(),project,node,occurred,"["+sitePhoto+"]",engineer.userId());
        var before=as(sales,()->workflow.get(sales,id));
        assertThat(before.completedCount()).isEqualTo(2);
        var builder=org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        new cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration().ldtEpochMillisCustomizer().customize(builder);
        var json=builder.build();
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new CustomerWorkflowController(workflow,currentPost), new CustomerWorkflowSupplementController(supplements,currentPost),
                new CustomerController(customers,currentPost,directory), new CustomerWorkflowQuotesController(quotes,currentPost),
                profiles)
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json)).build();
        var actors=Map.of("sales",sales,"readonly",boss,"otherPost",other);
        Map<String,Object> input=new java.util.LinkedHashMap<>();
        actors.forEach((role,post)->input.put(role,Map.of("postId",Long.toString(post.postId()),"userId",Long.toString(post.userId()),
                "orgId",Long.toString(post.orgId()),"tenantId",Long.toString(tenant),"orgType",post.orgType(),"roleCode",post.roleCode())));
        input.put("customerId",Long.toString(id));input.put("otherCustomerId",Long.toString(another));
        input.put("assets",List.of(Map.of("assetId",Long.toString(pdf),"title","沟通图纸.pdf","contentType","application/pdf","sha256",sha(PDF)),
                Map.of("assetId",Long.toString(image),"title","现场补录.png","contentType","image/png","sha256",sha(photo))));
        input.put("stepCodes",java.util.Arrays.stream(CustomerWorkflowModel.Step.values()).map(Enum::name).toList());
        var result=CustomerSupplementPair.run(client,input,actors,currentPost,assets,mvc,json);
        assertThat(result.path("stages").asInt()).isEqualTo(8);
        assertThat(result.path("writes").asInt()).isEqualTo(3);assertThat(result.path("authorizedDownloads").asInt()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_customer_step_supplement WHERE customer_id=?",Long.class,id)).isEqualTo(2L);
        var after=as(sales,()->workflow.get(sales,id));
        assertThat(after).isEqualTo(before);
        assertThat(as(sales,()->supplements.list(sales,id,"START",1,20)).list()).singleElement().satisfies(row->{
            assertThat(row.actorName()).isEqualTo("黄工");assertThat(row.attachments().get(0).assetId()).isEqualTo(Long.toString(image));
        });
    }

    private long accepted(PostSnapshot post,String type,String mime,byte[] bytes) throws Exception {
        currentPost.use(post);
        var ticket=channel.uploadTicket(post,type,mime,bytes.length,sha(bytes));
        assets.receiveUpload(actor(post),ticket.assetId(),token(ticket.uploadUrl()),new ByteArrayInputStream(bytes));
        assertThat(channel.completeUpload(post,ticket.assetId()).uploadStatus()).isEqualTo("ACCEPTED");
        return ticket.assetId();
    }

    private static byte[] png() throws java.io.IOException {
        var encoded=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",encoded);
        return encoded.toByteArray();
    }

    @Test void acceptedDocumentIsPrivateBeforeAttachmentAndFollowsCustomerVisibilityAfterward() throws Exception {
        long id = upload(PDF, "application/pdf"), customer = customer();
        denied(() -> as(colleague, () -> channel.createDownloadUrl(colleague, id)));
        denied(() -> as(boss, () -> channel.createDownloadUrl(boss, id)));
        as(sales, () -> workflow.save(sales, customer, "QUOTATION", quote(0, id), "link"));
        assertThat(as(lead, () -> channel.createDownloadUrl(lead, id)).downloadUrl()).contains("/" + id + "/content");
        assertThat(as(boss, () -> workflow.download(boss, customer, "QUOTATION", id)).downloadUrl()).isNotBlank();
        denied(() -> as(colleague, () -> channel.createDownloadUrl(colleague, id)));
        denied(() -> as(colleague, () -> assets.validateReference(actor(colleague), id, Set.of("BUSINESS_DOCUMENT"))));
        denied(() -> as(other, () -> channel.createDownloadUrl(other, id)));
        denied(() -> as(sales, () -> workflow.download(sales, customer, "MEASUREMENT", id)));
        TenantContextHolder.setTenantId(tenant + 100);
        denied(() -> as(sales, () -> channel.createDownloadUrl(sales, id)));
    }

    @Test void replacingAttachmentRetainsHistoricalDownloadAndCustomerOwnership() throws Exception {
        long customer = customer(), first = upload(PDF, "application/pdf"), second = upload(PDF, "application/pdf");
        as(sales, () -> workflow.save(sales, customer, "QUOTATION", quote(0, first), "v1"));
        as(sales, () -> workflow.save(sales, customer, "QUOTATION", quote(1, second), "v2"));
        var result = as(sales, () -> workflow.download(sales, customer, "QUOTATION", first));
        var bytes = as(sales, () -> assets.readDownload(actor(sales), first, token(result.downloadUrl())));
        assertThat(bytes.bytes()).isEqualTo(PDF); assertThat(bytes.mimeType()).isEqualTo("application/pdf");
        assertThat(as(sales, () -> workflow.get(sales, customer)).steps().get(5).attachments().get(0).assetId()).isEqualTo(Long.toString(second));
        assertThat(as(sales, () -> workflow.records(sales, customer, 1, 20)).getList().get(1).attachments().get(0).assetId()).isEqualTo(Long.toString(first));
        long otherCustomer = customer();
        denied(() -> as(sales, () -> workflow.save(sales, otherCustomer, "QUOTATION", quote(0, first), "wrong-owner")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_customer_workflow WHERE customer_id=?", Long.class, otherCustomer)).isZero();
    }

    @Test void bossCanReadOnlyCustomerBoundLegacyDesignReferencesThroughTheWorkflowScope() throws Exception {
        long customer = customer();
        var encoded = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", encoded);
        byte[] png = encoded.toByteArray();
        var upload = channel.uploadTicket(sales, "DESIGN_REFERENCE", "image/png", png.length, sha(png));
        assets.receiveUpload(actor(sales), upload.assetId(), token(upload.uploadUrl()), new ByteArrayInputStream(png));
        assertThat(channel.completeUpload(sales, upload.assetId()).uploadStatus()).isEqualTo("ACCEPTED");
        long file = upload.assetId();
        denied(() -> as(boss, () -> assets.createWorkflowDownloadUrl(actor(boss), customer, "HOUSE_SELECTION", file)));
        as(sales, () -> workflow.save(sales, customer, "HOUSE_SELECTION", new CustomerWorkflowModel.SaveCommand(0,
                LocalDate.now(ZoneId.of("Asia/Shanghai")), "旧外部方案", Map.of("selectionResult", "两层庭院", "confirmed", true),
                List.of(new CustomerWorkflowModel.Attachment(Long.toString(file), "旧方案.png"))), "legacy-reference"));
        var ticket = as(boss, () -> workflow.download(boss, customer, "HOUSE_SELECTION", file));
        assertThat(as(boss, () -> assets.readDownload(actor(boss), file, token(ticket.downloadUrl()))).mimeType()).isEqualTo("image/png");
        // The generic role matrix remains closed; unrelated and invisible customer files do not get this exception.
        denied(() -> as(boss, () -> channel.createDownloadUrl(boss, file)));
        denied(() -> as(colleague, () -> assets.createWorkflowDownloadUrl(actor(colleague), customer, "HOUSE_SELECTION", file)));
        denied(() -> as(other, () -> assets.createWorkflowDownloadUrl(actor(other), customer, "HOUSE_SELECTION", file)));
        long unrelated = customer();
        denied(() -> as(boss, () -> assets.createWorkflowDownloadUrl(actor(boss), unrelated, "HOUSE_SELECTION", file)));
    }

    @Test void customerTransferRevokesPreviouslyIssuedTicketAndTheOriginalUploader() throws Exception {
        long customer = customer(), file = upload(PDF, "application/pdf");
        as(sales, () -> workflow.save(sales, customer, "QUOTATION", quote(0, file), "save"));
        String oldToken = token(as(sales, () -> workflow.download(sales, customer, "QUOTATION", file)).downloadUrl());
        jdbc.update("UPDATE fr_customer SET owner_user_id=? WHERE id=?", colleague.userId(), customer);
        denied(() -> as(sales, () -> assets.readDownload(actor(sales), file, oldToken)));
        denied(() -> as(sales, () -> channel.createDownloadUrl(sales, file)));
        var current = as(colleague, () -> workflow.download(colleague, customer, "QUOTATION", file));
        assertThat(as(colleague, () -> assets.readDownload(actor(colleague), file, token(current.downloadUrl()))).bytes()).isEqualTo(PDF);
    }

    @Test void transferDuringObjectReadIsCheckedAgainBeforeReturningPrivateBytes() throws Exception {
        long customer = customer(), file = upload(PDF, "application/pdf");
        as(sales, () -> workflow.save(sales, customer, "QUOTATION", quote(0, file), "save"));
        String token = token(as(sales, () -> workflow.download(sales, customer, "QUOTATION", file)).downloadUrl());
        storage.beforeRead = () -> jdbc.update("UPDATE fr_customer SET owner_user_id=? WHERE id=?", colleague.userId(), customer);
        denied(() -> as(sales, () -> assets.readDownload(actor(sales), file, token)));
    }

    @Test void businessDocumentSizeMimeAndAcceptedDigestAreEnforced() throws Exception {
        denied(() -> channel.uploadTicket(sales, "BUSINESS_DOCUMENT", "application/pdf", (30L << 20) + 1, "0".repeat(64)));
        denied(() -> channel.uploadTicket(sales, "BUSINESS_DOCUMENT", "image/svg+xml", 10, "0".repeat(64)));
        var bad = channel.uploadTicket(sales, "BUSINESS_DOCUMENT", "image/jpeg", PDF.length, sha(PDF));
        assets.receiveUpload(actor(sales), bad.assetId(), token(bad.uploadUrl()), new ByteArrayInputStream(PDF));
        assertThat(channel.completeUpload(sales, bad.assetId()).uploadStatus()).isEqualTo("REJECTED");
        assertThat(assets.validateReference(actor(sales), bad.assetId(), Set.of("BUSINESS_DOCUMENT"))).isFalse();
        long file = upload(PDF, "application/pdf");
        String ticket = token(channel.createDownloadUrl(sales, file).downloadUrl());
        jdbc.update("UPDATE fr_asset SET accepted_sha256=? WHERE id=?", "0".repeat(64), file);
        denied(() -> assets.readDownload(actor(sales), file, ticket));
    }

    @Test void sameCustomerCanReuseOneAssetAcrossStepsAndConcurrentSameKeyRetryWritesOnce() throws Exception {
        long customer = customer(), file = upload(PDF, "application/pdf");
        var invitation = new CustomerWorkflowModel.SaveCommand(0, LocalDate.now(ZoneId.of("Asia/Shanghai")),
                "客户已确认邀约", Map.of("contactMethod", "微信", "invitationResult", "CONFIRMED",
                "appointmentAt", LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1) + "T10:00"),
                List.of(new CustomerWorkflowModel.Attachment(Long.toString(file), "同一资料.pdf")));

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<CustomerWorkflowModel.View> request = () -> {
                TenantContextHolder.setTenantId(tenant);
                try {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("same-key requests did not start");
                    return as(sales, () -> workflow.save(sales, customer, "INVITATION", invitation, "same-customer-asset-key"));
                } finally {
                    TenantContextHolder.clear();
                }
            };
            var first = pool.submit(request);
            var second = pool.submit(request);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var firstResult = first.get(20, TimeUnit.SECONDS);
            var secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(firstResult).isEqualTo(secondResult);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fr_customer_workflow_step s
                    JOIN fr_customer_workflow w ON w.id=s.workflow_id
                    WHERE w.customer_id=? AND s.step_code='INVITATION'
                    """, Long.class, customer)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fr_customer_workflow_event e
                    JOIN fr_customer_workflow w ON w.id=e.workflow_id
                    WHERE w.customer_id=? AND e.step_code='INVITATION' AND e.action='SAVE'
                    """, Long.class, customer)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        // The same private asset may be referenced by another step of the same customer.
        var quotation = quote(1, file);
        as(sales, () -> workflow.save(sales, customer, "QUOTATION", quotation, "same-customer-second-step"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fr_customer_workflow_step s
                JOIN fr_customer_workflow w ON w.id=s.workflow_id
                WHERE w.customer_id=? AND s.attachments @> CAST(? AS jsonb)
                """, Long.class, customer, "[{\\\"assetId\\\":\\\""+file+"\\\"}]")).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT w.customer_id) FROM fr_customer_workflow_step s
                JOIN fr_customer_workflow w ON w.id=s.workflow_id
                WHERE s.attachments @> CAST(? AS jsonb)
                """, Long.class, "[{\\\"assetId\\\":\\\""+file+"\\\"}]")).isEqualTo(1);
    }

    @Test void simultaneousFirstAttachmentCannotBindOnePrivateDocumentToTwoCustomers() throws Exception {
        long first = customer(), second = customer(), file = upload(PDF, "application/pdf");
        currentPost.use(sales); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = List.of(first, second).stream().map(customer -> pool.submit(() -> {
                TenantContextHolder.setTenantId(tenant);
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("requests did not start");
                    workflow.save(sales, customer, "QUOTATION", quote(0, file), "attach-" + customer); return true;
                } catch (ServiceException rejected) { assertThat(rejected.getCode()).isEqualTo(400); return false; }
                finally { TenantContextHolder.clear(); }
            })).toList();
            start.countDown();
            int accepted = 0; for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_customer_workflow WHERE customer_id IN (?,?)", Long.class, first, second)).isEqualTo(1);
    }

    @Test void reversedAttachmentAndDesignSlotAcquireOneOrderAndRejectTheOtherCustomer() throws Exception {
        long first = customer(), second = customer();
        long low = upload(PDF, "application/pdf"), high = upload(PDF, "application/pdf");
        assertThat(low).isLessThan(high);
        long lock = cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys.lockKey(
                new cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys.PostKeyScope("fr-workflow-asset", tenant),
                Long.toString(low));
        var pool = Executors.newFixedThreadPool(2);
        try (var blocker = jdbc.getDataSource().getConnection();
             var acquire = blocker.prepareStatement("SELECT pg_advisory_lock(?)");
             var release = blocker.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            acquire.setLong(1, lock); acquire.execute();
            java.util.concurrent.Future<Boolean> lowerFirst, higherFirst;
            try {
                // Queue the low attachment first, then the opposite attachment/slot pair. With split
                // locking, the second request holds high while waiting for low, creating a real PG cycle.
                lowerFirst = pool.submit(() -> saveMixedAssets(first, low, high));
                awaitAssetWaiters(lock, 1);
                higherFirst = pool.submit(() -> saveMixedAssets(second, high, low));
                awaitAssetWaiters(lock, 2);
            } finally {
                release.setLong(1, lock); release.execute();
            }
            assertThat(List.of(lowerFirst.get(20, TimeUnit.SECONDS), higherFirst.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_customer_workflow WHERE customer_id IN (?,?)", Long.class, first, second)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fr_customer_workflow_step s JOIN fr_customer_workflow w ON w.id=s.workflow_id
                WHERE w.customer_id IN (?,?) AND s.step_code='QUOTATION'
                """, Long.class, first, second)).isEqualTo(1);
    }

    private boolean saveMixedAssets(long customer, long attachment, long slot) {
        TenantContextHolder.setTenantId(tenant);
        try {
            var original = quote(0, attachment);
            var fields = new java.util.HashMap<>(original.fields());
            fields.put("designFileAssetId", Long.toString(slot)); fields.put("designFileName", "设计方案.pdf");
            as(sales, () -> workflow.save(sales, customer, "QUOTATION",
                    new CustomerWorkflowModel.SaveCommand(0, original.occurredOn(), original.note(), fields, original.attachments()),
                    "mixed-assets-" + customer));
            return true;
        } catch (ServiceException rejected) {
            assertThat(rejected.getCode()).isEqualTo(400); return false;
        } finally { TenantContextHolder.clear(); }
    }

    private void awaitAssetWaiters(long lock, int count) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(8)).until(() ->
                jdbc.queryForObject("""
                        SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted
                          AND classid::bigint=? AND objid::bigint=? AND objsubid=1
                          AND database=(SELECT oid FROM pg_database WHERE datname=current_database())
                        """, Integer.class, lock >>> 32, lock & 0xffffffffL) == count);
    }

    private long customer() {
        return as(sales, () -> leads.capture(sales, new LeadService.CaptureCommand("王先生", Long.toString(PHONES.incrementAndGet()), "自建房", "到店"), null).customerId());
    }
    private long upload(byte[] bytes, String mime) throws Exception {
        currentPost.use(sales);
        var ticket = channel.uploadTicket(sales, "BUSINESS_DOCUMENT", mime, bytes.length, sha(bytes));
        assets.receiveUpload(actor(sales), ticket.assetId(), token(ticket.uploadUrl()), new ByteArrayInputStream(bytes));
        assertThat(channel.completeUpload(sales, ticket.assetId()).uploadStatus()).isEqualTo("ACCEPTED");
        return ticket.assetId();
    }
    private CustomerWorkflowModel.SaveCommand quote(long revision, long asset) {
        return new CustomerWorkflowModel.SaveCommand(revision, LocalDate.now(ZoneId.of("Asia/Shanghai")), "外部报价",
                Map.of("amountCents", "18000000", "scope", "主体土建", "versionLabel", "V1", "result", "已说明", "communicated", true),
                List.of(new CustomerWorkflowModel.Attachment(Long.toString(asset), "正式报价.pdf")));
    }
    private AssetAccessPort.Actor actor(PostSnapshot post) { return new AssetAccessPort.Actor(post.orgId(), post.orgId() + 1_000_000L, post.userId(), post.postId()); }
    private <T> T as(PostSnapshot post, Supplier<T> body) { currentPost.use(post); return body.get(); }
    private static void denied(Runnable body) { assertThatThrownBy(body::run).isInstanceOf(ServiceException.class); }
    private static String token(String url) { return url.substring(url.indexOf("token=") + 6); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
