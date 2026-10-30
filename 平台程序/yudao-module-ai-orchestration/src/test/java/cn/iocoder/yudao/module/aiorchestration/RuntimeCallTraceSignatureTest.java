package cn.iocoder.yudao.module.aiorchestration;

import cn.iocoder.yudao.module.aiorchestration.controller.internal.InternalSignatureVerifier;
import cn.iocoder.yudao.module.aiorchestration.controller.internal.RuntimeCallTraceInternalController;
import cn.iocoder.yudao.module.aiorchestration.job.RuntimeCallTraceService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeCallTraceSignatureTest {
    @Test void realHttpBindingAndRepeatedBodyReadPreserveTheSignedTraceAndNullableFloor() throws Exception {
        var service=mock(RuntimeCallTraceService.class);
        when(service.record(eq(1L),any())).thenReturn(true);
        var signatures=new InternalSignatureVerifier();
        ReflectionTestUtils.setField(signatures,"sharedSecret","trace-fixture-secret");
        ReflectionTestUtils.setField(signatures,"replayWindowSeconds",300L);
        var controller=new RuntimeCallTraceInternalController(service,signatures);
        var ref=new RuntimeCallTraceService.Reference("11",null,"123","CASE_FLOOR_PLAN","a".repeat(64),"a".repeat(64),"image/png",100L);
        var spec=new RuntimeCallTraceService.RequestSpec("flat-v1","model","/chat/completions","系统","保留原要求",null,0.0,1800,null,null,java.util.List.of(ref));
        var event=new RuntimeCallTraceService.Event(1,3,"plan-1",1,3,"PLAN",0,"REQUESTED",spec,null,null);
        String body=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(event),uri="/internal-api/design/v1/ai-jobs/1/call-traces";
        String ts=String.valueOf(System.currentTimeMillis()/1000);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(uri).contentType("application/json").content(body)
                .header("X-ZS-Timestamp",ts).header("X-ZS-Signature",signatures.sign(ts,"POST",uri,body.getBytes(StandardCharsets.UTF_8))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data").value(true));
        verify(service).record(1,event);
    }

    @Test void missingWrongExpiredOrBodyTamperedSignaturesNeverReachPersistence() {
        var service=mock(RuntimeCallTraceService.class);
        var signatures=new InternalSignatureVerifier();
        ReflectionTestUtils.setField(signatures,"sharedSecret","trace-fixture-secret");
        ReflectionTestUtils.setField(signatures,"replayWindowSeconds",300L);
        var controller=new RuntimeCallTraceInternalController(service,signatures);
        String uri="/internal-api/design/v1/ai-jobs/1/call-traces",body="{\"stage\":\"PLAN\"}";
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        for(String mode:new String[]{"missing","wrong","expired","tampered"}) {
            var request=new MockHttpServletRequest("POST",uri);
            request.setContentType("application/json");request.setContent(mode.equals("tampered")?"{}".getBytes(StandardCharsets.UTF_8):bytes);
            String ts=String.valueOf(System.currentTimeMillis()/1000-(mode.equals("expired")?1000:0));
            request.addHeader("X-ZS-Timestamp",ts);
            if(!mode.equals("missing")) request.addHeader("X-ZS-Signature",mode.equals("wrong")?"bad":signatures.sign(ts,"POST",uri,bytes));
            assertThatThrownBy(()->controller.record("1",null,request)).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(service);
    }

    @Test void validSignatureBindsTheExactBodyPathAndForwardsOnlyTheTraceCommand() {
        var service=mock(RuntimeCallTraceService.class);
        var signatures=new InternalSignatureVerifier();
        ReflectionTestUtils.setField(signatures,"sharedSecret","trace-fixture-secret");
        ReflectionTestUtils.setField(signatures,"replayWindowSeconds",300L);
        var controller=new RuntimeCallTraceInternalController(service,signatures);
        var request=new MockHttpServletRequest("POST","/internal-api/design/v1/ai-jobs/1/call-traces");
        request.setContentType("application/json");request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        String ts=String.valueOf(System.currentTimeMillis()/1000);
        request.addHeader("X-ZS-Timestamp",ts);request.addHeader("X-ZS-Signature",signatures.sign(ts,"POST",request.getRequestURI(),"{}".getBytes(StandardCharsets.UTF_8)));
        var event=new RuntimeCallTraceService.Event(1,3,"plan-1",1,3,"PLAN",0,"REQUESTED",null,null,null);
        when(service.record(1,event)).thenReturn(true);
        assertThat(controller.record("1",event,request).getData()).isTrue();
        verify(service).record(1,event);
        request.setRequestURI("/internal-api/design/v1/ai-jobs/2/call-traces");
        assertThatThrownBy(()->controller.record("2",event,request)).isInstanceOf(AccessDeniedException.class);
        verifyNoMoreInteractions(service);
    }

    @Test void realHttpReconciliationBindingUsesItsOwnSignedPathAndOnlyTheMetadataService() throws Exception {
        var service=mock(RuntimeCallTraceService.class);
        when(service.reconcile(eq(1L),any())).thenReturn("RECORDED");
        var signatures=new InternalSignatureVerifier();
        ReflectionTestUtils.setField(signatures,"sharedSecret","trace-fixture-secret");
        ReflectionTestUtils.setField(signatures,"replayWindowSeconds",300L);
        var controller=new RuntimeCallTraceInternalController(service,signatures);
        var spec=new RuntimeCallTraceService.RequestSpec("flat-v1","original-model","/chat/completions","系统","原需求",null,0.0,1800,null,null,java.util.List.of());
        var event=new RuntimeCallTraceService.Event(1,3,"plan-1",1,3,"PLAN",0,"SUCCEEDED",spec,
                new RuntimeCallTraceService.ResponseSpec("原响应","a".repeat(64),null,null,null,null),null);
        String body=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(event);
        String uri="/internal-api/design/v1/ai-jobs/1/call-traces/reconciliations",ts=String.valueOf(System.currentTimeMillis()/1000);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(uri).contentType("application/json").content(body)
                        .header("X-ZS-Timestamp",ts).header("X-ZS-Signature",signatures.sign(ts,"POST",uri,body.getBytes(StandardCharsets.UTF_8))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data").value("RECORDED"));
        verify(service).reconcile(1,event);verifyNoMoreInteractions(service);
    }

    @Test void reconciliationRejectsMissingExpiredWrongPathAndTamperedBodySignaturesBeforePersistence() {
        var service=mock(RuntimeCallTraceService.class);
        var signatures=new InternalSignatureVerifier();
        ReflectionTestUtils.setField(signatures,"sharedSecret","trace-fixture-secret");
        ReflectionTestUtils.setField(signatures,"replayWindowSeconds",300L);
        var controller=new RuntimeCallTraceInternalController(service,signatures);
        String uri="/internal-api/design/v1/ai-jobs/1/call-traces/reconciliations",body="{\"state\":\"SUCCEEDED\"}";
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        for(String mode:new String[]{"missing","expired","wrongPath","tampered"}) {
            var request=new MockHttpServletRequest("POST",uri);
            request.setContentType("application/json");request.setContent(mode.equals("tampered")?"{}".getBytes(StandardCharsets.UTF_8):bytes);
            String ts=String.valueOf(System.currentTimeMillis()/1000-(mode.equals("expired")?1000:0));
            request.addHeader("X-ZS-Timestamp",ts);
            if(!mode.equals("missing"))request.addHeader("X-ZS-Signature",signatures.sign(ts,"POST",
                    mode.equals("wrongPath")?"/internal-api/design/v1/ai-jobs/1/call-traces":uri,bytes));
            assertThatThrownBy(()->controller.reconcile("1",null,request)).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(service);
    }
}
