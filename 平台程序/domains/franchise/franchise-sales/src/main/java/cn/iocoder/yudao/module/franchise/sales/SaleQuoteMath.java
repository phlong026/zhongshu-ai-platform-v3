package cn.iocoder.yudao.module.franchise.sales;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.QUOTE_TOTAL_INVALID;

/**
 * 报价金额服务端计算（B2 板块 B，Codex R2/R4 定稿）：
 * rows[{label,quantity,unitPriceCents,valueCents}]，valueCents=Math.multiplyExact(quantity,unitPriceCents)，
 * total=Σmain+Σdecor+insurance+management（Math.addExact）；边界：quantity∈[1,10^6] 整数、
 * unitPriceCents∈[0,10^9]、单行/附加费∈[0,10^12]、total≤10^12（B1 上限）、label≤32 字、行数≤50。
 */
public final class SaleQuoteMath {

    public static final long MAX_AMOUNT = 1_000_000_000_000L;
    private static final int MAX_ROWS = 50;

    private SaleQuoteMath() {
    }

    /** 规范化并计算一组行；返回 valueCents 数组（与输入顺序一致）；服务端值覆盖客户端伪造值 */
    public static long[] normalizeRows(String group, List<Map<String, Object>> rows) {
        if (rows == null) {
            rows = List.of();
        }
        if (rows.size() > MAX_ROWS) {
            throw exception(QUOTE_TOTAL_INVALID);
        }
        long[] values = new long[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            Object label = row.get("label");
            if (label == null || String.valueOf(label).isBlank() || String.valueOf(label).length() > 32) {
                throw exception(QUOTE_TOTAL_INVALID);
            }
            Integer quantity = toInt(row.get("quantity"));
            Long unitPrice = toLong(row.get("unitPriceCents"));
            if (quantity == null || quantity < 1 || quantity > 1_000_000) {
                throw exception(QUOTE_TOTAL_INVALID);
            }
            if (unitPrice == null || unitPrice < 0 || unitPrice > 1_000_000_000L) {
                throw exception(QUOTE_TOTAL_INVALID);
            }
            try {
                values[i] = Math.multiplyExact((long) quantity, unitPrice);
            } catch (ArithmeticException e) {
                throw exception(QUOTE_TOTAL_INVALID); // 乘法回绕防护
            }
            if (values[i] > MAX_AMOUNT) {
                throw exception(QUOTE_TOTAL_INVALID);
            }
            row.put("valueCents", values[i]);
        }
        return values;
    }

    public static long sum(long[] values) {
        try {
            long total = 0;
            for (long v : values) {
                total = Math.addExact(total, v);
            }
            return total;
        } catch (ArithmeticException e) {
            throw exception(QUOTE_TOTAL_INVALID);
        }
    }

    public static long extraFee(Object value) {
        Long fee = toLong(value);
        if (fee == null || fee < 0 || fee > MAX_AMOUNT) {
            throw exception(QUOTE_TOTAL_INVALID);
        }
        return fee;
    }

    public static long totalOf(long main, long decor, long insurance, long management) {
        try {
            long total = Math.addExact(Math.addExact(main, decor), Math.addExact(insurance, management));
            if (total <= 0 || total > MAX_AMOUNT) {
                throw exception(QUOTE_TOTAL_INVALID);
            }
            return total;
        } catch (ArithmeticException e) {
            throw exception(QUOTE_TOTAL_INVALID);
        }
    }

    private static Integer toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                // 非法值按缺失处理
            }
        }
        return null;
    }

    private static Long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                // 非法值按缺失处理
            }
        }
        return null;
    }
}
