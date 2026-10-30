package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.ai.NotePolishLimiter;
import cn.iocoder.yudao.module.design.ai.NotePolishProperties;
import cn.iocoder.yudao.module.design.ai.NotePolishService;
import cn.iocoder.yudao.module.design.controller.app.AppNotePolishController;
import cn.iocoder.yudao.module.design.controller.app.vo.AppNotePolishReqVO;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 润色端点的鉴权与按账号限流：未登录、受限账号不得触达 runtime；限流按登录账号计，而不是按 IP 或全局 */
class NotePolishControllerTest {

    private final AtomicInteger outbound = new AtomicInteger();

    private AppNotePolishController controller(int perMinute) {
        NotePolishProperties properties = new NotePolishProperties();
        properties.setRuntimeUrl("http://127.0.0.1:8081");
        NotePolishService service = new NotePolishService(properties, "test-internal-secret-0123456789abcdef",
                (url, body, headers) -> {
                    outbound.incrementAndGet();
                    return new NotePolishService.Response(200, "{\"polished\":\"好\"}");
                }, new NotePolishLimiter(perMinute, 100, 8, System::currentTimeMillis));
        IdentitySessionPort sessions = token -> token == null ? Optional.empty() : switch (token) {
            case "alice" -> Optional.of(new IdentitySessionPort.SessionContext(1L, "app", "oa", false));
            case "bob" -> Optional.of(new IdentitySessionPort.SessionContext(2L, "app", "ob", false));
            case "restricted" -> Optional.of(new IdentitySessionPort.SessionContext(3L, "app", "oc", true));
            default -> Optional.empty();
        };
        AppNotePolishController controller = new AppNotePolishController();
        ReflectionTestUtils.setField(controller, "notePolishService", service);
        ReflectionTestUtils.setField(controller, "identitySessionPort", sessions);
        return controller;
    }

    private static AppNotePolishReqVO request(String note) {
        AppNotePolishReqVO vo = new AppNotePolishReqVO();
        vo.setNote(note);
        vo.setLine("FLAT");
        return vo;
    }

    @Test
    void missingInvalidOrRestrictedSessionNeverReachesRuntime() {
        AppNotePolishController controller = controller(10);
        for (String authorization : new String[]{null, "", "Bearer unknown"}) {
            assertThatThrownBy(() -> controller.notePolish(request("老人房在一楼"), authorization))
                    .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(401));
        }
        assertThatThrownBy(() -> controller.notePolish(request("老人房在一楼"), "Bearer restricted"))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IdentitySessionPort.ACCESS_GRANT_REQUIRED));
        assertThat(outbound.get()).as("鉴权失败不得产生任何出站调用").isZero();
    }

    @Test
    void validSessionReturnsPolishedAndRateLimitIsPerLoggedInAccount() {
        AppNotePolishController controller = controller(1);
        assertThat(controller.notePolish(request("老人房在一楼"), "Bearer alice").getData().getPolished()).isEqualTo("好");
        assertThatThrownBy(() -> controller.notePolish(request("老人房在一楼"), "Bearer alice"))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(429));
        assertThat(controller.notePolish(request("老人房在一楼"), "Bearer bob").getData().getPolished())
                .as("另一账号不受影响").isEqualTo("好");
        assertThat(outbound.get()).isEqualTo(2);
    }
}
