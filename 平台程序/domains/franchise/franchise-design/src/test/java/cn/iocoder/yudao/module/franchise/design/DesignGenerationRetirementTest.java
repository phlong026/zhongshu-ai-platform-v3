package cn.iocoder.yudao.module.franchise.design;

import cn.iocoder.yudao.module.franchise.api.*;
import cn.iocoder.yudao.module.franchise.api.capability.*;
import cn.iocoder.yudao.module.franchise.design.remote.RemoteDesignGateway;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DesignGenerationRetirementTest {
    private final PostSnapshot post=new PostSnapshot(11,22,33,"store","sales",null,false,Set.of());
    private void retired(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                e->assertThat(e.getCode()).isEqualTo(DesignGenerationPolicy.RETIRED));
    }
    @Test void directPaidDrawingMethodsStopBeforeRemoteCallsOrAccountCreation() {
        var legacy=mock(DesignServicePort.class);var remote=mock(RemoteDesignGateway.class);
        var accounts=mock(StoreServiceAccountPort.class);var commands=mock(FranchiseCommands.class);
        var service=new DesignAccessService(legacy,mock(CustomerSchemePort.class),accounts,commands,mock(CurrentPost.class),
                mock(PlatformTransactionManager.class),mock(DataSource.class));
        service.setRemoteGateway(remote);
        retired(()->service.createFlatJob(post,7,null));retired(()->service.createElevationJob(post,7,null));
        retired(()->service.createRevisionRequest(post,7,null));retired(()->service.notePolish(post,"建房要求","FLAT","key"));
        verifyNoInteractions(legacy,remote,accounts,commands);
    }
    @Test void flowAliasesAndOldDrawingPriceRequestsCannotCreateJobsOrChargePoints() {
        var remote=mock(RemoteDesignGateway.class);var points=mock(OrgPointPort.class);
        var flow=new DesignFlowAccessService(mock(DesignAccessService.class),remote,null,null,null,points);
        for(String path:List.of("/design-projects/7/flat-jobs","/design-projects/7/elevation-jobs","/design-projects/7/revision-requests"))
            retired(()->flow.invoke(post,new DesignFlowCall("POST",path,Map.of(),Map.of("count",2),"key")));
        retired(()->flow.quote(post,"ai_flat",2));retired(()->flow.quote(post,"ai_elevation",2));
        verifyNoInteractions(remote,points);
    }
    @Test void independentBudgetsAndHistoricalResultsRemainAvailable() {
        var remote=mock(RemoteDesignGateway.class);
        var flow=new DesignFlowAccessService(mock(DesignAccessService.class),remote,null,null,null,mock(OrgPointPort.class));
        var budget=new DesignFlowCall("POST","/budget-drafts/7/estimates",Map.of(),Map.of("buildingArea",240),"budget-key");
        var value=Map.of("estimateId","9");
        when(remote.call(post,DesignCapabilityContract.Operation.FLOW_BUDGET,"budget-key",budget)).thenReturn(value);
        assertThat(flow.invoke(post,budget)).isEqualTo(value);
        var history=new DesignFlowCall("GET","/design-projects/7/result-versions",Map.of(),Map.of(),"read-key");
        when(remote.call(post,DesignCapabilityContract.Operation.FLOW_READ,null,history)).thenReturn(List.of(Map.of("id","10")));
        assertThat(flow.invoke(post,history)).isEqualTo(List.of(Map.of("id","10")));
    }
}
