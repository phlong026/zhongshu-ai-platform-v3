package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.StageBalance;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeDO;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** One due-date rule for collection views, reminders and dunning. */
public final class EngPaymentDates {
    private EngPaymentDates() {}
    public static boolean beforeStart(String trigger,String key) {
        return "ON_SIGN".equals(trigger) || "BEFORE_START".equals(trigger)
                || trigger==null && ("SIGN_AFTER".equals(key) || "START".equals(key));
    }
    public static LocalDate due(StageBalance stage,List<FrEngPlanNodeDO> nodes) {
        String trigger=stage.trigger();
        if(trigger==null) trigger=switch(stage.stage()) {
            case "SIGN_AFTER","START" -> "BEFORE_START";
            case "BASE_DONE" -> "LEGACY_BASE_DONE";
            case "CAP_DONE","TOPPING" -> "LEGACY_TOPPING";
            default -> "MANUAL";
        };
        return switch(trigger) {
            case "ON_SIGN","DATE","MANUAL" -> stage.dueDate();
            case "BEFORE_START" -> nodes.stream().filter(n->"START".equals(n.getMilestone())).map(FrEngPlanNodeDO::getStartDate).filter(Objects::nonNull).findFirst().orElse(null);
            case "NODE_COMPLETED" -> nodes.stream().filter(n->Objects.equals(n.getNodeIndex(),stage.nodeIndex()) && "DONE".equals(n.getStatus())).map(FrEngPlanNodeDO::getCompletedAt).filter(Objects::nonNull).findFirst().orElse(null);
            case "ON_FINISH" -> !nodes.isEmpty() && nodes.stream().allMatch(n->"DONE".equals(n.getStatus()) && n.getCompletedAt()!=null)
                    ? nodes.stream().map(FrEngPlanNodeDO::getCompletedAt).max(LocalDate::compareTo).orElse(null) : null;
            // Old agreements retain their existing meanings; no foundation node number is invented.
            case "LEGACY_BASE_DONE" -> nodes.stream().filter(n->!"TOPPING".equals(n.getMilestone()) && !"START".equals(n.getMilestone()) && "DONE".equals(n.getStatus())).map(FrEngPlanNodeDO::getEndDate).filter(Objects::nonNull).findFirst().orElse(null);
            case "LEGACY_TOPPING" -> nodes.stream().filter(n->"TOPPING".equals(n.getMilestone()) && "DONE".equals(n.getStatus())).map(FrEngPlanNodeDO::getEndDate).filter(Objects::nonNull).findFirst().orElse(null);
            default -> null;
        };
    }
    public static boolean waitingForWork(StageBalance stage,List<FrEngPlanNodeDO> nodes) {
        String t=stage.trigger();
        return ("NODE_COMPLETED".equals(t) || "ON_FINISH".equals(t) || "LEGACY_TOPPING".equals(t)
                || t==null && ("CAP_DONE".equals(stage.stage()) || "TOPPING".equals(stage.stage()))) && due(stage,nodes)==null;
    }
}
