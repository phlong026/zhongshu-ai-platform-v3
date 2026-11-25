package cn.iocoder.yudao.module.platform.headquarters;

import cn.iocoder.yudao.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.logger.dto.OperateLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.HeadquartersDocumentAccessPort.Request;
import cn.iocoder.yudao.module.franchise.api.PayrollReadPort;
import cn.iocoder.yudao.module.platform.headquarters.controller.admin.HeadquartersPayrollController;
import cn.iocoder.yudao.module.franchise.api.HeadquartersDocumentAccessPort.Resource;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowAssetPolicy;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowReadService;
import cn.iocoder.yudao.module.franchise.finance.FinWorkbenchService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.module.platform.headquarters.HeadquartersReadModel.Filter;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL ownership joins, workflow references, finance rows, exports and transaction boundaries. */
@TestPropertySource(properties = {"platform.test.extra-flyway-locations=,classpath:db/migration/franchise,classpath:db/migration/platform-wallet,classpath:db/migration/franchise-asset",
        "platform.legacy-design-bridge.enabled=false"})
@Import({HeadquartersReadGuard.class, HeadquartersReadService.class, HeadquartersExportService.class,
        HeadquartersDocumentAccess.class, CustomerWorkflowReadService.class, CustomerWorkflowAssetPolicy.class,
        HeadquartersReadIntegrationTest.Config.class})
class HeadquartersReadIntegrationTest extends PlatformPgIntegrationTest {
    @TestConfiguration
    static class Config {
        @Bean CurrentPost posts() { return mock(CurrentPost.class); }
        @Bean OperateLogCommonApi audit() { return mock(OperateLogCommonApi.class); }
        @Bean SystemDirectory directory() {
            var directory = mock(SystemDirectory.class);
            when(directory.usersByIds(anyList())).thenReturn(List.of());
            return directory;
        }
        @Bean FinWorkbenchService finance(JdbcTemplate jdbc) { return new FinWorkbenchService(jdbc, null, null, null); }
    }

    @jakarta.annotation.Resource JdbcTemplate jdbc;
    @jakarta.annotation.Resource CurrentPost posts;
    @jakarta.annotation.Resource OperateLogCommonApi audit;
    @jakarta.annotation.Resource HeadquartersReadGuard guard;
    @jakarta.annotation.Resource HeadquartersReadService reads;
    @jakarta.annotation.Resource HeadquartersExportService exports;
    @jakarta.annotation.Resource HeadquartersDocumentAccess documents;
    @jakarta.annotation.Resource PlatformTransactionManager transactions;
    private static final AtomicLong IDS = new AtomicLong(38_000_000);
    private long tenant, foreignTenant, headquarters, companyA, companyB, peerCompany, foreignCompany;
    private long user, customerA, customerB, peerCustomer, foreignCustomer, brandA, brandB;
    private PostSnapshot admin;

