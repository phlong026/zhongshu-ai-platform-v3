package cn.iocoder.yudao.module.franchise.performance.content;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.performance.enums.FranchisePerformanceErrorCodes.*;

/** 阶段 3 公共支撑（Codex R1 修复）：聚合 revision 递增、manage 服务层角色校验、签名摘要。 */
final class PerfSupport {

    private PerfSupport() {
    }

    /** 该聚合当前命令事件最大 revision+1（command_event 按 (聚合, revision) 唯一防重——固定 1 必撞） */
    static long nextAggregateRevision(JdbcTemplate jdbc, String aggregateType, long aggregateId) {
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision), 0) FROM pf_command_event WHERE aggregate_type = ? AND aggregate_id = ?",
                Long.class, aggregateType, aggregateId);
        return (max == null ? 0 : max) + 1;
    }

    /** manage 服务层角色校验（D-9/P1-4：boss/owner；越权 000——不依赖控制器菜单码） */
    static void requireManage(PostSnapshot post) {
        if (!List.of(FranchiseRoles.BOSS, FranchiseRoles.OWNER).contains(post.roleCode())) {
            throw exception(PERFORMANCE_FORBIDDEN);
        }
    }

    /** finance 族校验（release） */
    static void requireFinance(PostSnapshot post) {
        if (!List.of(FranchiseRoles.FINANCE, FranchiseRoles.FINANCE_LEAD).contains(post.roleCode())) {
            throw exception(PERFORMANCE_FORBIDDEN);
        }
    }

    /** 严格 boss（policy/reconcile——owner 持 commission 码越界 000） */
    static void requireBoss(PostSnapshot post) {
        if (!FranchiseRoles.BOSS.equals(post.roleCode())) {
            throw exception(PERFORMANCE_FORBIDDEN);
        }
    }

    /** 签名摘要（P1-12：稳定排序数据 → 固定长 SHA-256——明细拼接会撑爆 VARCHAR(2048)） */
    static String signature(List<String> orderedParts) {
        return DigestUtil.sha256Hex(String.join("|", orderedParts));
    }
}
