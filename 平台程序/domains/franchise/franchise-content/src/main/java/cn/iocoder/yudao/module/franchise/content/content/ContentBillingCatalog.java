package cn.iocoder.yudao.module.franchise.content.content;

import java.util.Map;

/**
 * 科目计费目录（D-1.5-8，对标复刻项目 SERVICES）：科目/单位/provider 代码常量；
 * 单价未表化（§十②：出现调价需求再 billing_tariffs 式表化）。
 * asr 在复刻项目 SERVICES 原按秒，本批平台简化按次——登记偏离（16 V3.1 §0.5）。
 */
public final class ContentBillingCatalog {

    public enum Unit { CALL, SECOND }

    public record Subject(String key, String name, Unit unit, String provider) {
    }

    public static final Subject LINK_RESOLUTION = new Subject("link_resolution", "链接解析", Unit.CALL, "diadi");
    public static final Subject COPY = new Subject("copy", "文案改写", Unit.CALL, "deepseek");
    public static final Subject ASR = new Subject("asr", "语音转写", Unit.CALL, "dashscope");
    public static final Subject DIGITAL = new Subject("digital", "数字人口播", Unit.SECOND, "hifly");
    public static final Subject AI_VIDEO_768P = new Subject("ai_video_768p", "AI 视频 768P", Unit.SECOND, "metaso");
    public static final Subject AI_VIDEO_2K = new Subject("ai_video_2k", "AI 视频 2K", Unit.SECOND, "metaso");

    private static final Map<String, Subject> SUBJECTS = Map.of(
            LINK_RESOLUTION.key(), LINK_RESOLUTION,
            COPY.key(), COPY,
            ASR.key(), ASR,
            DIGITAL.key(), DIGITAL,
            AI_VIDEO_768P.key(), AI_VIDEO_768P,
            AI_VIDEO_2K.key(), AI_VIDEO_2K);

    private ContentBillingCatalog() {
    }

    public static Subject subject(String key) {
        Subject subject = SUBJECTS.get(key);
        if (subject == null) {
            throw new IllegalArgumentException("未知内容计费科目：" + key);
        }
        return subject;
    }

    /** 口播秒数=字数÷2 向上取整（对标 oral_budget_units：max(1,(len+1)//2)）——按秒预扣。 */
    public static int oralSeconds(String scriptText) {
        return Math.max(1, ((scriptText == null ? 0 : scriptText.length()) + 1) / 2);
    }

    /** 任务提交时的预扣单位数（digital=口播秒数；ai_video=时长秒；其余=1）。 */
    public static int estimatedUnits(String subjectKey, String scriptText, Integer durationSeconds) {
        Subject subject = subject(subjectKey);
        return switch (subject.unit()) {
            case SECOND -> subject == DIGITAL
                    ? oralSeconds(scriptText)
                    : Math.max(1, durationSeconds == null ? 1 : durationSeconds);
            case CALL -> 1;
        };
    }

}
