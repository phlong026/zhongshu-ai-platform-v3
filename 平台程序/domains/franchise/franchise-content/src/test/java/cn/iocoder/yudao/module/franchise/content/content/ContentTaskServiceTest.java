package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import cn.iocoder.yudao.module.franchise.content.provider.AsrPort;
import cn.iocoder.yudao.module.franchise.content.provider.ContentProviderProperties;
import cn.iocoder.yudao.module.franchise.content.provider.OralVideoPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务机收口行为回归锁（评审十三）：供应商明确失败必须回退预扣配额（P2-1）、
 * ASR 恢复轮询仅凭任务号（P1-1 服务侧契约——客户端不得再校验音频输入）。
 */
class ContentTaskServiceTest {

    private static FrContentTaskDO task(String kind, String inputJson, String providerTaskId) {
        return new FrContentTaskDO().setId(9L).setCompanyId(100L).setAuthorUserId(7L)
                .setKind(kind).setInputSnapshot(inputJson).setStatus("RUNNING").setProviderTaskId(providerTaskId);
    }

    @Test
    void digitalPollFailureReleasesReservedQuota() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        ContentQuotaService quota = mock(ContentQuotaService.class);
        OralVideoPort oral = mock(OralVideoPort.class);
        ContentTaskService service = new ContentTaskService(store, quota, null, null, null, oral, null,
                null, null, new ContentProviderProperties());
        // 数字人按秒预扣（createDigital）；供应商明确失败（FAILED 快照）必须回退，与 ai_video 同口径
        FrContentTaskDO task = task("digital", "{\"voice\":\"nv\",\"text\":\"测试口播文案内容\"}", "ht-1");
        when(oral.poll(eq("ht-1"), any())).thenReturn(new OralVideoPort.Snapshot("FAILED", null, null));
        when(store.fail(eq(9L), eq(100L), anyString())).thenReturn(true);

        service.pollOne(task);

        verify(store).fail(eq(9L), eq(100L), contains("数字人"));
        verify(quota).release(eq(100L), eq("digital"), anyInt());
    }

    @Test
    void asrPollResumesWithProviderTaskIdOnly() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        ContentQuotaService quota = mock(ContentQuotaService.class);
        AsrPort asr = mock(AsrPort.class);
        ContentTaskService service = new ContentTaskService(store, quota, null, asr, null, null, null,
                null, null, new ContentProviderProperties());
        FrContentTaskDO task = task("asr", "{\"assetId\":5}", "ds-task-1");
        when(asr.transcribe(any())).thenReturn(new AsrPort.Transcript("转写文本", 12.0));
        when(store.succeedPolled(eq(9L), eq(100L), isNull(), anyString())).thenReturn(true);

        service.pollOne(task);

        ArgumentCaptor<AsrPort.AsrCall> captor = ArgumentCaptor.forClass(AsrPort.AsrCall.class);
        verify(asr).transcribe(captor.capture());
        assertThat(captor.getValue().existingTaskId()).isEqualTo("ds-task-1");
        // 恢复轮询不带音频输入——下游 client 不得再走新提交输入校验（评审十三 P1-1）
        assertThat(captor.getValue().audioUrl()).isNull();
    }

}
