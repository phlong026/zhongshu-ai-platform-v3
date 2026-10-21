package cn.iocoder.yudao.module.commerce.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.commerce.enums.PermissionConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 充值方案（页面 09/10）")
@RestController
@RequestMapping("/design/v1/recharge-plans")
public class RechargePlanAdminController {

    private final cn.iocoder.yudao.module.commerce.payment.RechargePaymentService paymentService;

    public RechargePlanAdminController(cn.iocoder.yudao.module.commerce.payment.RechargePaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping
    @Operation(summary = "充值方案分页查询")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.RECHARGE_PLAN_QUERY + "')")
    public CommonResult<PageResult<Map<String, Object>>> getPlanPage(
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        var where = new java.util.ArrayList<String>(List.of("deleted = FALSE"));
        var args = new java.util.ArrayList<Object>();
        if (enabled != null) {
            where.add("enabled = ?");
            args.add(enabled);
        }
        String base = "FROM recharge_plan WHERE " + String.join(" AND ", where);
        Integer total = jdbc.queryForObject("SELECT count(*) " + base, Integer.class, args.toArray());
        var params = new java.util.ArrayList<Object>(args);
        params.add(pageSize);
        params.add((long) Math.max(pageNo - 1, 0) * pageSize);
        var rows = jdbc.queryForList(
                "SELECT id, name, amount_cents, base_points, bonus_points, recommended, sort, enabled, wx_product_id "
                        + base + " ORDER BY sort ASC, id ASC LIMIT ? OFFSET ?",
                params.toArray());
        var list = rows.stream().map(r -> {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", String.valueOf(((Number) r.get("id")).longValue()));
            item.put("name", r.get("name"));
            item.put("amountCents", ((Number) r.get("amount_cents")).longValue());
            item.put("basePoints", ((Number) r.get("base_points")).longValue());
            item.put("bonusPoints", ((Number) r.get("bonus_points")).longValue());
            item.put("recommended", Boolean.TRUE.equals(r.get("recommended")));
            item.put("sort", ((Number) r.get("sort")).intValue());
            item.put("enabled", Boolean.TRUE.equals(r.get("enabled")));
            // T-XPAY：微信虚拟支付道具编号（MP 后台道具管理发布后回填，xpay 通道下单必需）
            item.put("wxProductId", r.get("wx_product_id"));
            return item;
        }).toList();
        return success(new PageResult<>(list, total == null ? 0 : total.longValue()));
    }

    @GetMapping("/{planId}")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.RECHARGE_PLAN_QUERY + "')")
    public CommonResult<Map<String, Object>> getPlan(@PathVariable("planId") String planId) {
        var row = jdbc.queryForMap("SELECT id,name,amount_cents,base_points,bonus_points,recommended,sort,enabled,wx_product_id "
                + "FROM recharge_plan WHERE id=? AND deleted=FALSE", Long.parseLong(planId));
        var item = new java.util.LinkedHashMap<String, Object>();
        item.put("id", String.valueOf(row.get("id")));
        item.put("name", row.get("name"));
        item.put("amountCents", row.get("amount_cents"));
        item.put("basePoints", row.get("base_points"));
        item.put("bonusPoints", row.get("bonus_points"));
        item.put("recommended", row.get("recommended"));
        item.put("sort", row.get("sort"));
        item.put("enabled", row.get("enabled"));
        item.put("wxProductId", row.get("wx_product_id"));
        return success(item);
    }

    @jakarta.annotation.Resource
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @PostMapping
    @Operation(summary = "新增充值方案（金额、基础点、赠送点、推荐、顺序、启停）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.RECHARGE_PLAN_MANAGE + "')")
    public CommonResult<Map<String, Object>> createPlan(@RequestBody Map<String, Object> body) {
        long planId = paymentService.createPlan(
                (String) body.getOrDefault("name", "充值方案"),
                ((Number) body.getOrDefault("amountCents", 0)).longValue(),
                ((Number) body.getOrDefault("basePoints", 0)).longValue(),
                ((Number) body.getOrDefault("bonusPoints", 0)).longValue(),
                Boolean.TRUE.equals(body.get("recommended")),
                ((Number) body.getOrDefault("sort", 0)).intValue(),
                (String) body.get("wxProductId"));
        return success(Map.of("planId", String.valueOf(planId)));
    }

    @PatchMapping("/{planId}")
    @Operation(summary = "编辑充值方案（启停/推荐/排序——价格字段变更走新增方案，历史订单引用快照）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.RECHARGE_PLAN_MANAGE + "')")
    public CommonResult<Boolean> updatePlan(@PathVariable("planId") String planId,
                                            @RequestBody Map<String, Object> body) {
        // wxProductId 属渠道映射（非价格字段），允许随时修正；金额/点数仍不可改（历史订单引用快照）
        if (body.keySet().stream().anyMatch(key -> !List.of("name", "enabled", "recommended", "sort", "wxProductId").contains(key))) {
            throw new IllegalArgumentException("金额和设计点创建后不可修改，请新建充值方案");
        }
        var sets = new java.util.ArrayList<String>();
        var args = new java.util.ArrayList<Object>();
        if (body.containsKey("name")) {
            if (!(body.get("name") instanceof String name) || name.isBlank() || name.length() > 40) throw new IllegalArgumentException("方案名称无效");
            sets.add("name = ?"); args.add(((String) body.get("name")).trim());
        }
        for (String flag : List.of("enabled", "recommended")) {
            if (body.containsKey(flag) && !(body.get(flag) instanceof Boolean)) throw new IllegalArgumentException("方案状态必须为布尔值");
        }
        if (body.containsKey("sort") && (!(body.get("sort") instanceof Number sort) || sort.doubleValue() != sort.intValue() || sort.intValue() < 0 || sort.intValue() > 999)) throw new IllegalArgumentException("排序无效");
        if (body.containsKey("enabled")) {
            sets.add("enabled = ?"); args.add(Boolean.TRUE.equals(body.get("enabled")));
        }
        if (body.containsKey("recommended")) {
            sets.add("recommended = ?"); args.add(Boolean.TRUE.equals(body.get("recommended")));
        }
        if (body.containsKey("sort")) {
            sets.add("sort = ?"); args.add(((Number) body.get("sort")).intValue());
        }
        if (body.containsKey("wxProductId")) {
            if (body.get("wxProductId") != null && !(body.get("wxProductId") instanceof String productId)) {
                throw new IllegalArgumentException("道具编号无效");
            }
            String productId = body.get("wxProductId") == null ? null : ((String) body.get("wxProductId")).trim();
            if (productId != null && productId.length() > 64) throw new IllegalArgumentException("道具编号过长（≤64 字符）");
            sets.add("wx_product_id = ?"); args.add(productId == null || productId.isBlank() ? null : productId);
        }
        if (sets.isEmpty()) {
            return success(true);
        }
        sets.add("version = version + 1"); sets.add("update_time = now()");
        args.add(Long.parseLong(planId));
        int updated = jdbc.update(
                "UPDATE recharge_plan SET " + String.join(", ", sets) + " WHERE id = ? AND deleted = FALSE",
                args.toArray());
        return success(updated == 1);
    }

}
