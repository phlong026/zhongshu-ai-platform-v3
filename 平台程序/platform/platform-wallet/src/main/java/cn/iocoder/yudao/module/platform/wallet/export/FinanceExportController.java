package cn.iocoder.yudao.module.platform.wallet.export;

import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P0 批3：加盟商财务 CSV 导出端点（§八）。B-only 装配：
 * 仅 {@code platform.legacy-design-bridge.enabled=false}（B 独立进程）时注册；
 * 旧融合 yudao-server 资源目录无该属性 → 缺省 → 条件不装配 → 零可写端点注册，
 * 不触碰 193 wired 写面 / 停写白名单（FROZEN 单调不可逆）。
 *
 * <p>端点形态 = POST（非 GET）：导出动作含 §8.1 L349 要求的审计写入（actor/筛选条件/行数），
 * 属于审计副作用而非 §5.2 排除的「账簿登记类业务写」——后者仍全部留在小程序。
 * POST 语义与「动作+副作用」一致，也不被浏览器预取/缓存重放触发重复审计。
 *
 * <p>参数校验（O3）：沿用批2 FinanceController.pageParam()/validateRange()/validateRefundDirection()
 * 的显式 ServiceException(GlobalErrorCodeConstants.BAD_REQUEST) 路径，不依赖 PageParam @Max(200)。
 */
@Tag(name = "加盟 - 财务导出（B-only）")
@RestController
@RequestMapping("/franchise/finance")
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class FinanceExportController {

    @Resource
    private FinanceExportService exportService;
    @Resource
    private CurrentPost currentPost;

    @PostMapping("/exports")
    @Operation(summary = "导出当前筛选条件的全部结果为 CSV（UTF-8 BOM，上限 5 万行；动作记审计）")
    @PreAuthorize("@post.has('franchise:fin:query')")
    public ResponseEntity<byte[]> export(
            @RequestParam("table") String table,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "projectId", required = false) Long projectId,
            @RequestParam(value = "keyword", required = false) String keyword,
            // 实收 receivedDate 区间
            @RequestParam(value = "receivedDateFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedDateFrom,
            @RequestParam(value = "receivedDateTo", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedDateTo,
            @RequestParam(value = "method", required = false) String method,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            // 付款 payDate 区间
            @RequestParam(value = "payDateFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate payDateFrom,
            @RequestParam(value = "payDateTo", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate payDateTo,
            // 退款 date 区间 + receiptId（保留旧语义） + refundDirection
            @RequestParam(value = "dateFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(value = "dateTo", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(value = "receiptId", required = false) Long receiptId,
            @RequestParam(value = "refundDirection", required = false) String refundDirection,
            // 异常资金 businessDate 区间
            @RequestParam(value = "businessDateFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDateFrom,
            @RequestParam(value = "businessDateTo", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDateTo,
            @RequestParam(value = "month", required = false) String month) {

        // ===== 显式参数校验（批2 同型，Controller 首行先行拦截） =====
        Map<String, String> filters = filters(table, status, projectId, keyword,
                receivedDateFrom, receivedDateTo, method, sourceType, payDateFrom, payDateTo,
                dateFrom, dateTo, receiptId, refundDirection, businessDateFrom, businessDateTo, month);
        FinanceExportService.validateFilters(table, filters);

        PostSnapshot post = currentPost.require();
        byte[] csv = exportService.export(table, filters, post.orgId(), post.userId());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=finance-" + table + ".csv");
        headers.setCacheControl("no-store");
        return ResponseEntity.ok().headers(headers).body(csv);
    }

    /** 筛选条件按 §三.1 五表形态收集（table 决定哪些生效；payables 无日期参数）。 */
    private static Map<String, String> filters(String table, String status, Long projectId, String keyword,
                                               LocalDate receivedDateFrom, LocalDate receivedDateTo,
                                               String method, String sourceType,
                                               LocalDate payDateFrom, LocalDate payDateTo,
                                               LocalDate dateFrom, LocalDate dateTo, Long receiptId,
                                               String refundDirection,
                                               LocalDate businessDateFrom, LocalDate businessDateTo,
                                               String month) {
        Map<String, String> f = new LinkedHashMap<>();
        put(f, "status", status);
        put(f, "projectId", projectId);
        put(f, "keyword", keyword);
        put(f, "receivedDateFrom", receivedDateFrom);
        put(f, "receivedDateTo", receivedDateTo);
        put(f, "method", method);
        put(f, "sourceType", sourceType);
        put(f, "payDateFrom", payDateFrom);
        put(f, "payDateTo", payDateTo);
        put(f, "dateFrom", dateFrom);
        put(f, "dateTo", dateTo);
        put(f, "receiptId", receiptId);
        put(f, "refundDirection", refundDirection);
        put(f, "businessDateFrom", businessDateFrom);
        put(f, "businessDateTo", businessDateTo);
        put(f, "month", month);
        return f;
    }

    private static void put(Map<String, String> f, String key, Object value) {
        if (value != null) {
            f.put(key, value.toString());
        }
    }
}
