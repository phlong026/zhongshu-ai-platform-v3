package cn.iocoder.yudao.module.franchise.design;

import cn.iocoder.yudao.module.franchise.api.CustomerSchemePort;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.design.publiccase.PublicCaseAccessService;
import cn.iocoder.yudao.module.franchise.design.remote.RemoteDesignGateway;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DesignAccessPublicCaseRoutingTest {

    @Test
    void customerLinkedProjectBeyondFiftyPagesCanStillBeFound() {
        var schemes=mock(CustomerSchemePort.class);
        var service=org.mockito.Mockito.spy(new DesignAccessService(mock(DesignServicePort.class), schemes,
                mock(StoreServiceAccountPort.class), mock(FranchiseCommands.class), mock(CurrentPost.class),
                mock(PlatformTransactionManager.class), mock(DataSource.class)));
        var post=new PostSnapshot(1001,101,10,"STORE","DESIGNER",null,false,Set.of());
        when(schemes.listDesignProjectIds(post,42L)).thenReturn(List.of(125L,124L));
        org.mockito.Mockito.doAnswer(call->{
            String cursor=call.getArgument(1);
            long projectId=cursor==null?180L:Long.parseLong(cursor)-1;
            return new DesignServicePort.ProjectPageView(List.of(
                    new DesignServicePort.ProjectListItem(projectId,null,null,null,null,null,null)),
                    projectId<=124?null:Long.toString(projectId));
        }).when(service).listProjects(org.mockito.ArgumentMatchers.eq(post),org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.eq(50));

        var first=service.listProjectsForCustomer(post,42L,null,1);
        org.assertj.core.api.Assertions.assertThat(first.get("nextCursor")).isEqualTo("125");
        var second=service.listProjectsForCustomer(post,42L,"125",1);
        org.assertj.core.api.Assertions.assertThat(second.get("list")).isEqualTo(List.of(
                new DesignServicePort.ProjectListItem(124L,null,null,null,null,null,null)));
        org.assertj.core.api.Assertions.assertThat(second.get("nextCursor")).isNull();
    }

    @Test
    void remoteModeRoutesEveryCatalogAndFavoriteOperationToBLocalAccess() {
        DesignServicePort legacy = mock(DesignServicePort.class);
        var service = new DesignAccessService(legacy, mock(CustomerSchemePort.class),
                mock(StoreServiceAccountPort.class), mock(FranchiseCommands.class), mock(CurrentPost.class),
                mock(PlatformTransactionManager.class), mock(DataSource.class));
        RemoteDesignGateway remote = mock(RemoteDesignGateway.class);
        PublicCaseAccessService publicCases = mock(PublicCaseAccessService.class);
        service.setRemoteGateway(remote);
        service.setPublicCaseAccess(publicCases);
        PostSnapshot post = new PostSnapshot(1001, 101, 10, "STORE", "DESIGNER", null, false, Set.of());
        var query = new DesignServicePort.CaseQuery(null, null, null, null, null, null, 20);
        var page = new DesignServicePort.CasePage(List.of(Map.of("caseId", "7")), null);
        var detail = new DesignServicePort.CaseDetailView(Map.of("caseId", "7"));
        when(publicCases.listCases(post, query)).thenReturn(page);
        when(publicCases.listCaseStyles(post)).thenReturn(List.of());
        when(publicCases.getCaseDetail(post, 7)).thenReturn(detail);
        when(publicCases.listFavorites(post, null, 20)).thenReturn(page);

        service.listCases(post, query);
        service.listCaseStyles(post);
        service.getCaseDetail(post, 7);
        service.favoriteCase(post, 7, "client-key");
        service.unfavoriteCase(post, 7, "client-key");
        service.listFavorites(post, null, 20);

        verify(publicCases).listCases(post, query);
        verify(publicCases).listCaseStyles(post);
        verify(publicCases).getCaseDetail(post, 7);
        verify(publicCases).favoriteCase(post, 7);
        verify(publicCases).unfavoriteCase(post, 7);
        verify(publicCases).listFavorites(post, null, 20);
        verifyNoInteractions(remote, legacy);
    }
}
