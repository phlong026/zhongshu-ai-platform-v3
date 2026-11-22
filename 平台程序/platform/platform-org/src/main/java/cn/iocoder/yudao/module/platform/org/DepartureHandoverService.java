package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.platform.org.dal.PlatformHandoverAuditDO;
import cn.iocoder.yudao.module.platform.org.dal.PlatformHandoverAuditMapper;
import cn.iocoder.yudao.module.platform.org.dal.PlatformHandoverPendingDO;
import cn.iocoder.yudao.module.platform.org.dal.PlatformHandoverPendingMapper;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostMapper;
import cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * M01-T04：离职交接编排（I03 两条路径），返回交接清单并落持久化审计（Codex R2 P2-6）。
 *
 * <p><b>正常离职交接</b>（{@link #handover}）：独立事务段内「双方 advisory 锁 → 通用资格校验 →
 * 只读快照全部域 → 仅对有在途对象的域校验域级接收资格（R2 P1：零在途域不得阻断交接）→
 * 统一批量转移（转移数≠快照数即整体回滚可重试，R1 P1-5）→ 撤本组织任职+钱包会话 →
 * 核销本轮已转移域的待分配行 → 落交接审计行」。事务提交后残留复核（R2 P1-5 的工程边界：
 * 不走交接锁的业务建户/转交可能在扫描后落对象——复核发现残留即落 OPEN 待分配行，补交接承接，
 * 残留不丢失；不侵入建户热路径取锁，锁协议边界如实记录于执行文档）。
 *
 * <p><b>紧急撤权</b>（{@link #emergencyRevoke}）：立即生效不等待交接——撤权在独立事务先行提交
 * （R2 P1-2：PG 下扫描 SQL 故障会污染同事务，故扫描/清单全部走各自独立小事务），逐域容错扫描
 * 并落 {@code pf_handover_pending} OPEN 行（扫描失败补 in_flight=-1 未知行、条目报 SCAN_FAILED）；
 * 审计行在撤权与清单齐备后落库（写失败仅记 error，不回滚已生效撤权）。
 *
 * <p>补交接：已撤岗员工存在 OPEN 待分配行时，正常交接入口仍然放行，完成后核销 OPEN 行（R1 P1-3：
 * 待分配可查询、可补交接，不伪造已交接）。未接入 Port 的域如实报告 NOT_INTEGRATED。
 *
 * <p>锁序（R1 P1-4）：交接双方 advisory 用户锁按 userId 升序获取；R2 对齐全部撤权入口——
 * 单岗位撤销（{@code revokePost}）同样先行取 advisory 用户锁，串行化与交接的交错。
 */
@Service
@Slf4j
public class DepartureHandoverService {

    public static final String MODE_NORMAL = "NORMAL";
    public static final String MODE_EMERGENCY = "EMERGENCY";
    /** 清单条目状态：已交接 / 无在途对象 / 待分配（紧急撤权未转移）/ 域未接入 / 扫描失败（待分配行 in_flight=-1） */
    public static final String ST_HANDED_OVER = "HANDED_OVER";
    public static final String ST_NONE = "NONE";
    public static final String ST_PENDING_UNASSIGNED = "PENDING_UNASSIGNED";
    public static final String ST_NOT_INTEGRATED = "NOT_INTEGRATED";
    public static final String ST_SCAN_FAILED = "SCAN_FAILED";

    /** 交接清单期望域清单：未注册 Port 的域如实报告 NOT_INTEGRATED，不伪造已交接 */
    private static final List<KnownDomain> KNOWN_DOMAINS = List.of(
            new KnownDomain("franchise-crm", "客户"),
            new KnownDomain("franchise-commission", "设计委托"),
            new KnownDomain("franchise-engineering", "工程任务"),
            new KnownDomain("franchise-finance", "财务账款"));

    private record KnownDomain(String domain, String label) {
    }

    /** 交接清单条目：某域在途对象数 / 已转移数 / 结果状态 */
    public record HandoverItem(String domain, String label, long inFlight, int transferred, String status) {
    }

    /** 交接结果清单：模式、双方、被撤任职、逐域明细 */
    public record HandoverResult(String mode, long orgId, long fromUserId, Long toUserId,
                                 List<Long> revokedPostIds, List<HandoverItem> items) {
    }

    @Resource
    private StaffPostService staffPostService;
    @Resource
    private SystemDirectory systemDirectory;
    @Resource
    private ObjectProvider<DepartureHandoverPort> ports;
    @Resource
    private StaffPostMapper staffPostMapper;
    @Resource
    private PlatformHandoverPendingMapper pendingMapper;
    @Resource
    private PlatformHandoverAuditMapper auditMapper;
    /** 自注入走代理：事务段（撤权/交接/逐域扫描）经代理获得独立事务（Codex R1 P1-2 / R2 P1-2） */
    @Resource
    @Lazy
    private DepartureHandoverService self;

    // ===================== 正常离职交接（含补交接） =====================

    /**
     * 正常交接入口（非事务外壳）：事务段提交后做残留复核，返回交接清单。
     */
    public HandoverResult handover(long orgId, long fromUserId, long toUserId, long operatorUserId) {
        if (toUserId == fromUserId) {
            throw exception(PlatformOrgErrorCodes.HANDOVER_TARGET_SELF);
        }
        HandoverResult result = self.handoverInNewTransaction(orgId, fromUserId, toUserId, operatorUserId);
        verifyResidualAfterCommit(orgId, fromUserId, operatorUserId);
        return result;
    }

    /**
     * 交接事务段：REQUIRES_NEW——advisory 锁内校验、快照先行、按需资格校验、统一转移、撤权、
     * 核销本轮已转移域的待分配行、落审计行。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public HandoverResult handoverInNewTransaction(long orgId, long fromUserId, long toUserId, long operatorUserId) {
        // R1 P1-4：双方 advisory 锁按 userId 升序（事务内持有至提交），锁内做全部校验
        lockBothUsers(fromUserId, toUserId);
        Map<String, DepartureHandoverPort> byDomain = portsByDomainOrThrow();
        requireHandoverSource(orgId, fromUserId, byDomain);
        validateTarget(orgId, fromUserId, toUserId);
        // 保留角色与最后管理员检查必须先于业务转移；组织锁持有至本次交接提交。
        staffPostService.validateRevokeAllInOrg(fromUserId, orgId);
        // R2 P1：只读快照先行——域级接收资格只校验实际需要承接的域（零在途域不得阻断交接）
        Map<String, Long> snapshots = new LinkedHashMap<>();
        for (DepartureHandoverPort port : byDomain.values()) {
            snapshots.put(port.domain(), port.scanInFlight(fromUserId, orgId));
        }
        for (DepartureHandoverPort port : byDomain.values()) {
            if (snapshots.get(port.domain()) > 0) {
                port.validateReceiver(toUserId, orgId);
            }
        }

        // 统一开始写入：逐域转移（快照复用，不再重扫）
        List<HandoverItem> items = new ArrayList<>();
        java.util.Set<String> transferredDomains = new java.util.HashSet<>();
        for (KnownDomain known : KNOWN_DOMAINS) {
            DepartureHandoverPort port = byDomain.remove(known.domain());
            if (port == null) {
                items.add(new HandoverItem(known.domain(), known.label(), 0, 0, ST_NOT_INTEGRATED));
                continue;
            }
            items.add(transferDomain(port, orgId, fromUserId, toUserId, operatorUserId, snapshots.get(port.domain())));
            transferredDomains.add(port.domain());
        }
        for (DepartureHandoverPort extra : byDomain.values()) { // 清单外已接入域追加在末尾
            items.add(transferDomain(extra, orgId, fromUserId, toUserId, operatorUserId, snapshots.get(extra.domain())));
            transferredDomains.add(extra.domain());
        }

        List<Long> revokedPostIds = revokeAllInOrgCollecting(fromUserId, orgId, operatorUserId);
        // 只核销本轮已转移域的 OPEN 行（未接入域不得误标已交接，Codex R2 P1）
        pendingMapper.markHandedOver(orgId, fromUserId, toUserId, transferredDomains);
        persistAudit(orgId, MODE_NORMAL, fromUserId, toUserId, operatorUserId, revokedPostIds, items);
        log.info("[handover][NORMAL orgId={} from={} to={} revokedPostIds={} items={}]", orgId,
                fromUserId, toUserId, revokedPostIds, items);
        return new HandoverResult(MODE_NORMAL, orgId, fromUserId, toUserId, revokedPostIds, List.copyOf(items));
    }

    /** 提交后残留复核（只读+落 OPEN 行，容错：复核故障不影响已提交的交接本身） */
    private void verifyResidualAfterCommit(long orgId, long fromUserId, long operatorUserId) {
        try {
            for (DepartureHandoverPort port : portsByDomainOrThrow().values()) {
                long residual = port.scanInFlight(fromUserId, orgId);
                if (residual > 0) {
                    log.warn("[handover][提交后残留复核 orgId={} from={} domain={} residual={}] 残留已落待分配行（补交接承接）",
                            orgId, fromUserId, port.domain(), residual);
                    try {
                        upsertOpenPending(orgId, fromUserId, port.domain(), residual, operatorUserId);
                    } catch (Exception conflict) {
                        log.warn("[handover][残留待分配行落地冲突（已有 OPEN 行或已核销，跳过）] {}", conflict.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[handover][提交后残留复核失败（不影响已提交交接，待分配可经 listPending 补查）] {}", e.getMessage());
        }
    }

    /** 单域转移：inFlight==0 报 NONE；转移数≠快照数抛 022 整体回滚（残留不得报已交接） */
    private HandoverItem transferDomain(DepartureHandoverPort port, long orgId,
                                        long fromUserId, long toUserId, long operatorUserId, long inFlight) {
        if (inFlight == 0) {
            return new HandoverItem(port.domain(), port.label(), 0, 0, ST_NONE);
        }
        int transferred = port.bulkTransfer(fromUserId, toUserId, orgId, operatorUserId);
        if (transferred != inFlight) {
            throw exception(PlatformOrgErrorCodes.HANDOVER_TRANSFER_INCOMPLETE);
        }
        return new HandoverItem(port.domain(), port.label(), inFlight, transferred, ST_HANDED_OVER);
    }

    // ===================== 紧急撤权 =====================

    /**
     * 紧急撤权入口：撤权独立事务先行提交（硬目标），逐域独立小事务扫描+落待分配行，审计最后落库。
     * 任何扫描/清单故障不影响已生效的撤权（R2 P1-2：PG aborted 事务只污染所在小事务）。
     */
    public HandoverResult emergencyRevoke(long orgId, long userId, long operatorUserId) {
        if (!hasActivePostInOrg(userId, orgId)) {
            throw exception(PlatformOrgErrorCodes.HANDOVER_SOURCE_NO_POST);
        }
        List<Long> revokedPostIds = self.revokeOnlyInNewTransaction(orgId, userId, operatorUserId);

        Map<String, DepartureHandoverPort> byDomain = portsByDomainOrThrow();
        List<HandoverItem> items = new ArrayList<>();
        for (KnownDomain known : KNOWN_DOMAINS) {
            DepartureHandoverPort port = byDomain.remove(known.domain());
            items.add(port == null
                    ? new HandoverItem(known.domain(), known.label(), 0, 0, ST_NOT_INTEGRATED)
                    : scanDomainIsolated(port, orgId, userId, operatorUserId));
        }
        for (DepartureHandoverPort extra : byDomain.values()) {
            items.add(scanDomainIsolated(extra, orgId, userId, operatorUserId));
        }
        try {
            persistAudit(orgId, MODE_EMERGENCY, userId, null, operatorUserId, revokedPostIds, items);
        } catch (Exception e) {
            log.error("[handover][紧急撤权审计落库失败（撤权已生效，需人工补记 orgId={} userId={}）] {}",
                    orgId, userId, e.getMessage());
        }
        log.info("[handover][EMERGENCY orgId={} userId={} revokedPostIds={} items={}]", orgId,
                userId, revokedPostIds, items);
        return new HandoverResult(MODE_EMERGENCY, orgId, userId, null, revokedPostIds, List.copyOf(items));
    }

    /** 撤权事务段（硬目标）：advisory 锁 + 撤本组织全部任职（内含钱包会话吊销），独立提交 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public List<Long> revokeOnlyInNewTransaction(long orgId, long userId, long operatorUserId) {
        lockBothUsers(userId, userId);
        return revokeAllInOrgCollecting(userId, orgId, operatorUserId);
    }

    /**
     * 单域扫描事务段：REQUIRES_NEW——扫描 + OPEN 待分配行原子落地。SQL 级故障只回滚本域小事务，
     * 由调用方补未知行（in_flight=-1），不污染撤权与其他域（R2 P1-2）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public HandoverItem scanDomainInNewTransaction(long orgId, long userId, DepartureHandoverPort port, long operatorUserId) {
        long inFlight = port.scanInFlight(userId, orgId);
        String status = inFlight > 0 ? ST_PENDING_UNASSIGNED : ST_NONE;
        if (inFlight != 0) { // 有遗留（>0）才落待分配行；确认为零的域不留待办
            upsertOpenPending(orgId, userId, port.domain(), inFlight, operatorUserId);
        }
        return new HandoverItem(port.domain(), port.label(), inFlight, 0, status);
    }

    /** 扫描失败后的未知行落地：in_flight=-1（补交接按全量转移，不依赖快照数字） */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void persistUnknownPendingInNewTransaction(long orgId, long userId, String domain, long operatorUserId) {
        upsertOpenPending(orgId, userId, domain, -1, operatorUserId);
    }

    /** 单域隔离扫描：成功取真实快照；失败补未知行并报 SCAN_FAILED */
    private HandoverItem scanDomainIsolated(DepartureHandoverPort port, long orgId, long userId, long operatorUserId) {
        try {
            return self.scanDomainInNewTransaction(orgId, userId, port, operatorUserId);
        } catch (Exception e) {
            log.warn("[handover][紧急撤权扫描失败 orgId={} userId={} domain={}] {}", orgId, userId,
                    port.domain(), e.getMessage());
            try {
                self.persistUnknownPendingInNewTransaction(orgId, userId, port.domain(), operatorUserId);
            } catch (Exception e2) {
                log.error("[handover][待分配未知行落地失败 orgId={} userId={} domain={}] 需人工核对在途对象：{}",
                        orgId, userId, port.domain(), e2.getMessage());
            }
            return new HandoverItem(port.domain(), port.label(), -1, 0, ST_SCAN_FAILED);
        }
    }

    // ===================== 待分配查询与公共 =====================

    /** 待分配行（可查询）：负责人可据此发起补交接 */
    public record PendingRow(long id, long orgId, long fromUserId, String domain, String domainLabel,
                             long inFlight, String createTime) {
    }

    /** 本组织 OPEN 待分配清单（Codex R1 P1-3：待分配必须可查询、可补交接） */
    public List<PendingRow> listPending(long orgId) {
        return pendingMapper.selectOpenByOrg(orgId).stream()
                .map(p -> new PendingRow(p.getId(), p.getOrgId(), p.getFromUserId(), p.getDomain(),
                        labelOf(p.getDomain()), p.getInFlight(),
                        p.getCreateTime() == null ? "" : p.getCreateTime().toString()))
                .toList();
    }

    private String labelOf(String domain) {
        return KNOWN_DOMAINS.stream().filter(k -> k.domain().equals(domain))
                .findFirst().map(KnownDomain::label).orElse(domain);
    }

    /** 交接审计落库（Codex R2 P2-6）：清单 JSON 持久化，不依赖运行日志 */
    private void persistAudit(long orgId, String mode, long fromUserId, Long toUserId,
                              long operatorUserId, List<Long> revokedPostIds, List<HandoverItem> items) {
        auditMapper.insert(new PlatformHandoverAuditDO()
                .setOrgId(orgId).setMode(mode).setFromUserId(fromUserId).setToUserId(toUserId)
                .setOperatorUserId(operatorUserId)
                .setRevokedPostIds(revokedPostIds.stream().map(String::valueOf)
                        .reduce((a, b) -> a + "," + b).orElse(""))
                .setItemsJson(JsonUtils.toJsonString(items))
                .setCreateTime(OffsetDateTime.now()));
    }

    /** 同一（组织，离职人，域）至多一条 OPEN 行；已存在则仅刷新快照（重复紧急撤权幂等） */
    private void upsertOpenPending(long orgId, long userId, String domain, long inFlight, long operatorUserId) {
        var existing = pendingMapper.selectOpenByUser(orgId, userId).stream()
                .filter(p -> domain.equals(p.getDomain()))
                .findFirst();
        if (existing.isPresent()) {
            pendingMapper.updateById(new PlatformHandoverPendingDO().setId(existing.get().getId())
                    .setInFlight(inFlight).setUpdateTime(OffsetDateTime.now()));
            return;
        }
        pendingMapper.insert(new PlatformHandoverPendingDO().setOrgId(orgId).setFromUserId(userId)
                .setDomain(domain).setInFlight(inFlight)
                .setStatus(PlatformHandoverPendingDO.STATUS_OPEN).setOperatorUserId(operatorUserId)
                .setCreateTime(OffsetDateTime.now()).setUpdateTime(OffsetDateTime.now()));
    }

    /** 同域多实现是装配错误：静默丢实现会导致该域既不扫描也不转移（Codex R1 P2-4） */
    private Map<String, DepartureHandoverPort> portsByDomainOrThrow() {
        Map<String, DepartureHandoverPort> byDomain = new LinkedHashMap<>();
        ports.orderedStream().forEach(p -> {
            if (byDomain.containsKey(p.domain())) {
                throw new IllegalStateException("离职交接 Port 域重复注册: " + p.domain());
            }
            byDomain.put(p.domain(), p);
        });
        return byDomain;
    }

    /** advisory 用户锁按 userId 升序获取（去重），避免交叉加锁死锁；事务结束自动释放 */
    private void lockBothUsers(long a, long b) {
        staffPostMapper.lockUserAdvisoryForWrite(Math.min(a, b));
        if (a != b) {
            staffPostMapper.lockUserAdvisoryForWrite(Math.max(a, b));
        }
    }

    /**
     * 交接源资格（三居其一即可）：在职岗位；或 OPEN 待分配行；或任一已接入域仍有在途对象
     * （R3 P1：对账式补交接——「交接提交后才提交的晚到对象」可能没有 OPEN 行，但只要
     * 域里扫描得到残留，补交接入口就放行，残留始终可恢复，不依赖待分配行的落库时序）。
     */
    private void requireHandoverSource(long orgId, long fromUserId, Map<String, DepartureHandoverPort> byDomain) {
        if (hasActivePostInOrg(fromUserId, orgId)) {
            return;
        }
        if (!pendingMapper.selectOpenByUser(orgId, fromUserId).isEmpty()) {
            return; // 已撤岗且有登记的待分配事项：允许补交接（Codex R1 P1-3）
        }
        for (DepartureHandoverPort port : byDomain.values()) {
            if (port.scanInFlight(fromUserId, orgId) > 0) {
                log.info("[handover][源已撤岗但扫描到未登记残留 orgId={} from={} domain={}] 对账式补交接放行",
                        orgId, fromUserId, port.domain());
                return;
            }
        }
        throw exception(PlatformOrgErrorCodes.HANDOVER_SOURCE_NO_POST);
    }

    /** 接收人通用资格校验（域级资格由各 Port validateReceiver 把守）：停用/不在本组织均拒绝 */
    private void validateTarget(long orgId, long fromUserId, long toUserId) {
        if (!systemDirectory.userUsable(toUserId)) {
            throw exception(PlatformOrgErrorCodes.HANDOVER_TARGET_DISABLED);
        }
        if (!hasActivePostInOrg(toUserId, orgId)) {
            throw exception(PlatformOrgErrorCodes.HANDOVER_TARGET_NOT_IN_ORG);
        }
    }

    private boolean hasActivePostInOrg(long userId, long orgId) {
        return staffPostService.listActivePosts(userId).stream().anyMatch(p -> p.getOrgId() == orgId);
    }

    /** 撤本组织全部任职（复用 revokeAllInOrg，内含钱包会话吊销），返回被撤任职编号供清单展示 */
    private List<Long> revokeAllInOrgCollecting(long userId, long orgId, long operatorUserId) {
        List<Long> revokedPostIds = staffPostService.listActivePosts(userId).stream()
                .filter(p -> p.getOrgId() == orgId)
                .map(StaffPostDO::getId)
                .toList();
        staffPostService.revokeAllInOrg(userId, orgId, operatorUserId);
        return revokedPostIds;
    }
}
