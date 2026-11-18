package cn.iocoder.yudao.module.franchise.commission;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class CommissionBriefTest {
 @Test void missingAndWronglyTypedRequiredFactsReturnBusinessValidationInsteadOfNullPointer(){
  assertThatThrownBy(()->CommissionBrief.validate(Map.of("projectName","测试"))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
  assertThatThrownBy(()->CommissionBrief.validate(Map.of("projectName",List.of("测试")))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
 }
 @Test void designViewKeepsBudgetSpecificationsWhileRemovingContactAndAllMoney(){
  String source="{\"designBrief\":{\"customer\":{\"phone\":\"19900000000\",\"name\":\"测试\"}},\"BUDGET\":{\"itemizedBudget\":{\"totalCents\":123456,\"items\":[{\"publicName\":\"主体结构\",\"amountCents\":456,\"pricedSubtotalCents\":456,\"lines\":[{\"optionLabel\":\"砖混结构\",\"quantity\":\"240\",\"amountCents\":456}]}]}}}";
  assertThat(CommissionBrief.forDesign(source)).contains("砖混结构","主体结构","240","测试").doesNotContain("19900000000","123456","456","amountCents","totalCents");
 }
}
