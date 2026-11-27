package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleContractDO;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleContractMapper;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleDeliveryDO;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleDeliveryMapper;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SaleDeliveryFingerprintTest {

    @Test
    void distinguishesRequirementWhenIdempotencyKeyIsReused() {
        var requirementOne = SaleDeliveryService.createFingerprint(
                new SaleDeliveryService.DeliveryCommand(9L, 1L));
        var requirementTwo = SaleDeliveryService.createFingerprint(
                new SaleDeliveryService.DeliveryCommand(9L, 2L));
        var withoutRequirement = SaleDeliveryService.createFingerprint(
                new SaleDeliveryService.DeliveryCommand(9L, null));

        assertThat(requirementOne).isNotEqualTo(requirementTwo);
        assertThat(requirementOne)
                .containsEntry("operation", "delivery-create")
                .containsEntry("contractId", 9L)
                .containsEntry("requirementId", 1L);
        assertThat(withoutRequirement).containsEntry("requirementId", null);
    }

    @Test
    void legacyReplayRequiresStoredDeliveryToMatchRequirement() {
        SaleDeliveryMapper deliveries = mock(SaleDeliveryMapper.class);
        SaleContractMapper contracts = mock(SaleContractMapper.class);
        SaleRequirementService requirements = mock(SaleRequirementService.class);
        FranchiseCommands commands = mock(FranchiseCommands.class);
        SaleDeliveryService service = new SaleDeliveryService(
                deliveries, contracts, requirements, commands);
        PostSnapshot post = new PostSnapshot(1, 2, 300, "store", "sales", null, false, Set.of());
        SaleContractDO contract = new SaleContractDO().setId(9L).setCompanyId(post.orgId()).setCustomerId(7L);
        SaleDeliveryDO legacyDelivery = new SaleDeliveryDO()
                .setId(11L).setContractId(9L).setRequirementId(1L).setCompanyId(post.orgId());
        when(contracts.selectById(9L)).thenReturn(contract);
        when(commands.replayed(eq(post), eq("legacy-key"), any(), any()))
                .thenReturn(Optional.of(new CommandEventDO().setAggregateId(11L).setAggregateType("fr-sale-delivery").setCommand("sale-delivery-create")));
        when(deliveries.selectById(11L)).thenReturn(legacyDelivery);

        assertThat(service.create(post, new SaleDeliveryService.DeliveryCommand(9L, 1L), "legacy-key"))
                .isSameAs(legacyDelivery);
        assertThatThrownBy(() -> service.create(
                post, new SaleDeliveryService.DeliveryCommand(9L, 2L), "legacy-key"))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants
                        .IDEMPOTENCY_KEY_REUSED.getCode());
    }
}
