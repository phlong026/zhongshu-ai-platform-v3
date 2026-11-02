package cn.iocoder.yudao.module.franchise.content.provider;

import java.util.function.Consumer;

/** 文案提取 ASR 端口（D-1.5-4）：双端点分流 + 提交即持久化回调 + 恢复轮询免二次付费。 */
public interface AsrPort {

    Transcript transcribe(AsrCall call);

    /**
     * audioUrl=公网 HTTPS 签名地址或 data:audio/mp4;base64 直传；durationSec 缺失视为长音频；
     * existingTaskId 非空=恢复轮询（绝不再次提交）；onSubmitted=拿到供应商任务号后、开轮询前回调（A2.1）。
     */
    record AsrCall(String audioUrl, Double durationSec, String existingTaskId,
                   Consumer<String> onSubmitted, ProviderCallAudit audit) {
    }

    record Transcript(String text, Double durationSec) {
    }

}
