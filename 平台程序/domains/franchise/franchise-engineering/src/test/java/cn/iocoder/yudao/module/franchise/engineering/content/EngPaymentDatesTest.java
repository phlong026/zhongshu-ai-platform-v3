package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.StageBalance;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeDO;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class EngPaymentDatesTest {
    private final LocalDate today=LocalDate.of(2026,11,25);
    @Test void aPlannedDateDoesNotPretendTheSpecifiedNodeHasCompleted() {
        var node=new FrEngPlanNodeDO().setNodeIndex(4).setStatus("PENDING").setEndDate(today.minusDays(10));
        var payment=balance("NODE_COMPLETED",4,null);
        assertThat(EngPaymentDates.due(payment,List.of(node))).isNull();
        assertThat(EngPaymentDates.waitingForWork(payment,List.of(node))).isTrue();
        node.setStatus("DONE").setCompletedAt(today);
        assertThat(EngPaymentDates.due(payment,List.of(node))).isEqualTo(today);
        assertThat(EngPaymentDates.waitingForWork(payment,List.of(node))).isFalse();
    }
    @Test void finishWaitsForAllActualCompletionDatesAndManualDoesNotUseStartDate() {
        var first=new FrEngPlanNodeDO().setNodeIndex(1).setMilestone("START").setStatus("DONE").setStartDate(today.minusDays(20)).setCompletedAt(today.minusDays(20));
        var last=new FrEngPlanNodeDO().setNodeIndex(2).setStatus("IN_PROGRESS").setEndDate(today.minusDays(1));
        assertThat(EngPaymentDates.due(balance("ON_FINISH",null,null),List.of(first,last))).isNull();
        last.setStatus("DONE").setCompletedAt(today);
        assertThat(EngPaymentDates.due(balance("ON_FINISH",null,null),List.of(first,last))).isEqualTo(today);
        assertThat(EngPaymentDates.due(balance("MANUAL",null,null),List.of(first,last))).isNull();
        assertThat(EngPaymentDates.due(balance("DATE",null,today.plusDays(30)),List.of(first,last))).isEqualTo(today.plusDays(30));
        assertThat(EngPaymentDates.beforeStart("MANUAL","STAGE_1")).isFalse();
        assertThat(EngPaymentDates.beforeStart("ON_SIGN","STAGE_1")).isTrue();
        assertThat(EngPaymentDates.beforeStart("BEFORE_START","STAGE_2")).isTrue();
    }
    private StageBalance balance(String trigger,Integer index,LocalDate due) {
        return new StageBalance("STAGE_1",100,0,due,1L,0,"约定款",trigger,index,1);
    }
}
