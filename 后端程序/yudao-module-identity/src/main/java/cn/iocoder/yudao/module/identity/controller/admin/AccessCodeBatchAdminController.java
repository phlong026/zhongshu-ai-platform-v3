package cn.iocoder.yudao.module.identity.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeCipher;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeService;
import cn.iocoder.yudao.module.identity.controller.admin.vo.AdminAccessCodeBatchCreateReqVO;
import cn.iocoder.yudao.module.identity.controller.admin.vo.AdminAccessCodeBatchRespVO;
import cn.iocoder.yudao.module.identity.enums.DeliveryModeEnum;
import cn.iocoder.yudao.module.identity.enums.PermissionConstants;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 授权码批次（页面 08 批量生成）")
@RestController
@RequestMapping("/design/v1/access-code-batches")
public class AccessCodeBatchAdminController {

    /** 业务平台单租户常量：直连 SQL 的行级租户过滤（与 design 模块 BudgetCatalogService 的同名惯例一致） */
    private static final long BUSINESS_TENANT_ID = 0L;

    @Resource
    private AccessCodeService accessCodeService;

    @jakarta.annotation.Resource
    private javax.sql.DataSource dataSource;

    @GetMapping
    @Operation(summary = "批次分页（F-1 批次视图）：数量/已兑换/明文暴露/用途；兑换数按兑换事实统计")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACCESS_CODE_MANAGE + "')")
    public CommonResult<cn.iocoder.yudao.framework.common.pojo.PageResult<java.util.Map<String, Object>>> getBatchPage(
            @org.springframework.web.bind.annotation.RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @org.springframework.web.bind.annotation.RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        int size = Math.min(Math.max(pageSize, 1), 100);
        Long total = jdbc.queryForObject("SELECT count(*) FROM design_access_code_batch WHERE deleted = FALSE", Long.class);
        var rows = jdbc.queryForList(
                "SELECT b.id, b.quantity, b.delivery_mode, b.exposed_count, b.issued_by, b.purpose_note, "
                        + "b.expires_at, b.create_time, "
                        + "(SELECT count(*) FROM access_code_redemption r WHERE r.code_id IN "
                        + "  (SELECT c.id FROM design_access_code c WHERE c.batch_id = b.id)) AS redeemed_count "
                        + "FROM design_access_code_batch b WHERE b.deleted = FALSE ORDER BY b.id DESC LIMIT ? OFFSET ?",
                size, (long) Math.max(pageNo - 1, 0) * size);
        var formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.of("Asia/Shanghai"));
        var list = rows.stream().map(r -> {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<String, Object>();
            item.put("id", String.valueOf(((Number) r.get("id")).longValue()));
            item.put("quantity", ((Number) r.get("quantity")).intValue());
            item.put("redeemedCount", ((Number) r.get("redeemed_count")).intValue());
            item.put("deliveryMode", r.get("delivery_mode"));
            item.put("exposedCount", ((Number) r.get("exposed_count")).intValue());
            item.put("issuedBy", r.get("issued_by"));
            item.put("purposeNote", r.get("purpose_note"));
            item.put("expiresAt", r.get("expires_at") == null ? null
                    : formatter.format(((java.sql.Timestamp) r.get("expires_at")).toInstant()));
            item.put("createdAt", formatter.format(((java.sql.Timestamp) r.get("create_time")).toInstant()));
            return item;
        }).toList();
        return success(new cn.iocoder.yudao.framework.common.pojo.PageResult<>(list, total == null ? 0 : total));
    }

    @PostMapping
    @Operation(summary = "创建授权码批次；INLINE 在响应返回完整码，TICKET 走一次性交付票据")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACCESS_CODE_MANAGE + "')")
    public CommonResult<AdminAccessCodeBatchRespVO> createBatch(@Valid @RequestBody AdminAccessCodeBatchCreateReqVO reqVO) {
        String operator = String.valueOf(SecurityFrameworkUtils.getLoginUserId());
        AccessCodeService.BatchCreateResult result = accessCodeService.createBatch(
                reqVO.getQuantity(), DeliveryModeEnum.of(reqVO.getDeliveryMode()).getMode(),
                reqVO.getValidityDays(), reqVO.getPurposeNote(), operator);
        AdminAccessCodeBatchRespVO vo = new AdminAccessCodeBatchRespVO();
        vo.setId(result.batchId());
        vo.setQuantity(reqVO.getQuantity());
        vo.setDeliveryMode(result.deliveryMode());
        vo.setExposedCount(result.oneTimeCodes().size());
        vo.setOneTimeCodes(result.oneTimeCodes());
        return success(vo);
    }

    @PostMapping("/{batchId}/delivery-tickets")
    @Operation(summary = "生成批次的一次性交付票据（TICKET 模式；已有明文交付即拒绝）")
    @Parameter(name = "batchId", description = "批次编号", required = true)
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACCESS_CODE_EXPORT + "')")
    public CommonResult<Map<String, Object>> createDeliveryTicket(@PathVariable("batchId") Long batchId) {
        String operator = String.valueOf(SecurityFrameworkUtils.getLoginUserId());
        var ticket = accessCodeService.issueDeliveryTicket(batchId, operator);
        return success(Map.of(
                "ticket", ticket.getToken(),
                "expiresAt", LocalDateTime.ofInstant(ticket.getExpiresAt(), ZoneOffset.UTC).toString()));
    }

    @PostMapping("/{batchId}/delivery-exports")
    @Operation(summary = "凭一次性票据交付完整明文（先原子消费票据再输出；票据重放必失败）")
    @Parameter(name = "batchId", description = "批次编号", required = true)
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACCESS_CODE_EXPORT + "')")
    public CommonResult<Map<String, Object>> exportByTicket(@PathVariable("batchId") Long batchId,
                                                            @RequestBody Map<String, String> body) {
        String operator = String.valueOf(SecurityFrameworkUtils.getLoginUserId());
        List<String> codes = accessCodeService.exportByTicket(batchId, body.get("ticket"), operator);
        return success(Map.of("codes", codes, "notice", "批次票据仅可消费一次；未使用单码可在授权码列表复制"));
    }

    @GetMapping("/company-summary")
    @Operation(summary = "按加盟商公司聚合兑换事实：公司→码数/账号数/有效授权/点数消耗/最近兑换（2026-10-11 优化建议①；R1 修正为公司级账号去重合计）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACCESS_CODE_MANAGE + "')")
    public CommonResult<List<Map<String, Object>>> companySummary() {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        var formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.of("Asia/Shanghai"));
        // R1：先按（公司,账号）去重聚合、再按账号汇总流水、最后加总到公司。
        // max(p.consumed) 会漏算多账号（100+200 只显示 200）；直接改 sum 会沿同账号多码的连接行重复放大。
        // 耗点口径不变：design_point_ledger 负 delta 取绝对值计入，退款/人工调增等正 delta 不计。
        // 行级租户边界：直连 SQL 显式限定业务租户（与 BudgetCatalogService 的 BUSINESS_TENANT_ID 惯例一致）。
        var rows = jdbc.queryForList(
                "SELECT company, sum(code_count) AS code_count, "
                        + "count(*) AS account_count, "
                        + "sum(active_grant) AS active_grant_count, "
                        + "COALESCE(sum(consumed), 0) AS points_consumed, "
                        + "max(last_redeemed_at) AS last_redeemed_at "
                        + "FROM ("
                        + "SELECT COALESCE(NULLIF(r.company_name, ''), '未登记') AS company, "
                        + "r.account_id, "
                        + "count(*) AS code_count, "
                        + "max(CASE WHEN g.id IS NOT NULL THEN 1 ELSE 0 END) AS active_grant, "
                        + "COALESCE(max(p.consumed), 0) AS consumed, "
                        + "max(r.create_time) AS last_redeemed_at "
                        + "FROM access_code_redemption r "
                        + "LEFT JOIN design_access_grant g ON g.account_id = r.account_id AND g.status = 'ACTIVE' AND g.deleted = FALSE AND g.tenant_id = ? "
                        + "LEFT JOIN (SELECT user_id, sum(CASE WHEN delta < 0 THEN -delta ELSE 0 END) AS consumed "
                        + "           FROM design_point_ledger WHERE deleted = FALSE AND tenant_id = ? GROUP BY user_id) p "
                        + "  ON p.user_id = r.account_id "
                        + "WHERE r.deleted = FALSE AND r.tenant_id = ? "
                        + "GROUP BY 1, r.account_id) a "
                        + "GROUP BY company ORDER BY code_count DESC, company",
                BUSINESS_TENANT_ID, BUSINESS_TENANT_ID, BUSINESS_TENANT_ID);
        var netUsage = netUsageByCompany(jdbc);
        var list = rows.stream().map(r -> {
            Map<String, Object> item = new java.util.LinkedHashMap<String, Object>();
            item.put("company", r.get("company"));
            item.put("codeCount", ((Number) r.get("code_count")).longValue());
            item.put("accountCount", ((Number) r.get("account_count")).longValue());
            item.put("activeGrantCount", ((Number) r.get("active_grant_count")).longValue());
            item.put("netPointsConsumed", netUsage.getOrDefault(r.get("company"), 0L));
            netUsage.remove(r.get("company"));
            item.put("pointsConsumed", ((Number) r.get("points_consumed")).longValue());
            item.put("lastRedeemedAt", r.get("last_redeemed_at") == null ? null
                    : formatter.format(((java.sql.Timestamp) r.get("last_redeemed_at")).toInstant()));
            return item;
        }).toList();
        var complete = new java.util.ArrayList<>(list);
        netUsage.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Map<String, Object> historical = new java.util.LinkedHashMap<>();
            historical.put("company", entry.getKey());
            for (String key : List.of("codeCount", "accountCount", "activeGrantCount", "pointsConsumed")) historical.put(key, 0L);
            historical.put("netPointsConsumed", entry.getValue());
            historical.put("lastRedeemedAt", null);
            complete.add(historical);
        });
        return success(complete);
    }


    /** Additive metric: keep legacy pointsConsumed gross semantics; net usage follows charge-time ownership. */
    private Map<String, Long> netUsageByCompany(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        jdbc.queryForList("""
                WITH redemptions AS (
                  SELECT r.*, COALESCE(NULLIF(company_name, ''), '未登记') AS company
                  FROM access_code_redemption r WHERE deleted=FALSE AND tenant_id=0
                ), debits AS (
                  SELECT user_id, biz_type, biz_id,
                    CASE WHEN type IN ('FLAT_GENERATION_DEBIT','ELEVATION_GENERATION_DEBIT') THEN 'GENERATION' ELSE type END AS family,
                    CASE WHEN biz_type IS NULL OR biz_id IS NULL THEN id ELSE NULL END AS unlinked_id,
                    min(create_time) AS charged_at, sum(-delta) AS gross
                  FROM design_point_ledger WHERE deleted=FALSE AND tenant_id=0 AND delta<0
                    AND EXISTS (SELECT 1 FROM redemptions r WHERE r.account_id=user_id)
                    AND type IN ('FLAT_GENERATION_DEBIT','ELEVATION_GENERATION_DEBIT','BUDGET_ESTIMATE_DEBIT','AI_PROMPT_DEBIT')
                  GROUP BY 1,2,3,4,5
                ), attributed AS (
                  SELECT d.*, CASE WHEN owners.names=1 THEN owners.company ELSE '归属待核实' END AS company,
                    d.gross - CASE WHEN d.family='GENERATION' THEN COALESCE((
                      SELECT sum(delta) FROM design_point_ledger p WHERE p.user_id=d.user_id
                        AND p.biz_type=d.biz_type AND p.biz_id=d.biz_id
                        AND p.type='TASK_SETTLEMENT_REFUND' AND p.deleted=FALSE AND p.tenant_id=0
                    ),0) ELSE 0 END AS net
                  FROM debits d
                  LEFT JOIN LATERAL (
                    SELECT count(DISTINCT historical.company) AS names, min(historical.company) AS company
                    FROM (
                      SELECT COALESCE(NULLIF(CASE WHEN a.id IS NOT NULL THEN a.old_company_name WHEN r.update_time>d.charged_at THEN '归属待核实' ELSE r.company_name END,''),'未登记') AS company
                      FROM redemptions r
                      LEFT JOIN LATERAL (
                        SELECT id,old_company_name FROM access_code_binding_audit a
                        WHERE a.code_id=r.code_id AND a.deleted=FALSE AND a.tenant_id=0 AND a.create_time>d.charged_at
                        ORDER BY a.create_time,a.id LIMIT 1
                      ) a ON TRUE
                      WHERE r.account_id=d.user_id AND r.create_time<=d.charged_at
                    ) historical
                  ) owners ON TRUE
                ) SELECT company,sum(net) AS net FROM attributed GROUP BY company
                """).forEach(row -> result.put((String) row.get("company"), ((Number) row.get("net")).longValue()));
        return result;
    }
}
