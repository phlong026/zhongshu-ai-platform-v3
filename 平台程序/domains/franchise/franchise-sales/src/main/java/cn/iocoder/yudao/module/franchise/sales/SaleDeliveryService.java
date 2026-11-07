package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.module.franchise.sales.dal.*;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.DELIVERY_DUPLICATE;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.DELIVERY_NODE_ORDER;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.REFERENCE_INVALID;

/**
 * 交付验收（B8 任务，D14 推荐默认）：四节点时间戳登记制（登记即事实、无审批、顺序强制）；
 * 节点登记后不可被不同幂等键覆盖（同节点重复调用返回既有时间戳）。
 */
@Service
@Slf4j
public class SaleDeliveryService {

    public static final List<String> NODES = List.of(
            "design-delivered", "design-confirmed", "construction-delivered", "construction-accepted");

    public record DeliveryCommand(long contractId, Long requirementId) {
    }

    private final SaleDeliveryMapper deliveries;
    private final SaleContractMapper contracts;
    private final SaleRequirementService requirements;
    private final FranchiseCommands commands;
    private final SalesAccess access;

    public SaleDeliveryService(SaleDeliveryMapper deliveries, SaleContractMapper contracts,
                               SaleRequirementService requirements, FranchiseCommands commands, SalesAccess access) {
        this.deliveries = deliveries;
        this.contracts = contracts;
        this.requirements = requirements;
        this.commands = commands;
        this.access = access;
    }

    @Transactional(rollbackFor = Exception.class)
    public SaleDeliveryDO create(PostSnapshot post, DeliveryCommand cmd, String idempotencyKey) {
        SaleContractDO contract = requireOrgContract(post, cmd.contractId());
        requirements.requireOfCustomer(contract.getCustomerId(), cmd.requirementId());
        // S1-23：指纹含 requirementId。Map.of 拒绝 null，需求可空，故用 LinkedHashMap。
        var input = createFingerprint(cmd);
        var replayed = commands.replayed(post, idempotencyKey, input);
        if (replayed.isPresent()) {
            return deliveries.selectById(replayed.get().getAggregateId());
        }
        SaleDeliveryDO delivery = new SaleDeliveryDO()
                .setCompanyId(post.orgId()).setCustomerId(contract.getCustomerId())
                .setContractId(cmd.contractId()).setRequirementId(cmd.requirementId())
                .setAuthorUserId(post.userId());
        try {
            deliveries.insert(delivery);
        } catch (DuplicateKeyException e) {
            // 一合同一交付唯一索引兜底（S1-22）：直发请求绕过前端 R14 探测时在此拦截
            log.warn("[create][contract={}] 交付重复创建被唯一索引拦下", cmd.contractId());
            throw exception(DELIVERY_DUPLICATE);
        }
        commands.record(post, "fr-sale-delivery", delivery.getId(), 1, "sale-delivery-create", input, idempotencyKey);
        return delivery;
    }

    @Transactional(rollbackFor = Exception.class)
    public SaleDeliveryDO advance(PostSnapshot post, long deliveryId, String node, String idempotencyKey) {
        int index = NODES.indexOf(node);
        if (index < 0) {
            throw exception(DELIVERY_NODE_ORDER);
        }
        SaleDeliveryDO delivery = deliveries.lockById(deliveryId);
        if (delivery == null || delivery.getCompanyId() != post.orgId()) {
            throw exception(REFERENCE_INVALID);
        }
        var input = Map.<String, Object>of("operation", "delivery-advance", "deliveryId", deliveryId, "node", node);
        var replayed = commands.replayed(post, idempotencyKey, input);
        if (replayed.isPresent()) {
            return deliveries.selectById(replayed.get().getAggregateId());
        }
        // 顺序强制：前一节点须已登记；同节点重复=幂等返回既有时间戳（不同键不覆盖）
        OffsetDateTime current = nodeAt(delivery, index);
        if (current != null) {
            commands.record(post, "fr-sale-delivery", delivery.getId(), com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),
                    "sale-delivery-advance", input, idempotencyKey);
            return delivery;
        }
        if (index > 0 && nodeAt(delivery, index - 1) == null) {
            throw exception(DELIVERY_NODE_ORDER);
        }
        OffsetDateTime now = OffsetDateTime.now();
        setNode(delivery, index, now);
        delivery.setRevision(delivery.getRevision() + 1);
        deliveries.updateById(delivery);
        commands.record(post, "fr-sale-delivery", delivery.getId(), com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),
                "sale-delivery-advance", input, idempotencyKey);
        return delivery;
    }

    public SaleDeliveryDO requireOfOrg(PostSnapshot post, long contractId) {
        SaleDeliveryDO delivery = deliveries.selectByContract(contractId);
        if (delivery == null || delivery.getCompanyId() != post.orgId()) {
            throw exception(REFERENCE_INVALID);
        }
        return delivery;
    }

    /** 交付创建幂等指纹（与 record 共用）。同键换 requirementId 必须冲突。 */
    static Map<String, Object> createFingerprint(DeliveryCommand cmd) {
        var input = new LinkedHashMap<String, Object>();
        input.put("operation", "delivery-create");
        input.put("contractId", cmd.contractId());
        input.put("requirementId", cmd.requirementId());
        return input;
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

    private static void setNode(SaleDeliveryDO d, int index, OffsetDateTime at) {
        switch (index) {
            case 0 -> d.setDesignDeliveredAt(at);
            case 1 -> d.setDesignConfirmedAt(at);
            case 2 -> d.setConstructionDeliveredAt(at);
            default -> d.setConstructionAcceptedAt(at);
        }
    }
}