    @BeforeEach
    void setup() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_users(id bigint PRIMARY KEY,tenant_id bigint,nickname text,mobile text,username text,password text,status int DEFAULT 0,deleted int2 DEFAULT 0)");
        jdbc.update("INSERT INTO pf_org_type(code,domain,name) VALUES('headquarters','franchise','总部'),('store','franchise','门店') ON CONFLICT DO NOTHING");
        for (String role : List.of("hq-admin", "finance", "finance-lead", "hq-design-admin", "boss", "sales"))
            jdbc.update("INSERT INTO pf_post_template(role_code,domain,org_types,lead) VALUES(?,'franchise','headquarters,store',false) ON CONFLICT DO NOTHING", role);
        tenant = id(); foreignTenant = id();
        headquarters = organization(tenant, "headquarters", null);
        companyA = organization(tenant, "store", "合家美宅");
        companyB = organization(tenant, "store", "金宅子");
        peerCompany = organization(tenant, "store", "合家美宅");
        foreignCompany = organization(foreignTenant, "store", "合家美宅");
        user = user(tenant);
        admin = post(user, headquarters, "headquarters", "hq-admin");
        brandA = jdbc.queryForObject("SELECT id FROM pf_map_brand WHERE tenant_id=? AND name='合家美宅'", Long.class, tenant);
        brandB = jdbc.queryForObject("SELECT id FROM pf_map_brand WHERE tenant_id=? AND name='金宅子'", Long.class, tenant);
        customerA = customer(companyA, "甲公司客户");
        customerB = customer(companyB, "乙公司客户");
        peerCustomer = customer(peerCompany, "同品牌同行客户");
        foreignCustomer = customer(foreignCompany, "外租户秘密");
        reset(audit);
        actor(admin, tenant);
    }

    @AfterEach void clear() { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }

    @Test
    void headquartersPayrollUsesExplicitCompanyAndRealHeadquartersGuardsForAllReadRoutes() throws Exception {
        var port=mock(PayrollReadPort.class);
        when(port.rulesAuthorized(anyLong(),anyLong(),nullable(String.class),anyInt(),anyInt())).thenReturn(Map.of("list",List.of()));
        when(port.recordsAuthorized(anyLong(),anyLong(),nullable(String.class),nullable(String.class),anyInt(),anyInt())).thenAnswer(call -> {
            assertThat(posts.require().orgId()).isEqualTo(headquarters);
            assertThat(TenantContextHolder.getTenantId()).isEqualTo(tenant);
            return Map.of("list",List.of(Map.of("companyId",Long.toString(call.getArgument(1,Long.class)))));
        });
        when(port.recordAuthorized(anyLong(),anyLong(),anyLong())).thenReturn(Map.of("id","8801"));
        when(port.historyAuthorized(anyLong(),anyLong(),anyLong(),anyInt(),anyInt())).thenReturn(Map.of("list",List.of()));
        var controller=new HeadquartersPayrollController(guard,port);
        for(String role:List.of("hq-admin","finance","finance-lead")) {
            actor(role.equals("hq-admin")?admin:post(user,headquarters,"headquarters",role),tenant);
            for(long company:List.of(companyA,companyB)) {
                var response=controller.records(company,"2026-11","BUSINESS",1,20);
                assertThat(response.getHeaders().getCacheControl()).contains("no-store");
                assertThat(response.getBody().getData()).containsEntry("readOnly",true);
                assertThat(controller.rules(company,"ENGINEERING",1,20).getBody().getData()).containsEntry("readOnly",true);
                assertThat(controller.record(company,8801).getBody().getData()).containsEntry("id","8801").containsEntry("readOnly",true);
                assertThat(controller.history(company,8801,1,20).getBody().getData()).containsEntry("readOnly",true);
            }
        }
        // Missing HTTP filters must stay null: the payroll port treats null as unrestricted, never the empty string.
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/platform/headquarters/companies/"+companyA+"/payroll/rules"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.readOnly").value(true));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/platform/headquarters/companies/"+companyA+"/payroll/records"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.readOnly").value(true));
        verify(port).rulesAuthorized(tenant,companyA,null,1,20);
        verify(port).recordsAuthorized(tenant,companyA,null,null,1,20);
        code(404,()->controller.records(foreignCompany,"2026-11","BUSINESS",1,20));
        verify(port,never()).recordsAuthorized(anyLong(),eq(foreignCompany),anyString(),anyString(),anyInt(),anyInt());
        for(var invalid:List.of(post(user,companyA,"store","finance"),post(user,headquarters,"headquarters","hq-design-admin"))) {
            actor(invalid,tenant);code(403,()->controller.rules(companyB,"BUSINESS",1,20));
        }
        actor(admin,foreignTenant);code(403,()->controller.records(companyA,"2026-11","BUSINESS",1,20));
    }

    @Test
    void payrollRechecksCompanyAndPostBeforeReturningTheSalaryResult() {
        var port=mock(PayrollReadPort.class);
        var controller=new HeadquartersPayrollController(guard,port);
        when(port.recordsAuthorized(tenant,companyB,"2026-11","BUSINESS",1,20)).thenAnswer(call -> {
            jdbc.update("UPDATE system_dept SET status=1 WHERE id=?",companyB);
            return Map.of("privateSalary",500000L);
        });
        code(403,()->controller.records(companyB,"2026-11","BUSINESS",1,20));
        when(port.recordAuthorized(tenant,companyA,8801)).thenAnswer(call -> {
            jdbc.update("UPDATE pf_staff_post SET status='REVOKED',revoked_at=now() WHERE id=?",admin.postId());
            return Map.of("privateSalary",500000L);
        });
        code(403,()->controller.record(companyA,8801));
    }

    @Test
    void allThreeHeadquartersRolesReadAllBrandsOnlyInsideTheirActualTenant() {
        for (String role : List.of("hq-admin", "finance", "finance-lead")) {
            var reader = role.equals("hq-admin") ? admin : post(user, headquarters, "headquarters", role);
            actor(reader, tenant);
            var context = reads.context();
            assertThat(context.readOnly()).isTrue();
            assertThat(context.brands()).extracting(row -> row.get("id")).containsExactlyInAnyOrder(s(brandA), s(brandB));
            assertThat(context.companies()).extracting(row -> row.get("id")).contains(s(companyA), s(companyB), s(peerCompany)).doesNotContain(s(foreignCompany));
            var page = reads.page("customers", Filter.all());
            assertThat(page.total()).isEqualTo(3);
            assertThat(page.list()).extracting(row -> row.get("id")).containsExactlyInAnyOrder(s(customerA), s(customerB), s(peerCustomer));
            assertThat(page.readOnly()).isTrue();
            assertThat(reads.workflow(customerB).canWrite()).isFalse();
            assertThat(reads.workflow(customerB).steps()).allSatisfy(step -> {
                assertThat(step.canWrite()).isFalse(); assertThat(step.canComplete()).isFalse(); assertThat(step.canReopen()).isFalse();
            });
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_customer_workflow WHERE tenant_id=?", Long.class, tenant)).isZero();
        code(404, () -> reads.detail("customers", foreignCustomer));
        code(404, () -> reads.page("customers", filter(null, foreignCompany, 1, 20)));
        code(404, () -> reads.ledger(foreignCompany, "company"));
    }

    @Test
    void storeFinanceOtherHeadquartersRolesForgedPostAndTenantSwitchAreDenied() {
        actor(post(user, companyA, "store", "finance"), tenant);
        code(403, reads::context);
        code(403, () -> exports.export("customers", Filter.all()));
        actor(post(user, headquarters, "headquarters", "hq-design-admin"), tenant);
        code(403, reads::context);
        actor(new PostSnapshot(id(), user, headquarters, "headquarters", "hq-admin", null, false, Set.of()), tenant);
        code(403, reads::context);
        var storePost = post(user, companyB, "store", "hq-admin");
        actor(new PostSnapshot(storePost.postId(), user, companyB, "headquarters", "hq-admin", null, false, Set.of()), tenant);
        code(403, reads::context);
        actor(admin, foreignTenant);
        code(403, reads::context);
        actor(admin, tenant);
        jdbc.update("UPDATE pf_staff_post SET status='REVOKED',revoked_at=now() WHERE id=?", admin.postId());
        code(403, reads::context);
    }

    @Test
    void authenticatedUserDisabledAccountAndDisabledCompanyCannotUseAStaleSnapshot() {
        actor(admin, tenant);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser().setId(user(tenant)).setUserType(2).setTenantId(tenant), null, List.of()));
        code(403, reads::context);
        actor(admin, tenant);
        jdbc.update("UPDATE system_users SET status=1 WHERE id=?", user);
        code(403, reads::context);
        jdbc.update("UPDATE system_users SET status=0 WHERE id=?", user);
        jdbc.update("UPDATE system_dept SET status=1 WHERE id=?", companyA);
        code(404, () -> reads.detail("customers", customerA));
        jdbc.update("UPDATE system_dept SET tenant_id=? WHERE id=?", foreignTenant, headquarters);
        code(403, reads::context);
    }

    @Test
    void brandAndCompanyFiltersNeverWidenForUnassignedOrAmbiguousMappings() {
        assertThat(reads.page("customers", filter(brandA, null, 1, 20)).total()).isEqualTo(2);
        assertThat(reads.page("customers", filter(brandB, companyA, 1, 20)).total()).isZero();
        long unassigned = organization(tenant, "store", null), unassignedCustomer = customer(unassigned, "未配置品牌客户");
        // Model an unlinked historical directory projection, while retaining its real BOSS account/location.
        // A new directory link must still satisfy the tenant/org unique constraint; no trigger is disabled.
        jdbc.update("UPDATE pf_franchise_directory SET org_id=NULL WHERE tenant_id=? AND org_id=?", tenant, companyA);
        account(companyA, tenant, "金宅子");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_franchise_directory WHERE tenant_id=? AND org_id=?",
                Long.class, tenant, companyA)).isEqualTo(1);
        assertThat(reads.context().companies()).filteredOn(row -> s(companyA).equals(row.get("id")))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("brandState", "AMBIGUOUS").containsEntry("brandId", null));
        assertThat(reads.page("customers", filter(brandA, null, 1, 20)).list()).extracting(row -> row.get("id")).containsExactly(s(peerCustomer));
        assertThat(reads.page("customers", filter(brandB, null, 1, 20)).list()).extracting(row -> row.get("id")).containsExactly(s(customerB));
        assertThat(reads.page("customers", Filter.all()).list()).extracting(row -> row.get("id"))
                .contains(s(customerA), s(unassignedCustomer)).doesNotContain(s(foreignCustomer));
        long foreignBrand = jdbc.queryForObject("SELECT id FROM pf_map_brand WHERE tenant_id=? LIMIT 1", Long.class, foreignTenant);
        assertThat(reads.page("customers", filter(foreignBrand, null, 1, 20)).total()).isZero();
    }

    @Test
    void paginationIsStableAndKeywordWildcardsRemainLiteral() {
        var first = reads.page("customers", filter(null, null, 1, 1));
        var second = reads.page("customers", filter(null, null, 2, 1));
        assertThat(first.total()).isEqualTo(3); assertThat(second.total()).isEqualTo(3);
        assertThat(first.list().get(0).get("id")).isNotEqualTo(second.list().get(0).get("id"));
        jdbc.update("UPDATE fr_customer SET name='100%_已确认' WHERE id=?", customerA);
        var literal = new Filter(null, null, "%_", "", null, null, 1, 20);
        assertThat(reads.page("customers", literal).list()).extracting(row -> row.get("id")).containsExactly(s(customerA));
        code(400, () -> reads.page("customers", filter(null, null, 0, 20)));
        code(400, () -> reads.page("customers", filter(null, null, 1, 101)));
        code(400, () -> reads.page("customers; DROP TABLE fr_customer", Filter.all()));
        code(400, () -> reads.workflowRecords(customerA, 0, 20));
    }

    @Test
    void financeUsesCanonicalCompanyTenantAndDoesNotJoinOtherCompaniesCounterparties() {
        long ownReceipt = receipt(companyA, "本公司收款", 5000, 9001), otherReceipt = receipt(foreignCompany, "外租户收款", 9000, 9002);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM fr_fin_receipt WHERE id=?", Long.class, ownReceipt)).isEqualTo(1);
        assertThat(reads.page("receipts", Filter.all()).list()).extracting(row -> row.get("id")).contains(s(ownReceipt)).doesNotContain(s(otherReceipt));
        assertThat(reads.detail("receipts", ownReceipt)).containsEntry("companyId", s(companyA)).containsEntry("assetId", "9001");
        long payable = id(), payment = id();
        jdbc.update("INSERT INTO fr_fin_payable(id,company_id,stage,total_cents,recipient) VALUES(?,?,'外租户应付',5000,'外租户供应商秘密')", payable, foreignCompany);
        jdbc.update("INSERT INTO fr_fin_payment(id,company_id,payable_id,amount_cents,operator_user_id) VALUES(?,?,?,?,?)", payment, companyA, payable, 300, user);
        assertThat(reads.detail("payments", payment)).containsEntry("counterparty", null).containsEntry("name", "付款登记");
        assertThat(reads.books(companyA, "")).isNotEmpty();
        assertThat(reads.ledger(companyA, "company")).isNotNull();
        code(404, () -> reads.detail("receipts", otherReceipt));
        code(404, () -> documents.requireReadable(new Request(Resource.RECEIPT, otherReceipt, "VOUCHER", 9002)));
        assertThat(documents.requireReadable(new Request(Resource.RECEIPT, ownReceipt, "VOUCHER", 9001)).ownerOrgId()).isEqualTo(companyA);
        code(404, () -> documents.requireReadable(new Request(Resource.RECEIPT, ownReceipt, "VOUCHER", 9002)));
    }

    @Test
    void workflowAttachmentsRemainReadableThroughHistoryButNeverAnotherCustomerOrStep() {
        long workflow = id();
        jdbc.update("INSERT INTO fr_customer_workflow(id,tenant_id,company_id,customer_id,revision) VALUES(?,?,?,?,1)", workflow, tenant, companyA, customerA);
        jdbc.update("INSERT INTO fr_customer_workflow_event(id,workflow_id,tenant_id,company_id,step_code,action,revision,attachments,actor_user_id,actor_post_id) "
                + "VALUES(?,?,?,?,'HOUSE_SELECTION','SAVE',1,CAST(? AS jsonb),?,?)", id(), workflow, tenant, companyA, "[{\"assetId\":\"9010\",\"title\":\"历史图纸\"}]", user, admin.postId());
        var request = new Request(Resource.CUSTOMER, customerA, "HOUSE_SELECTION", 9010);
        var scope = documents.requireReadable(request);
        assertThat(scope.tenantId()).isEqualTo(tenant); assertThat(scope.ownerOrgId()).isEqualTo(companyA);
        assertThat(scope.readerPostId()).isEqualTo(admin.postId());
        assertThat(reads.workflowRecords(customerA, 1, 20).getTotal()).isEqualTo(1);
        assertThatThrownBy(() -> documents.requireReadable(new Request(Resource.CUSTOMER, customerA, "QUOTATION", 9010))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> documents.requireReadable(new Request(Resource.CUSTOMER, customerB, "HOUSE_SELECTION", 9010))).isInstanceOf(ServiceException.class);
        code(404, () -> documents.requireReadable(new Request(Resource.CUSTOMER, foreignCustomer, "HOUSE_SELECTION", 9010)));
        actor(post(user, companyA, "store", "boss"), tenant);
        code(403, () -> documents.requireReadable(request));
    }

    @Test
    void signedContractFilesAndProjectDocumentsUseActualRelationsWithoutReturningRawUrls() {
        long contract = contract(companyA, customerA, 9020), project = project(companyA, customerA, contract);
        assertThat(documents.requireReadable(new Request(Resource.CUSTOMER, customerA, "CONTRACT", 9020)).allowedTypes()).containsExactly("CONTRACT_ATTACHMENT");
        code(404, () -> documents.requireReadable(new Request(Resource.CUSTOMER, customerB, "CONTRACT", 9020)));
        assertThat(documents.requireReadable(new Request(Resource.PROJECT, project, "CONTRACT", 9020)).ownerOrgId()).isEqualTo(companyA);
        long localDocument = document(companyA, project, "图纸", "9021");
        long external = document(companyA, project, "待迁移历史资料", "https://private.invalid/path?signature=secret");
        for (int i = 0; i < 23; i++) document(companyA, project, "资料" + i, "9021");
        var page = reads.projectItems(project, "documents", 1, 20);
        assertThat(page.total()).isEqualTo(25); assertThat(page.list()).hasSize(20);
        var next = reads.projectItems(project, "documents", 2, 20);
        assertThat(next.list()).hasSize(5);
        assertThat(JsonUtils.toJsonString(next)).doesNotContain("private.invalid", "signature", "file_ref", "reference");
        assertThat(next.list()).filteredOn(row -> s(external).equals(row.get("id"))).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("assetId", null).containsEntry("downloadAvailable", false));
        assertThat(documents.requireReadable(new Request(Resource.PROJECT, project, "DOCUMENT", localDocument)).assetId()).isEqualTo(9021);
        code(404, () -> documents.requireReadable(new Request(Resource.PROJECT, project, "DOCUMENT", external)));
        jdbc.update("UPDATE fr_sale_contract SET status='VOIDED' WHERE id=?", contract);
        code(404, () -> documents.requireReadable(new Request(Resource.CUSTOMER, customerA, "CONTRACT", 9020)));
    }

    @Test
    void historicalDrawingOwnerComesFromTheDispatchAndBothCompaniesMustBelongToTheTenant() {
        long contract = contract(companyA, customerA, 9020), project = project(companyA, customerA, contract), commission = id();
        jdbc.update("INSERT INTO fr_commission(id,company_id,design_org_id,customer_id,customer_name,status,submission_version,dispatch_seq) "
                + "VALUES(?,?,?,?,'客户','ACCEPTED',1,1)", commission, companyA, headquarters, customerA);
        jdbc.update("UPDATE fr_eng_project SET source_commission_id=? WHERE id=?", commission, project);
        jdbc.update("INSERT INTO fr_commission_dispatch_manifest(id,commission_id,dispatch_seq,submission_version,design_org_id,"
                + "result_asset_id,drawing_asset_id,review_asset_id,drawing_version,actor_user_id,actor_post_id) VALUES(?,?,1,1,?,9101,9102,9103,'v1',?,?)",
                id(), commission, headquarters, user, admin.postId());
        String reference = JsonUtils.toJsonString(Map.of("commissionId", s(commission), "dispatchSeq", 1, "slot", "DRAWING", "assetId", "9102"));
        long document = document(companyA, project, "历史交底图纸", reference);
        var request = new Request(Resource.PROJECT, project, "DOCUMENT", document);
        var scope = documents.requireReadable(request);
        assertThat(scope.ownerOrgId()).isEqualTo(headquarters); assertThat(scope.assetId()).isEqualTo(9102);
        assertThat(scope.allowedTypes()).containsExactly("DESIGN_DELIVERABLE");
        jdbc.update("UPDATE fr_eng_document SET file_ref=? WHERE id=?", reference.replace("9102", "9103"), document);
        code(404, () -> documents.requireReadable(request));
        jdbc.update("UPDATE fr_eng_document SET file_ref=? WHERE id=?", reference, document);
        jdbc.update("UPDATE fr_commission_dispatch_manifest SET design_org_id=? WHERE commission_id=?", foreignCompany, commission);
        code(404, () -> documents.requireReadable(request));
        jdbc.update("UPDATE fr_commission_dispatch_manifest SET design_org_id=? WHERE commission_id=?", headquarters, commission);
        jdbc.update("UPDATE fr_commission SET company_id=? WHERE id=?", foreignCompany, commission);
        code(404, () -> documents.requireReadable(request));
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectPaymentReadReturnsEveryActualStageAndUsesSameCompanyNetReceipts() {
        long contract = contract(companyA, customerA, 9030), project = project(companyA, customerA, contract);
        long first = 0;
        for (String stage : List.of("first", "tail", "SIGN_AFTER", "BASE_DONE", "CAP_DONE")) {
            long stageId = id(); if (first == 0) first = stageId;
            jdbc.update("INSERT INTO fr_sale_payment_stage(id,company_id,customer_id,contract_id,stage,amount_cents) VALUES(?,?,?,?,?,1000)",
                    stageId, companyA, customerA, contract, stage);
        }
        long receipt = receipt(companyA, "本期收款", 800, 0);
        jdbc.update("INSERT INTO fr_sale_stage_receipt(id,company_id,contract_id,stage_id,receipt_id,amount_cents) VALUES(?,?,?,?,?,800)", id(), companyA, contract, first, receipt);
        jdbc.update("INSERT INTO fr_fin_refund(id,company_id,receipt_id,amount_cents,reason,operator_user_id) VALUES(?,?,?,100,'退回',?)", id(), companyA, receipt, user);
        jdbc.update("INSERT INTO fr_fin_refund(id,company_id,receipt_id,amount_cents,reason,operator_user_id) VALUES(?,?,?,123,'不应计入的异公司关系',?)", id(), foreignCompany, receipt, user);
        var detail = reads.detail("projects", project);
        var stages = (List<Map<String,Object>>) detail.get("paymentStages");
        assertThat(stages).hasSize(5);
        String firstId = s(first);
        assertThat(stages).filteredOn(row -> firstId.equals(row.get("stageId"))).singleElement().satisfies(row -> {
            assertThat(((Number)row.get("grossCents")).longValue()).isEqualTo(800);
            assertThat(((Number)row.get("refundedCents")).longValue()).isEqualTo(100);
            assertThat(((Number)row.get("collectedCents")).longValue()).isEqualTo(700);
            assertThat(((Number)row.get("balanceCents")).longValue()).isEqualTo(300);
        });
        assertThat(((Map<String,Object>) detail.get("saleContract")).get("id")).isEqualTo(s(contract));
    }

    @Test
    void exportUsesTheSameFiltersEscapesFormulasAndRequiresAuditBeforeBytes() {
        jdbc.update("UPDATE fr_customer SET name='=HYPERLINK(\"secret\")' WHERE id=?", customerA);
        var filtered = filter(brandA, companyA, 1, 20);
        String csv = new String(exports.export("customers", filtered), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFF").contains("'=HYPERLINK").doesNotContain("乙公司客户", "同品牌同行客户", "外租户秘密");
        var logged = ArgumentCaptor.forClass(OperateLogCreateReqDTO.class);
        verify(audit).createOperateLog(logged.capture());
        assertThat(logged.getValue().getUserId()).isEqualTo(user);
        assertThat(logged.getValue().getExtra()).contains(s(tenant), s(admin.postId())).doesNotContain("HYPERLINK");
        reset(audit); doThrow(new IllegalStateException("audit unavailable")).when(audit).createOperateLog(any());
        assertThatThrownBy(() -> exports.export("customers", filtered)).hasMessageContaining("audit unavailable");
    }

    @Test
    void exportRevocationDuringAuditIsObservedOutsideTheOldReadSnapshot() {
        doAnswer(call -> {
            var tx = new TransactionTemplate(transactions);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(status -> jdbc.update("UPDATE pf_staff_post SET status='REVOKED',revoked_at=now() WHERE id=?", admin.postId()));
            return null;
        }).when(audit).createOperateLog(any());
        code(403, () -> exports.export("customers", filter(null, companyA, 1, 20)));
    }

    @Test
    void exportCompanyTransferDuringAuditIsObservedOutsideTheOldReadSnapshot() {
        doAnswer(call -> {
            var tx = new TransactionTemplate(transactions);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(status -> jdbc.update("UPDATE system_dept SET tenant_id=? WHERE id=?", foreignTenant, companyA));
            return null;
        }).when(audit).createOperateLog(any());
        code(403, () -> exports.export("customers", filter(null, companyA, 1, 20)));
    }

    @Test
    void exportCompanyDisablementDuringAuditDoesNotReleasePreparedBytes() {
        doAnswer(call -> {
            var tx = new TransactionTemplate(transactions);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(status -> jdbc.update("UPDATE pf_org_unit SET status='DISABLED' WHERE dept_id=?", companyB));
            return null;
        }).when(audit).createOperateLog(any());
        code(403, () -> exports.export("customers", Filter.all()));
    }

    @Test
    void oversizedExportFailsBeforeRowsOrAuditAreReleased() {
        long base = IDS.getAndAdd(60_000);
        jdbc.update("INSERT INTO fr_customer(id,company_id,owner_user_id,name,phone,source,status) "
                + "SELECT ?+n,?,?,'批量记录','1'||lpad(n::text,10,'0'),'test','REGISTERED' FROM generate_series(1,50001)n", base, companyA, user);
        code(400, () -> exports.export("customers", filter(null, companyA, 1, 20)));
        verifyNoInteractions(audit);
    }

    private long id() { return IDS.incrementAndGet(); }
    private static String s(long id) { return Long.toString(id); }
    private long user(long userTenant) {
        long id = id();
        jdbc.update("INSERT INTO system_users(id,tenant_id,nickname,username,password,mobile,status,deleted) VALUES(?,?,?,?,'unused',?,0,0)",
                id, userTenant, "人员" + id, "hq-test-" + id, "1" + String.format("%010d", id));
        return id;
    }
    private long organization(long orgTenant, String type, String brand) {
        long id = id();
        jdbc.update("INSERT INTO system_dept(id,name,tenant_id,status,deleted) VALUES(?,?,?,0,0)", id, "公司" + id, orgTenant);
        jdbc.update("INSERT INTO pf_org_unit(dept_id,kind,org_id,org_type) VALUES(?,'ORG',?,?)", id, id, type);
        if (brand != null) account(id, orgTenant, brand);
        return id;
    }
    private void account(long company, long accountTenant, String brand) {
        long owner = user(accountTenant);
        jdbc.update("INSERT INTO pf_franchise_account(account_id,tenant_id,org_id,user_id,login_name,org_name,owner_name,brand,province,city,area,area_pinyin,owner_pinyin,created_by) "
                + "VALUES(?,?,?,?,?,?,?,?,'江西','南昌','青山湖','qingshanhu','owner',?)", UUID.randomUUID().toString(), accountTenant,
                company, owner, "account-" + owner, "门店" + owner, "老板" + owner, brand, owner);
    }
    private PostSnapshot post(long actor, long org, String type, String role) {
        long id = id();
        jdbc.update("INSERT INTO pf_staff_post(id,user_id,org_id,role_code) VALUES(?,?,?,?)", id, actor, org, role);
        return new PostSnapshot(id, actor, org, type, role, null, false, Set.of());
    }
    private void actor(PostSnapshot post, long actorTenant) {
        when(posts.require()).thenReturn(post);
        TenantContextHolder.setTenantId(actorTenant);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser().setId(post.userId()).setUserType(2).setTenantId(actorTenant), null, List.of()));
    }
    private long customer(long company, String name) {
        long id = id();
        jdbc.update("INSERT INTO fr_customer(id,company_id,owner_user_id,name,phone,source,status) VALUES(?,?,?,?,?,'test','REGISTERED')",
                id, company, user, name, "1" + String.format("%010d", id));
        return id;
    }
    private long receipt(long company, String name, long amount, long asset) {
        long id = id();
        jdbc.update("INSERT INTO fr_fin_receipt(id,company_id,name,source_type,amount_cents,registrar_user_id,received_date,voucher_file_id) VALUES(?,?,?,'manual',?,?,CURRENT_DATE,?)",
                id, company, name, amount, user, asset == 0 ? null : asset);
        return id;
    }
    private long contract(long company, long customer, long asset) {
        long id = id();
        jdbc.update("INSERT INTO fr_sale_contract(id,company_id,customer_id,total_cents,first_cents,status,signed_date,asset_id,author_user_id) VALUES(?,?,?,5000,1000,'SIGNED',CURRENT_DATE,?,?)",
                id, company, customer, asset, user);
        return id;
    }
    private long project(long company, long customer, long contract) {
        long id = id();
        jdbc.update("INSERT INTO fr_eng_project(id,company_id,customer_id,customer_name,source_type,manager_user_id,name,sale_contract_id,author_user_id) VALUES(?,?,?,'客户','MANUAL',?,?,?,?)",
                id, company, customer, user, "工程" + id, contract, user);
        return id;
    }
    private long document(long company, long project, String name, String reference) {
        long id = id();
        jdbc.update("INSERT INTO fr_eng_document(id,company_id,project_id,name,file_ref,kind) VALUES(?,?,?,?,?,'DOCUMENT')", id, company, project, name, reference);
        return id;
    }
    private static Filter filter(Long brand, Long company, int pageNo, int pageSize) { return new Filter(brand, company, "", "", null, null, pageNo, pageSize); }
    private static void code(int code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode()).isEqualTo(code));
    }
}
