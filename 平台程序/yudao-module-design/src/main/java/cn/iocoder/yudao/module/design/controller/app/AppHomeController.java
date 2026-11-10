package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.design.controller.app.vo.AppCaseSummaryRespVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppHomeRespVO;
import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import jakarta.annotation.security.PermitAll;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;

import jakarta.annotation.Resource;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "小程序 - 首页（页面 01）")
@RestController
@RequestMapping("/design/v1/home")
@PermitAll // 首页聚合匿名可读（accessGrantStatus 由调用方会话决定）
public class AppHomeController {

    @Resource
    private CaseCatalogService caseCatalogService;

    @Resource
    private IdentitySessionPort identitySessionPort;

    @Resource
    private javax.sql.DataSource dataSource;

    @GetMapping
    @Operation(summary = "首页聚合：精选案例（显示到首页标记优先、最新发布补齐）、未读数、使用权状态")
    public CommonResult<AppHomeRespVO> getHome(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        AppHomeRespVO vo = new AppHomeRespVO();
        // 精选户型 8 条：首页 swiper 两页、每页 2×2；运营「显示到首页」标记优先，其余按最新发布补齐（需求 3b）
        var featured = caseCatalogService.listHomeCases(8);
        vo.setFeaturedCases(featured.stream().map(summary -> {
            AppCaseSummaryRespVO s = new AppCaseSummaryRespVO();
            s.setCaseId(String.valueOf(summary.caseId()));
            s.setTitle(summary.title());
            s.setSourceType(summary.sourceType());
            s.setStyleCode(summary.styleCode());
            s.setFloorCount(summary.floorCount());
            s.setBuildingArea(summary.buildingArea());
            // 缺封面会导致首页精选位全空图（评审 P0）
            s.setCoverAssetId(summary.coverAssetId());
            return s;
        }).toList());
        vo.setUnreadCount(0L); // P7B 消息中心交付后接入
        // P2-4（报告 15）：按调用方会话返回真实授权态——ANONYMOUS（无/无效会话）、
        // NONE（受限会话）、ACTIVE（已激活），与 /access-grant 语义一致，不再硬编码。
        String grantStatus = resolveGrantStatus(authorization);
        vo.setAccessGrantStatus(grantStatus);
        // 需求 3d：已激活但绑定信息（公司/联系人/电话）不完整时，前端展示补绑定提醒（仅提醒可关闭）。
        // 仅 ACTIVE 会话适用；identity 库表缺失（合同测试库）按完整处理，不触发提醒。
        vo.setBindingComplete(resolveBindingComplete(authorization, grantStatus));
        return success(vo);
    }

    /** 仅 ACTIVE 会话判定绑定完整性；identity 库表缺失（合同测试库）按完整处理，不触发提醒 */
    private Boolean resolveBindingComplete(String authorization, String grantStatus) {
        if (!"ACTIVE".equals(grantStatus)) {
            return null;
        }
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        var ctx = identitySessionPort.resolveByBearerToken(token);
        if (ctx.isEmpty()) {
            return null;
        }
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        Integer tables = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() "
                        + "AND table_name IN ('account', 'access_code_redemption', 'design_activation_request')",
                Integer.class);
        if (tables == null || tables < 3) {
            return true;
        }
        // 与「我的」页/补录接口同口径：绑定信息有两个来源，取该账号最近一条——
        // ① 授权码兑换记录（access_code_redemption）；② 已通过的开通申请（design_activation_request.status='APPROVED'）。
        // 只认 APPROVED：PENDING 未审批通过前不应视为已绑定。
        Integer incomplete = jdbc.queryForObject(
                "SELECT CASE WHEN b.id IS NULL OR COALESCE(b.company_name, '') = '' "
                        + "OR COALESCE(b.contact_name, '') = '' OR COALESCE(b.contact_phone, '') = '' "
                        + "THEN 1 ELSE 0 END FROM account a "
                        + "LEFT JOIN LATERAL (SELECT x.id, x.company_name, x.contact_name, x.contact_phone "
                        + "FROM (SELECT id, company_name, contact_name, contact_phone, create_time "
                        + "      FROM access_code_redemption WHERE account_id = a.id AND deleted = FALSE "
                        + "      UNION ALL "
                        + "      SELECT id, company_name, contact_name, contact_phone, create_time "
                        + "      FROM design_activation_request WHERE account_id = a.id AND deleted = FALSE "
                        + "        AND status = 'APPROVED') x "
                        + "ORDER BY x.create_time DESC, x.id DESC LIMIT 1) b ON TRUE "
                        + "WHERE a.id = ? AND a.deleted = FALSE",
                Integer.class, ctx.get().accountId());
        return incomplete == null || incomplete == 0;
    }

    private String resolveGrantStatus(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return "ANONYMOUS";
        }
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        return identitySessionPort.resolveByBearerToken(token)
                .map(ctx -> ctx.restricted() ? "NONE" : "ACTIVE")
                .orElse("ANONYMOUS");
    }

}
