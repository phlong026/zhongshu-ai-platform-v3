package cn.iocoder.yudao.module.franchise.performance.controller.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 控制器公共解析工具（阶段 2 同款宽松口径；大雪花 id 字符串禁走 Double）。 */
final class PerfCommon {

    private PerfCommon() {
    }

    static String str(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    static Long longOrNull(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        return new BigDecimal(String.valueOf(raw)).longValueExact();
    }

    static Long centsOrNull(Object yuan) {
        if (yuan == null || String.valueOf(yuan).isBlank()) {
            return null;
        }
        return new BigDecimal(String.valueOf(yuan)).movePointRight(2)
                .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    static Integer intOrNull(Object raw) {
        Long v = longOrNull(raw);
        return v == null ? null : v.intValue();
    }

    static LocalDate localDate(Object raw) {
        return raw == null || String.valueOf(raw).isBlank() ? null : LocalDate.parse(String.valueOf(raw));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object raw) {
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> mapList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(x -> (Map<String, Object>) x).toList();
    }

    static String requireIdem(String idem) {
        if (idem == null || idem.isBlank()) {
            throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception0(
                    400, "缺少 Idempotency-Key 请求头");
        }
        return idem.trim();
    }
}
