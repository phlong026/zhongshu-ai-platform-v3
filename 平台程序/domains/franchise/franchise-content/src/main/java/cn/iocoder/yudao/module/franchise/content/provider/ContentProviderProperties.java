package cn.iocoder.yudao.module.franchise.content.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内容域 provider 配置（红线：密钥只经环境变量占位注入，不进代码/日志/PR；
 * pg 基线只留 ${ENV:} 占位，zsdev 全 fake 零密钥）。
 * 通道选择 zhongshu.content.provider=real|fake——fake 仅 local/zsdev/dev，生产守卫硬禁（RealServiceWiringPolicy）。
 */
@ConfigurationProperties(prefix = "zhongshu.content")
public class ContentProviderProperties {

    /** real=真实外呼（缺密钥的 provider 调用时报 1_114_000_006）；fake=确定性假通道（e2e 零付费） */
    private ProviderMode provider = ProviderMode.REAL;

    /** fake 结局控制（ok|failed|uncertain），供 e2e 分支断言 */
    private Fake fake = new Fake();

    /** 组织日配额默认值（按科目；可用配置覆盖，D-1.5-8） */
    private Map<String, Integer> quota = new LinkedHashMap<>();

    private Diadi diadi = new Diadi();
    private DashScope dashscope = new DashScope();
    private DeepSeek deepseek = new DeepSeek();
    private Hifly hifly = new Hifly();
    private Metaso metaso = new Metaso();

    public enum ProviderMode { REAL, FAKE }

    public static class Fake {
        /** ok|failed|uncertain（env: ZS_CONTENT_FAKE_OUTCOME） */
        private String outcome = "ok";
        /** fake 成片直链（可指本地夹具 URL，A2.4） */
        private String resultUrl = "https://fake.local/content/result.mp4";

        public String getOutcome() { return outcome; }
        public void setOutcome(String outcome) { this.outcome = outcome; }
        public String getResultUrl() { return resultUrl; }
        public void setResultUrl(String resultUrl) { this.resultUrl = resultUrl; }
    }

    /** 豆一豆链接解析（16a A1：GET /api/parse，Sign=MD5(query+secret)） */
    public static class Diadi {
        private String baseUrl = "https://gateway.diadi.cn";
        private String appId = "";
        private String appSecret = "";
        private int timeoutSeconds = 25;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    /** DashScope Fun-ASR 双端点分流（16a A1：≤300s Flash 同步 / 长音频异步） */
    public static class DashScope {
        private String baseUrl = "https://dashscope.aliyuncs.com";
        private String apiKey = "";
        private String model = "fun-asr";
        private String flashModel = "fun-asr-flash-2026-06-15";
        private double flashThresholdSeconds = 300.0;
        private double pollIntervalSeconds = 2.0;
        private int pollMaxAttempts = 90;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getFlashModel() { return flashModel; }
        public void setFlashModel(String flashModel) { this.flashModel = flashModel; }
        public double getFlashThresholdSeconds() { return flashThresholdSeconds; }
        public void setFlashThresholdSeconds(double v) { this.flashThresholdSeconds = v; }
        public double getPollIntervalSeconds() { return pollIntervalSeconds; }
        public void setPollIntervalSeconds(double v) { this.pollIntervalSeconds = v; }
        public int getPollMaxAttempts() { return pollMaxAttempts; }
        public void setPollMaxAttempts(int v) { this.pollMaxAttempts = v; }
    }

    /** DeepSeek 文案改写（16a A1：deepseek-chat，temperature 1.3——勘误定标） */
    public static class DeepSeek {
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String model = "deepseek-chat";
        private int timeoutSeconds = 240;
        private int maxTokens = 8192;
        private double temperature = 1.3;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int v) { this.maxTokens = v; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double v) { this.temperature = v; }
    }

    /** HiFly 数字人口播 V2（16a A1：create_by_tts → /video/task 状态 1/2/3/4） */
    public static class Hifly {
        private String baseUrl = "https://hfw-api.hifly.cc";
        private String apiKey = "";
        private int timeoutSeconds = 60;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
    }

    /** MiniMax-H3 经秘塔网关（16a A1：提交/查询；轮询 5s×1080≈1.5h + 2h 总窗） */
    public static class Metaso {
        private String baseUrl = "https://metaso.cn";
        private String apiKey = "";
        private int timeoutSeconds = 90;
        private int pollIntervalSeconds = 5;
        private int maxPollAttempts = 1080;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
        public int getPollIntervalSeconds() { return pollIntervalSeconds; }
        public void setPollIntervalSeconds(int v) { this.pollIntervalSeconds = v; }
        public int getMaxPollAttempts() { return maxPollAttempts; }
        public void setMaxPollAttempts(int v) { this.maxPollAttempts = v; }
    }

    public ProviderMode getProvider() { return provider; }
    public void setProvider(ProviderMode provider) { this.provider = provider; }
    public Fake getFake() { return fake; }
    public void setFake(Fake fake) { this.fake = fake; }
    public Map<String, Integer> getQuota() { return quota; }
    public void setQuota(Map<String, Integer> quota) { this.quota = quota; }
    public Diadi getDiadi() { return diadi; }
    public void setDiadi(Diadi diadi) { this.diadi = diadi; }
    public DashScope getDashscope() { return dashscope; }
    public void setDashscope(DashScope dashscope) { this.dashscope = dashscope; }
    public DeepSeek getDeepseek() { return deepseek; }
    public void setDeepseek(DeepSeek deepseek) { this.deepseek = deepseek; }
    public Hifly getHifly() { return hifly; }
    public void setHifly(Hifly hifly) { this.hifly = hifly; }
    public Metaso getMetaso() { return metaso; }
    public void setMetaso(Metaso metaso) { this.metaso = metaso; }

}
