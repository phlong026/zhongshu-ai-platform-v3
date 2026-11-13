package cn.iocoder.yudao.module.commerce.orgpoint;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 科目价格目录（24 号任务书 B-2，D8 首批默认价表）。
 *
 * <p>价格以「微积分/计量单位」整数存储（1 积分 = 1_000_000 微积分），换算全程整数运算、无浮点：
 * 积分 = ceil(quantity × priceMicros / 1_000_000)，且不低于 minCharge。
 * **结算只认快照**：RESERVE 时把 subject/unit/priceMicros/priceVersion/quantity/N 冻结进
 * price_snapshot，SETTLE 按快照重算 M，禁止重取现价或信任调用方传入积分数（四原则①）。
 *
 * <p>首批价格数值属运营决策（D8）：下表为待确认默认值，改动必须升 priceVersion。
 */
public final class BillingPriceCatalog {

    public enum Unit { CALL, SECOND }

    public record Subject(String key, String label, Unit unit, long priceMicrosPerUnit,
                          String priceVersion, long minCharge) {
    }

    public static final Subject LINK_RESOLUTION = new Subject(
            "link_resolution", "链接解析", Unit.CALL, 1_000_000L, "v1", 1);
    public static final Subject COPY = new Subject(
            "copy", "文案改写", Unit.CALL, 2_000_000L, "v1", 1);
    public static final Subject ASR = new Subject(
            "asr", "语音转写", Unit.CALL, 1_000_000L, "v1", 1);
    public static final Subject DIGITAL = new Subject(
            "digital", "数字人口播", Unit.SECOND, 50_000L, "v1", 1);
    public static final Subject AI_VIDEO_768P = new Subject(
            "ai_video_768p", "AI 视频 768P", Unit.SECOND, 166_666L, "v1", 1);
    public static final Subject AI_VIDEO_2K = new Subject(
            "ai_video_2k", "AI 视频 2K", Unit.SECOND, 333_333L, "v1", 1);
    public static final Subject AI_FLAT = new Subject(
            "ai_flat", "AI 平面方案", Unit.CALL, 2_000_000L, "v1", 1);
    public static final Subject AI_ELEVATION = new Subject(
            "ai_elevation", "AI 立面方案", Unit.CALL, 3_000_000L, "v1", 1);
    public static final Subject BUDGET_ESTIMATE = new Subject(
            "budget_estimate", "快速预算", Unit.CALL, 1_000_000L, "v1", 1);
    public static final Subject NOTE_POLISH = new Subject(
            "note_polish", "笔记润色", Unit.CALL, 1_000_000L, "v1", 1);

    private static final Map<String, Subject> SUBJECTS = new LinkedHashMap<>();

    static {
        for (Subject subject : new Subject[]{LINK_RESOLUTION, COPY, ASR, DIGITAL, AI_VIDEO_768P,
                AI_VIDEO_2K, AI_FLAT, AI_ELEVATION, BUDGET_ESTIMATE, NOTE_POLISH}) {
            SUBJECTS.put(subject.key(), subject);
        }
    }

    private BillingPriceCatalog() {
    }

    /** 未知科目直接拒绝——宁可不计费也不按 0 积分放行。 */
    public static Subject subject(String key) {
        Subject subject = key == null ? null : SUBJECTS.get(key);
        if (subject == null) {
            throw new IllegalArgumentException("未知计费科目：" + key);
        }
        return subject;
    }

    public static boolean contains(String key) {
        return key != null && SUBJECTS.containsKey(key);
    }

    /** 单位数量 → 积分（向上取整，不低于 minCharge）。数量上限防整数溢出（评审 P2-2）。 */
    public static long creditsFor(Subject subject, long quantity) {
        long q = Math.max(quantity, 1);
        if (q > MAX_QUANTITY) {
            throw new IllegalArgumentException("计量数量超出上限：" + q);
        }
        long numerator = q * subject.priceMicrosPerUnit();
        long credits = (numerator + 999_999L) / 1_000_000L;
        return Math.max(credits, subject.minCharge());
    }

    /** 单笔计量上限（秒/次）：1e9 秒 ≈ 31 年，足以覆盖任何真实业务且乘加不溢出。 */
    public static final long MAX_QUANTITY = 1_000_000_000L;
}
