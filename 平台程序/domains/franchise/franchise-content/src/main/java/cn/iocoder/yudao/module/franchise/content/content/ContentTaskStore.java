package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * fr_content_task 七态机的持久化心脏（16 §四/D-1.5-7）：
 * 一切状态迁移都是带前置状态条件的单语句 CAS（对标「短事务 CAS 固化终态」惯例，
 * AssetService 三段式同款）——迟到写手不会覆盖别人已推进的状态。
 * JSONB 列（input_snapshot/result_snapshot）经 CAST(? AS jsonb) 写入（infra JdbcAuditPort 先例）。
 *
 * <p>为何 JdbcTemplate 而非 Mapper：CAS 条件更新与条件 upsert 在 MyBatis-Plus 无等价表达；
 * 本类全部语句带显式 company_id 谓词，仅服务后台任务机与属主校验后的固化写——
 * 不承担用户态组织过滤（列表/详情查询仍走 Mapper，OrgScope 生效，见 FrContentTaskMapper）。
 */
@Component
public class ContentTaskStore {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<FrContentTaskDO> ROW = ContentTaskStore::map;

    public ContentTaskStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static FrContentTaskDO map(ResultSet rs, int rowNum) throws SQLException {
        return new FrContentTaskDO()
                .setId(rs.getLong("id"))
                .setCompanyId(rs.getLong("company_id"))
                .setAuthorUserId(rs.getLong("author_user_id"))
                .setKind(rs.getString("kind"))
                .setResultState(rs.getString("result_state")).setResultError(rs.getString("result_error"))
                .setInputSnapshot(rs.getString("input_snapshot"))
                .setResultSnapshot(rs.getString("result_snapshot"))
                .setInputSha256(rs.getString("input_sha256"))
                .setProvider(rs.getString("provider"))
                .setProviderTaskId(rs.getString("provider_task_id"))
                .setResultUrl(rs.getString("result_url"))
                .setEngine(rs.getString("engine"))
                .setModel(rs.getString("model"))
                .setStatus(rs.getString("status"))
                .setFailedReason(rs.getString("failed_reason"))
                .setPoolBizKey(rs.getString("pool_biz_key"))
                .setPoolReservedUnits((Long) rs.getObject("pool_reserved_units"))
                .setPoolTerminalIntent(rs.getString("pool_terminal_intent"))
                .setDispatchStartedAt(rs.getObject("dispatch_started_at", OffsetDateTime.class))
                .setPoolReconcileAttempts(rs.getInt("pool_reconcile_attempts"))
                .setPoolReconcileAfter(rs.getObject("pool_reconcile_after", OffsetDateTime.class))
                .setPoolReconcileError(rs.getString("pool_reconcile_error"))
                .setPoolReconcileLeaseToken(rs.getString("pool_reconcile_lease_token"))
                .setPoolReconcileLeaseUntil(rs.getObject("pool_reconcile_lease_until", OffsetDateTime.class))
                .setManualVerifyDecision(rs.getString("manual_verify_decision"))
                .setManualVerifyActualUnits((Long) rs.getObject("manual_verify_actual_units"))
                .setManualVerifyReason(rs.getString("manual_verify_reason"))
                .setManualVerifyEvidence(rs.getString("manual_verify_evidence"))
                .setManualVerifyOperatorUserId((Long) rs.getObject("manual_verify_operator_user_id"))
                .setManualVerifyOperatorPostId((Long) rs.getObject("manual_verify_operator_post_id"))
                .setManualVerifyIdempotencyKey(rs.getString("manual_verify_idempotency_key"))
                .setManualVerifiedAt(rs.getObject("manual_verified_at", OffsetDateTime.class))
                .setCreateTime(rs.getObject("create_time", OffsetDateTime.class))
                .setCompleteTime(rs.getObject("complete_time", OffsetDateTime.class));
    }

    /** 建任务：QUEUED 落库（input JSONB CAST；sha 可空——上传素材提取才有）。 */
    public FrContentTaskDO insert(long id, long companyId, long authorUserId, String kind,
                                  String inputJson, String inputSha256) {
        jdbcTemplate.update(
                "INSERT INTO fr_content_task (id, company_id, author_user_id, kind, input_snapshot, input_sha256, status) "
                        + "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, 'QUEUED')",
                id, companyId, authorUserId, kind, inputJson, inputSha256);
        return byId(companyId, id);
    }

