package cn.iocoder.yudao.module.franchise.org.controller.admin.orgpoint;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.franchise.org.orgpoint.OrgPointErrorCodes;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 公司积分池视图（24 号任务书 B-10/B-11）。
 *
 * <p>老板/owner：余额、在途、任务合并视图、逐笔流水、员工额度管理；
 * 全部在岗员工：本人当月用量。全部按当前岗位的 org 域隔离（不接受跨组织 orgId 入参）。
 */
@Tag(name = "公司积分池视图")
@RestController
@RequestMapping("/platform/org-point")
public class OrgPointViewController {

    @Resource
    private CurrentPost currentPost;

    @Resource
    private OrgPointPort orgPoint;

    public record MemberQuotaReqVO(@NotNull @Positive Long userId,
                                   @NotNull @Positive Long monthlyCredits) {
    }

    public record MemberPermissionReqVO(@NotNull Long userId,
                                        @NotNull List<String> allowedSubjects) {
    }

    private PostSnapshot requireBoss() {
        PostSnapshot post = currentPost.require();
        if (!FranchiseRoles.isBoss(post)) {
            throw exception(OrgPointErrorCodes.ORG_POINT_FORBIDDEN);
        }
        return post;
    }

    @GetMapping("/summary")
    @PreAuthorize("@post.has('franchise:org:point:query')")
    @Operation(summary = "公司积分池概览：可用余额 + 在途预留 + 状态")
    public CommonResult<OrgPointPort.PoolSummary> summary() {
        PostSnapshot post = requireBoss();
        return success(orgPoint.summary(post.orgId()));
    }

    @GetMapping("/operations")
    @PreAuthorize("@post.has('franchise:org:point:query')")
    @Operation(summary = "任务合并视图（服务端分页前折叠）")
    public CommonResult<List<OrgPointPort.OperationView>> operations(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PostSnapshot post = requireBoss();
        return success(orgPoint.operations(post.orgId(), page, Math.min(Math.max(size, 1), 100)));
    }

    @GetMapping("/ledger")
    @PreAuthorize("@post.has('franchise:org:point:query')")
    @Operation(summary = "逐笔流水（可按员工/科目/月份筛选）")
    public CommonResult<List<OrgPointPort.LedgerEntry>> ledger(
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PostSnapshot post = requireBoss();
        return success(orgPoint.ledger(post.orgId(), actorUserId, subject, month,
                page, Math.min(Math.max(size, 1), 100)));
    }

    @GetMapping("/my-usage")
    @Operation(summary = "本人当月用量与明细（全部在岗员工；按当前岗位本人查询）")
    public CommonResult<OrgPointPort.MyUsage> myUsage(
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PostSnapshot post = currentPost.require();
        return success(orgPoint.myUsage(post.orgId(), post.userId(), month, page,
                Math.min(Math.max(size, 1), 100)));
    }

    @GetMapping("/member-quotas")
    @PreAuthorize("@post.has('franchise:org:point:query')")
    @Operation(summary = "员工月度额度清单")
    public CommonResult<List<OrgPointPort.MemberQuota>> memberQuotas() {
        PostSnapshot post = requireBoss();
        return success(orgPoint.memberQuotas(post.orgId()));
    }

    @PutMapping("/member-quotas")
    @PreAuthorize("@post.has('franchise:org:point:write')")
    @Operation(summary = "设置员工月度额度")
    public CommonResult<Boolean> setMemberQuota(@Valid @RequestBody MemberQuotaReqVO req) {
        PostSnapshot post = requireBoss();
        orgPoint.setMemberQuota(post.orgId(), req.userId(), req.monthlyCredits(), post.userId());
        return success(true);
    }

    @DeleteMapping("/member-quotas")
    @PreAuthorize("@post.has('franchise:org:point:write')")
    @Operation(summary = "清除员工月度额度（删行=不限）")
    public CommonResult<Boolean> clearMemberQuota(@RequestParam @NotNull Long userId) {
        PostSnapshot post = requireBoss();
        orgPoint.clearMemberQuota(post.orgId(), userId, post.userId());
        return success(true);
    }

    @GetMapping("/member-permissions")
    @PreAuthorize("@post.has('franchise:org:point:query')")
    @Operation(summary = "员工业务权限清单（无行=全允许）")
    public CommonResult<List<OrgPointPort.MemberPermission>> memberPermissions() {
        PostSnapshot post = requireBoss();
        return success(orgPoint.memberPermissions(post.orgId()));
    }

    @PutMapping("/member-permissions")
    @PreAuthorize("@post.has('franchise:org:point:write')")
    @Operation(summary = "设置员工业务权限（空列表=全禁）")
    public CommonResult<Boolean> setMemberPermission(@Valid @RequestBody MemberPermissionReqVO req) {
        PostSnapshot post = requireBoss();
        orgPoint.setMemberPermission(post.orgId(), req.userId(), req.allowedSubjects(), post.userId());
        return success(true);
    }

    @DeleteMapping("/member-permissions")
    @PreAuthorize("@post.has('franchise:org:point:write')")
    @Operation(summary = "清除员工业务权限（删行=全允许）")
    public CommonResult<Boolean> clearMemberPermission(@RequestParam @NotNull Long userId) {
        PostSnapshot post = requireBoss();
        orgPoint.clearMemberPermission(post.orgId(), userId, post.userId());
        return success(true);
    }
}
