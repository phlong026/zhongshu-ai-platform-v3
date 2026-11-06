package cn.iocoder.yudao.server.dev;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobSettlementService;
import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.asset.StubContentModerationAdapter;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.Base64;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 设计赋能 dev 测试基建（B2 板块 A / P-7，诚实标注：仅 local profile 注册）。
 *
 * <p>与既有 FranchiseE2ESetupController 的区别：本控制器类级 @Profile("local") 控注册环境
 * （既有控制器缺 profile 守卫的事实已登记 00 预留，本批不修不继承）；端点全部走员工 admin-api
 * 鉴权（token + X-Post-Id + @post.has），数据注入仅限本组织服务账号。dev 包不自动获得
 * /admin-api 前缀，映射使用绝对路径。
 */
@Slf4j
@Profile("local")
@Tag(name = "dev - 设计赋能测试基建")
@RestController
@RequestMapping("/admin-api/platform/dev/design")
@PreAuthorize("@post.has('franchise:design:use')")
public class DesignDevController {

    private final StoreServiceAccountPort serviceAccounts;
    private final CurrentPost currentPost;
    private final AssetService assetService;
    private final AiJobOrchestrationService orchestration;
    private final AiJobSettlementService settlement;
    private final JdbcTemplate jdbcTemplate;

