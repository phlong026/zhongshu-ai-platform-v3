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

    /** JSONB 列批量更新（红线：jsonb 列禁 MyBatis-Plus 直写——一律 CAST；cols 空 no-op） */
    static void updateJsonb(org.springframework.jdbc.core.JdbcTemplate jdbc, String table,
                            java.util.Map<String, String> cols, long id, long orgId) {
        if (cols == null || cols.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("UPDATE ").append(table).append(" SET ");
        java.util.List<Object> args = new java.util.ArrayList<>();
        boolean first = true;
        for (java.util.Map.Entry<String, String> entry : cols.entrySet()) {
            if (!first) {
                sql.append(", ");
            }
            first = false;
            sql.append(entry.getKey()).append(" = CAST(? AS jsonb)");
            args.add(entry.getValue());
        }
        sql.append(", update_time = now() WHERE id = ? AND company_id = ?");
        args.add(id);
        args.add(orgId);
        jdbc.update(sql.toString(), args.toArray());
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
