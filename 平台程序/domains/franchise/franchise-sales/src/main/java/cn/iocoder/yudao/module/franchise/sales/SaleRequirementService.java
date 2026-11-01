package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.module.franchise.sales.dal.*;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.REFERENCE_INVALID;

/**
 * 需求登记（B1 任务）：一客户多版本（version=latest+1，只追加）。
 */
@Service
public class SaleRequirementService {

    public record RequirementCommand(long customerId, BigDecimal width, BigDecimal depth, Integer floors,
                                     String style, String prompt, String note) {
    }

    private final SaleRequirementMapper requirements;
    private final FranchiseCommands commands;
    private final SalesAccess access;

    public SaleRequirementService(SaleRequirementMapper requirements, FranchiseCommands commands, SalesAccess access) {
        this.requirements = requirements;
        this.commands = commands;
        this.access = access;
    }

    @Transactional(rollbackFor = Exception.class)
    public SaleRequirementDO create(PostSnapshot post, RequirementCommand cmd, String idempotencyKey) {
        access.requireOrgCustomer(post, cmd.customerId());
        var input = new LinkedHashMap<String, Object>();
        input.put("operation", "requirement-create");
        input.put("customerId", cmd.customerId());
        input.put("width", cmd.width() == null ? null : cmd.width().stripTrailingZeros().toPlainString());
        input.put("depth", cmd.depth() == null ? null : cmd.depth().stripTrailingZeros().toPlainString());
        input.put("floors", cmd.floors());
        input.put("style", trimmed(cmd.style()));
        input.put("prompt", cmd.prompt());
        input.put("note", trimmed(cmd.note()));
        var replayed = commands.replayed(post, idempotencyKey, input);
        if (replayed.isPresent()) {
            return requirements.selectById(replayed.get().getAggregateId());
        }
        SaleRequirementDO latest = requirements.selectLatest(cmd.customerId());
        SaleRequirementDO requirement = new SaleRequirementDO()
                .setCompanyId(post.orgId()).setCustomerId(cmd.customerId())
                .setVersion(latest == null ? 1 : latest.getVersion() + 1)
                .setWidth(cmd.width()).setDepth(cmd.depth()).setFloors(cmd.floors())
                .setStyle(trimmed(cmd.style())).setPrompt(cmd.prompt()).setNote(trimmed(cmd.note()))
                .setAuthorUserId(post.userId());
        requirements.insert(requirement);
        commands.record(post, "fr-sale-requirement", requirement.getId(), requirement.getVersion(),
                "sale-requirement-create", input, idempotencyKey);
        return requirement;
    }

    public java.util.List<SaleRequirementDO> listByCustomer(PostSnapshot post, long customerId) {
        access.requireOrgCustomer(post, customerId);
        return requirements.selectByCustomer(customerId);
    }

    /** 供合同引用校验：需求必须属于该客户与该组织 */
    public SaleRequirementDO requireOfCustomer(long customerId, Long requirementId) {
        if (requirementId == null) {
            return null;
        }
        SaleRequirementDO requirement = requirements.selectById(requirementId);
        if (requirement == null || requirement.getCustomerId() != customerId) {
            throw exception(REFERENCE_INVALID);
        }
        return requirement;
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
