package cn.iocoder.yudao.module.franchise.crm;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerMapper;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.module.franchise.crm.dal.LeadDO;
import cn.iocoder.yudao.module.franchise.crm.dal.LeadMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.crm.enums.FranchiseCrmErrorCodes.LEAD_IMPORT_FORBIDDEN;

/**
 * P1 批3：客资批量导入（Web /fr/customers，R5 §三.8 客资行 + 导入事务语义行 + §四#7）。
 *
 * <p>不走 {@link LeadService#capture}（capture 硬要求门店业务岗，老板不能直接套用）；
 * boss/owner 经导入入口创建客资。预检只读；提交逐行调
 * {@link LeadService#importNewCustomer} / {@link LeadService#importLinkRequiredLead}
 * （均为 @Transactional 独立事务），部分成功。
 *
 * <p><b>幂等（零 DDL）</b>：客户端为一次导入生成稳定 {@code batchId}，键为
 * {@code lead-import:{orgId}:{sha256(orgId:batchId)前16位}:{line}}（重试失败行时保持同键）。提交前先查命令事件 replay：
 * 命中即 SKIPPED，因此同批重试不会重复建客户/待关联客资；换批次再导入同号客户则按业务状态
 * 落"待关联"（需求要求同号一律不覆盖）。
 *
 * <p>组织隔离：本服务所有查询都显式带 {@code company_id = orgId}，不依赖线程内当前岗位。
 *
 * <p>三态（R5 定案）：新手机号→建客户(owner=操作者)+DIRECT；同手机号已有客户→仅建
 * LINK_REQUIRED（不覆盖、不自动关联，姓名相同也不跳过）；已有待关联客资→SKIPPED。
 *
 * <p>B-only 装配：仅 platform.legacy-design-bridge.enabled=false（B 独立进程）注册。
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class LeadImportService {

    public static final int MAX_ROWS = 500;
    public static final int BATCH_ID_MAX = 64;
    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");
    private static final Pattern BATCH_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** 行处置动作：导入提交时据此分派，避免用 message 文本做分支。 */
    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_LINK_REQUIRED = "LINK_REQUIRED";
    public static final String ACTION_SKIP = "SKIP";
    public static final String ACTION_FAIL = "FAIL";

    public record Row(int line, String name, String phone, String need, String source) {
    }

    public record RowResult(int line, String name, String phone, String need, String source,
                            String status, Integer code, String message,
                            Long leadId, Long customerId, String note, String action) {
    }

    public record ImportResult(long orgId, int total, int created, int skipped, int failed,
                               List<RowResult> rows) {
    }

    private final LeadService leadService;
    private final LeadMapper leadMapper;
    private final CustomerMapper customerMapper;
    private final FranchiseCommands commands;

    public LeadImportService(LeadService leadService, LeadMapper leadMapper,
                             CustomerMapper customerMapper, FranchiseCommands commands) {
        this.leadService = leadService;
        this.leadMapper = leadMapper;
        this.customerMapper = customerMapper;
        this.commands = commands;
    }

    /** 预检（只读 dry-run）：逐行结果，不产生任何写入。 */
    public ImportResult precheck(PostSnapshot post, String batchId, List<Row> rows) {
        long orgId = requireOperator(post);
        requireBatch(batchId, rows);
        List<RowResult> results = new ArrayList<>(rows.size());
        Set<String> seenPhones = new HashSet<>();
        Set<Integer> seenLines = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int line = lineOf(row, i);
            if (!seenLines.add(line)) {
                results.add(failedRow(line, row, 400, "行号重复：同一批次每行行号必须唯一"));
                continue;
            }
            try {
                results.add(checkRow(orgId, post, batchId, line, row, seenPhones));
            } catch (ServiceException e) {
                results.add(failedRow(line, row, e.getCode(), e.getMessage()));
            } catch (RuntimeException e) {
                logRowFailure(orgId, line, e);
                results.add(failedRow(line, row, 500, "该行处理失败，请稍后重试"));
            }
        }
        return summarize(orgId, results);
    }

    /** 提交（逐行独立事务，部分成功）。组织与操作者一律取自 post。 */
    public ImportResult submit(PostSnapshot post, String batchId, List<Row> rows) {
        long orgId = requireOperator(post);
        requireBatch(batchId, rows);
        List<RowResult> results = new ArrayList<>(rows.size());
        Set<String> seenPhones = new HashSet<>();
        Set<Integer> seenLines = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int line = lineOf(row, i);
            if (!seenLines.add(line)) {
                results.add(failedRow(line, row, 400, "行号重复：同一批次每行行号必须唯一"));
                continue;
            }
            try {
                RowResult checked = checkRow(orgId, post, batchId, line, row, seenPhones);
                if (!"OK".equals(checked.status())) {
                    results.add(checked);
                    continue;
                }
                String key = idempotencyKey(orgId, batchId, line);
                if (ACTION_LINK_REQUIRED.equals(checked.action())) {
                    var linkResult = leadService.importLinkRequiredLead(post, checked.name(),
                            checked.phone(), checked.need(), checked.source(), key);
                    results.add(new RowResult(line, checked.name(), checked.phone(), checked.need(),
                            checked.source(), "CREATED", null, "", linkResult.leadId(),
                            checked.customerId(), checked.note(), ACTION_LINK_REQUIRED));
                } else {
                    var createResult = leadService.importNewCustomer(post, checked.name(),
                            checked.phone(), checked.need(), checked.source(), key);
                    results.add(new RowResult(line, checked.name(), checked.phone(), checked.need(),
                            checked.source(), "CREATED", null, "", createResult.leadId(),
                            createResult.customerId(), null, ACTION_CREATE));
                }
            } catch (ServiceException e) {
                results.add(failedRow(line, row, e.getCode(), e.getMessage()));
            } catch (DuplicateKeyException e) {
                // 预检与提交之间被并发写入（同号客户/待关联客资）：该行可重试，不算数据错误。
                results.add(failedRow(line, row, 409, "数据已被并发处理，请重试该行"));
            } catch (RuntimeException e) {
                logRowFailure(orgId, line, e);
                results.add(failedRow(line, row, 500, "该行处理失败，请稍后重试"));
            }
        }
        ImportResult result = summarize(orgId, results);
        log.info("[lead-import][submitted] orgId={}, operatorUserId={}, batchId={}, total={}, created={}, skipped={}, failed={}",
                orgId, post.userId(), batchId, result.total(), result.created(), result.skipped(), result.failed());
        return result;
    }

    /**
     * 行级只读校验（预检与提交共享）：字段格式 + 批内重复 + 本组织同手机号状态。
     * <ul>
     *   <li>命令事件 replay 命中 → SKIPPED（同批重试幂等）</li>
     *   <li>本组织已有待关联客资 → SKIPPED</li>
     *   <li>本组织已有该手机号客户 → OK/LINK_REQUIRED（需求：同号一律不覆盖，姓名相同也不跳过）</li>
     *   <li>本组织已有该手机号已转化客资（客户已不存在）→ SKIPPED</li>
     *   <li>否则 → OK/CREATE</li>
     * </ul>
     */
    private RowResult checkRow(long orgId, PostSnapshot post, String batchId, int line,
                               Row row, Set<String> seenPhones) {
        String name = trim(row == null ? null : row.name());
        String phone = trim(row == null ? null : row.phone());
        String need = trim(row == null ? null : row.need());
        String source = trim(row == null ? null : row.source());

        if (name.isEmpty()) return failed(line, name, phone, need, source, 400, "姓名不能为空");
        if (name.length() > 64) return failed(line, name, phone, need, source, 400, "姓名最长 64 字");
        if (phone.isEmpty() || !PHONE.matcher(phone).matches()) {
            return failed(line, name, phone, need, source, 400, "手机号格式不正确（1 开头 11 位）");
        }
        if (need.isEmpty()) return failed(line, name, phone, need, source, 400, "需求描述不能为空");
        if (need.length() > 500) return failed(line, name, phone, need, source, 400, "需求描述最长 500 字");
        if (source.isEmpty()) return failed(line, name, phone, need, source, 400, "来源不能为空");
        if (source.length() > 64) return failed(line, name, phone, need, source, 400, "来源最长 64 字");

        if (!seenPhones.add(phone)) {
            return failed(line, name, phone, need, source, 400, "批内重复行（相同手机号）");
        }

        String key = idempotencyKey(orgId, batchId, line);
        if (commands.replayed(post, key, new LeadService.CaptureCommand(name, phone, need, source))
                .isPresent()) {
            return skipped(line, name, phone, need, source, "该行已导入（批次重试）", null);
        }

        if (leadMapper.existsPendingInOrg(orgId, phone)) {
            return skipped(line, name, phone, need, source, "已存在待关联客资", null);
        }

        CustomerDO existing = customerMapper.selectByPhoneInOrg(orgId, phone);
        if (existing != null) {
            // 需求 §三.8：同手机号已有客户一律不覆盖、落待关联；姓名相同也不能证明是重放。
            return new RowResult(line, name, phone, need, source, "OK", null,
                    "同手机号已存在客户，等负责人关联", null, existing.getId(),
                    "同手机号已存在客户，等负责人关联", ACTION_LINK_REQUIRED);
        }

        LeadDO latest = leadMapper.selectLatestByPhoneInOrg(orgId, phone);
        if (latest != null && LeadDO.STATUS_CONVERTED.equals(latest.getStatus())) {
            return skipped(line, name, phone, need, source, "该手机号已登记", latest.getCustomerId());
        }

        return new RowResult(line, name, phone, need, source, "OK", null, "", null, null, null,
                ACTION_CREATE);
    }

    /**
     * 幂等键：组织 + 批次摘要 + 行号。批次部分取 sha256 前 16 位，
     * 避免长 batchId 使键超过 FranchiseCommands.scopedKey 的 80 字符上限而被截断丢行号。
     */
    static String idempotencyKey(long orgId, String batchId, int line) {
        String digest = DigestUtil.sha256Hex(orgId + ":" + batchId).substring(0, 16);
        return "lead-import:" + orgId + ":" + digest + ":" + line;
    }

    /** 仅老板/门店负责人可发起导入（产品契约；与员工导入的 boss/owner 放行对齐）。 */
    private static long requireOperator(PostSnapshot post) {
        if (post == null || !FranchiseRoles.isBoss(post)) {
            throw exception(LEAD_IMPORT_FORBIDDEN);
        }
        return post.orgId();
    }

    private static void requireBatch(String batchId, List<Row> rows) {
        if (batchId == null || !BATCH_ID.matcher(batchId).matches()) {
            throw new ServiceException(400, "缺少或非法的批次号（batchId）");
        }
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ROWS) {
            throw new ServiceException(400, "单批须为 1-500 行");
        }
    }

    private static RowResult skipped(int line, String name, String phone, String need, String source,
                                     String message, Long customerId) {
        return new RowResult(line, name, phone, need, source, "SKIPPED", null, message, null,
                customerId, message, ACTION_SKIP);
    }

    private static ImportResult summarize(long orgId, List<RowResult> rows) {
        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (RowResult row : rows) {
            if ("CREATED".equals(row.status()) || "OK".equals(row.status())) created++;
            else if ("SKIPPED".equals(row.status())) skipped++;
            else failed++;
        }
        return new ImportResult(orgId, rows.size(), created, skipped, failed, rows);
    }

    private static int lineOf(Row row, int index) {
        return row != null && row.line() > 0 ? row.line() : index + 1;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static void logRowFailure(long orgId, int line, RuntimeException e) {
        log.error("[lead-import][row failed] orgId={}, line={}, exType={}", orgId, line, e.getClass().getName());
    }

    private static RowResult failedRow(int line, Row row, int code, String message) {
        return new RowResult(line, trim(row == null ? null : row.name()),
                trim(row == null ? null : row.phone()), trim(row == null ? null : row.need()),
                trim(row == null ? null : row.source()), "FAILED", code, message, null, null, null,
                ACTION_FAIL);
    }

    private static RowResult failed(int line, String name, String phone, String need, String source,
                                    int code, String message) {
        return new RowResult(line, name, phone, need, source, "FAILED", code, message, null, null,
                null, ACTION_FAIL);
    }
}
