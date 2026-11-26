package cn.iocoder.yudao.module.franchise.design;

import cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall;
import cn.iocoder.yudao.module.franchise.design.remote.RemoteDesignGateway;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DesignProjectCompanyPointsTest {
    @Test
    void listAndDetailReplaceConsumerCostsWithCompanyNetWithoutChangingOtherFieldsOrInput() {
        var post=new PostSnapshot(2,1,10,"store","sales",null,false,Set.of());
        var remote=mock(RemoteDesignGateway.class);
        Map<String,Object> flat=Map.of("projectId","123","pointsSpent",999,"name","Frozen plan","createdAt","2026-11-16T02:00:00Z");
        Map<String,Object> elevation=Map.of("projectId","456","pointsSpent",999,"name","Frozen facade");
        when(remote.call(eq(post),any(),isNull(),any())).thenAnswer(invocation->{
            DesignFlowCall call=invocation.getArgument(3);
            return call.path().equals("/design-projects")?Map.of("list",List.of(flat,elevation),"nextCursor","next"):flat;
        });
        when(remote.projectPointsSpent(eq(post),anySet())).thenReturn(Map.of(123L,4L,456L,9L));
        var service=new DesignFlowAccessService(null,remote,null);
        var page=(Map<?,?>)service.invoke(post,new DesignFlowCall("GET","/design-projects",Map.of(),Map.of(),"read"));
        var rows=(List<?>)page.get("list");
        assertThat(((Map<?,?>)rows.get(0)).get("pointsSpent")).isEqualTo(4L);
        assertThat(((Map<?,?>)rows.get(1)).get("pointsSpent")).isEqualTo(9L);
        assertThat(page.get("nextCursor")).isEqualTo("next");
        var detail=(Map<?,?>)service.invoke(post,new DesignFlowCall("GET","/design-projects/123",Map.of(),Map.of(),"read"));
        assertThat(detail.get("pointsSpent")).isEqualTo(4L);
        assertThat(detail.get("createdAt")).isEqualTo(flat.get("createdAt"));
        assertThat(detail.get("name")).isEqualTo("Frozen plan");
        assertThat(flat.get("pointsSpent")).isEqualTo(999);
        verify(remote).projectPointsSpent(post,Set.of(123L,456L));
        verify(remote).projectPointsSpent(post,Set.of(123L));
    }
}
