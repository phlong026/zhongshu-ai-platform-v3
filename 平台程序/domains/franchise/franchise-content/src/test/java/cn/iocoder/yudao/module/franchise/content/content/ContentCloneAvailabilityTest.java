package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import cn.iocoder.yudao.module.franchise.content.provider.*;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentCloneAvailabilityTest {
    @Test void reportsOrganizationDenialBeforeAskingUserToUploadOrConfirmPrice() {
        OrgPointPort points=mock(OrgPointPort.class);
        PostSnapshot post=new PostSnapshot(9,154,120,"FRANCHISE","sales",null,false,Set.of());
        doThrow(new ServiceException(403,"该组织尚未开通此能力，请联系平台管理员"))
                .when(points).assertSubjectAllowed(120,154,"avatar_clone");
        ContentCloneService service=new ContentCloneService(null,null,null,points,new ContentProviderProperties(),
                new ContentProviderTransport.Scripted(),null,mock(PlatformTransactionManager.class));
        var result=service.quote(post,"AVATAR");
        assertThat(result.get("available")).isEqualTo(false);
        assertThat(result.get("message")).asString().contains("该组织尚未开通");
        verify(points,never()).quoteCredits(anyString(),anyLong());
        assertThatThrownBy(()->service.assertAccess(post,"AVATAR")).isInstanceOf(ServiceException.class);
    }
}
