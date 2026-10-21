package cn.iocoder.yudao.module.identity.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.identity.account.AccountLoginService;
import cn.iocoder.yudao.module.identity.account.AccountProfileService;
import cn.iocoder.yudao.module.identity.controller.app.vo.AppProfileUpdateReqVO;
import jakarta.validation.Valid;
import cn.iocoder.yudao.module.identity.controller.app.vo.AppProfileRespVO;
import cn.iocoder.yudao.module.identity.session.UserSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * "我的"页基础信息（页面 15）。
 *
 * 下发身份、实时授权态与跨域只读统计；设计点余额走 GET /design/v1/point-account。
 */
@Tag(name = "小程序 - 个人信息")
@RestController
@RequestMapping("/design/v1/profile")
@PermitAll
public class AppProfileController {

    @Resource
    private UserSessionService sessionService;

    @Resource
    private AccountLoginService accountLoginService;

    @Resource
    private AccountProfileService accountProfileService;

    @Resource
    private javax.sql.DataSource dataSource;

    @Resource
    private cn.iocoder.yudao.module.identity.accesscode.AccessCodeService accessCodeService;

    /** 与充值接口同源：关闭时不向客户端下发 RECHARGE 动作。 */
    @Value("${zhongshu.commerce.recharge.enabled:false}")
    private boolean rechargeEnabled;

    @GetMapping
    @Operation(summary = "获取“我的”页基础信息（身份与授权态 + 设计/发布统计；余额走独立端点）")
    public CommonResult<AppProfileRespVO> getProfile(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        UserSessionService.AccessContext context = requireContext(authorization);
        var profile = accountLoginService.findProfile(context.accountId())
                .orElseThrow(() -> new AccessDeniedException("账号不存在"));
        AppProfileRespVO vo = new AppProfileRespVO();
        vo.setId(profile.accountId());
        vo.setNickname(profile.nickname());
        vo.setAvatarAssetId(AccountProfileService.avatarAssetId(profile.avatar()));
        vo.setAvatar(vo.getAvatarAssetId() == null ? profile.avatar() : null);
        vo.setStatus(profile.status());
        // restricted 由会话校验实时联表判定，撤销授权后下一次请求即降级
        vo.setAccessGrantStatus(context.restricted() ? "NONE" : "ACTIVE");
        vo.setAllowedActions(context.restricted()
                ? List.of("REDEEM_ACCESS_CODE", "VIEW_STATUS", "CONTACT_SUPPORT")
                : rechargeEnabled
                    ? List.of("VIEW_POINTS", "VIEW_PROJECTS", "RECHARGE", "CONTACT_SUPPORT")
                    : List.of("VIEW_POINTS", "VIEW_PROJECTS", "CONTACT_SUPPORT"));
        vo.setStats(buildStats(profile.accountId()));
        applyBinding(vo, profile.accountId());
        return success(vo);
    }

    /** 本人最近一次兑换登记的绑定信息（公司/联系人/电话）；未兑换过为 null，前端按"未登记"展示 */
    private void applyBinding(AppProfileRespVO vo, long accountId) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT company_name, contact_name, contact_phone FROM access_code_redemption "
                        + "WHERE account_id = ? AND deleted = FALSE ORDER BY id DESC LIMIT 1", accountId);
        if (rows.isEmpty()) return;
        Map<String, Object> row = rows.get(0);
        vo.setCompanyName((String) row.get("company_name"));
        vo.setContactName((String) row.get("contact_name"));
        vo.setContactPhone((String) row.get("contact_phone"));
    }

    /**
     * 设计/发布统计（审查 C9）：「我的」页是天然的跨域读模型，
     * 沿用工作台先例（DashboardAdminController）——各业务模块不对外暴露计数端点，
     * 为此引入跨模块服务调用反而把展示需求传染进领域层；计数走参数化只读 SQL。
     */
    private Map<String, Integer> buildStats(long accountId) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        Map<String, Integer> stats = new java.util.LinkedHashMap<>();
        stats.put("projectCount", (int) count(jdbc,
                "SELECT count(*) FROM design_project WHERE user_id = ? AND deleted = FALSE", accountId));
        stats.put("publishedCount", (int) count(jdbc,
                "SELECT count(*) FROM case_submission s JOIN design_case c ON c.id = s.published_case_id "
                        + "WHERE s.user_id = ? AND s.deleted = FALSE AND c.deleted = FALSE "
                        + "AND c.publication_status = 'PUBLISHED'",
                accountId));
        return stats;
    }

    private long count(org.springframework.jdbc.core.JdbcTemplate jdbc, String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    @PutMapping("/binding")
    @Operation(summary = "本人补录绑定信息（公司/联系人/电话）：存量已激活客户未登记时的自助补填，"
            + "复用兑换绑定口径与审计留痕，写本人最近一次兑换记录")
    public CommonResult<Boolean> updateBinding(
            @Valid @RequestBody cn.iocoder.yudao.module.identity.controller.app.vo.AppProfileBindingUpdateReqVO body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        UserSessionService.AccessContext context = requireContext(authorization);
        if (context.restricted()) throw new cn.iocoder.yudao.framework.common.exception.ServiceException(
                cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort.ACCESS_GRANT_REQUIRED, "请先激活账号");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        List<Long> codeIds = jdbc.queryForList(
                "SELECT code_id FROM access_code_redemption WHERE account_id = ? AND deleted = FALSE "
                        + "ORDER BY id DESC LIMIT 1", Long.class, context.accountId());
        if (codeIds.isEmpty() || codeIds.get(0) == null) {
            // 激活走兑换，正常必有兑换记录；无记录说明数据异常，引导线下处理而不是静默造数
            throw new cn.iocoder.yudao.framework.common.exception.ServiceException(400, "未找到激活码兑换记录，请联系管理员登记");
        }
        return success(accessCodeService.updateBinding(codeIds.get(0),
                new cn.iocoder.yudao.module.identity.accesscode.AccessCodeService.BindingUpdate(
                        body.getCompanyName(), body.getContactName(), body.getContactPhone()),
                "account:" + context.accountId()));
    }

    @PatchMapping("/preferences")
    @Operation(summary = "更新用户偏好设置（字段白名单由服务端决定，白名单外的键丢弃）")
    public CommonResult<Boolean> updatePreferences(
            @RequestBody Map<String, Object> preferences,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        UserSessionService.AccessContext context = requireContext(authorization);
        return success(accountLoginService.updatePreferences(context.accountId(), preferences));
    }

    @PatchMapping
    @Operation(summary = "修改本人昵称与已通过校验的头像")
    public CommonResult<Boolean> updateProfile(
            @Valid @RequestBody AppProfileUpdateReqVO body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        UserSessionService.AccessContext context = requireContext(authorization);
        if (context.restricted()) throw new cn.iocoder.yudao.framework.common.exception.ServiceException(cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort.ACCESS_GRANT_REQUIRED, "请先激活账号");
        return success(accountProfileService.update(context.accountId(), body.getNickname(), body.getAvatarAssetId()));
    }

    private UserSessionService.AccessContext requireContext(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        return sessionService.validateAccessToken(token)
                .orElseThrow(() -> new cn.iocoder.yudao.framework.common.exception.ServiceException(401, "会话无效或已过期"));
    }

}
