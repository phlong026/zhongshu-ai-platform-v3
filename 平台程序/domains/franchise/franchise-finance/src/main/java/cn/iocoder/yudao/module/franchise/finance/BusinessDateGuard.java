package cn.iocoder.yudao.module.franchise.finance;

import java.time.LocalDate;
import java.time.ZoneId;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.BUSINESS_DATE_FUTURE;
import static cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.BUSINESS_DATE_REQUIRED;

/**
 * M08-T05 / R04：业务发生日期写侧契约——正常归单归月日期必填（后端拒绝空值，不静默代填）；
 * 不接受未来发生日期（未来收付由计划表达）。「今天」按业务时区 Asia/Shanghai 判定。
 * 日期待核的真实异常资金单列待归月（读侧不回退 createTime），登记载体随 M08-T08 异常资金登记。
 */
public final class BusinessDateGuard {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private BusinessDateGuard() {
    }

    public static LocalDate requireValid(LocalDate date) {
        if (date == null) {
            throw exception(BUSINESS_DATE_REQUIRED);
        }
        if (date.isAfter(LocalDate.now(BUSINESS_ZONE))) {
            throw exception(BUSINESS_DATE_FUTURE);
        }
        return date;
    }
}
