package cn.iocoder.yudao.module.franchise.engineering;

import cn.iocoder.yudao.module.franchise.sales.SaleWorkflowFactsAdapter;
import cn.iocoder.yudao.module.franchise.test.FranchiseFixture.Store;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the actual source schemas and SQL, including legacy rows that merely claim a status. */
@Import({SaleWorkflowFactsAdapter.class, EngWorkflowFactsAdapter.class, WorkflowSourceFactsPgIntegrationTest.Config.class})
class WorkflowSourceFactsPgIntegrationTest extends FranchisePgIntegrationTest {
    @org.springframework.boot.test.context.TestConfiguration
    static class Config {
        @org.springframework.context.annotation.Bean
        cn.iocoder.yudao.module.franchise.api.AssetEvidenceQueryPort evidence() {
            return (tenant, ids, types) -> {
                var owners=new java.util.HashMap<Long,Long>();
                if (ids.contains(9001L) && types.contains("CONTRACT_ATTACHMENT")) owners.put(9001L,tenant);
                if (ids.contains(9003L) && types.contains("ENG_SITE_PHOTO")) owners.put(9003L,tenant);
                return owners;
            };
        }
    }
    @Resource JdbcTemplate jdbc;
    @Resource SaleWorkflowFactsAdapter contracts;
    @Resource EngWorkflowFactsAdapter engineering;
    private Store store;
    private long customer, tenant;

    @BeforeEach void fixtures() {
        store = fixture.store(); tenant = store.orgId(); customer = IdWorker.getId();
        jdbc.update("INSERT INTO system_dept(id,name,status,deleted,tenant_id) VALUES(?,'事实测试门店',0,0,?) ON CONFLICT(id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id", store.orgId(), tenant);
        jdbc.update("INSERT INTO fr_customer(id,company_id,owner_user_id,name,phone,source,status) VALUES(?,?,1,'张先生','13900000001','到店','REGISTERED')", customer, store.orgId());
    }

    @Test void signedStatusRequiresDateAndAttachmentAndTenantCompanyMustMatch() {
        long contract = IdWorker.getId();
        jdbc.update("INSERT INTO fr_sale_contract(id,company_id,customer_id,total_cents,first_cents,status,author_user_id) VALUES(?,?,?,1000000,100000,'SIGNED',1)", contract, store.orgId(), customer);
        var old = contracts.findFacts(tenant, Map.of(customer, store.orgId()));
        assertThat(old).singleElement().satisfies(fact -> {
            assertThat(fact.complete()).isFalse(); assertThat(fact.missingEvidence()).hasSize(2);
        });
        jdbc.update("UPDATE fr_sale_contract SET signed_date=?,asset_id=9001 WHERE id=?", today(), contract);
        assertThat(contracts.findFacts(tenant, Map.of(customer, store.orgId()))).singleElement().satisfies(fact -> assertThat(fact.complete()).isTrue());
        jdbc.update("UPDATE fr_sale_contract SET asset_id=9002 WHERE id=?", contract);
        assertThat(contracts.findFacts(tenant, Map.of(customer, store.orgId()))).singleElement().satisfies(fact -> {
            assertThat(fact.complete()).isFalse();
            assertThat(fact.missingEvidence()).contains("合同附件未通过验收或已失效，请在合同模块补录");
        });
        jdbc.update("UPDATE fr_sale_contract SET asset_id=9001 WHERE id=?", contract);
        assertThat(contracts.findFacts(tenant + 1, Map.of(customer, store.orgId()))).isEmpty();
        assertThat(contracts.findFacts(tenant, Map.of(customer, store.orgId() + 1))).isEmpty();
        jdbc.update("UPDATE fr_sale_contract SET signed_date=? WHERE id=?", today().plusDays(1), contract);
        assertThat(contracts.findFacts(tenant, Map.of(customer, store.orgId()))).singleElement().satisfies(fact -> assertThat(fact.complete()).isFalse());
        jdbc.update("UPDATE fr_sale_contract SET status='VOIDED' WHERE id=?", contract);
        assertThat(contracts.findFacts(tenant, Map.of(customer, store.orgId()))).isEmpty();
    }