    /** 任务行先落库，再原子固化预扣键及当时数量；0 行表示事务内部顺序或数据异常。 */
    public void markPoolReservation(long id, long companyId, String poolBizKey, long reservedUnits) {
        if (reservedUnits <= 0) {
            throw new IllegalArgumentException("预扣数量必须为正数");
        }
        int updated = jdbcTemplate.update(
                "UPDATE fr_content_task SET pool_biz_key = ?, pool_reserved_units = ? "
                        + "WHERE id = ? AND company_id = ? AND pool_biz_key IS NULL",
                poolBizKey, reservedUnits, id, companyId);
        if (updated != 1) {
            throw new IllegalStateException("池预扣事实落库失败：taskId=" + id);
        }
    }

    /** 扫描已进终态但积分池仍只有 RESERVE 的任务，供幂等对账恢复。 */
    public List<FrContentTaskDO> claimTerminalWithPendingPool(int limit, String token, long leaseSeconds) {
        return jdbcTemplate.query(
                "WITH picked AS (SELECT t.id FROM fr_content_task t WHERE t.pool_biz_key IS NOT NULL "
                        + "AND t.status IN ('SUCCEEDED','FAILED','CANCELLED') AND t.pool_reconcile_after <= now() "
                        + "AND (t.pool_reconcile_lease_until IS NULL OR t.pool_reconcile_lease_until <= now()) "
                        + "AND EXISTS (SELECT 1 FROM org_point_ledger r WHERE r.biz_key = t.pool_biz_key "
                        + "AND r.type = 'RESERVE' AND NOT EXISTS (SELECT 1 FROM org_point_ledger x "
                        + "WHERE x.operation_id = r.operation_id AND x.type IN ('SETTLE','RELEASE'))) "
                        + "ORDER BY t.pool_reconcile_after, t.complete_time NULLS LAST, t.id "
                        + "LIMIT ? FOR UPDATE SKIP LOCKED) UPDATE fr_content_task t "
                        + "SET pool_reconcile_lease_token=?, pool_reconcile_lease_until=now()+(?*interval '1 second') "
                        + "FROM picked WHERE t.id=picked.id RETURNING t.*",
                ROW, limit, token, leaseSeconds);
    }

    /** 持久退避，防止前 20 条毒任务每轮重占固定批次。 */
    public int deferPoolReconcile(long id, long companyId, String token, String reason) {
        List<Integer> attempts = jdbcTemplate.queryForList(
                "UPDATE fr_content_task SET pool_reconcile_attempts = pool_reconcile_attempts + 1, "
                        + "pool_reconcile_after = now() + (LEAST(3600, 30 * power(2, "
                        + "LEAST(pool_reconcile_attempts, 7))) * interval '1 second'), "
                        + "pool_reconcile_error = ?, pool_reconcile_lease_token=NULL, pool_reconcile_lease_until=NULL "
                        + "WHERE id = ? AND company_id = ? "
                        + "AND (CAST(? AS varchar) IS NULL OR pool_reconcile_lease_token = ?) "
                        + "RETURNING pool_reconcile_attempts",
                Integer.class, clip(reason), id, companyId, token, token);
        return attempts.isEmpty() ? 0 : attempts.get(0);
    }

    public int deferPoolReconcile(long id, long companyId, String reason) {
        return deferPoolReconcile(id, companyId, null, reason);
    }

    public boolean completePoolReconcile(long id, long companyId, String token) {
        return jdbcTemplate.update(
                "UPDATE fr_content_task SET pool_reconcile_attempts=0, pool_reconcile_after=now(), "
                        + "pool_reconcile_error=NULL, pool_reconcile_lease_token=NULL, pool_reconcile_lease_until=NULL "
                        + "WHERE id=? AND company_id=? "
                        + "AND (CAST(? AS varchar) IS NULL OR pool_reconcile_lease_token=?)",
                id, companyId, token, token) == 1;
    }

