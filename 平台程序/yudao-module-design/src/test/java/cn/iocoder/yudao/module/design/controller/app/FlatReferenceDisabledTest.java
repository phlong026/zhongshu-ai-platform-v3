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

/** 平面纯文生图：API 层必须在建项目/扣点前拒绝参考案例和手绘草图。 */
class FlatReferenceDisabledTest {

    private AppDesignProjectController controller(DesignProjectService service) {
        var controller = new AppDesignProjectController();
        ReflectionTestUtils.setField(controller, "designProjectService", service);
        IdentitySessionPort identity = token -> Optional.of(
                new IdentitySessionPort.SessionContext(901L, "app", "fixture", false));
        ReflectionTestUtils.setField(controller, "identitySessionPort", identity);
        return controller;
    }

    @Test
    void flatCaseReferenceAndSketchAreRejectedBeforeProjectCreation() {
        var service = mock(DesignProjectService.class);
        var controller = controller(service);

        var caseReference = new AppDesignProjectCreateReqVO();
        caseReference.setSourceType("CASE_REFERENCE");
        caseReference.setRefCaseId("11");
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

        verifyNoInteractions(service);
    }
}
