package cn.iocoder.yudao.module.franchise.design;

import cn.iocoder.yudao.module.franchise.api.CustomerSchemePort;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PG advisory transaction; transport/CRM ports are fixtures, with CRM ownership tested separately. */
@Testcontainers
class DesignItemizedArchiveServiceTest {
    @Container static final PostgreSQLContainer<?> PG=cn.iocoder.yudao.module.platform.test.TestPostgres.database("postgres:17-alpine");
    private final PostSnapshot post=new PostSnapshot(2,3,4,"STORE","sales",null,false,Set.of());
    private CustomerSchemePort customers;private FranchiseCommands commands;private DesignFlowAccessService flow;private DesignItemizedArchiveService service;private Map<String,Object> snapshot;
    @BeforeEach void setup() {
        var ds=new SimpleDriverDataSource(new org.postgresql.Driver(),PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        customers=mock(CustomerSchemePort.class);commands=mock(FranchiseCommands.class);flow=mock(DesignFlowAccessService.class);
        service=new DesignItemizedArchiveService(customers,commands,flow,ds,new DataSourceTransactionManager(ds),new ObjectMapper());
        snapshot=new LinkedHashMap<>(Map.of("model","ITEMIZED_V1","budgetId","51","revisionId","61","completeness","COMPLETE","totalCents",6800000L,"inputSummary",Map.of("faceWidth","12","depth","10","floorCount",2,"buildingArea","240")));
        when(commands.replayed(any(),anyString(),any())).thenReturn(Optional.empty());
        when(flow.invoke(any(),any())).thenAnswer(inv->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return snapshot;});
        when(customers.saveItemizedBudgetSnapshot(any(),anyLong(),any())).thenAnswer(inv->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();return new CustomerSchemePort.SchemeSaved(71,1);});
    }
    @Test void copiesOnlyTheOwnedFrozenServerAmountAndOriginalDimensionFields() {
        service.save(post,new DesignItemizedArchiveService.Input(41,51,61L),"frozen-budget");
        var capture=ArgumentCaptor.forClass(CustomerSchemePort.SchemeSave.class);verify(customers).saveItemizedBudgetSnapshot(eq(post),eq(41L),capture.capture());
        var saved=capture.getValue();assertThat(saved.width()).isEqualByComparingTo("12");assertThat(saved.depth()).isEqualByComparingTo("10");assertThat(saved.totalCents()).isEqualTo(6800000L);assertThat(saved.payload()).contains("ITEMIZED_V1","61");
        var order=inOrder(customers,flow);order.verify(customers).requireWritable(post,41);order.verify(flow).invoke(eq(post),any());
    }
    @Test void incompleteBudgetsRetainNullTotalAndNeverInventDimensions() {
        snapshot.put("completeness","INCOMPLETE");snapshot.put("totalCents",null);snapshot.put("inputSummary",Map.of("footprintArea","120","floorCount",2,"buildingArea","240"));
        service.save(post,new DesignItemizedArchiveService.Input(41,51,61L),"incomplete-budget");
        var capture=ArgumentCaptor.forClass(CustomerSchemePort.SchemeSave.class);verify(customers).saveItemizedBudgetSnapshot(eq(post),eq(41L),capture.capture());
        assertThat(capture.getValue().totalCents()).isNull();assertThat(capture.getValue().width()).isNull();assertThat(capture.getValue().depth()).isNull();
    }
    @Test void mismatchedVersionOrCustomerPermissionNeverWritesACustomerScheme() {
        assertThatThrownBy(()->service.save(post,new DesignItemizedArchiveService.Input(41,51,62L),"wrong-revision")).isInstanceOf(IllegalStateException.class);
        verify(customers,never()).saveItemizedBudgetSnapshot(any(),anyLong(),any());clearInvocations(flow);
        doThrow(new cn.iocoder.yudao.framework.common.exception.ServiceException(403,"客户不可写")).when(customers).requireWritable(post,42);
        assertThatThrownBy(()->service.save(post,new DesignItemizedArchiveService.Input(42,51,61L),"wrong-customer")).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);verifyNoInteractions(flow);
    }
    @Test void aRecordedReplayReturnsTheFirstSchemeWithoutReadingOrSavingAgain() {
        when(commands.replayed(any(),anyString(),any())).thenReturn(Optional.of(new CommandEventDO().setAggregateId(71L).setRevision(1L)));
        var result=service.save(post,new DesignItemizedArchiveService.Input(41,51,61L),"already-saved");assertThat(result.toString()).contains("71","replayed=true");verifyNoInteractions(flow);verify(customers,never()).saveItemizedBudgetSnapshot(any(),anyLong(),any());
    }
}
