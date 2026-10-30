package cn.iocoder.yudao.module.aiorchestration;

import cn.iocoder.yudao.module.aiorchestration.controller.internal.AiJobInternalController;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobSettlementService;
import cn.iocoder.yudao.module.aiorchestration.controller.internal.InternalSignatureVerifier;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * prompt-call-events 的提示词记录是「计费之后的尽力而为」：记录失败不能让计费事件失败，
 * 否则 runtime 会重试整个计费事件；而计费本身失败（陈旧持有者等）时更不能去记录。
 */
class AiJobInternalPromptEventTest {

    private AiJobSettlementService settlement;
    private AiJobInternalController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        settlement = mock(AiJobSettlementService.class);
        var verifier = mock(InternalSignatureVerifier.class);
        when(verifier.verify(any(), any(), any(), any(), any())).thenReturn(true);
        controller = new AiJobInternalController();
        ReflectionTestUtils.setField(controller, "signatureVerifier", verifier);
        ReflectionTestUtils.setField(controller, "settlementService", settlement);
        request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
    }

    private Map<String, Object> body(String outcome, Object promptText, Object model) {
        Map<String, Object> body = new HashMap<>();
        body.put("attemptNo", 2);
        body.put("fencingToken", 77);
        body.put("outcome", outcome);
        body.put("callId", "plan-9");
        body.put("responseHash", "a".repeat(64));
        if (promptText != null) body.put("promptText", promptText);
        if (model != null) body.put("model", model);
        return body;
    }

    @Test
    void successfulPromptCallRecordsPromptAndModelAfterBilling() {
        when(settlement.finishPromptCall(eq(9L), eq(2), eq(77L), eq("SUCCEEDED"), eq("plan-9"), anyString())).thenReturn(true);

        var result = controller.finishPromptCall("9", request, body("SUCCEEDED", "两层别墅提示词", "gpt-fixture"));

        assertThat(result.getData()).isTrue();
        verify(settlement).recordPromptText(9L, 2, 77L, "两层别墅提示词", "gpt-fixture");
    }

    @Test
    void recordingFailureNeverFailsTheBillingEvent() {
        when(settlement.finishPromptCall(anyLong(), anyInt(), anyLong(), anyString(), anyString(), anyString())).thenReturn(true);
        when(settlement.recordPromptText(anyLong(), anyInt(), anyLong(), anyString(), any()))
                .thenThrow(new IllegalStateException("boom"));

        var result = controller.finishPromptCall("9", request, body("SUCCEEDED", "提示词", "m"));

        assertThat(result.getData()).as("计费结果照常返回").isTrue();
    }

    @Test
    void nothingIsRecordedForOtherOutcomesOrNonStringPayloads() {
        when(settlement.finishPromptCall(anyLong(), anyInt(), anyLong(), anyString(), anyString(), any())).thenReturn(false);

        controller.finishPromptCall("9", request, body("DISPATCHED", "不该记录", "m"));
        controller.finishPromptCall("9", request, body("SUCCEEDED", Map.of("evil", "object"), "m"));
        controller.finishPromptCall("9", request, body("SUCCEEDED", null, "m"));

        verify(settlement, never()).recordPromptText(anyLong(), anyInt(), anyLong(), any(), any());
    }

    @Test
    void staleBillingAttemptPropagatesAndSkipsRecording() {
        when(settlement.finishPromptCall(anyLong(), anyInt(), anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("Stale prompt attempt"));

        assertThatThrownBy(() -> controller.finishPromptCall("9", request, body("SUCCEEDED", "提示词", "m")))
                .isInstanceOf(IllegalStateException.class);

        verify(settlement, never()).recordPromptText(anyLong(), anyInt(), anyLong(), any(), any());
    }
}
