package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeDO;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Baseline stays fixed when the recovery plan changes. Positive = late; negative = early. */
public final class EngProgress {
    static LocalDate today() { return businessDate(java.time.Clock.systemUTC()); }
    static LocalDate businessDate(java.time.Clock clock) {
        return LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Shanghai")));
    }
    private EngProgress() { }
    public static Integer deviationDays(FrEngPlanNodeDO node, LocalDate today) {
        LocalDate end = node.getBaselineEndDate() == null ? node.getEndDate() : node.getBaselineEndDate();
        if (end == null) return null;
        boolean done = "DONE".equals(node.getStatus());
        LocalDate actual = done ? node.getCompletedAt() : today;
        if (actual == null) return null;
        int days = (int) ChronoUnit.DAYS.between(end, actual);
        return done ? days : Math.max(0, days);
    }
    public static boolean overdue(FrEngPlanNodeDO node, LocalDate today) {
        Integer days = deviationDays(node, today);
        return !"DONE".equals(node.getStatus()) && days != null && days > 0;
    }
}
