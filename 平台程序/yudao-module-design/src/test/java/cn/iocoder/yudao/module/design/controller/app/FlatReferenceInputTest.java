package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants;
import cn.iocoder.yudao.module.design.controller.app.vo.AppDesignProjectCreateReqVO;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.*;

/** Case versions, image ownership and sketch validity remain enforced by the original domain. */
class FlatReferenceInputTest {

    private AppDesignProjectController controller(DesignProjectService service) {
        var controller = new AppDesignProjectController();
        ReflectionTestUtils.setField(controller, "designProjectService", service);
        IdentitySessionPort identity = token -> Optional.of(
                new IdentitySessionPort.SessionContext(901L, "app", "fixture", false));
        ReflectionTestUtils.setField(controller, "identitySessionPort", identity);
        return controller;
    }

    @Test
    void flatCaseVersionAndSketchReachTheOriginalDomainValidation() {
        var service = mock(DesignProjectService.class);
        when(service.createProjectWithKey(anyLong(),anyString(),any(),any(),any(),any(),anyString(),any()))
                .thenThrow(new ServiceException(1_071_000_008,"参考素材的版本或授权不可用"));
        var controller = controller(service);

        var caseReference = new AppDesignProjectCreateReqVO();
        caseReference.setSourceType("CASE_REFERENCE");
        caseReference.setRefCaseId("11");
        caseReference.setRefCaseVersionId("21");
        caseReference.setLine("FLAT");
        assertThatThrownBy(() -> controller.createProject(caseReference, "Bearer fixture"))
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(1_071_000_008));

        var sketch = new AppDesignProjectCreateReqVO();
        sketch.setSourceType("SELF_UPLOAD");
        sketch.setSketchAssetId("12");
        sketch.setLine("FLAT");
        assertThatThrownBy(() -> controller.createProject(sketch, "Bearer fixture"))
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(1_071_000_008));

        verify(service).createProjectWithKey(eq(901L),eq("CASE_REFERENCE"),eq(11L),isNull(),any(),isNull(),eq("FLAT"),eq(21L));
        verify(service).createProjectWithKey(eq(901L),eq("SELF_UPLOAD"),isNull(),eq(12L),any(),isNull(),eq("FLAT"),isNull());
    }
}
