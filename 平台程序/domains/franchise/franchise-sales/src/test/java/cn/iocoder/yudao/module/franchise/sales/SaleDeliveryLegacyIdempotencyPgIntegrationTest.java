package cn.iocoder.yudao.module.franchise.sales;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.crm.CustomerSchemeAdapter;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.SALES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({SaleDeliveryService.class, SaleRequirementService.class, SalesAccess.class,
        CustomerSchemeAdapter.class, CustomerService.class, FranchiseCommands.class, CommandLogImpl.class})
class SaleDeliveryLegacyIdempotencyPgIntegrationTest extends FranchisePgIntegrationTest {

    @Resource private SaleDeliveryService deliveries;
    @Resource private JdbcTemplate jdbc;
    @Resource private PlatformTransactionManager transactions;

    private PostSnapshot sales;
    private long customerId;
    private long contractId;
    private long requirementOne;
    private long requirementTwo;

    @BeforeEach
    void setUp() {
        var store = fixture.store();
        sales = fixture.post(store.orgId(), SALES, store.salesTeamA());
        customerId = id();
        contractId = id();
        requirementOne = id();
        requirementTwo = id();
        jdbc.update("INSERT INTO fr_customer (id,company_id,owner_user_id,name,phone,source,status) "
                        + "VALUES (?,?,?,'旧幂等交付客户',?,'业务登记','REGISTERED')",
                customerId, sales.orgId(), sales.userId(), "137" + Math.floorMod(customerId, 100_000_000L));
        insertRequirement(requirementOne, 1);
        insertRequirement(requirementTwo, 2);
        jdbc.update("INSERT INTO fr_sale_contract (id,company_id,customer_id,total_cents,first_cents,status,"
                        + "revision,requirement_id,author_user_id) VALUES (?,?,?,?,?,'SIGNED',1,?,?)",
                contractId, sales.orgId(), customerId, 1_000_000L, 300_000L, requirementOne, sales.userId());
    }

    @Test
    void legacyHashReplaysSameBusinessButRejectsChangedRequirementWithoutNewFacts() {
        long deliveryId = id();
        insertDelivery(deliveryId, requirementOne);
        insertLegacyEvent("legacy-same", deliveryId, sales.orgId());

        assertThat(deliveries.create(sales,
                new SaleDeliveryService.DeliveryCommand(contractId, requirementOne), "legacy-same").getId())
                .isEqualTo(deliveryId);

        long deliveriesBefore = count("fr_sale_delivery");
        long commandsBefore = count("pf_command_event");
        assertThatThrownBy(() -> deliveries.create(sales,
                new SaleDeliveryService.DeliveryCommand(contractId, requirementTwo), "legacy-same"))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("fr_sale_delivery")).isEqualTo(deliveriesBefore);
        assertThat(count("pf_command_event")).isEqualTo(commandsBefore);
    }

    @Test
    void missingDeliveryAndCrossOrganizationReplayFailAndRollbackCallerTransaction() {
        insertLegacyEvent("legacy-missing", id(), sales.orgId());
        long missingDeliveriesBefore = count("fr_sale_delivery");
        long missingCommandsBefore = count("pf_command_event");
        long sentinelRequirement = id();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            insertRequirement(sentinelRequirement, 99);
            deliveries.create(sales,
                    new SaleDeliveryService.DeliveryCommand(contractId, requirementOne), "legacy-missing");
        })).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_requirement WHERE id=?",
                Long.class, sentinelRequirement)).isZero();
        assertThat(count("fr_sale_delivery")).isEqualTo(missingDeliveriesBefore);
        assertThat(count("pf_command_event")).isEqualTo(missingCommandsBefore);

        long deliveryId = id();
        insertDelivery(deliveryId, requirementOne);
        insertLegacyEvent("legacy-cross-org", deliveryId, fixture.store().orgId());
        long deliveriesBefore = count("fr_sale_delivery");
        long commandsBefore = count("pf_command_event");
        assertThatThrownBy(() -> deliveries.create(sales,
                new SaleDeliveryService.DeliveryCommand(contractId, requirementOne), "legacy-cross-org"))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("fr_sale_delivery")).isEqualTo(deliveriesBefore);
        assertThat(count("pf_command_event")).isEqualTo(commandsBefore);
    }

    private void insertRequirement(long requirementId, int version) {
        jdbc.update("INSERT INTO fr_sale_requirement (id,company_id,customer_id,version,author_user_id) "
                        + "VALUES (?,?,?,?,?)",
                requirementId, sales.orgId(), customerId, version, sales.userId());
    }

    private void insertDelivery(long deliveryId, long requirementId) {
        jdbc.update("INSERT INTO fr_sale_delivery (id,company_id,customer_id,contract_id,requirement_id,"
                        + "revision,author_user_id) VALUES (?,?,?,?,?,1,?)",
                deliveryId, sales.orgId(), customerId, contractId, requirementId, sales.userId());
    }

    private void insertLegacyEvent(String key, long deliveryId, long orgId) {
        Map<String, Object> payload = Map.of("operation", "delivery-create", "contractId", contractId);
        String json = JsonUtils.toJsonString(payload);
        jdbc.update("INSERT INTO pf_command_event (id,domain,aggregate_type,aggregate_id,revision,command,org_id,"
                        + "actor_user_id,actor_post_id,actor_role_code,payload,payload_hash,idempotency_key) "
                        + "VALUES (?,'franchise','fr-sale-delivery',?,1,'sale-delivery-create',?,?,?,?,?,?,?)",
                id(), deliveryId, orgId, sales.userId(), sales.postId(), sales.roleCode(), json,
                DigestUtil.sha256Hex(json), "franchise:" + sales.userId() + ":" + key);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private static long id() {
        return cn.hutool.core.util.IdUtil.getSnowflakeNextId();
    }
}
