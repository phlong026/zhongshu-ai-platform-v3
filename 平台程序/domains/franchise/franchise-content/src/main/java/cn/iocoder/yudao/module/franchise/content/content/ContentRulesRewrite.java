package cn.iocoder.yudao.module.franchise.content.content;

/**
 * rules 离线兜底改写（D-1.5-2）：未配置 Key/上游失败时的规则改写，engine='rules' 落库标注。
 * 纯函数、确定性、零外呼——「永不空手」：产出基于原文的可发布二创稿骨架，绝不编造新事实。
 */
public final class ContentRulesRewrite {

    private ContentRulesRewrite() {
    }

    public record Rewritten(String text) {
    }

    public static Rewritten rewrite(String sourceText, String requirement) {
        String source = sourceText == null ? "" : sourceText.trim();
        if (source.isEmpty()) {
            return new Rewritten("");
        }
        // 句子边界切分（中英文标点），保留事实只重组开头与节奏
        String[] sentences = source.split("(?<=[。！？!?；;\\n])");
        StringBuilder body = new StringBuilder();
        for (String sentence : sentences) {
            String clean = sentence.trim();
            if (!clean.isEmpty()) {
                body.append(clean);
            }
        }
        String lead = "这条视频告诉你：";
        // 开头两句提亮点（若原文首句已短，直接沿用）
        String opener = sentences.length > 0 && sentences[0].trim().length() <= 30
                ? sentences[0].trim()
                : lead + firstClause(source);
        StringBuilder result = new StringBuilder(opener);
        String rest = body.substring(Math.min(body.length(), opener.length()));
        if (!rest.isBlank()) {
            result.append(rest);
        }
        if (requirement != null && !requirement.isBlank() && requirement.contains("口播")) {
            result.append("关注我，下期继续讲。");
        }
        return new Rewritten(result.toString());
    }

    private static String firstClause(String text) {
        int cut = text.indexOf('，');
        int end = text.indexOf('。');
        if (cut > 0 && (end < 0 || cut < end)) {
            return text.substring(0, cut) + "。";
        }
        if (end > 0) {
            return text.substring(0, end + 1);
        }
        return text.length() <= 40 ? text : text.substring(0, 40);
    }

}
