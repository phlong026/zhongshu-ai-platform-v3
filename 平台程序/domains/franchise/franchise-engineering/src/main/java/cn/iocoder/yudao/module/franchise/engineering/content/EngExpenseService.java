package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.module.franchise.api.EngineeringLedgerQueryPort;
import cn.iocoder.yudao.module.franchise.api.EngineeringPayablePort;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngExpenseDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngExpenseMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectDO;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.ApprovalServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.engineering.enums.FranchiseEngineeringErrorCodes.ENGINEERING_INPUT_INVALID;

/**
 * M09-T04：直接项目费用登记与确认（I03 结算屏费用汇总的真实数据源）。
 * 规则（权威 16 直接项目费用）：
 * <ul>
 *   <li>确认（REGISTERED）即计成本——汇总 Σ已确认金额；</li>
 *   <li>付款经 EngineeringPayablePort 挂 finance 应付（付款不重计成本——应付清偿是资金侧事实）；</li>
 *   <li>退回减已付现金、确认费用变更才调成本——本服务无「退回调成本」动作（退多付款不减正确费用）；</li>
 *   <li>无公共费用分摊（费用全部归属单项目）。</li>
 * </ul>
 * 契约供 M07-T07 劳务应付付款联动引用：劳务费用走本服务登记确认（purpose=LABOR），
 * 付款沿 payable_id 经 finance 应付清偿。
 */
@Service
@Slf4j
public class EngExpenseService {

    public static final String AGGREGATE = "fr-eng-project";

    public record RegisterCommand(String name, String purpose, BigDecimal amount, String note) {
    }

    public record TermsCommand(String supplier, String basis) {
    }

    @Resource
    private FrEngExpenseMapper expenses;
    @Resource
    private EngProjectService projectService;
    @Resource
    private FranchiseCommands commands;
    @Resource
    private EngineeringPayablePort payables;
    @Resource
    private EngineeringLedgerQueryPort ledger;

