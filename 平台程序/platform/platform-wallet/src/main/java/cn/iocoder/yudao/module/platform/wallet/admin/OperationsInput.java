package cn.iocoder.yudao.module.platform.wallet.admin;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;

/** HTTP 配置数字必须本来就是 JSON 整数；不允许 Jackson 将小数或字符串默默转成 long。 */
public final class OperationsInput {
    public static final long MAX_INTEGER = 9007199254740991L;
    private OperationsInput() { }
    public static long integer(JsonNode body, String field, long minimum, long maximum) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid(field + "必须为整数");
        return integer(value.longValue(), field, minimum, maximum);
    }
    public static long integer(long value, String field, long minimum, long maximum) {
        if (value < minimum || value > maximum) throw invalid(field + "超出整数范围");
        return value;
    }
    public static String text(JsonNode body, String field, int limit, boolean required) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || value.isNull()) return text((String) null, field, limit, required);
        if (!value.isTextual()) throw invalid(field + "必须为文本");
        return text(value.textValue(), field, limit, required);
    }
    public static String text(String value, String field, int limit, boolean required) {
        String text = value == null ? null : value.trim();
        if (text == null || text.isEmpty()) {
            if (required) throw invalid(field + "不能为空");
            return null;
        }
        if (text.length() > limit) throw invalid(field + "过长");
        return text;
    }
    public static boolean bool(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || !value.isBoolean()) throw invalid(field + "必须为布尔值");
        return value.booleanValue();
    }
    public static long nextVersion(long version) {
        integer(version, "版本", 0, MAX_INTEGER - 1);
        return Math.addExact(version, 1);
    }
    public static ServiceException invalid(String message) { return new ServiceException(400, message); }
}
