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

    /** 提交即持久化：拿到供应商任务号后、开轮询前落库（A2.1）——SUBMITTING→RUNNING。 */
    public boolean persistProviderTask(long id, long companyId, String provider, String providerTaskId) {
        return jdbcTemplate.update(
                "UPDATE fr_content_task SET status = 'RUNNING', provider = ?, provider_task_id = ?, "
                        + "failed_reason = NULL, update_time = now() "
                        + "WHERE id = ? AND company_id = ? AND status IN ('QUEUED','SUBMITTING') "
                        + "AND provider_task_id IS NULL",
                provider, providerTaskId, id, companyId) > 0;
    }

    /** 同步成功（无供应商任务号：豆一豆/Flash ASR/DeepSeek/缓存命中）。 */
    public boolean succeedSync(long id, long companyId, String provider, String engine, String model,
                               String resultUrl, String resultJson) {
        return jdbcTemplate.update(
                "UPDATE fr_content_task SET status = 'SUCCEEDED', provider = ?, engine = ?, model = ?, "
                        + "result_url = ?, result_snapshot = CAST(? AS jsonb), complete_time = now(), update_time = now() "
                        + "WHERE id = ? AND company_id = ? AND status IN ('QUEUED','SUBMITTING','RUNNING')",
                provider, engine, model, resultUrl, resultJson, id, companyId) > 0;
    }

    /** 轮询成功（有供应商任务号）。 */
    public boolean succeedPolled(long id, long companyId, String resultUrl, String resultJson) {
        return jdbcTemplate.update(
                "UPDATE fr_content_task SET status = 'SUCCEEDED', result_url = ?, "
                        + "result_snapshot = COALESCE(CAST(? AS jsonb), result_snapshot), "
                        + "complete_time = now(), update_time = now() "
                        + "WHERE id = ? AND company_id = ? AND status IN ('RUNNING','SUBMISSION_UNCERTAIN')",
                resultUrl, resultJson, id, companyId) > 0;
    }

    /** 终态失败（明确拒绝/供应商明确失败）。 */
    public boolean fail(long id, long companyId, String reason) {
        return jdbcTemplate.update(
                "UPDATE fr_content_task SET status = 'FAILED', failed_reason = ?, complete_time = now(), update_time = now() "
                        + "WHERE id = ? AND company_id = ? AND status <> 'SUCCEEDED'",
                clip(reason), id, companyId) > 0;
    }

    /** 提交不确定：冻结计费、绝不自动重投，出路=人工核验或对账轮询（A2.2）。 */
    public boolean markUncertain(long id, long companyId, String reason) {
        return jdbcTemplate.update(
                "UPDATE fr_content_task SET status = 'SUBMISSION_UNCERTAIN', failed_reason = ?, update_time = now() "
                        + "WHERE id = ? AND company_id = ? AND status <> 'SUCCEEDED'",
                clip(reason), id, companyId) > 0;
    }

    /** 引擎元数据补充（copy 任务：deepseek/rules 标注；D-1.5-2）。 */
    public boolean noteEngine(long id, long companyId, String engine, String model) {
        return jdbcTemplate.update(
                "UPDATE fr_content_task SET engine = ?, model = ?, update_time = now() "
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
                        + "WHERE status = 'RUNNING' OR (status = 'SUBMISSION_UNCERTAIN' AND provider_task_id IS NOT NULL) "
                        + "ORDER BY create_time, id LIMIT ?",
                ROW, limit);
    }

    /** 总轮询窗（2h）超窗的 RUNNING 任务：转 UNCERTAIN 冻结待人工（A2.6）。 */
    public List<FrContentTaskDO> findPollTimedOut(int hours, int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM fr_content_task WHERE status = 'RUNNING' "
                        + "AND create_time < now() - (? || ' hours')::interval ORDER BY create_time, id LIMIT ?",
                ROW, String.valueOf(hours), limit);
    }

    private static String clip(String reason) {
        String clean = reason == null ? "" : reason.trim();
        return clean.length() <= 500 ? clean : clean.substring(0, 500);
    }

    static long newId() {
        return IdWorker.getId();
    }

}
