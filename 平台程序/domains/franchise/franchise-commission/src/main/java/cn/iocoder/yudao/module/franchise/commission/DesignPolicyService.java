package cn.iocoder.yudao.module.franchise.commission;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.franchise.commission.dal.DesignPolicyDO;
import cn.iocoder.yudao.module.franchise.commission.dal.DesignPolicyMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.commission.enums.FranchiseCommissionErrorCodes.*;

/**
 * 标准设计费用（门店老板配置，只追加版本）：委托费用与本店当前有效标准完全一致时免审批。
 */
@Service
public class DesignPolicyService {

    /** 总额上限 1 亿元（分） */
    static final long TOTAL_MAX_CENTS = 10_000_000_000L;

    public record PolicyCommand(BigDecimal total, BigDecimal first, String scope, String recipient) {
    }

    private final DesignPolicyMapper policyMapper;
    private final FranchiseCommands commands;
    @jakarta.annotation.Resource private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public DesignPolicyService(DesignPolicyMapper policyMapper, FranchiseCommands commands) {
        this.policyMapper = policyMapper;
        this.commands = commands;
    }

    public DesignPolicyDO getCurrent(PostSnapshot post) {
        requireStoreBoss(post);
        DesignPolicyDO policy = policyMapper.selectActive(post.orgId());
        if (policy == null) {
            throw exception(POLICY_NOT_FOUND);
        }
        return policy;
    }

    /** 每次保存一个新版本并作为当前有效标准 */
    @Transactional(rollbackFor = Exception.class)
    public DesignPolicyDO save(PostSnapshot post, PolicyCommand command, String idempotencyKey) {
        requireStoreBoss(post);
        PolicyCommand input = normalize(command);
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?,0))) s",Integer.class,"design-policy:"+post.orgId());
        var replay = commands.replayed(post, idempotencyKey, input);
        if (replay.isPresent()) {
            return policyMapper.selectActive(post.orgId());
        }
        Integer maxVersion = policyMapper.selectMaxVersion(post.orgId());
        DesignPolicyDO policy = new DesignPolicyDO().setCompanyId(post.orgId())
                .setVersion(maxVersion == null ? 1 : maxVersion + 1)
                .setTotalCents(cents(input.total())).setFirstCents(cents(input.first()))
                .setScope(input.scope()).setRecipient(input.recipient()).setActive(true)
                .setApprovedByUserId(post.userId()).setApprovedByPostId(post.postId());
        policyMapper.insert(policy);
        commands.record(post, "design-policy", policy.getId(), policy.getVersion(), "design-policy-save", input, idempotencyKey);
        return policy;
    }

    private static void requireStoreBoss(PostSnapshot post) {
        if (!FranchiseRoles.atStore(post) || !FranchiseRoles.isBoss(post)) {
            throw exception(POLICY_INPUT_INVALID);
        }
    }

    /** 元转分：最多两位小数 */
    static long cents(BigDecimal yuan) {
        return yuan.movePointRight(2).longValueExact();
    }

    /** 0 < 首款 < 总额 ≤ 1 亿元，最多两位小数 */
    static void requireAmount(BigDecimal total, BigDecimal first) {
        if (total == null || first == null || total.stripTrailingZeros().scale() > 2
                || first.stripTrailingZeros().scale() > 2
                || first.signum() <= 0 || first.compareTo(total) >= 0
                || cents(total) > TOTAL_MAX_CENTS) {
            throw exception(SUBMISSION_AMOUNT_INVALID);
        }
    }

    static PolicyCommand normalize(PolicyCommand c) {
        String scope = StrUtil.trim(c.scope());
        String recipient = StrUtil.trim(c.recipient());
        requireAmount(c.total(), c.first());
        if (StrUtil.isBlank(scope) || scope.length() > 500 || StrUtil.isBlank(recipient) || recipient.length() > 64) {
            throw exception(POLICY_INPUT_INVALID);
        }
        return new PolicyCommand(c.total(), c.first(), scope, recipient);
    }

}
