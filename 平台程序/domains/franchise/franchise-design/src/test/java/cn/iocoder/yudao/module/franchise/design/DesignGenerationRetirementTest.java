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
        retired(()->service.confirmFlatCandidate(post,7,8L,9));
        retired(()->service.selectElevationCandidate(post,7,8L,9));
        verifyNoInteractions(legacy,remote,accounts,commands);
    }
    @Test void flowAliasesAndOldDrawingPriceRequestsCannotCreateJobsOrChargePoints() {
        var remote=mock(RemoteDesignGateway.class);
        var flow=new DesignFlowAccessService(mock(DesignAccessService.class),remote,null,null,null,mock(OrgPointPort.class));
        for(String path:List.of("/design-projects/7/flat-jobs","/design-projects/7/elevation-jobs","/design-projects/7/revision-requests"))
            retired(()->flow.invoke(post,new DesignFlowCall("POST",path,Map.of(),Map.of("count",2),"key")));
        retired(()->flow.quote(post,"ai_flat",2));retired(()->flow.quote(post,"ai_elevation",2));
        verifyNoInteractions(remote);
    }
    @Test void budgetDraftsEstimatesAndSavingUseTheOriginalRemoteContract() {
        var remote=mock(RemoteDesignGateway.class);
        var flow=new DesignFlowAccessService(mock(DesignAccessService.class),remote,null,null,null,mock(OrgPointPort.class));
        for (String route : List.of("POST /budget-drafts","PUT /budget-drafts/7","POST /budget-drafts/7/estimates",
                "POST /design-projects/7/budget-estimates/itemized","POST /budget-estimates/7/save","POST /budget/my-regions",
                "PUT /budget/my-prices/7","POST /design-projects")) {
            var parts = route.split(" ");
            var command = new DesignFlowCall(parts[0],parts[1],Map.of(),Map.of(),"budget-key");
            var result = Map.of("id","10");
            when(remote.call(post,command.operation(),"budget-key",command)).thenReturn(result);
            assertThat(flow.invoke(post,command)).isEqualTo(result);
        }
        for (String path : List.of("/design-projects/7/flat-selections","/design-projects/7/elevation-selections")) {
            retired(() -> flow.invoke(post,new DesignFlowCall("POST",path,Map.of(),Map.of(),"old-key")));
        }
    }
    @Test void budgetQuotesPreserveCompanyPermissionWithoutLookingUpARate() {
        var points=mock(OrgPointPort.class);
        var flow=new DesignFlowAccessService(mock(DesignAccessService.class),mock(RemoteDesignGateway.class),null,null,null,points);
        var quote=new OrgPointPort.PriceQuote("budget_estimate",1,0,"budget-free-v1");
        assertThat(flow.quote(post,"budget_estimate",1)).isEqualTo(quote);
        verify(points).assertSubjectAllowed(post.orgId(),post.userId(),"budget_estimate");
        assertThatThrownBy(()->flow.quote(post,"budget_estimate",2)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void frozenBudgetsQuotesAndDesignVersionsRemainReadable() {
        var remote=mock(RemoteDesignGateway.class);
        var flow=new DesignFlowAccessService(mock(DesignAccessService.class),remote,null,null,null,mock(OrgPointPort.class));
        for (String path : List.of("/budget-estimates/independent","/budget-estimates/9","/budget-estimates/9/quotes/10",
                "/design-projects/7/result-versions","/design-projects/7/budget-estimates")) {
            var history=new DesignFlowCall("GET",path,Map.of(),Map.of(),"read-key");
            when(remote.call(post,DesignCapabilityContract.Operation.FLOW_READ,null,history)).thenReturn(List.of(Map.of("id","10")));
            assertThat(flow.invoke(post,history)).isEqualTo(List.of(Map.of("id","10")));
        }
    }
}