    /** 登记（确认即计成本：REGISTERED 落行并留审计） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> register(PostSnapshot post, long projectId, RegisterCommand cmd,
                                        String idempotencyKey) {
        FrEngProjectDO project = projectService.requireOwned(post, projectId, "expenses");
        var input = Map.of("projectId", projectId, "name", cmd.name() == null ? "" : cmd.name(),
                "purpose", cmd.purpose() == null ? "" : cmd.purpose(),
                "amount", cmd.amount() == null ? "" : cmd.amount().toPlainString());
        var replayed = commands.replayed(post, idempotencyKey, input);
        if (replayed.isPresent()) {
            return view(post, projectId, ((Number) replayed.get().getAggregateId()).longValue());
        }
        if (cmd.name() == null || cmd.name().isBlank() || cmd.name().length() > 100) {
            throw exception(ENGINEERING_INPUT_INVALID, "请填写费用名称（100 字内）");
        }
        if (!"LABOR".equals(cmd.purpose()) && !"TRAVEL".equals(cmd.purpose()) && !"OTHER".equals(cmd.purpose())) {
            throw exception(ENGINEERING_INPUT_INVALID, "费用用途须为 劳务/差旅/其它");
        }
        long amountCents = EngGuard.requirePositiveCents(cmd.amount());
        FrEngExpenseDO expense = new FrEngExpenseDO()
                .setCompanyId(post.orgId()).setProjectId(projectId)
                .setName(cmd.name().trim()).setPurpose(cmd.purpose()).setAmountCents(amountCents)
                .setStatus(FrEngExpenseDO.STATUS_REGISTERED)
                .setNote(cmd.note() == null ? null : cmd.note().trim())
                .setAuthorUserId(post.userId());
        expenses.insert(expense);
        commands.record(post, AGGREGATE, expense.getId(), 1, "eng-expense-register", input, idempotencyKey);
        return view(post, projectId, expense.getId());
    }

    /**
     * 采购凭据+应付挂接（付款不重计成本：应付清偿走 finance 资金侧；费用成本在确认时已计）。
     * sourceRef=engineering-expense:{projectId}:{expenseId}（多笔付款累计制唯一成分）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> terms(PostSnapshot post, long expenseId, TermsCommand cmd, String idempotencyKey) {
        FrEngExpenseDO expense = expenses.lockByCompany(post.orgId(), expenseId);
        if (expense == null || expense.getCompanyId() != post.orgId()
                || FrEngExpenseDO.STATUS_CANCELLED.equals(expense.getStatus())) {
            throw exception(ENGINEERING_INPUT_INVALID, "费用不存在或已取消");
        }
        FrEngProjectDO project = projectService.requireOwned(post, expense.getProjectId(), "expenses");
        // R1 P2-4：replay 与 record 共用同一 canonical（LinkedHashMap 固定键序）
        var canonical = new java.util.LinkedHashMap<String, Object>();
        canonical.put("expenseId", expenseId);
        canonical.put("supplier", cmd.supplier() == null ? "" : cmd.supplier().trim());
        canonical.put("basis", cmd.basis() == null ? "" : cmd.basis().trim());
        var replayed = commands.replayed(post, idempotencyKey, canonical);
        if (replayed.isPresent()) {
            return view(post, expense.getProjectId(), expenseId);
        }
        if (cmd.supplier() == null || cmd.supplier().isBlank() || cmd.basis() == null || cmd.basis().isBlank()) {
            throw exception(ENGINEERING_INPUT_INVALID, "请填写收款方与费用凭据");
        }
        long payableId;
        if (expense.getPayableId() != null) {
            payableId = expense.getPayableId(); // 幂等：沿用原应付
        } else {
            payableId = payables.registerPayable(post, new EngineeringPayablePort.PayableCommand(
                    expense.getProjectId(), "engineering-expense:" + expense.getProjectId() + ":" + expenseId,
                    cmd.supplier().trim(), expense.getAmountCents(), cmd.basis().trim()));
            expenses.updateById(new FrEngExpenseDO().setId(expenseId).setPayableId(payableId));
        }
        commands.record(post, AGGREGATE, expense.getProjectId(),
                projectService.bumpRevision(post, project),
                "eng-expense-terms", canonical, idempotencyKey);
        return view(post, expense.getProjectId(), expenseId);
    }

    /** 取消（0 成本调整路径）：已挂应付者拒绝（交财务在原付款事项处理——与材料已付款锁定同口径） */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(PostSnapshot post, long expenseId, String reason, String idempotencyKey) {
        FrEngExpenseDO expense = expenses.lockByCompany(post.orgId(), expenseId);
        if (expense == null || expense.getCompanyId() != post.orgId()) {
            throw exception(ENGINEERING_INPUT_INVALID, "费用不存在");
        }
        projectService.requireOwned(post, expense.getProjectId(), "expenses");
        var replayed = commands.replayed(post, idempotencyKey,
                Map.of("expenseId", expenseId, "reason", reason == null ? "" : reason));
        if (replayed.isPresent()) {
            return;
        }
        // R1 P1-3：已挂应付费用不可单方取消（即使未付款）——应付须财务侧原子关闭（端口支持前拒绝），
        // 否则费用状态 CANCELLED 而应付仍可付款，结算屏已排除该费用、财务却能继续付款
        if (expense.getPayableId() != null) {
            throw exception(ENGINEERING_INPUT_INVALID, "已挂应付的费用不可取消，请先在财务侧关闭原应付事项");
        }
        if (reason == null || reason.isBlank()) {
            throw exception(ENGINEERING_INPUT_INVALID, "请填写取消原因");
        }
        expenses.updateById(new FrEngExpenseDO().setId(expenseId)
                .setStatus(FrEngExpenseDO.STATUS_CANCELLED));
        commands.record(post, AGGREGATE, expense.getProjectId(),
                projectService.bumpRevision(post, projectService.requireProject(post, expense.getProjectId())),
                "eng-expense-cancel", Map.of("expenseId", expenseId, "reason", reason), idempotencyKey);
    }

