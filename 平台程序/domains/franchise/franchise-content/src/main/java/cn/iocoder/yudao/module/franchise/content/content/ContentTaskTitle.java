package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.Objects;

/** Read-time display title derived from text submitted for this task. */
public final class ContentTaskTitle {
    private static final TypeReference<Map<String, Object>> INPUT = new TypeReference<>() {};
    private static final String SEPARATOR = "｜";
    private static final String UNTITLED = "未命名创作";
    private static final int MAX_CODE_POINTS = 24;

    private ContentTaskTitle() {}

    public static String forViewer(FrContentTaskDO task, long companyId, long userId) {
        // A same-company task detail may be readable by a colleague. Its input remains private.
        if (!Objects.equals(task.getCompanyId(), companyId) || !Objects.equals(task.getAuthorUserId(), userId)) {
            return untitled(task.getKind());
        }
        return fromSnapshot(task.getKind(), task.getInputSnapshot());
    }

    static String fromSnapshot(String kind, String snapshot) {
        String label = label(kind);
        String field = switch (kind == null ? "" : kind) {
            case "digital" -> "text";
            case "ai_video" -> "description";
            case "copy" -> "sourceText";
            default -> null;
        };
        Map<String, Object> input = field == null || snapshot == null
                ? null : JsonUtils.parseObjectQuietly(snapshot, INPUT);
        String theme = input != null && input.get(field) instanceof String text
                ? text.replace('\u0085', '\n').replace('\u2028', '\n').replace('\u2029', '\n').lines()
                        .map(StringUtils::normalizeSpace)
                        .map(line -> stripLabel(line, label))
                        .filter(line -> !line.isBlank())
                        .findFirst().orElse(UNTITLED)
                : UNTITLED;
        String title = label + SEPARATOR + theme;
        int length = title.codePointCount(0, title.length());
        return length > MAX_CODE_POINTS
                ? title.substring(0, title.offsetByCodePoints(0, MAX_CODE_POINTS)).stripTrailing() : title;
    }

    static String untitled(String kind) {
        return label(kind) + SEPARATOR + UNTITLED;
    }

    private static String label(String kind) {
        return switch (kind == null ? "" : kind) {
            case "digital" -> "数字人口播";
            case "ai_video" -> "AI视频";
            case "copy" -> "文案改写";
            case "asr" -> "语音转写";
            case "link_parse" -> "链接解析";
            case "remix" -> "口播文案";
            default -> "创作";
        };
    }

    private static String stripLabel(String line, String label) {
        int start = 0;
        while (start < line.length()) {
            String prefix = "AI视频".equals(label) && line.startsWith("AI 视频", start) ? "AI 视频" : label;
            if (!line.startsWith(prefix, start)) {
                break;
            }
            int next = afterSpace(line, start + prefix.length());
            if (line.startsWith("任务", next)) {
                next = afterSpace(line, next + "任务".length());
            }
            if (next == line.length()) {
                return "";
            }
            if ("｜|：:".indexOf(line.charAt(next)) < 0) {
                break;
            }
            start = afterSpace(line, next + 1);
        }
        return line.substring(start);
    }

    private static int afterSpace(String normalizedLine, int index) {
        return index < normalizedLine.length() && normalizedLine.charAt(index) == ' ' ? index + 1 : index;
    }
}
