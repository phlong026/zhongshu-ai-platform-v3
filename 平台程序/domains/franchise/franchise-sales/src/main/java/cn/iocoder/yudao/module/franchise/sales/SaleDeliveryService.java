package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.module.franchise.sales.dal.*;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.DELIVERY_WRITE_RETIRED;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.DELIVERY_NODE_ORDER;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.REFERENCE_INVALID;

/**
 * 旧四节点交付仅保留历史查询及原成功请求的只读重放。施工交付事实由原工程记录。
 */
@Service
public class SaleDeliveryService {

    public static final List<String> NODES = List.of(
            "design-delivered", "design-confirmed", "construction-delivered", "construction-accepted");

    public record DeliveryCommand(long contractId, Long requirementId) {
    }

    private final SaleDeliveryMapper deliveries;
    private final SaleContractMapper contracts;
    private final SaleRequirementService requirements;
    private final FranchiseCommands commands;

    public SaleDeliveryService(SaleDeliveryMapper deliveries, SaleContractMapper contracts,
                               SaleRequirementService requirements, FranchiseCommands commands) {
        this.deliveries = deliveries;
        this.contracts = contracts;
        this.requirements = requirements;
        this.commands = commands;
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public SaleDeliveryDO create(PostSnapshot post, DeliveryCommand cmd, String idempotencyKey) {
        SaleContractDO contract = requireOrgContract(post, cmd.contractId());
        requirements.requireOfCustomer(contract.getCustomerId(), cmd.requirementId());
        var input = createFingerprint(cmd);
        var replayed = commands.replayed(post, idempotencyKey, input, legacyFingerprint(cmd));
        if (replayed.isPresent()) {
            SaleDeliveryDO existing = deliveries.selectById(replayed.get().getAggregateId());
            if (!"sale-delivery-create".equals(replayed.get().getCommand())
                    || !"fr-sale-delivery".equals(replayed.get().getAggregateType())
                    || existing == null || !java.util.Objects.equals(existing.getCompanyId(), post.orgId())
                    || !java.util.Objects.equals(existing.getContractId(), cmd.contractId())
                    || !java.util.Objects.equals(existing.getRequirementId(), cmd.requirementId())) {
                throw exception(IDEMPOTENCY_KEY_REUSED);
            }
            return existing;
        }
        throw exception(DELIVERY_WRITE_RETIRED);
    }

    static Map<String, Object> createFingerprint(DeliveryCommand cmd) {
        var input = new LinkedHashMap<String, Object>();
        input.put("operation", "delivery-create");
        input.put("contractId", cmd.contractId());
        input.put("requirementId", cmd.requirementId());
        return input;
    }

    private static Map<String, Object> legacyFingerprint(DeliveryCommand cmd) {
        return Map.of("operation", "delivery-create", "contractId", cmd.contractId());
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public SaleDeliveryDO advance(PostSnapshot post, long deliveryId, String node, String idempotencyKey) {
        int index = NODES.indexOf(node);
        if (index < 0) {
            throw exception(DELIVERY_NODE_ORDER);
        }
        SaleDeliveryDO delivery = deliveries.selectById(deliveryId);
        if (delivery == null || delivery.getCompanyId() != post.orgId()) {
            throw exception(REFERENCE_INVALID);
        }
        var input = Map.<String, Object>of("operation", "delivery-advance", "deliveryId", deliveryId, "node", node);
        var replayed = commands.replayed(post, idempotencyKey, input);
        if (replayed.isPresent()) {
            var event = replayed.get();
            if (!"sale-delivery-advance".equals(event.getCommand())
                    || !"fr-sale-delivery".equals(event.getAggregateType())
                    || !java.util.Objects.equals(event.getAggregateId(), deliveryId)
                    || nodeAt(delivery, index) == null) {
                throw exception(IDEMPOTENCY_KEY_REUSED);
            }
            return delivery;
        }
        throw exception(DELIVERY_WRITE_RETIRED);
    }

    public SaleDeliveryDO requireOfOrg(PostSnapshot post, long contractId) {
        SaleDeliveryDO delivery = deliveries.selectByContract(contractId);
        if (delivery == null || delivery.getCompanyId() != post.orgId()) {
            throw exception(REFERENCE_INVALID);
        }
        return delivery;
    }

    private SaleContractDO requireOrgContract(PostSnapshot post, long contractId) {
        SaleContractDO contract = contracts.selectById(contractId);
        if (contract == null || contract.getCompanyId() != post.orgId()) {
            throw exception(REFERENCE_INVALID);
        }
        return contract;
    }

    private static OffsetDateTime nodeAt(SaleDeliveryDO d, int index) {
        return switch (index) {
            case 0 -> d.getDesignDeliveredAt();
            case 1 -> d.getDesignConfirmedAt();
            case 2 -> d.getConstructionDeliveredAt();
            default -> d.getConstructionAcceptedAt();
        };
    }

}
