package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import cn.iocoder.yudao.module.franchise.content.provider.AsrPort;
import cn.iocoder.yudao.module.franchise.content.provider.ContentProviderException;
import cn.iocoder.yudao.module.franchise.content.provider.ContentProviderProperties;
import cn.iocoder.yudao.module.franchise.content.provider.LinkParsePort;
import cn.iocoder.yudao.module.franchise.content.provider.OralVideoPort;
import cn.iocoder.yudao.module.franchise.content.provider.ScriptRewritePort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 任务机收口行为回归锁：供应商明确失败释放公司池预扣、
 * ASR 恢复轮询仅凭任务号（P1-1 服务侧契约——客户端不得再校验音频输入）。
 */
class ContentTaskServiceTest {

    private static OrgPointPort.Settlement settlement() {
        return new OrgPointPort.Settlement(91L, 1L, 0L, false);
    }

    private static FrContentTaskDO task(String kind, String inputJson, String providerTaskId) {
        return new FrContentTaskDO().setId(9L).setCompanyId(100L).setAuthorUserId(7L)
                .setKind(kind).setInputSnapshot(inputJson).setStatus("RUNNING").setProviderTaskId(providerTaskId);
    }

    @Test
    void digitalPollFailureReleasesCompanyPoolWithoutTouchingLegacyDailyUsage() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        ContentQuotaService quota = mock(ContentQuotaService.class);
        OralVideoPort oral = mock(OralVideoPort.class);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        ContentTaskService service = new ContentTaskService(store, quota, null, null, null, oral, null,
                null, null, new ContentProviderProperties(), orgPoint);
        // 数字人按秒预扣（createDigital）；供应商明确失败（FAILED 快照）必须回退，与 ai_video 同口径
        FrContentTaskDO task = task("digital", "{\"voice\":\"nv\",\"text\":\"测试口播文案内容\"}", "ht-1");
        when(oral.poll(eq("ht-1"), any())).thenReturn(new OralVideoPort.Snapshot("FAILED", null, null));
        when(store.claimPoll(eq(9L), anyString(), anyLong())).thenReturn(true);
        when(store.fail(eq(9L), eq(100L), anyString())).thenReturn(true);
        when(store.byId(100L, 9L)).thenReturn(task.setStatus("FAILED").setPoolBizKey("content:9:digital"));
        when(orgPoint.releaseByBizKey("content:9:digital")).thenReturn(settlement());

        service.pollOne(task);