    /**
     * M09-T04/R1 P2-5：确认费用变更（金额修订）——独立于现金退回的成本调整路径
     * （费用 100 已付款、确认减项至 80：成本调为 80，已付现金不减——「退多付款不减正确费用」）。
     * 无有效付款时同步原应付金额；已有付款时不重写财务应付或付款。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> revise(PostSnapshot post, long expenseId, BigDecimal newAmount,
                                      String note, String idempotencyKey) {
        FrEngExpenseDO expense = expenses.lockByCompany(post.orgId(), expenseId);
        if (expense == null || expense.getCompanyId() != post.orgId()
                || FrEngExpenseDO.STATUS_CANCELLED.equals(expense.getStatus())) {
            throw exception(ENGINEERING_INPUT_INVALID, "费用不存在或已取消");
        }
        projectService.requireOwned(post, expense.getProjectId(), "expenses");
        long newCents = EngGuard.requirePositiveCents(newAmount);
        var canonical = Map.of("expenseId", expenseId, "newAmountCents", newCents,
                "note", note == null ? "" : note);
        var replayed = commands.replayed(post, idempotencyKey, canonical);
        if (replayed.isPresent()) {
            return view(post, expense.getProjectId(), expenseId);
        }
        if (expense.getPayableId() != null) {
            payables.syncUnpaidExpenseAmount(post, expense.getProjectId(), expenseId, expense.getPayableId(),
                    newCents, "eng-expense-unpaid:" + cn.hutool.crypto.digest.DigestUtil.sha256Hex(idempotencyKey));
        }
        // record 与 replay 同 canonical（R2 P2：字段集不同致同键重试摘要不匹配）；旧金额入日志
        expenses.updateById(new FrEngExpenseDO().setId(expenseId).setAmountCents(newCents));
        commands.record(post, AGGREGATE, expense.getProjectId(),
                projectService.bumpRevision(post, projectService.requireProject(post, expense.getProjectId())),
                "eng-expense-revise", canonical, idempotencyKey);
        log.info("[revise][expense={} {}→{}]", expenseId, expense.getAmountCents(), newCents);
        return view(post, expense.getProjectId(), expenseId);
    }

    /** 费用视图 */
    public Map<String, Object> view(PostSnapshot post, long projectId, long expenseId) {
        FrEngExpenseDO e = expenses.selectById(expenseId);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("expenseId", String.valueOf(e.getId()));
        row.put("name", e.getName());
        row.put("purpose", e.getPurpose());
        row.put("amountCents", e.getAmountCents());
        row.put("status", e.getStatus());
        row.put("payableId", e.getPayableId() == null ? "" : String.valueOf(e.getPayableId()));
        return row;
    }

    /**
     * 项目费用清单（R1 P1-2：D-13 金额脱敏——非负责人/非财务不含 amountCents/payableId，
     * 与结算屏 full 判定同口径：finance/负责人/受派工程师可见金额）。
     */
    public List<Map<String, Object>> listByProject(PostSnapshot post, long projectId) {
        FrEngProjectDO project = projectService.requireProject(post, projectId);
        boolean full = EngProjectService.isFinanceOrAbove(post)
                || post.userId() == project.getManagerUserId()
                || (project.getAssignedEngineerUserId() != null
                        && post.userId() == project.getAssignedEngineerUserId());
        return expenses.selectByProject(post.orgId(), projectId).stream()
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("expenseId", String.valueOf(e.getId()));
                    row.put("name", e.getName());
                    row.put("purpose", e.getPurpose());
                    row.put("status", e.getStatus());
                    if (full) {
                        row.put("amountCents", e.getAmountCents());
                        row.put("payableId", e.getPayableId() == null ? "" : String.valueOf(e.getPayableId()));
                    }
                    return row;
                }).toList();
    }
}
