package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.org.dal.OrgUnitDO;
import cn.iocoder.yudao.module.platform.org.dal.PostTemplateDO;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.POST_TEAM_CONFLICT;
import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.TEAM_NOT_IN_ORG;

/**
 * P1 批2：员工批量导入（§三.8 员工行 + 导入事务语义行）。
 *
 * <p>预检严格只读（不建号/不写任职/不写审计）：团队名→teamId、角色码∈既有岗位模板、
 * 手机号→既有账号→同 org+role 任职现状；所有校验均为只读投影。
 *
 * <p>提交复用同一行校验。门店入口提供统一开户回调，非门店沿用
 * {@link StaffPostService#createStaffInOrg}。本方法<b>不加事务注解</b>，
 * 每行调用经 Spring 代理获得独立事务：单行失败不影响已成功行。
 *
 * <p>预检与提交共享同一行校验（{@link #checkRow}），提交前不信任预检结果，逐行重新校验。
 * 幂等按业务自然键（手机号+组织+岗位+团队）：重试同一行不会产生重复任职，无需跟踪表。
 *
 * <p>门店开户的手机号去重、账号别名与初始密码策略由统一开户服务处理。
 * 非门店建档继续沿用原有账号/任职语义。
 *
 * <p>B-only 装配：仅 platform.legacy-design-bridge.enabled=false（B 独立进程）注册。
 */
