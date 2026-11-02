package cn.iocoder.yudao.module.franchise.performance.content;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** JSONB 小工具（字符串数组/对象追加；写路径统一 JdbcTemplate CAST——阶段 2 同款纪律）。 */
final class JsonUtil {

    private JsonUtil() {
    }

    /** 对象数组追加（existingJsonArray 为 null/空时新建） */
    static String append(String existingJsonArray, Map<String, Object> row) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (StrUtil.isNotBlank(existingJsonArray)) {
            for (Object parsed : JsonUtils.parseArray(existingJsonArray, Map.class)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsedRow = (Map<String, Object>) parsed;
                rows.add(parsedRow);
            }
        }
        rows.add(row);
        return JsonUtils.toJsonString(rows);
    }

    /** 解析对象（null 安全） */
    static Map<String, Object> parseMap(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        return JsonUtils.parseObject(json, Map.class);
    }

    /** 解析对象数组（null 安全） */
    static List<Map<String, Object>> parseList(String json) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (StrUtil.isBlank(json)) {
            return rows;
        }
        for (Object parsed : JsonUtils.parseArray(json, Map.class)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsedRow = (Map<String, Object>) parsed;
            rows.add(parsedRow);
        }
        return rows;
    }
}