    @Test void constructionRequiresMatchingRealStartNodeAndRecordWithEvidence() {
        long project = primaryProject(), node = IdWorker.getId(), record = IdWorker.getId();
        jdbc.update("UPDATE fr_eng_project SET actual_start_at=?,status='IN_PROGRESS' WHERE id=?", today(), project);
        assertThat(engineering.findFacts(tenant, Map.of(customer, store.orgId()))).singleElement().satisfies(fact -> assertThat(fact.complete()).isFalse());
        jdbc.update("INSERT INTO fr_eng_plan_node(id,company_id,project_id,node_index,name,status,milestone,completed_at) VALUES(?,?,?,0,'正式开工','DONE','START',?)", node, store.orgId(), project, today());
        jdbc.update("INSERT INTO fr_eng_site_record(id,company_id,project_id,node_id,occurred_at,note,completion,result,file_asset_ids,author_user_id) VALUES(?,?,?,?,?,'正式开工',TRUE,'PASS','[]'::jsonb,1)", record, store.orgId(), project, node, today());
        assertThat(engineering.findFacts(tenant, Map.of(customer, store.orgId()))).singleElement().satisfies(fact -> assertThat(fact.complete()).isFalse());
        for (String invalid:java.util.List.of("[\"9001\"]","[\"9002\"]","[\"9003\",\"bad\"]","[null]","{}")) {
            jdbc.update("UPDATE fr_eng_site_record SET file_asset_ids=CAST(? AS jsonb) WHERE id=?",invalid,record);
            assertThat(engineering.findFacts(tenant,Map.of(customer,store.orgId()))).singleElement().satisfies(fact -> assertThat(fact.complete()).isFalse());
        }
        jdbc.update("UPDATE fr_eng_site_record SET file_asset_ids='[\"9003\"]'::jsonb WHERE id=?", record);
        assertThat(engineering.findFacts(tenant, Map.of(customer, store.orgId()))).singleElement().satisfies(fact -> {
            assertThat(fact.complete()).isTrue(); assertThat(fact.fields()).containsEntry("projectId", Long.toString(project));
        });
        assertThat(engineering.findFacts(tenant + 1, Map.of(customer, store.orgId()))).isEmpty();
        assertThat(engineering.findFacts(tenant, Map.of(customer, store.orgId() + 1))).isEmpty();
        jdbc.update("UPDATE fr_eng_site_record SET occurred_at=? WHERE id=?", today().minusDays(1), record);
        assertThat(engineering.findFacts(tenant, Map.of(customer, store.orgId()))).singleElement().satisfies(fact -> assertThat(fact.complete()).isFalse());
    }

    @Test void aForeignProjectCannotUseAnotherCompanysAcceptedStartProofOrOverwriteItsOwner() {
        long own=primaryProject(),ownNode=IdWorker.getId();
        var other=fixture.store(); long foreignCustomer=IdWorker.getId(),foreignProject=IdWorker.getId(),foreignNode=IdWorker.getId();
        jdbc.update("INSERT INTO system_dept(id,name,status,deleted,tenant_id) VALUES(?,'另一公司',0,0,?) ON CONFLICT(id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id",other.orgId(),tenant);
        jdbc.update("INSERT INTO fr_customer(id,company_id,owner_user_id,name,phone,source,status) VALUES(?,?,1,'另一客户','13900000002','到店','REGISTERED')",foreignCustomer,other.orgId());
        jdbc.update("INSERT INTO fr_eng_project(id,company_id,customer_id,source_type,manager_user_id,name,author_user_id) VALUES(?,?,?,'HANDOVER',1,'其他工程',1)",foreignProject,other.orgId(),foreignCustomer);
        for (long[] row:java.util.List.of(new long[]{own,ownNode,store.orgId()},new long[]{foreignProject,foreignNode,other.orgId()})) {
            jdbc.update("UPDATE fr_eng_project SET actual_start_at=? WHERE id=?",today(),row[0]);
            jdbc.update("INSERT INTO fr_eng_plan_node(id,company_id,project_id,node_index,name,status,milestone,completed_at) VALUES(?,?,?,0,'正式开工','DONE','START',?)",row[1],row[2],row[0],today());
            jdbc.update("INSERT INTO fr_eng_site_record(id,company_id,project_id,node_id,occurred_at,note,completion,result,file_asset_ids,author_user_id) VALUES(?,?,?,?,?,'实际开工',TRUE,'PASS','[9003]'::jsonb,1)",IdWorker.getId(),row[2],row[0],row[1],today());
        }
        var facts=engineering.findFacts(tenant,Map.of(customer,store.orgId(),foreignCustomer,other.orgId()));
        assertThat(facts).hasSize(2);
        assertThat(facts.stream().filter(f -> f.customerId()==customer).findFirst().orElseThrow().complete()).isTrue();
        assertThat(facts.stream().filter(f -> f.customerId()==foreignCustomer).findFirst().orElseThrow().complete()).isFalse();
    }

    @Test void incrementProjectsCannotCompleteTheInitialConstructionStep() {
        long primary = primaryProject(), increment = IdWorker.getId();
        jdbc.update("INSERT INTO fr_eng_project(id,company_id,customer_id,source_type,parent_project_id,manager_user_id,name,actual_start_at,status,author_user_id) VALUES(?,?,?,'INCREMENT',?,1,?,?,'IN_PROGRESS',1)",
                increment, store.orgId(), customer, primary, "增项" + increment, today());
        assertThat(engineering.findFacts(tenant, Map.of(customer, store.orgId()))).singleElement().satisfies(fact -> {
            assertThat(fact.fields()).containsEntry("projectId", Long.toString(primary)); assertThat(fact.complete()).isFalse();
        });
    }

    private long primaryProject() {
        long id = IdWorker.getId();
        jdbc.update("INSERT INTO fr_eng_project(id,company_id,customer_id,source_type,manager_user_id,name,author_user_id) VALUES(?,?,?,'HANDOVER',1,?,1)", id, store.orgId(), customer, "主工程" + id);
        return id;
    }
    private static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }
}
