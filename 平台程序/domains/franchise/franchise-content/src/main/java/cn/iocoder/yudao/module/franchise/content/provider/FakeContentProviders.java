package cn.iocoder.yudao.module.franchise.content.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * fake 内容 provider 通道（D-1.5-7 / A2.4）：e2e 与本地联调零付费外呼；生产硬禁（双保险）。
 * 保险一：本类仅被 ContentProviderConfiguration 在 zhongshu.content.provider=fake 时装配，
 * 且构造器断言仅 local/zsdev/dev profile（StubWechatStaffClient 同款）；
 * 保险二：RealServiceWiringPolicy.PORTS 登记 zhongshu.content.provider——生产 profile 下 fake 即拒启。
 * 结局由 zhongshu.content.fake.outcome 控制（ok|failed|uncertain），成片直链可指本地夹具 URL。
 */
public class FakeContentProviders {

    private static final Logger log = LoggerFactory.getLogger(FakeContentProviders.class);

    private final ContentProviderProperties properties;
    private final ContentProviderCallRecorder recorder;

    /** e2e 运行时切换结局/探测结果（仅 dev 端点写入；生产无此 Bean 与端点）。 */
    private static final java.util.concurrent.atomic.AtomicReference<String> OUTCOME_OVERRIDE =
            new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicReference<Boolean> PROBE_EXPIRED_OVERRIDE =
            new java.util.concurrent.atomic.AtomicReference<>();

    public FakeContentProviders(ContentProviderProperties properties, ContentProviderCallRecorder recorder) {
        this.properties = properties;
        this.recorder = recorder;
    }

    public String outcome() {
        String override = OUTCOME_OVERRIDE.get();
        return override != null ? override : properties.getFake().getOutcome();
    }

    /** e2e 钩子：设置结局覆盖（ok|failed|uncertain）与直链探测覆盖（true=已失效）。 */
    public static void override(String outcome, Boolean probeExpired) {
        OUTCOME_OVERRIDE.set(outcome);
        PROBE_EXPIRED_OVERRIDE.set(probeExpired);
    }

    static Boolean probeOverride() {
        return PROBE_EXPIRED_OVERRIDE.get();
    }

    private void guardOutcomeFailed() {
        if ("failed".equals(outcome())) {
            throw new ContentProviderException.Rejection("内容生成服务暂时不可用（fake.failed）", 502, "FAKE_FAILED");
        }
        if ("uncertain".equals(outcome())) {
            throw new ContentProviderException.Uncertain("内容生成提交结果未知（fake.uncertain），请勿重复提交");
        }
    }

    private void record(ProviderCallAudit audit, String provider, String endpoint, String resultCode, Integer units) {
        recorder.record(audit.companyId(), audit.taskId(), provider, endpoint, 0, resultCode, units);
    }

    // ---------------- 五端口实现 ----------------

    public LinkParsePort linkParsePort() {
        return (shareUrl, audit) -> {
            record(audit, "fake", "api/parse", "OK", 1);
            guardOutcomeFailed();
            return new LinkParsePort.ParsedLink("douyin",
                    properties.getFake().getResultUrl(),
                    null, "fake 解析标题", "fake 解析文案：这是链接解析通道返回的原文。",
                    null, "fake 作者", 60000);
        };
    }

    public AsrPort asrPort() {
        return call -> {
            record(call.audit(), "fake", "asr/transcribe", "OK", 1);
            guardOutcomeFailed();
            return new AsrPort.Transcript("fake 转写文案：这是语音转写通道返回的原文。", call.durationSec());
        };
    }

    public ScriptRewritePort scriptRewritePort() {
        return call -> {
            record(call.audit(), "fake", "chat/completions", "OK", 1);
            guardOutcomeFailed();
            return new ScriptRewritePort.Result(
                    "fake 改写稿：" + call.sourceText(), "fake-chat");
        };
    }

    public OralVideoPort oralVideoPort() {
        return new OralVideoPort() {
            @Override
            public String createByTts(TtsRequest request) {
                int seconds = Math.max(1, (request.text().length() + 1) / 2); // 口播秒数=字数÷2 向上取整
                record(request.audit(), "fake", "video/create_by_tts", "SUBMITTED", seconds);
                guardOutcomeFailed();
                String taskId = "fake-hifly-" + UUID.randomUUID();
                if (request.onCreated() != null) {
                    request.onCreated().accept(taskId); // 提交即持久化同样生效
                }
                return taskId;
            }

            @Override
            public Snapshot poll(String providerTaskId, ProviderCallAudit audit) {
                record(audit, "fake", "video/task", "DONE", null);
                guardOutcomeFailed();
                return new Snapshot("DONE", properties.getFake().getResultUrl(), 62);
            }
        };
    }

    public VideoGenerationPort videoGenerationPort() {
        return new VideoGenerationPort() {
            @Override
            public String submit(H3Request request, java.util.function.Consumer<String> onCreated,
                                 ProviderCallAudit audit) {
                record(audit, "fake", "video_generation", "SUBMITTED", request.durationSeconds());
                guardOutcomeFailed();
                String taskId = "fake-h3-" + UUID.randomUUID();
                if (onCreated != null) {
                    onCreated.accept(taskId);
                }
                return taskId;
            }

            @Override
            public H3Snapshot poll(String providerTaskId, ProviderCallAudit audit) {
                record(audit, "fake", "query/video_generation", "SUCCEEDED", null);
                guardOutcomeFailed();
                return new H3Snapshot("SUCCEEDED", properties.getFake().getResultUrl(),
                        8.0, null);
            }

            @Override
            public Boolean probeUrlExpired(String url, ProviderCallAudit audit) {
                record(audit, "fake", "result-head", "OK", null);
                guardOutcomeFailed();
                Boolean override = probeOverride();
                return override != null ? override : false;
            }
        };
    }

    /** 构造期生产守卫：fake 只许 local/zsdev/dev（与 RealServiceWiringPolicy 双保险） */
    public static void assertNonProduction(org.springframework.core.env.Environment environment) {
        if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod", "production"))) {
            throw new IllegalStateException("fake content provider is forbidden in production");
        }
    }

}
