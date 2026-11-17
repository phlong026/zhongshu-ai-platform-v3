package cn.iocoder.yudao.module.franchise.commission;
import cn.iocoder.yudao.module.franchise.commission.dal.*;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
class CustomerDesignStatusTest {
 @Test void onlySubmittedCommissionsAppearInCustomerProgressAndEmptyIdsNeverScanTheCompany(){
  com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"status-test"),CommissionDO.class);
  var mapper=mock(CommissionMapper.class);var adapter=new CommissionQueryAdapter();ReflectionTestUtils.setField(adapter,"commissionMapper",mapper);
  assertThat(adapter.customerDesignStatuses(120,List.of())).isEmpty();verifyNoInteractions(mapper);
  when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
   new CommissionDO().setCustomerId(1L).setSubmissionVersion(1).setStatus("DESIGNING"),
   new CommissionDO().setCustomerId(2L).setSubmissionVersion(2).setStatus("FEE_RETURNED"),
   new CommissionDO().setCustomerId(3L).setSubmissionVersion(0).setStatus("DESIGNING")));
  assertThat(adapter.customerDesignStatuses(120,List.of(1L,2L,3L))).containsExactlyInAnyOrderEntriesOf(Map.of(1L,"DESIGNING",2L,"FEE_RETURNED"));
 }
}
