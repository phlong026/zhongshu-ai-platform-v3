package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngClosureDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngSupplyReceiptDO;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.engineering.enums.FranchiseEngineeringErrorCodes.*;

/**
 * 工程域共享守卫与派生纯函数（蓝本 engineering-records.js 同源逻辑的服务端定稿）。
 * 记录流办理=负责人本人（owner(p)=manager/assignedEngineer===操作者，D-14）；
 * 交付后/归档后动作白名单（F10）；材料齐全=合格到货+无未闭环不合格（engineering-records.js:31-32）。
 */
public final class EngGuard {

    /** 交付后可办白名单（蓝本 :52：settle/archive/material-terms/project/collect/dunning） */
    private static final Set<String> AFTER_DELIVER = Set.of(
            "settle", "archive", "material-terms", "project", "collect", "dunning", "expenses", "expense-terms");
    /** 归档后仅补原单付款资料；费用登记、金额修订、取消仍由 expenses 动作冻结。 */
    private static final Set<String> AFTER_ARCHIVE = Set.of("material-terms", "collect", "dunning", "expense-terms");

    private EngGuard() {
    }

    /** 实际发生日期统一取上海业务日；历史补录保留输入日期。 */
    static java.time.LocalDate actualDate(java.time.LocalDate supplied) {
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var date = supplied == null ? today : supplied;
        if (date.isAfter(today)) throw exception(ENGINEERING_INPUT_INVALID, "实际日期不能晚于今天");
        return date;
    }

    /** write 码之上的服务层 owner 校验：负责人或指定工程师本人（000） */
    public static void requireOwner(FrEngProjectDO project, long userId) {
        boolean owner = userId == project.getManagerUserId()
                || (project.getAssignedEngineerUserId() != null && userId == project.getAssignedEngineerUserId());
        if (!owner) {
            throw exception(ENGINEERING_FORBIDDEN);
        }
    }

    /** 交付后/归档后动作白名单（000；null closure=未交付） */
    public static void requireActionAllowed(FrEngClosureDO closure, String action) {
        if (closure != null && closure.getArchivedAt() != null && !AFTER_ARCHIVE.contains(action)) {
            throw exception(ENGINEERING_FORBIDDEN);
        }
        if (closure != null && closure.getArchivedAt() == null && closure.getDeliveredAt() != null
                && !AFTER_DELIVER.contains(action)) {
            throw exception(ENGINEERING_FORBIDDEN);
        }
    }

    /** 主体封顶完成=milestone=TOPPING 节点 DONE（recordFlow.topping 的派生等价，收款侧前置 F8） */
    public static boolean toppingDone(List<FrEngPlanNodeDO> nodes) {
        return nodes.stream().anyMatch(n -> FrEngPlanNodeDO.MILESTONE_TOPPING.equals(n.getMilestone())
                && FrEngPlanNodeDO.STATUS_DONE.equals(n.getStatus()));
    }

    /** 首个未完成节点（蓝本「请从当前施工节点继续记录」） */
    public static FrEngPlanNodeDO currentNode(List<FrEngPlanNodeDO> nodes) {
        return nodes.stream().filter(n -> !FrEngPlanNodeDO.STATUS_DONE.equals(n.getStatus())).findFirst().orElse(null);
    }

    /** 合格到货量：OK 或质检闭环（更换后合格）的实到合计（蓝本 good() :31，RESHIP 行并入） */
    public static BigDecimal goodQuantity(List<FrEngSupplyReceiptDO> receipts) {
        BigDecimal sum = BigDecimal.ZERO;
        for (FrEngSupplyReceiptDO r : receipts) {
            if (!qualityPassed(r) || r.getActualQuantity() == null) {
                continue;
            }
            sum = sum.add(r.getActualQuantity());
        }
        return sum;
    }

    /** 质检通过=OK 或 qualityResolution.result=更换后合格 */
    public static boolean qualityPassed(FrEngSupplyReceiptDO receipt) {
        if (FrEngSupplyReceiptDO.QUALITY_OK.equals(receipt.getQuality())) {
            return true;
        }
        String resolution = receipt.getQualityResolution();
        return resolution != null && resolution.contains("\"更换后合格\"");
    }

    /**
     * 材料齐全门禁（蓝本 materialReady() :32）：指定节点（nodeId=null=全项目）的
     * 全部在册材料合格到货 ≥ 需用量，且无未闭环不合格收货。
     */
    public static boolean materialReady(List<MaterialFact> facts, Long nodeId) {
        for (MaterialFact fact : facts) {
            if (fact.cancelled()) {
                continue;
            }
            if (nodeId != null && fact.nodeId() != nodeId) {
                continue;
            }
            BigDecimal good = goodQuantity(fact.receipts());
            boolean unresolved = fact.receipts().stream().anyMatch(r -> !qualityPassed(r));
            if (unresolved || good.add(EPSILON).compareTo(fact.quantity()) < 0) {
                return false;
            }
        }
        return true;
    }

    private static final BigDecimal EPSILON = new BigDecimal("0.0001");

    /** 材料事实（调用方按 material 聚合其全部供应的收货行） */
    public record MaterialFact(long materialId, long nodeId, BigDecimal quantity, boolean cancelled,
                               List<FrEngSupplyReceiptDO> receipts) {
    }

    /** 状态固化列重算（F2 派生态；变更后由各服务调用） */
    public static String deriveStatus(FrEngProjectDO project, List<FrEngPlanNodeDO> nodes, FrEngClosureDO closure) {
        if (closure != null && closure.getArchivedAt() != null) {
            return FrEngProjectDO.STATUS_ARCHIVED;
        }
        if (closure != null && closure.getDeliveredAt() != null) {
            return FrEngProjectDO.STATUS_DELIVERED;
        }
        if (project.getActualStartAt() == null) {
            return FrEngProjectDO.STATUS_PENDING_START;
        }
        boolean allDone = nodes.stream().allMatch(n -> FrEngPlanNodeDO.STATUS_DONE.equals(n.getStatus()));
        return allDone ? FrEngProjectDO.STATUS_READY_DELIVER : FrEngProjectDO.STATUS_IN_PROGRESS;
    }

    /** 金额（分）：>0 且上限一亿（蓝本 cents()） */
    public static long requirePositiveCents(BigDecimal yuan) {
        if (yuan == null) {
            throw exception(ENGINEERING_INPUT_INVALID, "金额必须填写");
        }
        long cents = yuan.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        if (cents <= 0 || cents > 10_000_000_000L) {
            throw exception(ENGINEERING_INPUT_INVALID, "金额须大于0且不超过一亿元");
        }
        return cents;
    }

    /** 数量：>0，最多三位小数（蓝本 quantity()） */
    public static BigDecimal requireQuantity(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0
                || value.scale() > 3 || value.compareTo(new BigDecimal("10000000")) > 0) {
            throw exception(ENGINEERING_INPUT_INVALID, "数量须大于0、最多三位小数");
        }
        return value.stripTrailingZeros().scale() <= 0
                ? value.setScale(0) : value.setScale(3);
    }
}
