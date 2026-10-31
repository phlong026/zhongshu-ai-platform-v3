package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.authz.enums.PlatformAuthzErrorCodes;
import cn.iocoder.yudao.module.platform.org.OrgService;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import cn.iocoder.yudao.module.platform.org.dal.OrgUnitDO;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.platform.authz.enums.PlatformAuthzErrorCodes.POST_REQUIRED;

/**
 * 组织成员管理（管理员指派制）：部门、员工录入、岗位指派/撤销、员工停用。
 *
 * <p>关联关系一律由管理员建立（员工登录后经 /platform/posts/mine 发现任职），不开放自助选部门。
 * 操作者仅能管理「当前岗位所在组织」：老板/门店负责人管本店，总部老板管总部。
 */
@Tag(name = "平台 - 组织成员管理")
@RestController
@RequestMapping("/platform/org")
@Validated
public class StaffOrgAdminController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private StaffPostService staffPostService;
    @Resource
    private OrgService orgService;
    @Resource
    private SystemDirectory systemDirectory;

    public record Team(long deptId, long orgId, String name, String line) {
    }

    public record StaffRow(long postId, long userId, String name, String mobile, boolean active,
                           String roleCode, Long teamId, String teamName, String createTime) {
    }

    public record CreateStaffReqVO(@NotNull(message = "组织不能为空") Long orgId,
                                   @NotBlank(message = "姓名不能为空") @Size(max = 20, message = "姓名最长 20 字") String name,
                                   @NotBlank(message = "手机号不能为空") @Pattern(regexp = "1\\d{10}", message = "手机号格式不正确") String mobile,
                                   @NotBlank(message = "岗位不能为空") @Size(max = 64) String roleCode,
                                   Long teamId) {
    }

    public record AssignPostReqVO(@NotNull(message = "组织不能为空") Long orgId,
                                  @NotNull(message = "员工不能为空") Long userId,
                                  @NotBlank(message = "岗位不能为空") @Size(max = 64) String roleCode,
                                  Long teamId) {
    }

    public record CreateTeamReqVO(@NotNull(message = "组织不能为空") Long orgId,
                                  @NotBlank(message = "部门名称不能为空") @Size(max = 32, message = "部门名称最长 32 字") String name,
                                  @Size(max = 32) String line) {
    }

    public record StaffStatusReqVO(@NotNull(message = "员工不能为空") Long userId,
                                   boolean active) {
    }

    @GetMapping("/teams")
    @Operation(summary = "组织内的部门列表")
    @PreAuthorize("@post.has('platform:org:query')")
    public CommonResult<List<Team>> teams(@RequestParam("orgId") long orgId) {
        requireScope(orgId);
        return success(orgService.listTeams(orgId).stream()
                .map(unit -> new Team(unit.getDeptId(), unit.getOrgId(),
                        systemDirectory.deptName(unit.getDeptId()), unit.getLine()))
                .toList());
    }

    @PostMapping("/teams")
    @Operation(summary = "在组织内新建部门")
    @PreAuthorize("@post.has('platform:org:manage')")
    public CommonResult<Team> createTeam(@Valid @RequestBody CreateTeamReqVO req) {
        requireScope(req.orgId());
        long deptId = orgService.createTeam(req.orgId(), null, req.name(), req.line());
        return success(new Team(deptId, req.orgId(), req.name(), req.line()));
    }

    @GetMapping("/staff")
    @Operation(summary = "组织内的成员与任职列表")
    @PreAuthorize("@post.has('platform:org:query')")
    public CommonResult<List<StaffRow>> staff(@RequestParam("orgId") long orgId) {
        requireScope(orgId);
        List<StaffPostDO> posts = staffPostService.listOrgPosts(orgId);
        Map<Long, SystemDirectory.UserBrief> users = usersByPosts(posts);
        return success(posts.stream().map(post -> {
            SystemDirectory.UserBrief user = users.get(post.getUserId());
            return new StaffRow(post.getId(), post.getUserId(),
                    user == null ? "" : user.name(),
                    user == null ? "" : user.mobile(),
                    user != null && user.active(),
                    post.getRoleCode(), post.getTeamId(),
                    systemDirectory.deptName(post.getTeamId()),
                    post.getCreateTime() == null ? "" : post.getCreateTime().toString());
        }).toList());
    }

    @PostMapping("/staff")
    @Operation(summary = "录入员工并指派岗位（手机号已存在则复用账号，岗位已存在则幂等返回）")
    @PreAuthorize("@post.has('platform:org:manage')")
    public CommonResult<StaffRow> createStaff(@Valid @RequestBody CreateStaffReqVO req) {
        requireScope(req.orgId());
        Long existing = systemDirectory.userIdByMobile(req.mobile());
        long userId = existing != null ? existing : systemDirectory.createUser(req.name(), req.mobile(), req.orgId());
        long postId = findOrAssign(userId, req.orgId(), req.roleCode(), req.teamId());
        return success(row(staffPostService.getPost(postId).orElseThrow(() -> exception(PlatformOrgErrorCodes.POST_NOT_FOUND)),
                systemDirectory.usersByIds(List.of(userId)).stream().findFirst().orElse(null)));
    }

    @PostMapping("/posts")
    @Operation(summary = "给已有员工追加岗位（同一岗位幂等返回）")
    @PreAuthorize("@post.has('platform:org:manage')")
    public CommonResult<StaffRow> assignPost(@Valid @RequestBody AssignPostReqVO req) {
        requireScope(req.orgId());
        long postId = findOrAssign(req.userId(), req.orgId(), req.roleCode(), req.teamId());
        return success(row(staffPostService.getPost(postId).orElseThrow(() -> exception(PlatformOrgErrorCodes.POST_NOT_FOUND)),
                systemDirectory.usersByIds(List.of(req.userId())).stream().findFirst().orElse(null)));
    }

    @DeleteMapping("/posts/{postId}")
    @Operation(summary = "撤销任职（员工登录后该岗位立即消失）")
    @PreAuthorize("@post.has('platform:org:manage')")
    @Parameter(name = "postId", description = "任职编号")
    public CommonResult<Boolean> revokePost(@PathVariable("postId") long postId) {
        StaffPostDO post = staffPostService.getPost(postId).orElseThrow(() -> exception(PlatformOrgErrorCodes.POST_NOT_FOUND));
        requireScope(post.getOrgId());
        staffPostService.revokePost(postId, currentPost.find().orElseThrow(() -> exception(POST_REQUIRED)).userId());
        return success(true);
    }

    @PostMapping("/staff-status")
    @Operation(summary = "启用或停用员工（停用后登录被拒、令牌失效；仅可操作本组织成员）")
    @PreAuthorize("@post.has('platform:org:manage')")
    public CommonResult<Boolean> setStatus(@Valid @RequestBody StaffStatusReqVO req) {
        PostSnapshotView post = scope();
        boolean belongs = staffPostService.listActivePosts(req.userId()).stream()
                .anyMatch(p -> p.getOrgId() == post.orgId());
        if (!belongs) {
            throw exception(PlatformOrgErrorCodes.USER_NOT_IN_ORG);
        }
        systemDirectory.setUserActive(req.userId(), req.active());
        return success(true);
    }

    // ===== 范围与组装 =====

    private record PostSnapshotView(long postId, long userId, long orgId, String roleCode) {
    }

    private PostSnapshotView scope() {
        return currentPost.find()
                .map(p -> new PostSnapshotView(p.postId(), p.userId(), p.orgId(), p.roleCode()))
                .orElseThrow(() -> exception(POST_REQUIRED));
    }

    /** 只能管理当前岗位所在组织（越权即拒绝，权限码之外的第二道校验） */
    private void requireScope(long orgId) {
        PostSnapshotView post = scope();
        if (post.orgId() != orgId) {
            throw exception(PlatformAuthzErrorCodes.ORG_SCOPE_FORBIDDEN);
        }
    }

    private long findOrAssign(long userId, long orgId, String roleCode, Long teamId) {
        Optional<Long> existing = staffPostService.listActivePosts(userId).stream()
                .filter(p -> p.getOrgId() == orgId && roleCode.equals(p.getRoleCode()))
                .map(StaffPostDO::getId).findFirst();
        return existing.orElseGet(() -> staffPostService.assignPost(userId, orgId, roleCode, teamId,
                currentPost.find().orElseThrow(() -> exception(POST_REQUIRED)).userId()));
    }

    private Map<Long, SystemDirectory.UserBrief> usersByPosts(List<StaffPostDO> posts) {
        Set<Long> ids = posts.stream().map(StaffPostDO::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return systemDirectory.usersByIds(ids).stream()
                .collect(Collectors.toMap(SystemDirectory.UserBrief::userId, Function.identity()));
    }

    private StaffRow row(StaffPostDO post, SystemDirectory.UserBrief user) {
        return new StaffRow(post.getId(), post.getUserId(),
                user == null ? "" : user.name(),
                user == null ? "" : user.mobile(),
                user != null && user.active(),
                post.getRoleCode(), post.getTeamId(),
                systemDirectory.deptName(post.getTeamId()),
                post.getCreateTime() == null ? "" : post.getCreateTime().toString());
    }

}