@Service
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class StaffImportService {

    public static final int MAX_ROWS = 500;
    private static final int NAME_MAX = 20;
    private static final int ROLE_MAX = 64;
    private static final int TEAM_MAX = 32;
    private static final Pattern MOBILE = Pattern.compile("^1\\d{10}$");
    private static final Logger log = LoggerFactory.getLogger(StaffImportService.class);

    public record Row(Integer line, String name, String mobile, String roleCode, String teamName) {
    }

    public record RowResult(int line, String name, String mobile, String roleCode, String teamName,
                            String status, Integer code, String message, Long postId, Long userId, String note,
                            AccountDelivery account) {
    }

    /** 仅本次响应交付；不写入导入历史、访问日志或异常日志。 */
    public record AccountDelivery(String accountId, String name, String orgName, String loginName,
                                  List<String> aliases, String initialPassword, boolean reusedUser) {
        @Override public String toString() {
            return "StaffImportAccount[accountId=" + accountId + "]";
        }
    }

    public record CreatedStaff(long postId, Long userId, AccountDelivery account) {
    }

    /** 由上层装配真实、带事务的开户服务，组织模块不反向依赖认证模块。 */
    @FunctionalInterface
    public interface RowCreator {
        CreatedStaff create(RowResult checked, Long teamId);
    }

    public record ImportResult(long orgId, int total, int created, int skipped, int failed,
                               List<RowResult> rows) {
    }

    private record Context(Map<String, List<Long>> teamsByName, Set<String> allowedRoles) {
    }

    private final StaffPostService staffPostService;
    private final OrgService orgService;
    private final SystemDirectory systemDirectory;

    public StaffImportService(StaffPostService staffPostService, OrgService orgService,
                              SystemDirectory systemDirectory) {
        this.staffPostService = staffPostService;
        this.orgService = orgService;
        this.systemDirectory = systemDirectory;
    }

    /** 预检（只读 dry-run）：逐行结果，不产生任何写入。 */
    public ImportResult precheck(long orgId, List<Row> rows) {
        requireBatchSize(rows);
        Context context;
        try {
            context = context(orgId);
        } catch (RuntimeException e) {
            return failedAll(orgId, rows, e);
        }
        Map<String, Integer> seen = new HashMap<>();
        List<RowResult> results = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int line = lineOf(row, i);
            try {
                results.add(checkRow(orgId, row, i + 1, context, seen));
            } catch (ServiceException e) {
                results.add(failedRow(line, row, e.getCode(), e.getMessage()));
            } catch (RuntimeException e) {
                logRowFailure(orgId, line, e);
                results.add(failedRow(line, row, 500, "该行处理失败，请稍后重试"));
            }
        }
        return summarize(orgId, results);
    }

    /** 提交（逐行独立事务，部分成功）。 */
    public ImportResult submit(long orgId, List<Row> rows, long operatorUserId) {
        return submit(orgId, rows, operatorUserId, null);
    }

    /** 已有同岗行也进入统一开户回调，补齐历史导入缺失的别名；不重置已有密码。 */
    public ImportResult submit(long orgId, List<Row> rows, long operatorUserId, RowCreator accountCreator) {
        requireBatchSize(rows);
        Context context;
        try {
            context = context(orgId);
        } catch (RuntimeException e) {
            return failedAll(orgId, rows, e);
        }
        Map<String, Integer> seen = new HashMap<>();
        List<RowResult> results = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int line = lineOf(row, i);
            RowResult checked;
            try {
                checked = checkRow(orgId, row, i + 1, context, seen);
            } catch (ServiceException e) {
                results.add(failedRow(line, row, e.getCode(), e.getMessage()));
                continue;
            } catch (RuntimeException e) {
                logRowFailure(orgId, line, e);
                results.add(failedRow(line, row, 500, "该行处理失败，请稍后重试"));
                continue;
            }
            if (!"OK".equals(checked.status())
                    && !("SKIPPED".equals(checked.status()) && accountCreator != null)) {
                results.add(checked);
                continue;
            }
            try {
                CreatedStaff created = accountCreator == null
                        ? new CreatedStaff(staffPostService.createStaffInOrg(orgId, checked.name(), checked.mobile(),
                            checked.roleCode(), teamIdOf(checked, context, line), operatorUserId), checked.userId(), null)
                        : accountCreator.create(checked, teamIdOf(checked, context, line));
                results.add(new RowResult(line, checked.name(), checked.mobile(), checked.roleCode(),
                        checked.teamName(), "SKIPPED".equals(checked.status()) ? "SKIPPED" : "CREATED", null, "",
                        created.postId(), created.userId(), checked.note(), created.account()));
            } catch (ServiceException e) {
                results.add(new RowResult(line, checked.name(), checked.mobile(), checked.roleCode(),
                        checked.teamName(), "FAILED", e.getCode(), e.getMessage(), null, checked.userId(), null, null));
            } catch (RuntimeException e) {
                logRowFailure(orgId, line, e);
                results.add(new RowResult(line, checked.name(), checked.mobile(), checked.roleCode(),
                        checked.teamName(), "FAILED", 500, "该行处理失败，请稍后重试", null, checked.userId(), null, null));
            }
        }
        ImportResult result = summarize(orgId, results);
        // 提交成功计数（不含任何手机号/姓名）；预检不打印。
        log.info("[staff-import][submitted] orgId={}, operatorUserId={}, total={}, created={}, skipped={}, failed={}",
                orgId, operatorUserId, result.total(), result.created(), result.skipped(), result.failed());
        return result;
    }

    private ImportResult failedAll(long orgId, List<Row> rows, RuntimeException e) {
        log.error("[staff-import][context failed] orgId={}, exType={}", orgId, e.getClass().getName());
        List<RowResult> results = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            results.add(failedRow(lineOf(row, i), row, 500, "批次加载失败（服务暂不可用），请整批稍后重试"));
        }
        return summarize(orgId, results);
    }

    private static void logRowFailure(long orgId, int line, RuntimeException e) {
        // PII 边界：只落 orgId/line/exType/code，不输出异常 message/Throwable（可能含手机号）。
        log.error("[staff-import][row failed] orgId={}, line={}, exType={}, code={}",
                orgId, line, e.getClass().getName(), 500);
    }

    private static RowResult failedRow(int line, Row row, int code, String message) {
        return failed(line, trim(row == null ? null : row.name()), trim(row == null ? null : row.mobile()),
                trim(row == null ? null : row.roleCode()), trim(row == null ? null : row.teamName()),
                code, message);
    }

    private Long teamIdOf(RowResult checked, Context context, int line) {
        List<Long> teams = context.teamsByName().get(checked.teamName());
        return teams == null || teams.size() != 1 ? null : teams.get(0);
    }

    /**
     * 共享行校验：预检与提交同源。返回 status=OK/SKIPPED/FAILED。
     * 副作用仅 {@code seen}（批内重复检测），不做任何持久化写入。
     */
    private RowResult checkRow(long orgId, Row row, int fallbackLine, Context context,
                               Map<String, Integer> seen) {
        int line = lineOf(row, fallbackLine - 1);
        String name = trim(row == null ? null : row.name());
        String mobile = trim(row == null ? null : row.mobile());
        String roleCode = trim(row == null ? null : row.roleCode());
        String teamName = trim(row == null ? null : row.teamName());

        if (name.isEmpty()) return failed(line, name, mobile, roleCode, teamName, 400, "姓名不能为空");
        if (name.length() > NAME_MAX) return failed(line, name, mobile, roleCode, teamName, 400, "姓名最长 20 字");
        if (mobile.isEmpty() || !MOBILE.matcher(mobile).matches()) {
            return failed(line, name, mobile, roleCode, teamName, 400, "手机号格式不正确（1 开头 11 位）");
        }
        if (roleCode.isEmpty()) return failed(line, name, mobile, roleCode, teamName, 400, "角色码不能为空");
        if (roleCode.length() > ROLE_MAX) return failed(line, name, mobile, roleCode, teamName, 400, "角色码过长");
        if (teamName.isEmpty()) return failed(line, name, mobile, roleCode, teamName, 400, "团队名不能为空");
        if (teamName.length() > TEAM_MAX) return failed(line, name, mobile, roleCode, teamName, 400, "团队名过长");

        List<Long> teams = context.teamsByName().get(teamName);
        if (teams == null || teams.isEmpty()) {
            return failed(line, name, mobile, roleCode, teamName, TEAM_NOT_IN_ORG.getCode(),
                    TEAM_NOT_IN_ORG.getMsg() + "：" + teamName);
        }
        if (teams.size() > 1) {
            return failed(line, name, mobile, roleCode, teamName, 400,
                    "团队名重复（" + teamName + " 命中 " + teams.size() + " 条），请先使用唯一名称");
        }
        long teamId = teams.get(0);
        if (!context.allowedRoles().contains(roleCode)) {
            return failed(line, name, mobile, roleCode, teamName, 400,
                    "角色码不在当前组织可指派范围：" + roleCode);
        }

        String naturalKey = mobile + "|" + roleCode + "|" + teamId;
        Integer firstLine = seen.get(naturalKey);
        if (firstLine != null) {
            return failed(line, name, mobile, roleCode, teamName, 400,
                    "批内重复行（与第 " + firstLine + " 行相同：手机号+角色码+团队）");
        }

        Long userId = systemDirectory.userIdByMobile(mobile);
        if (userId == null) {
            seen.put(naturalKey, line);
            return new RowResult(line, name, mobile, roleCode, teamName, "OK", null, "", null, null, null, null);
        }
        StaffPostDO existing = staffPostService.listActivePosts(userId).stream()
                .filter(post -> post.getOrgId() == orgId && roleCode.equals(post.getRoleCode()))
                .findFirst().orElse(null);
        if (existing != null) {
            if (!Objects.equals(existing.getTeamId(), teamId)) {
                return failed(line, name, mobile, roleCode, teamName, POST_TEAM_CONFLICT.getCode(),
                        POST_TEAM_CONFLICT.getMsg());
            }
            seen.put(naturalKey, line);
            return new RowResult(line, name, mobile, roleCode, teamName, "SKIPPED", null, "",
                    existing.getId(), userId, null, null);
        }
        seen.put(naturalKey, line);
        String existingName = systemDirectory.usersByIds(List.of(userId)).stream()
                .findFirst().map(SystemDirectory.UserBrief::name).orElse(null);
        String note = existingName == null ? "手机号已存在账号，将保留原资料仅追加任职"
                : "手机号已存在账号（" + existingName + "），将保留原资料仅追加任职";
        return new RowResult(line, name, mobile, roleCode, teamName, "OK", null, "", null, userId, note, null);
    }

    private Context context(long orgId) {
        Map<String, List<Long>> teamsByName = new HashMap<>();
        for (OrgUnitDO unit : orgService.listTeams(orgId)) {
            String name = trim(systemDirectory.deptName(unit.getDeptId()));
            if (!name.isEmpty()) {
                teamsByName.computeIfAbsent(name, key -> new ArrayList<>()).add(unit.getDeptId());
            }
        }
        Set<String> allowedRoles = new HashSet<>();
        OrgUnitDO org = orgService.getUnit(orgId);
        for (PostTemplateDO template : orgService.listPostTemplates(orgId)) {
            if (MemberRolePolicy.isAssignable(org == null ? null : org.getOrgType(), template.getRoleCode())) allowedRoles.add(template.getRoleCode());
        }
        return new Context(teamsByName, allowedRoles);
    }

    private static ImportResult summarize(long orgId, List<RowResult> rows) {
        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (RowResult row : rows) {
            if ("OK".equals(row.status()) || "CREATED".equals(row.status())) {
                created++;
            } else if ("SKIPPED".equals(row.status())) {
                skipped++;
            } else {
                failed++;
            }
        }
        return new ImportResult(orgId, rows.size(), created, skipped, failed, rows);
    }

    private static void requireBatchSize(List<Row> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ROWS) {
            // 错误码模板无占位符：直接构造，避免 doFormat 记录 ERROR。
            throw new ServiceException(400, "单批须为 1-500 行");
        }
    }

    private static int lineOf(Row row, int index) {
        return row != null && row.line() != null && row.line() > 0 ? row.line() : index + 1;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static RowResult failed(int line, String name, String mobile, String roleCode, String teamName,
                                    int code, String message) {
        return new RowResult(line, name, mobile, roleCode, teamName, "FAILED", code, message,
                null, null, null, null);
    }
}
