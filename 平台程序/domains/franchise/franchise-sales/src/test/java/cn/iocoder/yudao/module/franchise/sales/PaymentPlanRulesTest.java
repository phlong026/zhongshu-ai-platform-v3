package cn.iocoder.yudao.module.franchise.sales;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class PaymentPlanRulesTest {
    private PaymentPlanRules.Input ratio(int bps) { return new PaymentPlanRules.Input(null,"按比例付款",null,bps,null,"MANUAL",null); }
    private PaymentPlanRules.Input amount(long cents) { return new PaymentPlanRules.Input(null,"节点付款",cents,null,null,"MANUAL",null); }

    @Test void finalRowAbsorbsOnlyTheCentRemainder() {
        var rows=PaymentPlanRules.normalize(10001,List.of(ratio(3333),ratio(3333),ratio(3334)));
        assertThat(rows).extracting(PaymentPlanRules.Stage::amountCents).containsExactly(3333L,3333L,3335L);
        assertThat(rows.stream().mapToLong(PaymentPlanRules.Stage::amountCents).sum()).isEqualTo(10001);
    }
    @Test void supportsTwoThreeAndFiveStagesWithoutPrescribedRatios() {
        for(int count:List.of(2,3,5)) {
            var input=java.util.stream.IntStream.range(0,count).mapToObj(i -> amount(10001)).toList();
            assertThat(PaymentPlanRules.normalize(10001L*count,input)).hasSize(count);
        }
    }
    @Test void rejectsInvalidTotalsMixedInputsAndMissingTriggers() {
        assertThatThrownBy(() -> PaymentPlanRules.normalize(10001,List.of(ratio(2000),ratio(7000)))).hasMessageContaining("100%");
        assertThatThrownBy(() -> PaymentPlanRules.normalize(10001,List.of(ratio(5000),amount(5000)))).hasMessageContaining("比例");
        assertThatThrownBy(() -> PaymentPlanRules.normalize(10001,List.of(amount(5000),amount(5000)))).hasMessageContaining("之和");
        assertThatThrownBy(() -> PaymentPlanRules.normalize(10001,List.of(new PaymentPlanRules.Input(null,"日期付款",10001L,null,null,"DATE",null)))).hasMessageContaining("到期条件");
        assertThatThrownBy(() -> PaymentPlanRules.normalize(10001,List.of(new PaymentPlanRules.Input(null,"节点付款",10001L,null,null,"NODE_COMPLETED",null)))).hasMessageContaining("到期条件");
        assertThatThrownBy(() -> PaymentPlanRules.normalize(1,List.of(ratio(5000),ratio(5000)))).hasMessageContaining("大于 0");
    }
    @Test void rejectsRepeatedStableIdsAndTooManyNodes() {
        var one=new PaymentPlanRules.Input(7L,"节点",500L,null,null,"MANUAL",null);
        assertThatThrownBy(() -> PaymentPlanRules.normalize(1000,List.of(one,one))).hasMessageContaining("编号");
        assertThatThrownBy(() -> PaymentPlanRules.normalize(21000,java.util.Collections.nCopies(21,amount(1000)))).hasMessageContaining("20");
    }
    @Test void legacyTriggersRequireAnExistingNodeIdentity() {
        assertThat(PaymentPlanRules.normalize(1000,List.of(new PaymentPlanRules.Input(7L,"原封顶款",1000L,null,null,"LEGACY_TOPPING",null))))
                .extracting(PaymentPlanRules.Stage::trigger).containsExactly("LEGACY_TOPPING");
        assertThatThrownBy(()->PaymentPlanRules.normalize(1000,List.of(new PaymentPlanRules.Input(null,"新节点",1000L,null,null,"LEGACY_TOPPING",null))))
                .hasMessageContaining("到期条件");
    }
}