    public FrContentTaskDO byManualVerifyKey(long companyId, String key) {
        List<FrContentTaskDO> rows = jdbcTemplate.query(
                "SELECT * FROM fr_content_task WHERE company_id=? AND manual_verify_idempotency_key=?", ROW,
                companyId, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** UNKNOWN 人工核验单语句 CAS；互斥结论只能有一个进入账务终态。 */
    public boolean manualVerify(long id, long companyId, long operatorUserId, long operatorPostId,
                                String decision, Long actualUnits, String reason, String evidence, String key) {
        return manualVerifyInternal(id,companyId,operatorUserId,operatorPostId,decision,actualUnits,reason,evidence,key);
    }

    /** 平台核验无员工岗位；显式记录NULL，不伪造老板任职。调用方必须校验平台权限。 */
    public boolean manualVerifyAsPlatform(long id,long companyId,long operatorUserId,String decision,Long actualUnits,
                                         String reason,String evidence,String key) {
        return manualVerifyInternal(id,companyId,operatorUserId,null,decision,actualUnits,reason,evidence,key);
    }

    private boolean manualVerifyInternal(long id,long companyId,long operatorUserId,Long operatorPostId,
                                         String decision,Long actualUnits,String reason,String evidence,String key) {
        boolean delivered = "DELIVERED".equals(decision);
        return jdbcTemplate.update(
                "UPDATE fr_content_task SET status=?, pool_terminal_intent=?, failed_reason=?, complete_time=now(), "
                        + "manual_verify_decision=?, manual_verify_actual_units=?, manual_verify_reason=?, "
                        + "manual_verify_evidence=?, manual_verify_operator_user_id=?, manual_verify_operator_post_id=?, "
                        + "manual_verify_idempotency_key=?, manual_verified_at=now() "
                        + "WHERE id=? AND company_id=? AND status='SUBMISSION_UNCERTAIN' "
                        + "AND pool_biz_key IS NOT NULL AND manual_verify_decision IS NULL"
                        + (operatorPostId==null?" AND provider_task_id IS NULL":""),
                delivered ? "SUCCEEDED" : "FAILED", delivered ? "SETTLE" : "RELEASE",
                delivered ? null : clip(reason), decision, actualUnits, clip(reason), clipEvidence(evidence),
                operatorUserId, operatorPostId, key, id, companyId) == 1;
    }

    public FrContentTaskDO byId(long companyId, long id) {
        List<FrContentTaskDO> rows = jdbcTemplate.query(
                "SELECT * FROM fr_content_task WHERE company_id = ? AND id = ?", ROW, companyId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 领取：QUEUED→SUBMITTING（job 拉单 CAS，防止双实例重复提交）。 */
    public boolean claim(long id) {
        return jdbcTemplate.update(
                "UPDATE fr_content_task SET status = 'SUBMITTING' WHERE id = ? AND status = 'QUEUED'",
                id) > 0;
    }

    /** 轮询租约：令牌防止旧实例在租约过期后误释放新实例的租约。 */
    public boolean claimPoll(long id, String token, long leaseSeconds) {
        return jdbcTemplate.update(
                "UPDATE fr_content_task SET poll_lease_token = ?, "
                        + "poll_lease_until = now() + (? * interval '1 second') "
                        + "WHERE id = ? AND provider_task_id IS NOT NULL "
                        + "AND status IN ('RUNNING','SUBMISSION_UNCERTAIN') "
                        + "AND (poll_lease_until IS NULL OR poll_lease_until <= now())",
                token, leaseSeconds, id) > 0;
    }

    public void releasePollLease(long id, String token) {
        jdbcTemplate.update(
                "UPDATE fr_content_task SET poll_lease_token = NULL, poll_lease_until = NULL "
                        + "WHERE id = ? AND poll_lease_token = ?",
                id, token);
    }

    /** 本地准备完成后、真实 provider 调用前固化资金风险边界。 */
    public boolean markDispatchStarted(long id, long companyId) {
        return jdbcTemplate.update(
                "UPDATE fr_content_task SET dispatch_started_at = COALESCE(dispatch_started_at, now()) "
                        + "WHERE id = ? AND company_id = ? AND status = 'SUBMITTING'",
                id, companyId) > 0;
    }

    /** 提交即持久化：拿到供应商任务号后、开轮询前落库（A2.1）——SUBMITTING→RUNNING。 */
    public boolean persistProviderTask(long id, long companyId, String provider, String providerTaskId) {
        return jdbcTemplate.update(
                "UPDATE fr_content_task SET status = 'RUNNING', provider = ?, provider_task_id = ?, "
                        + "failed_reason = NULL "
                        + "WHERE id = ? AND company_id = ? AND status IN ('QUEUED','SUBMITTING') "
                        + "AND provider_task_id IS NULL",
                provider, providerTaskId, id, companyId) > 0;
    }

    /** 同步成功（无供应商任务号：豆一豆/Flash ASR/DeepSeek/缓存命中）。 */
    public boolean succeedSync(long id, long companyId, String provider, String engine, String model,
                               String resultUrl, String resultJson) {
        return jdbcTemplate.update(
                "UPDATE fr_content_task SET status = 'SUCCEEDED', provider = ?, engine = ?, model = ?, "
                        + "result_url = ?, result_snapshot = CAST(? AS jsonb), complete_time = now() "
                        + "WHERE id = ? AND company_id = ? AND status IN ('QUEUED','SUBMITTING','RUNNING')",
                provider, engine, model, resultUrl, resultJson, id, companyId) > 0;
    }

    /** 明确未受理/未配置时的本地 rules 免费产物，恢复时必须 RELEASE 而非 SETTLE。 */
    public boolean succeedRules(long id, long companyId, String resultJson) {
        return jdbcTemplate.update(
                "UPDATE fr_content_task SET status = 'SUCCEEDED', provider = NULL, engine = 'rules', "
                        + "result_snapshot = CAST(? AS jsonb), pool_terminal_intent = 'RELEASE', complete_time = now() "
                        + "WHERE id = ? AND company_id = ? AND status = 'SUBMITTING'",
                resultJson, id, companyId) > 0;
    }

    /** 轮询成功（有供应商任务号）。 */
    public boolean succeedPolled(long id, long companyId, String resultUrl, String resultJson) {
        return jdbcTemplate.update(
                "UPDATE fr_content_task SET status = 'SUCCEEDED', result_url = ?, "
                        + "result_snapshot = COALESCE(CAST(? AS jsonb), result_snapshot), "
                        + "complete_time = now() "
                        + "WHERE id = ? AND company_id = ? AND status IN ('RUNNING','SUBMISSION_UNCERTAIN')",
                resultUrl, resultJson, id, companyId) > 0;
    }

    /** 终态失败（明确拒绝/供应商明确失败）。 */
    public boolean fail(long id, long companyId, String reason) {
        return jdbcTemplate.update(
                "UPDATE fr_content_task SET status = 'FAILED', failed_reason = ?, complete_time = now() "
                        + "WHERE id = ? AND company_id = ? "
                        + "AND status IN ('QUEUED','SUBMITTING','RUNNING','ARCHIVING','SUBMISSION_UNCERTAIN')",
                clip(reason), id, companyId) > 0;
    }

    /** 仅外呼前本地异常可以确定失败并释放预扣。 */
    public boolean failBeforeDispatch(long id, long companyId, String reason) {
        return jdbcTemplate.update(
                "UPDATE fr_content_task SET status = 'FAILED', failed_reason = ?, complete_time = now() "
                        + "WHERE id = ? AND company_id = ? AND status = 'SUBMITTING' AND dispatch_started_at IS NULL",
                clip(reason), id, companyId) > 0;
    }

    /** 提交不确定：冻结计费、绝不自动重投，出路=人工核验或对账轮询（A2.2）。 */
    public boolean markUncertain(long id, long companyId, String reason) {
        return jdbcTemplate.update(
                "UPDATE fr_content_task SET status = 'SUBMISSION_UNCERTAIN', failed_reason = ? "
                        + "WHERE id = ? AND company_id = ? AND status IN ('SUBMITTING','RUNNING')",
                clip(reason), id, companyId) > 0;
    }

    /** 引擎元数据补充（copy 任务：deepseek/rules 标注；D-1.5-2）。 */
    public boolean noteEngine(long id, long companyId, String engine, String model) {
        return jdbcTemplate.update(
                "UPDATE fr_content_task SET engine = ?, model = ? "
                        + "WHERE id = ? AND company_id = ?",
                engine, model, id, companyId) > 0;
    }

    /** job 扫描：待提交任务（QUEUED，按创建序）。 */
    public List<FrContentTaskDO> findQueued(int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM fr_content_task WHERE status = 'QUEUED' ORDER BY create_time, id LIMIT ?",
                ROW, limit);
    }

    /** job 扫描：在途任务（RUNNING 或带供应商任务号的 UNCERTAIN——后者只查询、绝不重投）。 */
    public List<FrContentTaskDO> findInFlight(int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM fr_content_task "
                        + "WHERE (status = 'RUNNING' OR "
                        + "(status = 'SUBMISSION_UNCERTAIN' AND provider_task_id IS NOT NULL)) "
                        + "AND (poll_lease_until IS NULL OR poll_lease_until <= now()) "
                        + "ORDER BY create_time, id LIMIT ?",
                ROW, limit);
    }

    /** 总轮询窗（2h）超窗的 RUNNING/SUBMITTING 任务：转 UNCERTAIN 冻结待人工（A2.6）。
     *  SUBMITTING 必须纳入：claim 后进程中断的任务此前无任何扫描会拾取（评审十三 P1-2 滞留死区）。 */
    public List<FrContentTaskDO> findPollTimedOut(int hours, int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM fr_content_task WHERE status IN ('RUNNING','SUBMITTING') "
                        + "AND create_time < now() - (? || ' hours')::interval ORDER BY create_time, id LIMIT ?",
                ROW, String.valueOf(hours), limit);
    }

    private static String clip(String reason) {
        String clean = reason == null ? "" : reason.trim();
        return clean.length() <= 500 ? clean : clean.substring(0, 500);
    }

    private static String clipEvidence(String evidence) {
        String clean = evidence == null ? "" : evidence.trim();
        return clean.length() <= 1000 ? clean : clean.substring(0, 1000);
    }

    static long newId() {
        return IdWorker.getId();
    }

}