        verify(store).fail(eq(9L), eq(100L), contains("数字人"));
        verifyNoInteractions(quota);
        verify(orgPoint).releaseByBizKey("content:9:digital");
    }

    @Test
    void paidTaskCanExceedLegacyDailyDefaultWhenCompanyPoolAllowsIt() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        ContentQuotaService legacyDailyUsage = mock(ContentQuotaService.class);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        FrContentTaskDO inserted = task("digital", "{}", null).setStatus("QUEUED");
        when(store.insert(anyLong(), eq(100L), eq(7L), eq("digital"), anyString(), isNull()))
                .thenReturn(inserted);
        ContentTaskService service = new ContentTaskService(store, legacyDailyUsage, null, null, null, null, null,
                null, null, new ContentProviderProperties(), orgPoint);
        // 超长文案的预估秒数可超过旧日配额；现在唯一门槛是公司池。
        String longScript = "乡墅内容".repeat(6000);

        service.createDigital(100L, 7L, "voice", longScript, "avatar", null, null);

        ArgumentCaptor<OrgPointPort.ReserveCommand> command = ArgumentCaptor.forClass(OrgPointPort.ReserveCommand.class);
        verify(orgPoint).reserve(command.capture());
        assertThat(command.getValue().subject()).isEqualTo("digital");
        assertThat(command.getValue().quantity()).isGreaterThan(3600L); // 旧实现默认日上限
        verifyNoInteractions(legacyDailyUsage);
    }

    @Test
    void everyContentSubjectUsesCompanyPoolWithoutLegacyDailyGate() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        ContentQuotaService legacyDailyUsage = mock(ContentQuotaService.class);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        when(store.insert(anyLong(), eq(100L), eq(7L), anyString(), anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class))).thenAnswer(invocation ->
                new FrContentTaskDO().setId(invocation.getArgument(0)).setCompanyId(100L).setAuthorUserId(7L)
                        .setKind(invocation.getArgument(3)).setInputSnapshot(invocation.getArgument(4))
                        .setStatus("QUEUED"));
        ContentTaskService service = new ContentTaskService(store, legacyDailyUsage, null, null, null, null, null,
                null, null, new ContentProviderProperties(), orgPoint);

        service.createLinkParse(100L, 7L, "https://example/video");
        service.createAsr(100L, 7L, 1L, null, "sha-1", 10.0, null);
        service.createCopy(100L, 7L, "原文", null, null, null);
        service.createDigital(100L, 7L, "voice", "口播", "avatar", null, null);
        service.createAiVideo(100L, 7L, "乡墅", List.of(), "768P", 8, "16:9");
        service.createAiVideo(100L, 7L, "乡墅", List.of(), "2K", 8, "16:9");

        ArgumentCaptor<OrgPointPort.ReserveCommand> commands =
                ArgumentCaptor.forClass(OrgPointPort.ReserveCommand.class);
        verify(orgPoint, times(6)).reserve(commands.capture());
        assertThat(commands.getAllValues()).extracting(OrgPointPort.ReserveCommand::subject)
                .containsExactly("link_resolution", "asr", "copy", "digital", "ai_video_768p", "ai_video_2k");
        ArgumentCaptor<Long> frozenUnits = ArgumentCaptor.forClass(Long.class);
        verify(store, times(6)).markPoolReservation(anyLong(), eq(100L), anyString(), frozenUnits.capture());
        assertThat(frozenUnits.getAllValues()).containsExactly(1L, 1L, 1L, 1L, 8L, 8L);
        verifyNoInteractions(legacyDailyUsage);
    }

    @Test
    void paidTaskStopsBeforePersistenceWhenPoolRejectsBalanceOrPermission() {
        for (String reason : new String[]{"公司积分余额不足", "当前员工无 copy 权限"}) {
            ContentTaskStore store = mock(ContentTaskStore.class);
            OrgPointPort orgPoint = mock(OrgPointPort.class);
            when(orgPoint.reserve(any())).thenThrow(new IllegalStateException(reason));
            ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                    null, null, null, null, null, null, null, new ContentProviderProperties(), orgPoint);

            assertThatThrownBy(() -> service.createCopy(100L, 7L, "原文", null, null, null))
                    .isInstanceOf(IllegalStateException.class).hasMessage(reason);
            verify(store, never()).insert(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any());
        }
    }

    @Test
    void digitalSuccessSettlesActualDeliveredSecondsAndReplayDoesNotCreateAnotherReservation() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        ContentQuotaService quota = mock(ContentQuotaService.class);
        OralVideoPort oral = mock(OralVideoPort.class);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        ContentTaskService service = new ContentTaskService(store, quota, null, null, null, oral, null,
                null, null, new ContentProviderProperties(), orgPoint);
        FrContentTaskDO task = task("digital", "{\"voice\":\"nv\",\"text\":\"测试口播文案内容\"}", "ht-1")
                .setPoolBizKey("content:9:digital");
        FrContentTaskDO succeeded = task("digital", task.getInputSnapshot(), "ht-1")
                .setStatus("SUCCEEDED").setPoolBizKey("content:9:digital")
                .setPoolReservedUnits(12L)
                .setResultSnapshot("{\"durationSec\":12}");
        when(oral.poll(eq("ht-1"), any())).thenReturn(new OralVideoPort.Snapshot("DONE", "https://v/1.mp4", 12));
        when(store.claimPoll(eq(9L), anyString(), anyLong())).thenReturn(true);
        when(store.succeedPolled(eq(9L), eq(100L), eq("https://v/1.mp4"), anyString())).thenReturn(true);
        when(store.byId(100L, 9L)).thenReturn(succeeded);
        when(orgPoint.settleByBizKey("content:9:digital", 12L)).thenReturn(settlement());

        service.pollOne(task);
        service.reconcilePool(succeeded);

        verify(orgPoint, times(2)).settleByBizKey("content:9:digital", 12L);
        verify(orgPoint, never()).reserve(any());
    }

    @Test
    void submissionUnknownKeepsPoolPending() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        ContentQuotaService quota = mock(ContentQuotaService.class);
        OralVideoPort oral = mock(OralVideoPort.class);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        ContentTaskService service = new ContentTaskService(store, quota, null, null, null, oral, null,
                null, null, new ContentProviderProperties(), orgPoint);
        FrContentTaskDO uncertain = task("digital", "{\"text\":\"内容\"}", "ht-1")
                .setStatus("SUBMISSION_UNCERTAIN").setPoolBizKey("content:9:digital");
        when(oral.poll(eq("ht-1"), any())).thenReturn(new OralVideoPort.Snapshot("UNKNOWN", null, null));
        when(store.claimPoll(eq(9L), anyString(), anyLong())).thenReturn(true);
        when(store.byId(100L, 9L)).thenReturn(uncertain);

        service.pollOne(uncertain);

        verify(orgPoint, never()).settleByBizKey(anyString(), anyLong());
        verify(orgPoint, never()).releaseByBizKey(anyString());
    }

    @Test
    void asrCacheHitChecksPermissionButDoesNotReservePoints() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        ContentQuotaService quota = mock(ContentQuotaService.class);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        ContentTaskService service = new ContentTaskService(store, quota, null, null, null, null, null,
                null, null, new ContentProviderProperties(), orgPoint);
        FrContentTaskDO cached = task("asr", "{}", null).setStatus("SUCCEEDED")
                .setProvider("dashscope").setResultSnapshot("{\"text\":\"已缓存\"}");
        FrContentTaskDO inserted = task("asr", "{}", null).setStatus("QUEUED");
        when(store.insert(anyLong(), eq(100L), eq(7L), eq("asr"), anyString(), eq("sha-1"))).thenReturn(inserted);

        service.createAsr(100L, 7L, 5L, null, "sha-1", 10.0, cached);

        verify(orgPoint).assertSubjectAllowed(100L, 7L, "asr");
        verify(orgPoint, never()).reserve(any());
    }

    @Test
    void asrPollResumesWithProviderTaskIdOnly() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        ContentQuotaService quota = mock(ContentQuotaService.class);
        AsrPort asr = mock(AsrPort.class);
        ContentTaskService service = new ContentTaskService(store, quota, null, asr, null, null, null,
                null, null, new ContentProviderProperties(), mock(OrgPointPort.class));
        FrContentTaskDO task = task("asr", "{\"assetId\":5}", "ds-task-1");
        when(asr.transcribe(any())).thenReturn(new AsrPort.Transcript("转写文本", 12.0));
        when(store.claimPoll(eq(9L), anyString(), anyLong())).thenReturn(true);
        when(store.succeedPolled(eq(9L), eq(100L), isNull(), anyString())).thenReturn(true);

        service.pollOne(task);

        ArgumentCaptor<AsrPort.AsrCall> captor = ArgumentCaptor.forClass(AsrPort.AsrCall.class);
        verify(asr).transcribe(captor.capture());
        assertThat(captor.getValue().existingTaskId()).isEqualTo("ds-task-1");
        // 恢复轮询不带音频输入——下游 client 不得再走新提交输入校验（评审十三 P1-1）
        assertThat(captor.getValue().audioUrl()).isNull();
    }

    @Test
    void definitiveCopyFailureFallsBackToFreeRulesAndReleasesReservation() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        ScriptRewritePort rewrite = mock(ScriptRewritePort.class);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                null, null, rewrite, null, null, null, null, new ContentProviderProperties(), orgPoint);
        FrContentTaskDO task = task("copy", "{\"sourceText\":\"原文\",\"requirement\":\"口播\"}", null)
                .setStatus("QUEUED").setPoolBizKey("content:9:copy");
        FrContentTaskDO freeResult = task("copy", task.getInputSnapshot(), null)
                .setStatus("SUCCEEDED").setPoolBizKey("content:9:copy")
                .setPoolTerminalIntent("RELEASE").setResultSnapshot("{\"engine\":\"rules\"}");
        when(store.claim(9L)).thenReturn(true);
        when(store.markDispatchStarted(9L, 100L)).thenReturn(true);
        when(rewrite.rewrite(any())).thenThrow(new ContentProviderException.Rejection("确定未受理", 422, "bad"));
        when(store.succeedRules(eq(9L), eq(100L), anyString())).thenReturn(true);
        when(store.byId(100L, 9L)).thenReturn(freeResult);
        when(orgPoint.releaseByBizKey("content:9:copy")).thenReturn(settlement());

        service.submitOne(task);

        verify(store).succeedRules(eq(9L), eq(100L), contains("rules"));
        verify(orgPoint).releaseByBizKey("content:9:copy");
        verify(orgPoint, never()).settleByBizKey(anyString(), anyLong());
    }

    @Test
    void copyNetworkUnknownKeepsPendingAndNeverFabricatesRulesSuccess() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        ScriptRewritePort rewrite = mock(ScriptRewritePort.class);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                null, null, rewrite, null, null, null, null, new ContentProviderProperties(), orgPoint);
        FrContentTaskDO task = task("copy", "{\"sourceText\":\"原文\"}", null)
                .setStatus("QUEUED").setPoolBizKey("content:9:copy");
        FrContentTaskDO uncertain = task("copy", task.getInputSnapshot(), null)
                .setStatus("SUBMISSION_UNCERTAIN").setPoolBizKey("content:9:copy");
        when(store.claim(9L)).thenReturn(true);
        when(store.markDispatchStarted(9L, 100L)).thenReturn(true);
        when(rewrite.rewrite(any())).thenThrow(new ContentProviderException.Uncertain("网络结果未知"));
        when(store.markUncertain(9L, 100L, "网络结果未知")).thenReturn(true);
        when(store.byId(100L, 9L)).thenReturn(uncertain);

        service.submitOne(task);

        verify(store, never()).succeedRules(anyLong(), anyLong(), anyString());
        verify(orgPoint, never()).releaseByBizKey(anyString());
        verify(orgPoint, never()).settleByBizKey(anyString(), anyLong());
    }

    @Test
    void providerSuccessThenResultPersistenceFailureBecomesUnknownWithoutRelease() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        LinkParsePort link = mock(LinkParsePort.class);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                link, null, null, null, null, null, null, new ContentProviderProperties(), orgPoint);
        FrContentTaskDO task = task("link_parse", "{\"url\":\"https://example/video\"}", null)
                .setStatus("QUEUED").setPoolBizKey("content:9:link_resolution");
        FrContentTaskDO uncertain = task("link_parse", task.getInputSnapshot(), null)
                .setStatus("SUBMISSION_UNCERTAIN").setPoolBizKey("content:9:link_resolution");
        when(store.claim(9L)).thenReturn(true);
        when(store.markDispatchStarted(9L, 100L)).thenReturn(true);
        when(link.parse(anyString(), any())).thenReturn(new LinkParsePort.ParsedLink(
                "douyin", "https://cdn/video", null, "title", "text", null, null, 1000));
        when(store.succeedSync(eq(9L), eq(100L), anyString(), isNull(), isNull(), isNull(), anyString()))
                .thenThrow(new IllegalStateException("数据库写入失败"));
        when(store.failBeforeDispatch(eq(9L), eq(100L), anyString())).thenReturn(false);
        when(store.markUncertain(eq(9L), eq(100L), contains("结果未知"))).thenReturn(true);
        when(store.byId(100L, 9L)).thenReturn(uncertain);

        service.submitOne(task);

        verify(store).markUncertain(eq(9L), eq(100L), contains("结果未知"));
        verify(orgPoint, never()).releaseByBizKey(anyString());
        verify(orgPoint, never()).settleByBizKey(anyString(), anyLong());
    }

    @Test
    void localPreparationFailureBeforeDispatchFailsAndReleasesReservation() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        AssetAccessPort assets = mock(AssetAccessPort.class);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                null, null, null, null, mock(cn.iocoder.yudao.module.franchise.content.provider.VideoGenerationPort.class),
                assets, null, new ContentProviderProperties(), orgPoint);
        FrContentTaskDO task = task("ai_video", "{\"description\":\"乡墅\",\"references\":[{\"role\":\"reference\","
                + "\"assetId\":5}],\"resolution\":\"768P\",\"duration\":8}", null)
                .setStatus("QUEUED").setPoolBizKey("content:9:ai_video_768p");
        FrContentTaskDO failed = task("ai_video", task.getInputSnapshot(), null)
                .setStatus("FAILED").setPoolBizKey("content:9:ai_video_768p");
        when(store.claim(9L)).thenReturn(true);
        when(assets.createTaskDownloadUrl(100L, 5L, "CONTENT_ASSET"))
                .thenThrow(new IllegalArgumentException("本地素材校验失败"));
        when(store.failBeforeDispatch(eq(9L), eq(100L), anyString())).thenReturn(true);
        when(store.byId(100L, 9L)).thenReturn(failed);
        when(orgPoint.releaseByBizKey("content:9:ai_video_768p")).thenReturn(settlement());

        service.submitOne(task);

        verify(store, never()).markDispatchStarted(anyLong(), anyLong());
        verify(orgPoint).releaseByBizKey("content:9:ai_video_768p");
    }

    @Test
    void poolSettlementFailurePersistsBackoffForRecoveryAndAlerting() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                null, null, null, null, null, null, null, new ContentProviderProperties(), orgPoint);
        FrContentTaskDO succeeded = task("copy", "{\"sourceText\":\"原文\"}", null)
                .setStatus("SUCCEEDED").setPoolBizKey("content:9:copy")
                .setPoolReservedUnits(1L)
                .setPoolTerminalIntent("SETTLE").setResultSnapshot("{\"text\":\"完成\"}");
        when(store.byId(100L, 9L)).thenReturn(succeeded);
        when(orgPoint.settleByBizKey("content:9:copy", 1L))
                .thenThrow(new IllegalStateException("账本短暂不可用"));
        when(store.deferPoolReconcile(9L, 100L, null, "账本短暂不可用")).thenReturn(3);

        service.reconcilePool(succeeded);

        verify(store).deferPoolReconcile(9L, 100L, null, "账本短暂不可用");
    }

    @Test
    void manualUnknownDeliveredSettlesActualQuantityAndPersistsAudit() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                null, null, null, null, null, null, null, new ContentProviderProperties(), orgPoint);
        PostSnapshot verifier = new PostSnapshot(18L, 8L, 100L, "store", "sales-lead", null, true, Set.of());
        FrContentTaskDO verified = task("copy", "{\"sourceText\":\"原文\"}", null)
                .setStatus("SUCCEEDED").setPoolBizKey("content:9:copy")
                .setManualVerifyDecision("DELIVERED").setManualVerifyActualUnits(1L)
                .setManualVerifyReason("供应商确认交付").setManualVerifyEvidence("回执A");
        when(store.byManualVerifyKey(100L, "verify-1")).thenReturn(null);
        when(store.byId(100L, 9L)).thenReturn(
                task("copy", "{\"sourceText\":\"原文\"}", null).setPoolReservedUnits(1L), verified);
        when(store.manualVerify(9L, 100L, 8L, 18L, "DELIVERED", 1L,
                "供应商确认交付", "回执A", "verify-1")).thenReturn(true);
        when(orgPoint.settleByBizKey("content:9:copy", 1L)).thenReturn(settlement());

        assertThat(service.manualVerify(verifier, 9L, "DELIVERED", 1L,
                "供应商确认交付", "回执A", "verify-1")).isSameAs(verified);

        verify(orgPoint).settleByBizKey("content:9:copy", 1L);
        verify(orgPoint, never()).releaseByBizKey(anyString());
    }

    @Test
    void manualUnknownDeliveredRejectsClientQuantityDifferentFromFrozenTaskQuantity() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                null, null, null, null, null, null, null, new ContentProviderProperties(), orgPoint);
        PostSnapshot verifier = new PostSnapshot(18L, 8L, 100L, "store", "boss", null, false, Set.of());
        FrContentTaskDO uncertain = task("digital", "{\"text\":\"一二三四五六七八\"}", null)
                .setStatus("SUBMISSION_UNCERTAIN").setPoolBizKey("content:9:digital")
                .setPoolReservedUnits(4L);
        when(store.byId(100L, 9L)).thenReturn(uncertain);

        assertThatThrownBy(() -> service.manualVerify(verifier, 9L, "DELIVERED", 1L,
                "已交付", "供应商回执", "verify-under-report"))
                .hasMessageContaining("提交内容不合法");
        assertThatThrownBy(() -> service.manualVerify(verifier, 9L, "DELIVERED", 5L,
                "已交付", "供应商回执", "verify-over-report"))
                .hasMessageContaining("提交内容不合法");

        verify(store, never()).manualVerify(anyLong(), anyLong(), anyLong(), anyLong(), anyString(),
                any(), anyString(), anyString(), anyString());
        verifyNoInteractions(orgPoint);
    }

    @Test
    void manualUnknownDeliveredUsesFrozenTaskQuantityAndNullPoolFactFails() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                null, null, null, null, null, null, null, new ContentProviderProperties(), orgPoint);
        PostSnapshot verifier = new PostSnapshot(18L, 8L, 100L, "store", "boss", null, false, Set.of());
        FrContentTaskDO uncertain = task("ai_video", "{\"duration\":8}", null)
                .setStatus("SUBMISSION_UNCERTAIN").setPoolBizKey("content:9:ai_video_768p")
                .setPoolReservedUnits(8L);
        FrContentTaskDO verified = task("ai_video", uncertain.getInputSnapshot(), null)
                .setStatus("SUCCEEDED").setPoolBizKey("content:9:ai_video_768p")
                .setManualVerifyDecision("DELIVERED").setManualVerifyActualUnits(8L)
                .setManualVerifyReason("已交付").setManualVerifyEvidence("供应商回执");
        when(store.byId(100L, 9L)).thenReturn(uncertain, verified);
        when(store.manualVerify(9L, 100L, 8L, 18L, "DELIVERED", 8L,
                "已交付", "供应商回执", "verify-frozen")).thenReturn(true);
        when(orgPoint.settleByBizKey("content:9:ai_video_768p", 8L)).thenReturn(null);

        assertThatThrownBy(() -> service.manualVerify(verifier, 9L, "DELIVERED", 8L,
                "已交付", "供应商回执", "verify-frozen"))
                .hasMessageContaining("积分池资金事实缺失");
    }

    @Test
    void nullPoolSettlementDefersRecoveryInsteadOfMarkingComplete() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                null, null, null, null, null, null, null, new ContentProviderProperties(), orgPoint);
        FrContentTaskDO succeeded = task("copy", "{\"sourceText\":\"原文\"}", null)
                .setStatus("SUCCEEDED").setPoolBizKey("content:9:copy").setPoolReservedUnits(1L)
                .setPoolTerminalIntent("SETTLE");
        when(store.byId(100L, 9L)).thenReturn(succeeded);
        when(orgPoint.settleByBizKey("content:9:copy", 1L)).thenReturn(null);
        when(store.deferPoolReconcile(eq(9L), eq(100L), isNull(), contains("积分池资金事实缺失")))
                .thenReturn(1);

        service.reconcilePool(succeeded);

        verify(store, never()).completePoolReconcile(anyLong(), anyLong(), any());
        verify(store).deferPoolReconcile(eq(9L), eq(100L), isNull(), contains("积分池资金事实缺失"));
    }

    @Test
    void nullPoolReleaseAlsoDefersRecoveryInsteadOfMarkingComplete() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                null, null, null, null, null, null, null, new ContentProviderProperties(), orgPoint);
        FrContentTaskDO failed = task("copy", "{\"sourceText\":\"原文\"}", null)
                .setStatus("FAILED").setPoolBizKey("content:9:copy").setPoolTerminalIntent("RELEASE");
        when(store.byId(100L, 9L)).thenReturn(failed);
        when(orgPoint.releaseByBizKey("content:9:copy")).thenReturn(null);
        when(store.deferPoolReconcile(eq(9L), eq(100L), isNull(), contains("积分池资金事实缺失")))
                .thenReturn(1);

        service.reconcilePool(failed);

        verify(store, never()).completePoolReconcile(anyLong(), anyLong(), any());
        verify(store).deferPoolReconcile(eq(9L), eq(100L), isNull(), contains("积分池资金事实缺失"));
    }

    @Test
    void manualUnknownNotDeliveredReleasesAndUnauthorizedRoleIsRejected() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                null, null, null, null, null, null, null, new ContentProviderProperties(), orgPoint);
        PostSnapshot boss = new PostSnapshot(18L, 8L, 100L, "store", "boss", null, false, Set.of());
        PostSnapshot sales = new PostSnapshot(19L, 9L, 100L, "store", "sales", null, false, Set.of());
        FrContentTaskDO verified = task("copy", "{\"sourceText\":\"原文\"}", null)
                .setStatus("FAILED").setPoolBizKey("content:9:copy")
                .setManualVerifyDecision("NOT_DELIVERED").setManualVerifyReason("未交付")
                .setManualVerifyEvidence("供应商查询截图");
        when(store.manualVerify(9L, 100L, 8L, 18L, "NOT_DELIVERED", null,
                "未交付", "供应商查询截图", "verify-2")).thenReturn(true);
        when(store.byId(100L, 9L)).thenReturn(task("copy", "{\"sourceText\":\"原文\"}", null), verified);
        when(orgPoint.releaseByBizKey("content:9:copy")).thenReturn(settlement());

        service.manualVerify(boss, 9L, "NOT_DELIVERED", null, "未交付", "供应商查询截图", "verify-2");
        verify(orgPoint).releaseByBizKey("content:9:copy");

        assertThatThrownBy(() -> service.manualVerify(sales, 9L, "NOT_DELIVERED", null,
                "未交付", "供应商查询截图", "verify-3"))
                .hasMessageContaining("无内容创作权限");
        verify(store, times(1)).manualVerify(anyLong(), anyLong(), anyLong(), anyLong(),
                anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void manualVerificationAuditRequiresVerifierAndRemainsOrganizationScoped() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                null, null, null, null, null, null, null, new ContentProviderProperties(),
                mock(OrgPointPort.class));
        PostSnapshot boss = new PostSnapshot(18L, 8L, 100L, "store", "boss", null, false, Set.of());
        PostSnapshot sales = new PostSnapshot(19L, 9L, 100L, "store", "sales", null, false, Set.of());
        FrContentTaskDO audited = task("copy", "{\"sourceText\":\"原文\"}", null)
                .setStatus("SUCCEEDED").setManualVerifyDecision("DELIVERED")
                .setManualVerifyReason("已交付").setManualVerifyEvidence("供应商回执");
        when(store.byId(100L, 9L)).thenReturn(audited);

        assertThat(service.requireVerificationAudit(boss, 9L)).isSameAs(audited);
        assertThatThrownBy(() -> service.requireVerificationAudit(sales, 9L))
                .hasMessageContaining("无内容创作权限");
        verify(store, times(1)).byId(100L, 9L);
    }

    @Test
    void manualVerificationIdempotentReplayDoesNotTouchPoolAgain() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                null, null, null, null, null, null, null, new ContentProviderProperties(), orgPoint);
        PostSnapshot verifier = new PostSnapshot(18L, 8L, 100L, "store", "boss", null, false, Set.of());
        FrContentTaskDO replay = task("copy", "{\"sourceText\":\"原文\"}", null)
                .setStatus("SUCCEEDED").setPoolBizKey("content:9:copy")
                .setManualVerifyDecision("DELIVERED").setManualVerifyActualUnits(1L)
                .setManualVerifyReason("已交付").setManualVerifyEvidence("回执A")
                .setManualVerifyIdempotencyKey("verify-replay");
        when(store.byManualVerifyKey(100L, "verify-replay")).thenReturn(replay);

        assertThat(service.manualVerify(verifier, 9L, "DELIVERED", 1L,
                "已交付", "回执A", "verify-replay")).isSameAs(replay);

        verify(store, never()).manualVerify(anyLong(), anyLong(), anyLong(), anyLong(), anyString(),
                any(), anyString(), anyString(), anyString());
        verifyNoInteractions(orgPoint);
    }

    @Test
    void concurrentSameManualVerificationKeyReplaysAfterCasLoss() {
        ContentTaskStore store = mock(ContentTaskStore.class);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                null, null, null, null, null, null, null, new ContentProviderProperties(), orgPoint);
        PostSnapshot verifier = new PostSnapshot(18L, 8L, 100L, "store", "boss", null, false, Set.of());
        FrContentTaskDO current = task("copy", "{\"sourceText\":\"原文\"}", null)
                .setStatus("SUBMISSION_UNCERTAIN").setPoolBizKey("content:9:copy")
                .setPoolReservedUnits(1L);
        FrContentTaskDO replay = task("copy", current.getInputSnapshot(), null)
                .setStatus("SUCCEEDED").setPoolBizKey("content:9:copy")
                .setManualVerifyDecision("DELIVERED").setManualVerifyActualUnits(1L)
                .setManualVerifyReason("已交付").setManualVerifyEvidence("回执A");
        when(store.byManualVerifyKey(100L, "verify-race")).thenReturn(null, replay);
        when(store.byId(100L, 9L)).thenReturn(current);
        when(store.manualVerify(9L, 100L, 8L, 18L, "DELIVERED", 1L,
                "已交付", "回执A", "verify-race")).thenReturn(false);

        assertThat(service.manualVerify(verifier, 9L, "DELIVERED", 1L,
                "已交付", "回执A", "verify-race")).isSameAs(replay);
        verifyNoInteractions(orgPoint);
    }

}
