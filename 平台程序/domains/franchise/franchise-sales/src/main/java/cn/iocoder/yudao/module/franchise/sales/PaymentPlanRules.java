package cn.iocoder.yudao.module.franchise.sales;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/** Money is calculated in integer cents; only the last percentage row absorbs rounding. */
public final class PaymentPlanRules {
    private PaymentPlanRules() {}

    public static final Set<String> TRIGGERS = Set.of("ON_SIGN", "BEFORE_START", "NODE_COMPLETED", "ON_FINISH", "DATE", "MANUAL");
    public record Input(Long stageId, String label, Long amountCents, Integer ratioBps,
                        LocalDate dueDate, String trigger, Integer nodeIndex) {}
    public record Stage(Long stageId, String label, long amountCents, Integer ratioBps,
                        LocalDate dueDate, String trigger, Integer nodeIndex, int sequence) {}

    public static List<Stage> normalize(long total, List<Input> input) {
        if (total <= 0 || total > SaleQuoteMath.MAX_AMOUNT || input == null || input.isEmpty() || input.size() > 20) {
            throw invalid("请填写合同总额和 1 至 20 个付款节点");
        }
        boolean percentages = input.get(0) != null && input.get(0).ratioBps() != null;
        long ratios = 0;
        long sum = 0;
        var ids = new HashSet<Long>();
        var result = new ArrayList<Stage>();
        for (int i = 0; i < input.size(); i++) {
            Input row = input.get(i);
            if (row == null || row.label() == null || row.label().trim().isEmpty()
                    || row.label().trim().length() > 64 || row.label().chars().anyMatch(Character::isISOControl)) {
                throw invalid("付款节点名称必填且不超过 64 字");
            }
            if (row.stageId() != null && (row.stageId() <= 0 || !ids.add(row.stageId()))) {
                throw invalid("付款节点编号重复或无效");
            }
            String trigger = row.trigger() == null ? "MANUAL" : row.trigger();
            boolean retainedLegacy = row.stageId() != null && Set.of("LEGACY_BASE_DONE","LEGACY_TOPPING").contains(trigger);
            if ((!TRIGGERS.contains(trigger) && !retainedLegacy)
                    || ("DATE".equals(trigger) && row.dueDate() == null)
                    || ("NODE_COMPLETED".equals(trigger) && (row.nodeIndex() == null || row.nodeIndex() < 1 || row.nodeIndex() > 100))
                    || (!"NODE_COMPLETED".equals(trigger) && row.nodeIndex() != null)) {
                throw invalid("请填写有效的付款到期条件、日期或工程节点");
            }
            long amount;
            if (percentages) {
                if (row.amountCents() != null || row.ratioBps() == null || row.ratioBps() <= 0 || row.ratioBps() > 10000) {
                    throw invalid("比例计划每期填写大于 0 的比例，不同时填写金额");
                }
                ratios += row.ratioBps();
                amount = i == input.size() - 1 ? total - sum : total * row.ratioBps() / 10000;
            } else {
                if (row.ratioBps() != null || row.amountCents() == null) throw invalid("同一计划统一按金额或比例填写");
                amount = row.amountCents();
            }
            if (amount <= 0 || amount > total) throw invalid("付款节点金额须大于 0 且不超过合同总额");
            sum = Math.addExact(sum, amount);
            result.add(new Stage(row.stageId(), row.label().trim(), amount, row.ratioBps(), row.dueDate(),
                    trigger, row.nodeIndex(), i + 1));
        }
        if (percentages && ratios != 10000) throw invalid("付款比例之和必须为 100%");
        if (sum != total) throw invalid("付款节点金额之和必须等于合同总额");
        return List.copyOf(result);
    }

    public static RuntimeException invalid(String message) {
        return exception(new cn.iocoder.yudao.framework.common.exception.ErrorCode(400, message));
    }
}
