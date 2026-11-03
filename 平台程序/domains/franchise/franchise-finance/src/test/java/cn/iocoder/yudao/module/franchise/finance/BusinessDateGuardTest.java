package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.BUSINESS_DATE_FUTURE;
import static cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.BUSINESS_DATE_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** M08-T05 / R04 写侧日期契约：必填拒空（不代填）、拒未来（东八业务时区）、过去与当天放行。 */
class BusinessDateGuardTest {

    @Test
    void nullDateIsRejectedNotSilentlyFilled() {
        assertThatThrownBy(() -> BusinessDateGuard.requireValid(null))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(BUSINESS_DATE_REQUIRED.getCode());
    }

    @Test
    void futureDateIsRejectedByBusinessZone() {
        LocalDate tomorrowShanghai = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1);
        assertThatThrownBy(() -> BusinessDateGuard.requireValid(tomorrowShanghai))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(BUSINESS_DATE_FUTURE.getCode());
    }

    @Test
    void todayAndPastAreAccepted() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        assertThat(BusinessDateGuard.requireValid(today)).isEqualTo(today);
        assertThat(BusinessDateGuard.requireValid(today.minusYears(3))).isEqualTo(today.minusYears(3)); // 历史补录
    }

    @Test
    void businessZoneIsAsiaShanghai() {
        assertThat(BusinessDateGuard.BUSINESS_ZONE).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }
}
