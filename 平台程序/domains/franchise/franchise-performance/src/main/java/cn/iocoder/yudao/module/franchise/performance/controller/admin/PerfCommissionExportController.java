package cn.iocoder.yudao.module.franchise.performance.controller.admin;

import cn.iocoder.yudao.module.franchise.performance.PerformancePermissions;
import cn.iocoder.yudao.module.franchise.performance.content.PerfCommissionExportService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * P1 尾批③：绩效报表导出端点（§四#13 / §8.1）。B-only 装配：
 * 仅 {@code platform.legacy-design-bridge.enabled=false}（B 独立进程 application.properties:80）时注册；
 * 旧融合 yudao-server 资源目录无该属性 → 缺省 → 条件不装配 → 零新端点注册
 * （FROZEN 单调不可逆；不动既有 PerfCommissionController 的四条端点）。
 *
 * <p><b>权限（与 §八 通用条款区分）</b>：本报表使用
 * {@code franchise:performance:commission}（V857：boss/owner/finance/finance-lead）；
 * §八通用条款的 {@code franchise:fin:query} 仅适用于财务账表导出（收款/付款/应付/退款/月度对账），
 * 不含本项。前端按 roleCode 显隐，服务端注解为最终门禁。
 *
 * <p>端点形态 = GET（只读导出；审计为唯一副作用）。Service 在首字节前同步持久化
 * attempt，流式完成后再记 success；中途失败保留 attempt 且不写 success。
 * 校验/5 万上限/规则完整性在返回 {@link StreamingResponseBody} <b>之前</b>完成，保证 400/500
 * 仍走生产异常链（HTTP 200 + CommonResult）；通过后 BOM/表头/员工行逐批写出，不整份缓冲。
 *
 * <p>响应：{@code text/csv; charset=UTF-8} + attachment + {@code Cache-Control: no-store} + UTF-8 BOM。
 * 月份 {@code YYYY-MM} 非法 → 生产异常链 HTTP 200 + CommonResult.code=400（可读提示）。
 */
@Tag(name = "加盟 - 绩效报表导出（B-only）")
@RestController
@RequestMapping("/franchise/performance")
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class PerfCommissionExportController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private PerfCommissionExportService exportService;

    @GetMapping("/commission/export")
    @Operation(summary = "导出员工×月份提成汇总 CSV（UTF-8 BOM，流式写出，员工数上限 5 万；动作记审计）")
    @PreAuthorize("@post.has('" + PerformancePermissions.COMMISSION + "')")
    public ResponseEntity<StreamingResponseBody> export(@RequestParam("month") String month) {
        // 月份先做静态校验：非法参数不进入 prepare，便于契约测试与快速失败
        String period = PerfCommissionExportService.validateMonth(month);
        PostSnapshot post = currentPost.require();
        // 必须先 prepare（发现 400/500 时尚未提交响应头，仍能走 CommonResult 异常链）
        PerfCommissionExportService.ExportPlan plan = exportService.prepare(post, period);
        StreamingResponseBody body = outputStream -> exportService.writeCsv(post, plan, outputStream);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=performance-commission-" + plan.month() + ".csv");
        headers.setCacheControl("no-store");
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