    public DesignDevController(StoreServiceAccountPort serviceAccounts, CurrentPost currentPost,
                               AssetService assetService, AiJobOrchestrationService orchestration,
                               AiJobSettlementService settlement, DataSource dataSource) {
        this.serviceAccounts = serviceAccounts;
        this.currentPost = currentPost;
        this.assetService = assetService;
        this.orchestration = orchestration;
        this.settlement = settlement;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * R6 余债（横向读取批）：dev 字节通道类型×岗位矩阵——
     * AssetAccessService.CHANNEL_ROLES 同源静态判定（FIN_VOUCHER=财务族/ENG_*=工程族/CONTRACT&CONTENT=销售族）。
     * 封闭「同组织跨岗覆盖他人 PENDING 对象」与「design:use 三岗读跨类资产字节」的横向面。
     * 未知类型保持放行（AssetAccessService 同语义）；资产不存在由 devPutBytes/devGetBytes 兜底。
     */
    private void requireAssetChannel(long assetId) {
        var rows = jdbcTemplate.queryForList(
                "SELECT asset_type FROM asset WHERE id = ? AND deleted = FALSE", assetId);
        if (rows.isEmpty()) {
            return;
        }
        String assetType = String.valueOf(rows.get(0).get("asset_type"));
        String roleCode = currentPost.require().roleCode();
        if (!cn.iocoder.yudao.module.franchise.asset.AssetAccessService.channelAllowed(assetType, roleCode)) {
            throw exception(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN); // 1_099_000_002 同码（不泄露区分）
        }
    }

    private long serviceAccount() {
        PostSnapshot post = currentPost.require();
        return serviceAccounts.requireServiceAccount(post.orgId());
    }

    // ========== 点数注入（P-7①） ==========

    @PostMapping("/point-account/seed")
    @Operation(summary = "服务账号点数注入（MANUAL_CREDIT；直插 design_point_account/ledger，测试基建）")
    @Transactional
    public CommonResult<Map<String, Object>> seedPoints(@RequestBody Map<String, Object> body) {
        long accountId = serviceAccount();
        long points = body.get("points") == null ? 1_000_000L : ((Number) body.get("points")).longValue();
        if (points <= 0 || points > 1_000_000_000L) {
            throw new IllegalArgumentException("points 取值 1~1e9");
        }
        var rows = jdbcTemplate.queryForList(
                "SELECT id, available_points FROM design_point_account WHERE user_id=? AND deleted=FALSE", accountId);
        long available;
        if (rows.isEmpty()) {
            available = points;
            jdbcTemplate.update(
                    "INSERT INTO design_point_account (id, user_id, available_points, reserved_points) "
                            + "VALUES (?, ?, ?, 0)", IdWorker.getId(), accountId, points);
        } else {
            available = ((Number) rows.get(0).get("available_points")).longValue() + points;
            jdbcTemplate.update(
                    "UPDATE design_point_account SET available_points=?, version=version+1, update_time=now() "
                            + "WHERE user_id=?", available, accountId);
        }
        jdbcTemplate.update(
                "INSERT INTO design_point_ledger (id, user_id, type, delta, available_after, reserved_after, "
                        + "biz_type, biz_id, idempotency_key, operator_id, reason) "
                        + "VALUES (?, ?, 'MANUAL_CREDIT', ?, ?, 0, 'dev-setup', ?, ?, ?, ?)",
                IdWorker.getId(), accountId, points, available, "dev:" + java.util.UUID.randomUUID(),
                "dev:" + java.util.UUID.randomUUID(), String.valueOf(currentPost.require().userId()),
                "B2板块A e2e 点数注入");
        log.info("[dev][seedPoints account={} points={} available={}]", accountId, points, available);
        return success(Map.of("accountId", accountId, "availablePoints", available));
    }

    @GetMapping("/point-account")
    @Operation(summary = "服务账号点数余额")
    public CommonResult<Map<String, Object>> pointAccount() {
        long accountId = serviceAccount();
        var rows = jdbcTemplate.queryForList(
                "SELECT available_points, reserved_points FROM design_point_account WHERE user_id=? AND deleted=FALSE",
                accountId);
        long available = rows.isEmpty() ? 0 : ((Number) rows.get(0).get("available_points")).longValue();
        long reserved = rows.isEmpty() ? 0 : ((Number) rows.get(0).get("reserved_points")).longValue();
        return success(Map.of("accountId", accountId, "availablePoints", available, "reservedPoints", reserved));
    }

    // ========== local 资产通道（P-7②③） ==========

    public record BytesBody(String contentBase64) {
    }

    @PostMapping("/assets/{assetId}/bytes")
    @Operation(summary = "local 模式上传字节（仅落对象，不 complete——R7 首次触发审核；属主=服务账号）")
    // R3 P1-2：双通路——853 三岗（sales/sales-lead/hq-design-admin）保持 design:use 原通路，
    // 858 四岗（工程/PM/财务两岗）走窄码 asset:bytes（AI 接口 DesignAccessController 门控不变）
    @PreAuthorize("@post.has('franchise:design:use') or @post.has('franchise:asset:bytes')")
    public CommonResult<Boolean> putBytes(@PathVariable("assetId") long assetId, @RequestBody BytesBody body) {
        long accountId = serviceAccount();
        // R6 余债（横向读取批）：PUT 写面补类型×岗位矩阵——共享服务账号仅属主校验，
        // 无此校验同组织跨岗可覆盖他人 PENDING 对象（干扰上传/SHA 校验失败）
        requireAssetChannel(assetId);
        byte[] content = Base64.getDecoder().decode(body.contentBase64());
        assetService.devPutBytes(accountId, assetId, content);
        return success(true);
    }

    // R4 P1-1：GET 读取通路仅 design:use（asset:bytes 四岗不上 GET——组织共享服务账号仅属主校验，
    // 放开将绕过类型×岗位矩阵横向读取 FIN_VOUCHER 等跨类资产）；业务读取走 download-tickets 类型矩阵
    @GetMapping("/assets/{assetId}/bytes")
    @Operation(summary = "local 模式读取已接受对象字节（属主校验+design:use 岗位门）")
    @PreAuthorize("@post.has('franchise:design:use')")
    public CommonResult<Map<String, Object>> getBytes(@PathVariable("assetId") long assetId) {
        long accountId = serviceAccount();
        // R6 余债（横向读取批）：GET 读面同矩阵——design:use 三岗（sales 等）也不得读跨类资产字节
        requireAssetChannel(assetId);
        byte[] content = assetService.devGetBytes(accountId, assetId);
        return success(Map.of("sizeBytes", content.length,
                "sha256", cn.hutool.crypto.digest.DigestUtil.sha256Hex(content),
                "contentBase64", Base64.getEncoder().encodeToString(content)));
    }

    @PostMapping("/assets/moderation-fault")
    @Operation(summary = "审核故障夹具（P-7⑤：置位后 stub 审核抛 ModerationUnavailableException）")
    public CommonResult<Boolean> moderationFault(@RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        StubContentModerationAdapter.FAULT_MODE.set(enabled);
        return success(enabled);
    }

    // ========== AI 任务定向模拟（P-7④⑥） ==========

    @PostMapping("/ai-jobs/{jobId}/simulate")
    @Operation(summary = "定向模拟结果（N 候选/0=全失败；写隔离前缀+完成屏障，驱动器校验/结算/晋升）")
    public CommonResult<Map<String, Object>> simulate(@PathVariable("jobId") long jobId,
                                                      @RequestBody Map<String, Object> body) {
        long accountId = serviceAccount();
        int acceptedCount = body.get("acceptedCount") == null ? 0 : ((Number) body.get("acceptedCount")).intValue();
        int written = orchestration.simulateCompletion(jobId, accountId, acceptedCount);
        return success(Map.of("jobId", jobId, "quarantinedResults", written));
    }

    @PostMapping("/ai-jobs/{jobId}/prompt-call")
    @Operation(summary = "提示词调用夹具（P-7⑥：复用 chargePrepared 幂等，恰扣一次 AI_PROMPT_DEBIT）")
    public CommonResult<Map<String, Object>> promptCall(@PathVariable("jobId") long jobId) {
        long accountId = serviceAccount();
        boolean charged = settlement.chargePromptCall(jobId);
        return success(Map.of("jobId", jobId, "charged", charged));
    }
}
